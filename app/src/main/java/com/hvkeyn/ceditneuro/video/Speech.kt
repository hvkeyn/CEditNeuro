package com.hvkeyn.ceditneuro.video

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/** A voiceover line written to WAV by the phone's own text-to-speech engine. */
object Speech {
    data class Result(val ok: Boolean, val text: String, val seconds: Double? = null)

    suspend fun toFile(
        context: Context,
        text: String,
        out: File,
        language: String?,
        rate: Float,
        pitch: Float,
    ): Result {
        val ready = CompletableDeferred<Int>()
        val tts = withContext(Dispatchers.Main) {
            TextToSpeech(context.applicationContext) { status -> ready.complete(status) }
        }
        try {
            val status = withTimeoutOrNull(15_000) { ready.await() }
            if (status != TextToSpeech.SUCCESS) {
                return Result(false, "The phone's text-to-speech engine did not start. Install or enable a voice in Android settings.")
            }
            val locale = when {
                !language.isNullOrBlank() -> Locale.forLanguageTag(language)
                text.any { it in '\u0400'..'\u04FF' } -> Locale.forLanguageTag("ru-RU")
                else -> Locale.US
            }
            val set = tts.setLanguage(locale)
            if (set == TextToSpeech.LANG_MISSING_DATA || set == TextToSpeech.LANG_NOT_SUPPORTED) {
                return Result(false, "No ${locale.toLanguageTag()} voice is installed on this phone. Add it in Android text-to-speech settings.")
            }
            tts.setSpeechRate(rate.coerceIn(0.5f, 2f))
            tts.setPitch(pitch.coerceIn(0.5f, 2f))
            out.parentFile?.mkdirs()
            if (out.exists()) out.delete()
            val done = CompletableDeferred<String>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { done.complete("") }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { done.complete("The engine stopped with an error.") }
                override fun onError(utteranceId: String?, errorCode: Int) { done.complete("The engine stopped with error $errorCode.") }
            })
            val code = tts.synthesizeToFile(text, Bundle(), out, "line")
            if (code != TextToSpeech.SUCCESS) return Result(false, "The engine refused the text.")
            val error = withTimeoutOrNull(120_000) { done.await() }
                ?: return Result(false, "The voiceover did not finish within two minutes.")
            if (error.isNotEmpty()) return Result(false, error)
            if (!out.isFile || out.length() < 100) return Result(false, "The engine wrote no audio.")
            val seconds = Wav.seconds(out)
            return Result(true, "voice ${locale.toLanguageTag()}", seconds)
        } finally {
            withContext(Dispatchers.Main) { runCatching { tts.shutdown() } }
        }
    }
}
