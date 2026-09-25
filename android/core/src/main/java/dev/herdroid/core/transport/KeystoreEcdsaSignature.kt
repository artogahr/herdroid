package dev.herdroid.core.transport

import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.signature.SignatureECDSA
import java.security.PrivateKey

/**
 * sshj creates every JCA Signature from its configured provider (BouncyCastle), which cannot
 * use an Android Keystore key. Signing here goes through a provider-less JCA engine, so
 * Android picks the Keystore provider for our key. Verification is unchanged.
 */
class KeystoreEcdsaSignature : SignatureECDSA("SHA256withECDSA", KeyType.ECDSA256.toString()) {
    private val signer = java.security.Signature.getInstance("SHA256withECDSA")
    private var signing = false

    override fun initSign(privateKey: PrivateKey) {
        signing = true
        try {
            signer.initSign(privateKey)
        } catch (e: Exception) {
            throw SSHRuntimeException(e)
        }
    }

    override fun initVerify(publicKey: java.security.PublicKey) {
        signing = false
        super.initVerify(publicKey)
    }

    override fun update(foo: ByteArray) = update(foo, 0, foo.size)

    override fun update(
        foo: ByteArray,
        off: Int,
        len: Int,
    ) {
        if (signing) signer.update(foo, off, len) else super.update(foo, off, len)
    }

    override fun sign(): ByteArray = if (signing) signer.sign() else super.sign()

    class Factory256 : Factory.Named<Signature> {
        override fun create(): Signature = KeystoreEcdsaSignature()

        override fun getName(): String = KeyType.ECDSA256.toString()
    }
}
