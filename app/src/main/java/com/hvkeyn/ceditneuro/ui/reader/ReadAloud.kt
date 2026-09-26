package com.hvkeyn.ceditneuro.ui.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/** Reads one page at a time with the phone's own speech engine. Nothing leaves the device. */
class ReadAloud(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: Pair<String, () -> Unit>? = null
    private var currentId = ""
    private var onDone: (() -> Unit)? = null
    private var serial = 0

    private val engine: TextToSpeech = TextToSpeech(context) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            pending?.let { (text, done) ->
                pending = null
                if (ready) speak(text, done)
            }
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                main.post {
                    if (utteranceId == currentId) {
                        val done = onDone
                        onDone = null
                        done?.invoke()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onDone(utteranceId)
        })
    }

    val available: Boolean get() = ready

    fun speak(text: String, done: () -> Unit) {
        if (!ready) {
            pending = text to done
            return
        }
        val clean = text.replace(Regex("[\u0000\u0001]"), " ").trim()
        serial += 1
        currentId = "page-$serial"
        onDone = done
        if (clean.isEmpty()) {
            val id = currentId
            main.postDelayed({ if (id == currentId) { onDone = null; done() } }, 300)
            return
        }
        val limit = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(500)
        val chunks = clean.chunked(limit - 1)
        chunks.forEachIndexed { index, chunk ->
            val id = if (index == chunks.lastIndex) currentId else "$currentId-$index"
            engine.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    fun stop() {
        pending = null
        onDone = null
        currentId = ""
        runCatching { engine.stop() }
    }

    fun release() {
        stop()
        runCatching { engine.shutdown() }
    }
}
