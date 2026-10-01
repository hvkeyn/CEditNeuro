package com.hvkeyn.ceditneuro

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.hvkeyn.ceditneuro.net.PacketCaptureHub
import com.hvkeyn.ceditneuro.tools.NetAuditTool
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
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier) {
                    WorkspaceScreen(app.workspaceModel)
                    com.hvkeyn.ceditneuro.video.RenderHost()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as CEditNeuroApp).workspaceModel.recheckNetwork()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeCapture(intent)
    }

    /** A same-app start used to check the recorder. The VPN prompt and the notification still apply. */
    private fun maybeCapture(intent: Intent?) {
        if (intent?.getBooleanExtra("cedit_shell", false) == true) {
            val dir = getExternalFilesDir(null) ?: return
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val text = runCatching {
                    com.hvkeyn.ceditneuro.shizuku.ShizukuShell(applicationContext).exec("id", "/", 15)
                }.getOrElse { "down ${it.message ?: "failed"}" }
                java.io.File(dir, "shell-check.txt").writeText(text)
            }
            return
        }
        if (intent?.getBooleanExtra("cedit_render", false) == true) {
            val dir = getExternalFilesDir(null) ?: return
            val html = java.io.File(dir, "render/index.html")
            val fps = intent.getIntExtra("fps", 30)
            lifecycleScope.launch {
                val outcome = com.hvkeyn.ceditneuro.video.VideoRenderHub.render(
                    html,
                    java.io.File(dir, "render/index.mp4"),
                    fps,
                    intent.getIntExtra("size", 1280),
                )
                java.io.File(dir, "render-check.txt").writeText((if (outcome.ok) "ok " else "error ") + outcome.text)
            }
            return
        }
        if (intent?.getBooleanExtra("cedit_sound", false) == true) {
            val dir = getExternalFilesDir(null) ?: return
            val ws = com.hvkeyn.ceditneuro.workspace.Workspace(java.io.File(dir, "render").apply { mkdirs() })
            val prompt = intent.getStringExtra("prompt") ?: "cinematic"
            val line = intent.getStringExtra("line")
            lifecycleScope.launch {
                fun args(vararg pairs: Pair<String, String>) = kotlinx.serialization.json.buildJsonObject {
                    for ((k, v) in pairs) put(k, kotlinx.serialization.json.JsonPrimitive(v))
                }
                val report = StringBuilder()
                val started = System.currentTimeMillis()
                val music = com.hvkeyn.ceditneuro.tools.MakeMusicTool(ws).execute(args("prompt" to prompt, "for" to "index.html"))
                report.append(if (music.isError) "error " else "ok ").append(music.content)
                    .append(" [").append(System.currentTimeMillis() - started).append(" ms]\n")
                val sfx = com.hvkeyn.ceditneuro.tools.SoundEffectTool(applicationContext, ws).execute(args("name" to "whoosh cinematic", "at" to "6"))
                report.append(if (sfx.isError) "error " else "ok ").append(sfx.content).append('\n')
                if (line != null) {
                    val vo = com.hvkeyn.ceditneuro.tools.VoiceoverTool(applicationContext, ws).execute(args("text" to line, "at" to "1"))
                    report.append(if (vo.isError) "error " else "ok ").append(vo.content).append('\n')
                }
                java.io.File(dir, "sound-check.txt").writeText(report.toString())
            }
            return
        }
        if (intent?.getBooleanExtra("cedit_audit", false) == true) {
            val dir = getExternalFilesDir(null) ?: return
            val sweep = intent.getBooleanExtra("sweep", false)
            lifecycleScope.launch {
                val args = kotlinx.serialization.json.buildJsonObject {
                    put("sweep", kotlinx.serialization.json.JsonPrimitive(sweep))
                }
                val result = NetAuditTool(applicationContext) { true }.execute(args)
                java.io.File(dir, "net-audit.txt").writeText(result.content)
            }
            return
        }
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
