package au.edu.deakin.lab.lockersim

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val copied = copyToSandbox(uri)
            append("Imported ${copied.name} into sandbox")
        } else append("Import cancelled")
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

    private fun append(s: String) {
        Log.d("LockerSim", s)
        b.output.append(s + "\n")
    }
}
