package au.edu.deakin.lab.lockersim.ransom

import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

class RansomNoteActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this).apply {
            settings.javaScriptEnabled = false
            loadUrl("file:///android_asset/note.html")
        }
        setContentView(web)
    }
}
