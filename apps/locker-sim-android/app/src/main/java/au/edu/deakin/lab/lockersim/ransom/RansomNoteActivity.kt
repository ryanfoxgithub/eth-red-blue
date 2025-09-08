package au.edu.deakin.lab.lockersim.ransom

import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

/**
 * Simple activity that shows my static “ransom note”.
 *
 * Why a separate activity?
 * - I want a clean, full-screen view that I can launch right after encryption.
 * - Keeping it isolated also makes it easy to screenshot for evidence.
 *
 * What content do I show?
 * - A single local HTML file at assets/note.html (loaded via file://).
 * - It’s 100% offline and self-contained on purpose.
 *
 * Safety:
 * - I explicitly disable JavaScript because I don’t need it here.
 * - The activity is declared `exported="false"` in the manifest so only my app
 *   can open it (adb/other apps can’t).
 */
class RansomNoteActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // I build the WebView in code (no XML) to keep this screen minimal.
        val web = WebView(this).apply {
            // No JS needed; disabling reduces attack surface and surprises.
            settings.javaScriptEnabled = false

            // Load the local HTML from app assets. This works offline and is deterministic.
            loadUrl("file:///android_asset/note.html")
        }

        // Make the WebView the whole content of this screen.
        setContentView(web)
    }
}
