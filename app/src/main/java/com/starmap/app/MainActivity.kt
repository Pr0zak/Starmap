package com.starmap.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.starmap.app.ui.MainScreen
import com.starmap.app.ui.theme.StarmapTheme
import com.starmap.app.update.DiagPrefs
import com.starmap.app.update.LogUploader
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If the previous launch crashed, show the report instead of running so
        // the error is never lost (handy when there's no way to read logcat).
        val crashFile = File(filesDir, CrashLog.FILE_NAME)
        if (crashFile.exists()) {
            try {
                Log.w(CrashLog.TAG, "Previous crash detected — showing report")
                showCrashReport(crashFile.readText(), crashFile)
                return
            } catch (t: Throwable) {
                Log.e(CrashLog.TAG, "Failed to show crash report; continuing", t)
                crashFile.delete()
            }
        }

        try {
            Log.i(CrashLog.TAG, "MainActivity.onCreate — starting UI")
            enableEdgeToEdge()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContent {
                StarmapTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        MainScreen()
                    }
                }
            }
            Log.i(CrashLog.TAG, "MainActivity.onCreate — UI set")
        } catch (t: Throwable) {
            // Synchronous failures during composition land here immediately.
            Log.e("StarmapCrash", "onCreate failed", t)
            val report = CrashLog.format(Thread.currentThread(), t)
            CrashLog.write(this, report)
            showCrashReport(report, crashFile)
        }
    }

    private fun showCrashReport(report: String, crashFile: File) {
        window.decorView.setBackgroundColor(0xFF101018.toInt())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "Starmap hit an error"
            textSize = 20f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Copy or upload this and send it over so it can be fixed."
            textSize = 13f
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(0, dp(6), 0, dp(12))
        })

        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF8FB7FF.toInt())
            setPadding(0, 0, 0, dp(8))
            visibility = TextView.GONE
        }

        val tokenField = EditText(this).apply {
            hint = "GitHub token (optional — paste to upload as a private Gist)"
            setText(DiagPrefs.getToken(this@MainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF888888.toInt())
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(tokenField)

        val copyBtn = Button(this)
        copyBtn.text = "Copy"
        copyBtn.setOnClickListener { copyToClipboard(report); toast("Copied") }

        val uploadBtn = Button(this)
        uploadBtn.text = "Upload link"
        uploadBtn.setOnClickListener {
            DiagPrefs.setToken(this, tokenField.text.toString())
            status.visibility = TextView.VISIBLE
            status.text = if (tokenField.text.isNotBlank()) "Uploading to Gist…" else "Uploading…"
            uploadBtn.isEnabled = false
            lifecycleScope.launch {
                LogUploader.upload(this@MainActivity, report)
                    .onSuccess {
                        status.text = "Link (copied): $it"
                        copyToClipboard(it)
                        toast("Link copied")
                    }
                    .onFailure { status.text = "Upload failed: ${it.message}. Use Copy instead." }
                uploadBtn.isEnabled = true
            }
        }

        val retryBtn = Button(this)
        retryBtn.text = "Retry"
        retryBtn.setOnClickListener { crashFile.delete(); recreate() }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(copyBtn)
        buttons.addView(uploadBtn)
        buttons.addView(retryBtn)
        root.addView(buttons)
        root.addView(status)

        val reportView = TextView(this).apply {
            text = report
            textSize = 11f
            setTextColor(0xFFCCCCCC.toInt())
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(reportView) }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Starmap report", text))
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).apply { setGravity(Gravity.CENTER, 0, 0) }.show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
