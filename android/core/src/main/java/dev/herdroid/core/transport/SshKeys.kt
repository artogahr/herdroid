package dev.herdroid.core.transport

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64

object SshKeys {
    /**
     * Android ships a stripped-down provider named "BC" that lacks algorithms sshj needs.
     * Replace it with the full BouncyCastle provider once per process, before connecting.
     */
    fun installProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) is BouncyCastleProvider) return
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
        SecurityUtils.setSecurityProvider(BouncyCastleProvider.PROVIDER_NAME)
    }

    fun keyProvider(pair: KeyPair): KeyProvider = KeyPairWrapper(pair)

    /** The `authorized_keys` line for [key]. */
    fun authorizedKeysLine(
        key: PublicKey,
        comment: String,
    ): String {
        val type = KeyType.fromKey(key).toString()
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        return "$type ${Base64.getEncoder().encodeToString(blob)} $comment"
    }

    /** OpenSSH-style `SHA256:...` fingerprint, as `ssh-keygen -lf` prints it. */
    fun fingerprint(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}
