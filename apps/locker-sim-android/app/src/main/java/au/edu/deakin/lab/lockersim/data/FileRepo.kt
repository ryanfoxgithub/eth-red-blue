package au.edu.deakin.lab.lockersim.data

import android.content.Context
import android.util.Log
import au.edu.deakin.lab.lockersim.crypto.Crypto
import java.io.File

class FileRepo(private val context: Context) {

    private fun lockerDir(): File =
        File(context.getExternalFilesDir("locker"), "data").apply { mkdirs() }

    fun seedDummyFiles(nText: Int = 12, nBin: Int = 3): List<File> {
        val dir = lockerDir()
        val created = mutableListOf<File>()
        repeat(nText) { i ->
            val f = File(dir, "file_%02d.txt".format(i))
            f.writeText("Dummy file #$i\ncreated=${System.currentTimeMillis()}\n")
            created += f
        }
        repeat(nBin) { i ->
            val f = File(dir, "blob_%02d.bin".format(i))
            val bytes = ByteArray(2048) { ((it * 31 + i) % 251).toByte() }
            f.writeBytes(bytes)
            created += f
        }
        Log.d("FileRepo", "Seeded ${created.size} files at ${dir.absolutePath}")
        return created
    }

    fun listFiles(): List<File> = lockerDir()
        .listFiles()?.sortedBy { it.name } ?: emptyList()

    fun encryptAll(): List<File> {
        val changed = mutableListOf<File>()
        listFiles().forEach { f ->
            if (f.extension == "gcm") return@forEach
            val plain = f.readBytes()
            val aad = f.name.toByteArray()
            val blob = Crypto.encrypt(plain, aad)
            val out = File(f.parentFile, f.name + ".gcm")
            out.writeBytes(blob)
            f.delete() // simulate locker behaviour (on your own files only)
            changed += out
        }
        Log.d("FileRepo", "Encrypted ${changed.size} files")
        return changed
    }

    fun decryptAll(): List<File> {
        val changed = mutableListOf<File>()
        listFiles().forEach { f ->
            if (f.extension != "gcm") return@forEach
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

    /** Return absolute dir path for evidence text */
    fun dirPath(): String = lockerDir().absolutePath
}
