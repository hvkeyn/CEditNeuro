package com.hvkeyn.ceditneuro

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.hvkeyn.ceditneuro.net.PacketCaptureHub
import kotlinx.coroutines.launch
import com.hvkeyn.ceditneuro.ui.WorkspaceScreen
import com.hvkeyn.ceditneuro.ui.theme.CEditNeuroTheme

class MainActivity : ComponentActivity() {

    private var captureAfterConsent: Pair<String, Int>? = null

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = captureAfterConsent
        captureAfterConsent = null
        val status = statusFile()
        if (result.resultCode != RESULT_OK) {
            status?.writeText("denied")
            return@registerForActivityResult
        }
        if (pending == null) {
            status?.writeText("accepted")
            return@registerForActivityResult
        }
        launchCapture(pending.first, pending.second)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeCapture(intent)

        val app = application as CEditNeuroApp

        setContent {
            CEditNeuroTheme {
                WorkspaceScreen(app.workspaceModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeCapture(intent)
    }

    /** A same-app start used to check the recorder. The VPN prompt and the notification still apply. */
    private fun maybeCapture(intent: Intent?) {
        val consentOnly = intent?.getBooleanExtra("cedit_consent", false) == true
        val asked = consentOnly ||
            intent?.getBooleanExtra("cedit_capture", false) == true ||
            intent?.getStringExtra("cedit_capture") == "true"
        if (!asked) return
        val dir = getExternalFilesDir(null) ?: return
        val path = java.io.File(dir, "capture.pcap").absolutePath
        val seconds = intent?.getIntExtra("seconds", 8)?.coerceIn(5, 60) ?: 8
        val status = java.io.File(dir, "capture-status.txt")
        try {
            val consent = android.net.VpnService.prepare(this)
            if (consent != null) {
                captureAfterConsent = if (consentOnly) null else path to seconds
                vpnConsent.launch(consent)
                status.writeText("consent")
                return
            }
            if (consentOnly) {
                status.writeText("accepted")
                return
            }
            launchCapture(path, seconds)
        } catch (thrown: Throwable) {
            status.writeText(thrown.javaClass.simpleName + ": " + thrown.message.orEmpty().take(200))
        }
    }

    private fun launchCapture(path: String, seconds: Int) {
        statusFile()?.writeText("started")
        lifecycleScope.launch {
            val text = PacketCaptureHub.record(applicationContext, path, seconds)
            statusFile()?.writeText(if (PacketCaptureHub.isError(text)) "error: " + text.take(200) else "done")
        }
    }

    private fun statusFile(): java.io.File? {
        val dir = getExternalFilesDir(null) ?: return null
        return java.io.File(dir, "capture-status.txt")
    }
}
