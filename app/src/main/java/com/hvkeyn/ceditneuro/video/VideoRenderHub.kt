package com.hvkeyn.ceditneuro.video

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * One render at a time. The render screen picks the job up, because a WebView only
 * draws a page while it sits in a window.
 */
object VideoRenderHub {
    data class Job(
        val html: File,
        val out: File,
        val fps: Int,
        val maxSide: Int,
        val result: CompletableDeferred<Outcome> = CompletableDeferred(),
    )

    data class Outcome(val ok: Boolean, val text: String)

    data class Progress(val frame: Int, val total: Int, val width: Int, val height: Int)

    private val _job = MutableStateFlow<Job?>(null)
    val job: StateFlow<Job?> = _job

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    const val MAX_SECONDS = 300.0

    suspend fun render(html: File, out: File, fps: Int, maxSide: Int): Outcome {
        if (_job.value != null) return Outcome(false, "A render is already running. Wait for it to finish.")
        val text = runCatching { html.readText() }.getOrElse { return Outcome(false, "Cannot read ${html.path}.") }
        val info = Composition.parse(text)
            ?: return Outcome(false, "No element with data-composition-id in ${html.name}. That file is not a composition.")
        val holes = Composition.audit(text)
        if (holes.isNotEmpty()) {
            return Outcome(false, "The reel does not cover its timeline. Fix the html, then render again.\n" + holes.joinToString("\n"))
        }
        val seconds = info.seconds.takeIf { it > 0 } ?: MAX_SECONDS
        val job = Job(html, out, fps, maxSide)
        _job.value = job
        _progress.value = null
        val frames = (seconds.coerceAtMost(MAX_SECONDS) * fps).toLong()
        val limit = frames * 1_500L + 120_000L
        val outcome = withTimeoutOrNull(limit) { job.result.await() }
            ?: Outcome(false, "The render did not finish within ${limit / 1000} seconds.")
        if (_job.value === job) _job.value = null
        _progress.value = null
        return outcome
    }

    fun report(progress: Progress) {
        _progress.value = progress
    }

    fun complete(job: Job, outcome: Outcome) {
        job.result.complete(outcome)
        if (_job.value === job) _job.value = null
        _progress.value = null
    }
}
