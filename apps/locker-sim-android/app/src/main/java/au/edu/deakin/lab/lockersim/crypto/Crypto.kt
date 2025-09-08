package au.edu.deakin.lab.lockersim.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KS_NAME = "AndroidKeyStore"
private const val ALIAS = "LockerSimKey"
private const val IV_LEN = 12
private const val GCM_TAG_BITS = 128

object Crypto {
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KS_NAME).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS_NAME)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    fun encrypt(plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val secret = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(Cipher.ENCRYPT_MODE, secret)
        if (aad != null) cipher.updateAAD(aad)

        val ct = cipher.doFinal(plain)
        val iv = cipher.iv
        return iv + ct
    }

    fun decrypt(blob: ByteArray, aad: ByteArray? = null): ByteArray {
        require(blob.size > IV_LEN)
        val secret = getOrCreateKey()
        val iv = blob.copyOfRange(0, IV_LEN)
        val ct = blob.copyOfRange(IV_LEN, blob.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }
}
