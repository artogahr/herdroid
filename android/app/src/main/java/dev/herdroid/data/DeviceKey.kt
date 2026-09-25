package dev.herdroid.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import dev.herdroid.core.transport.Ed25519PublicKey
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * The phone's SSH identity, kept in the Android Keystore so the private key cannot be
 * exported; signing happens inside it. Ed25519 where the Keystore supports it (Android 13+,
 * depending on the hardware), ECDSA P-256 otherwise.
 */
object DeviceKey {
    private const val ED25519 = "herdroid-ssh-ed25519"
    private const val ECDSA = "herdroid-ssh"
    private const val PROVIDER = "AndroidKeyStore"

    fun get(): KeyPair {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        load(store, ED25519)?.let { return it.asEd25519() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { generate(ED25519, "ed25519", KeyProperties.DIGEST_NONE) }
                .onSuccess { return it.asEd25519() }
                .onFailure { Log.w("DeviceKey", "Keystore has no Ed25519 here; using ECDSA", it) }
        }
        return load(store, ECDSA) ?: generate(ECDSA, "secp256r1", KeyProperties.DIGEST_SHA256)
    }

    private fun load(
        store: KeyStore,
        alias: String,
    ): KeyPair? {
        // Not getEntry(): building a PrivateKeyEntry for a Keystore Ed25519 key throws,
        // because the key and its certificate report different algorithm names.
        val private = store.getKey(alias, null) as? PrivateKey ?: return null
        val public = store.getCertificate(alias)?.publicKey ?: return null
        return KeyPair(public, private)
    }

    private fun generate(
        alias: String,
        curve: String,
        digest: String,
    ): KeyPair {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec(curve))
                .setDigests(digest)
                .build(),
        )
        return generator.generateKeyPair()
    }

    private fun KeyPair.asEd25519() = KeyPair(Ed25519PublicKey(public), private)

    /** Where Android says the private key lives, in words for the key card. */
    fun storage(key: PrivateKey): String {
        // Keystore Ed25519 keys answer to different algorithm names across Android versions.
        val info =
            listOf(key.algorithm, "EC", "Ed25519", "EdDSA", "XDH")
                .distinct()
                .firstNotNullOfOrNull { name ->
                    runCatching { KeyFactory.getInstance(name, PROVIDER).getKeySpec(key, KeyInfo::class.java) }
                        .onFailure { Log.d("DeviceKey", "KeyInfo via $name failed: ${it.message}") }
                        .getOrNull()
                } ?: return "in the Android Keystore"
        val level =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.securityLevel
            } else {
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT else KeyProperties.SECURITY_LEVEL_SOFTWARE
            }
        return when (level) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "in this phone's StrongBox security chip"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "in this phone's secure hardware (Android Keystore, TEE)"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "in the Android Keystore (software, no secure hardware)"
            else -> "in the Android Keystore"
        }
    }
}
