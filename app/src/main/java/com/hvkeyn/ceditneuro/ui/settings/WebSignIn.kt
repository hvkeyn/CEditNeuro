package com.hvkeyn.ceditneuro.ui.settings

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hvkeyn.ceditneuro.agent.deepseek.web.WebClient
import com.hvkeyn.ceditneuro.agent.deepseek.web.WebLogin
import com.hvkeyn.ceditneuro.agent.deepseek.web.WebSession
import com.hvkeyn.ceditneuro.agent.deepseek.web.WebSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Sign-in state and actions for the on-phone DeepSeek web provider. */
@Composable
internal fun WebPhoneLine() {
    val context = LocalContext.current
    val store = remember { WebSessionStore(context.applicationContext) }
    var session by remember { mutableStateOf(store.load()) }
    var status by remember { mutableStateOf("") }
    var signingIn by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "No key, no payment and no computer: the app talks to the free DeepSeek web chat with your account. " +
                "Sign in once on the DeepSeek page; if Google refuses inside the app, use email and password or a phone code. " +
                "Each request opens a short web chat that is removed afterwards. The context is 64K, so old turns are shortened.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val signedIn = session
        Text(
            text = status.ifBlank {
                if (signedIn == null) "Not signed in." else "Signed in " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(signedIn.capturedAt)) + "."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { signingIn = true }) { Text(if (signedIn == null) "Sign in" else "Sign in again") }
            if (signedIn != null) {
                TextButton(onClick = {
                    status = "Checking…"
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            runCatching { WebClient().check(signedIn) }.getOrElse { it.message ?: "Check failed." }
                        }
                    }
                }) { Text("Check") }
                TextButton(onClick = {
                    WebLogin.signOut(store)
                    session = null
                    status = ""
                }) { Text("Sign out") }
            }
        }
    }

    if (signingIn) {
        WebSignInDialog(
            onDismiss = { signingIn = false },
            onSignedIn = { captured ->
                store.save(captured)
                session = captured
                status = ""
                signingIn = false
            },
        )
    }
}

@Composable
private fun WebSignInDialog(onDismiss: () -> Unit, onSignedIn: (WebSession) -> Unit) {
    var view by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(view) {
        val page = view ?: return@LaunchedEffect
        while (true) {
            delay(1_000)
            val token = WebLogin.token(page)
            if (token.isNotBlank()) {
                onSignedIn(WebLogin.capture(page, token))
                break
            }
        }
    }
    fun back() {
        val page = view
        if (page != null && page.canGoBack()) page.goBack() else onDismiss()
    }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = ::back) { Text("Back") }
                    Text("Sign in to DeepSeek", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).also { page ->
                            WebLogin.configure(page)
                            page.webViewClient = WebViewClient()
                            page.loadUrl(WebLogin.SIGN_IN)
                            view = page
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }
}
