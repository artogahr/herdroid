package dev.herdroid.core.transport

import com.hierynomus.sshj.signature.SignatureEdDSA
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.signature.Signature
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Ed25519 signing that works with an Android Keystore key. sshj's own EdDSA signature takes
 * its engine from BouncyCastle, which cannot use a Keystore key, and its constructor is not
 * public, so this signs through a provider-less JCA engine (Android picks the Keystore) and
 * leaves verification (host keys) to sshj's implementation.
 */
class KeystoreEd25519Signature : Signature {
    private val verifier = SignatureEdDSA.Factory().create()
    private val signer = java.security.Signature.getInstance("Ed25519")
    private var signing = false

    override fun getSignatureName(): String = KeyType.ED25519.toString()

    override fun initVerify(pubkey: PublicKey) {
        signing = false
        verifier.initVerify(pubkey)
    }

    override fun initSign(prvkey: PrivateKey) {
        signing = true
        try {
            signer.initSign(prvkey)
        } catch (e: Exception) {
            throw SSHRuntimeException(e)
        }
    }

    override fun update(H: ByteArray) = update(H, 0, H.size)

    override fun update(
        H: ByteArray,
        off: Int,
        len: Int,
    ) {
        if (signing) signer.update(H, off, len) else verifier.update(H, off, len)
    }

    override fun sign(): ByteArray = signer.sign()

    // Ed25519 signatures go on the wire as the raw 64 bytes.
    override fun encode(signature: ByteArray): ByteArray = signature

    override fun verify(sig: ByteArray): Boolean = verifier.verify(sig)

    class Factory256 : Factory.Named<Signature> {
        override fun create(): Signature = KeystoreEd25519Signature()

        override fun getName(): String = KeyType.ED25519.toString()
    }
}

/**
 * sshj recognises Ed25519 keys by algorithm name ("EdDSA" or "Ed25519"); Keystore keys may
 * report another. The encoding (X.509, raw key in the last 32 bytes) is what sshj reads.
 */
class Ed25519PublicKey(
    private val delegate: PublicKey,
) : PublicKey {
    override fun getAlgorithm(): String = "Ed25519"

    override fun getFormat(): String? = delegate.format

    override fun getEncoded(): ByteArray = delegate.encoded
}
