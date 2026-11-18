package dev.zeroinput.security.message

import com.google.crypto.tink.aead.internal.InsecureNonceXChaCha20Poly1305
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.CamelliaEngine
import org.bouncycastle.crypto.engines.GOST3412_2015Engine
import org.bouncycastle.crypto.engines.SM4Engine
import org.bouncycastle.crypto.engines.TwofishEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/** Explicit nonces are fresh CSPRNG values owned by MessageEnvelope, never UI inputs. */
internal object MessagePrimitives {
    fun aead(encrypt: Boolean, cipher: MessageCipher, key: ByteArray, nonce: ByteArray,
             aad: ByteArray, input: ByteArray): ByteArray {
        if (key.size * 8 !in cipher.keyBits || nonce.size != cipher.nonceBytes ||
            !encrypt && input.size < 16) throw MessageFailure()
        var output: ByteArray? = null
        try {
            if (cipher == MessageCipher.XCHACHA20) {
                val engine = InsecureNonceXChaCha20Poly1305(key)
                return if (encrypt) engine.encrypt(nonce, input, aad) else engine.decrypt(nonce, input, aad)
            }
            val block = when (cipher) {
                MessageCipher.AES -> AESEngine.newInstance()
                MessageCipher.KUZNYECHIK -> GOST3412_2015Engine()
                MessageCipher.TWOFISH -> TwofishEngine()
                MessageCipher.CAMELLIA -> CamelliaEngine()
                MessageCipher.SM4 -> SM4Engine()
                MessageCipher.XCHACHA20 -> throw MessageFailure()
            }
            val engine = GCMBlockCipher.newInstance(block)
            engine.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val result = ByteArray(engine.getOutputSize(input.size))
            output = result
            val count = engine.processBytes(input, 0, input.size, result, 0)
            if (count + engine.doFinal(result, count) != result.size) throw MessageFailure()
            output = null
            return result
        } catch (_: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw MessageFailure()
        } catch (_: java.security.GeneralSecurityException) {
            throw MessageFailure()
        } catch (_: IllegalArgumentException) {
            throw MessageFailure()
        } finally { output?.fill(0) }
    }
}
