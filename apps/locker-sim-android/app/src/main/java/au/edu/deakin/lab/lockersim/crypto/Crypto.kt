package au.edu.deakin.lab.lockersim.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// --- Constants I use throughout ---
//
// I store the key inside the Android hardware-backed Keystore when available.
// This means the raw key material never leaves the TEE/secure element.
private const val KS_NAME = "AndroidKeyStore"

// Alias (name) for my app’s AES key inside the keystore.
private const val ALIAS = "LockerSimKey"

// GCM uses a 96-bit (12-byte) IV by convention → best interoperability.
private const val IV_LEN = 12

// GCM authentication tag length (bits). 128 is the common, strong choice.
private const val GCM_TAG_BITS = 128

/**
 * Crypto — tiny AES-GCM helper.
 *
 * Design choices (why I do it this way):
 * - I generate/store the key in AndroidKeyStore so I never hold the raw key in app memory long-term.
 * - On ENCRYPT I let the keystore **choose a fresh random IV** (required for keystore keys);
 *   I then prepend that IV to the ciphertext so DECRYPT can recover it.
 * - I support optional AAD (Additional Authenticated Data). I pass filenames there so
 *   the decrypt will fail if someone renames a blob (prevents mixups/tampering).
 */
object Crypto {

    /** Fetch the AES key from the keystore, or create it the first time. */
    private fun getOrCreateKey(): SecretKey {
        // Open the system keystore (no password when using AndroidKeyStore)
        val ks = KeyStore.getInstance(KS_NAME).apply { load(null) }

        // If my key already exists, reuse it.
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        // Otherwise, generate a new 256-bit AES key bound to GCM usage.
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS_NAME)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            // Restrict the key to GCM without padding (the correct mode for AES-GCM).
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // 256-bit key size (strong and widely supported on Android >= Lollipop).
            .setKeySize(256)
            .build()

        kg.init(spec)
        return kg.generateKey()
    }

    /**
     * Encrypt a message with AES-GCM.
     *
     * @param plain the bytes I want to protect
     * @param aad   optional AAD that must match on decrypt (e.g., filename)
     * @return IV || CIPHERTEXT||TAG   (I simply concatenate IV in front)
     */
    fun encrypt(plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val secret = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        // IMPORTANT: I let the keystore pick a fresh random IV.
        // Passing my own IV would throw "Caller-provided IV not permitted" for keystore keys
        // and is also risky if reused. This call sets a new random IV internally.
        cipher.init(Cipher.ENCRYPT_MODE, secret)

        // If the caller supplied AAD, I bind it into the authentication tag.
        if (aad != null) cipher.updateAAD(aad)

        // Encrypt; GCM appends the auth tag to the ciphertext.
        val ct = cipher.doFinal(plain)

        // I read back the random IV the keystore chose and prefix it to the output.
        val iv = cipher.iv
        return iv + ct
    }

    /**
     * Decrypt a blob previously produced by [encrypt].
     *
     * @param blob IV||CIPHERTEXT||TAG (as returned by encrypt)
     * @param aad  the same AAD used at encrypt time (must match)
     * @return the recovered plaintext, or an exception if tag verification fails
     */
    fun decrypt(blob: ByteArray, aad: ByteArray? = null): ByteArray {
        // Quick sanity check so I don’t slice out of bounds.
        require(blob.size > IV_LEN)

        val secret = getOrCreateKey()

        // Split the input into IV and ciphertext+tag.
        val iv = blob.copyOfRange(0, IV_LEN)
        val ct = blob.copyOfRange(IV_LEN, blob.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        // I supply the IV from the blob. If the blob or AAD was tampered with,
        // doFinal() will throw (GCM tag verification fails).
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)

        return cipher.doFinal(ct)
    }
}
