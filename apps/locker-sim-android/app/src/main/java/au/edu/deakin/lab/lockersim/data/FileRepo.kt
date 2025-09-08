package au.edu.deakin.lab.lockersim.data

import android.content.Context
import android.util.Log
import au.edu.deakin.lab.lockersim.crypto.Crypto
import java.io.File

/**
 * FileRepo — tiny helper that I use to manage **my app's sandbox files**.
 *
 * What I store where:
 * - All files live under: /sdcard/Android/data/<pkg>/files/locker/data
 *   (this is my app-specific external directory; safe and private to me).
 *
 * What I can do:
 * - seedDummyFiles()  → create small text/bin files for the demo
 * - listFiles()       → list what’s in the sandbox (sorted for nicer output)
 * - encryptAll()      → AES-GCM encrypt every non-encrypted file → *.gcm
 * - decryptAll()      → restore every *.gcm back to its original name/content
 *
 * Safety note:
 * - I never touch user DCIM here; this class is **sandbox-only** on purpose.
 * - I use the filename as AAD so I can detect accidental rename/tamper on decrypt.
 */
class FileRepo(private val context: Context) {

    /**
     * Resolve (and create if missing) my demo directory.
     * I keep this in a function so every call reuses the same resolved path.
     */
    private fun lockerDir(): File =
        File(context.getExternalFilesDir("locker"), "data").apply { mkdirs() }

    /**
     * Create N tiny text files and M small binary files.
     * This gives me deterministic test data that’s safe to encrypt/decrypt.
     *
     * @param nText how many text files to create (default 12)
     * @param nBin  how many 2 KB binary blobs to create (default 3)
     * @return the list of files I just created
     */
    fun seedDummyFiles(nText: Int = 12, nBin: Int = 3): List<File> {
        val dir = lockerDir()
        val created = mutableListOf<File>()

        // Text files: file_00.txt … file_11.txt with a timestamp inside.
        repeat(nText) { i ->
            val f = File(dir, "file_%02d.txt".format(i))
            f.writeText("Dummy file #$i\ncreated=${System.currentTimeMillis()}\n")
            created += f
        }

        // Binary files: blob_00.bin … blob_02.bin with a simple repeatable pattern.
        repeat(nBin) { i ->
            val f = File(dir, "blob_%02d.bin".format(i))
            val bytes = ByteArray(2048) { ((it * 31 + i) % 251).toByte() } // 2 KB payload
            f.writeBytes(bytes)
            created += f
        }

        Log.d("FileRepo", "Seeded ${created.size} files at ${dir.absolutePath}")
        return created
    }

    /**
     * List every file currently in my sandbox (sorted by name for stable output).
     * If the directory is empty/missing, I return an empty list (no crashes).
     */
    fun listFiles(): List<File> = lockerDir()
        .listFiles()?.sortedBy { it.name } ?: emptyList()

    /**
     * Encrypt every plain file in the sandbox.
     * Rules I follow:
     *  - I skip files that already end with .gcm.
     *  - I read the bytes, encrypt with AES-GCM (AAD = original filename),
     *  - I write "<name>.gcm" next to it,
     *  - I delete the original plaintext (simulates locker behavior).
     *
     * @return the list of new *.gcm files I produced
     */
    fun encryptAll(): List<File> {
        val changed = mutableListOf<File>()
        listFiles().forEach { f ->
            if (f.extension == "gcm") return@forEach           // already encrypted → skip

            val plain = f.readBytes()
            val aad = f.name.toByteArray()                      // I bind ciphertext to the name
            val blob = Crypto.encrypt(plain, aad)               // AES-GCM; IV is handled in Crypto

            val out = File(f.parentFile, f.name + ".gcm")
            out.writeBytes(blob)

            f.delete() // remove plaintext (my demo acts like a locker but only in my sandbox)
            changed += out
        }
        Log.d("FileRepo", "Encrypted ${changed.size} files")
        return changed
    }

    /**
     * Decrypt every *.gcm file back to its original name/content.
     * Steps:
     *  - I split "<name>.gcm" → "name" and use that as the AAD,
     *  - I decrypt the blob,
     *  - I write the recovered bytes to "name",
     *  - I delete the *.gcm file.
     *
     * @return the list of restored plaintext files
     */
    fun decryptAll(): List<File> {
        val changed = mutableListOf<File>()
        listFiles().forEach { f ->
            if (f.extension != "gcm") return@forEach           // only handle ciphertext blobs

            val blob = f.readBytes()
            val orig = f.name.removeSuffix(".gcm")
            val aad = orig.toByteArray()
            val plain = Crypto.decrypt(blob, aad)

            val out = File(f.parentFile, orig)
            out.writeBytes(plain)

            f.delete()
            changed += out
        }
        Log.d("FileRepo", "Decrypted ${changed.size} files")
        return changed
    }

    /**
     * Absolute path to the sandbox directory.
     * I expose this so I can print it in the UI/logs for evidence.
     */
    fun dirPath(): String = lockerDir().absolutePath
}
