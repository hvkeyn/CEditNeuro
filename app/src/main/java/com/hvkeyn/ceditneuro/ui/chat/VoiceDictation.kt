package com.hvkeyn.ceditneuro.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Turns speech into text with the phone's on-device recognizer. Audio is not sent
 * to a network service. A missing language pack is downloaded by Android itself.
 */
internal class VoiceDictation(
    private val context: Context,
    private val mainExecutor: Executor,
) : RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var disposed = false
    private var downloadTried = false
    private var onPartial: (String) -> Unit = {}
    private var onFinal: (String) -> Unit = {}
    private var onNote: (String?) -> Unit = {}

    fun release() {
        disposed = true
        recognizer?.destroy()
        recognizer = null
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onNote: (String?) -> Unit) {
        this.onPartial = onPartial
        this.onFinal = onFinal
        this.onNote = onNote
        if (disposed) return
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onNote("On-device speech is not available on this phone.")
            return
        }
        val ear = recognizer ?: try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
                recognizer = it
            }
        } catch (_: RuntimeException) {
            onNote("On-device speech is not available on this phone.")
            return
        }
        try {
            ear.startListening(listenIntent())
        } catch (_: RuntimeException) {
            onNote("Couldn't open the microphone.")
        }
    }

    @RequiresApi(33)
    private fun downloadLanguage(listener: ModelDownloadListener) {
        recognizer?.triggerModelDownload(listenIntent(), mainExecutor, listener)
    }

    private fun listenIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
    }

    override fun onReadyForSpeech(params: Bundle?) {
        onNote("Listening…")
    }

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        if (disposed) return
        if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE &&
            Build.VERSION.SDK_INT >= 33 &&
            !downloadTried
        ) {
            downloadTried = true
            onNote("Downloading the on-device speech language…")
            val listener = object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) {
                    onNote("Downloading speech $completedPercent%")
                }

                override fun onSuccess() {
                    if (!disposed) start(onPartial, onFinal, onNote)
                }

                override fun onScheduled() {
                    onNote("Downloading the on-device speech language…")
                }

                override fun onError(error: Int) {
                    onNote("On-device speech for this language is not on the phone.")
                }
            }
            downloadLanguage(listener)
            return
        }
        onNote(errorNote(error))
    }

    override fun onResults(results: Bundle?) {
        if (disposed) return
        onNote(null)
        val text = results.best()
        if (text.isNotEmpty()) onFinal(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (disposed) return
        val text = partialResults.best()
        if (text.isNotEmpty()) onPartial(text)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun Bundle?.best(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private fun errorNote(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed to dictate."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> "On-device speech for this language is not on the phone."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> "On-device speech is not available on this phone."
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> "Didn't catch that."
        else -> "Couldn't hear that. Try again."
    }
}
