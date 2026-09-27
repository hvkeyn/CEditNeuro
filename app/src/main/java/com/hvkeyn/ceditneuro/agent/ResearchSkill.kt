package com.hvkeyn.ceditneuro.agent

import java.io.File

/** Built-in procedure. It is read only when a study task needs it, so ordinary edits stay short. */
object ResearchSkill {
    const val NAME = "research"

    /** Each line is added to an existing file once, when its tool name is missing. */
    private val ADDITIONS = listOf(
        "calculate" to "Numbers go through calculate. A fact, paper, or DOI goes through reference before it is cited.\n" +
            "open_file shows research/report.pdf to the user when the report is ready.",
        "research_plot" to "A chart of measured numbers is research_plot. Send the data; the app draws the SVG.",
        "use_skill deep-read" to "Studying a page or a scheme: use_skill deep-read. A code change: use_skill review. A failure: use_skill debug. A key or another app: use_skill security.",
    )

    private val TEXT = """
        # Research

        Use this for a study question, a check of a claim, or building something that has to be measured.

        Stay on this one agent. Do not start another agent and do not fetch a pile of papers.

        1. Restate the question in the user's language. research_log kind=question.
        2. Write one hypothesis that could be wrong. research_log kind=hypothesis.
        3. Check it with a project file, one command, or one page. research_log kind=evidence and include the path or URL you actually opened.
        4. Say what the check showed. research_log kind=check. A number that no tool printed is unchecked.
        5. If the task is to build, change the project, then research_log kind=build.
        6. research_report writes the short result: question, what was checked, a markdown table when numbers exist, and what is still open.
        7. A chart of numbers is research_plot; send only the data. A scheme is research_figure, a single small SVG.

        Do not invent a DOI, a citation, or a measurement. Write the report in the user's language.
    """.trimIndent() + "\n\n" + ADDITIONS.joinToString("\n") { it.second } + "\n"

    /** Writes the default once. An existing file keeps the user's edits and only gains the missing lines. */
    fun ensure(appDir: File) {
        val file = File(appDir, "$NAME.md")
        if (!file.isFile) {
            appDir.mkdirs()
            file.writeText(TEXT, Charsets.UTF_8)
        } else {
            val current = file.readText(Charsets.UTF_8)
            val missing = ADDITIONS.filter { (marker, _) -> !current.contains(marker) }
            if (missing.isNotEmpty()) {
                file.writeText(current.trimEnd() + "\n\n" + missing.joinToString("\n") { it.second } + "\n", Charsets.UTF_8)
            }
        }
        StarterSkills.ensure(appDir)
    }
}

/**
 * Short procedures for this phone. Written once; the user may edit or delete them.
 * The four study and engineering ones are original steps for this app's tools.
 * The domains follow the MIT catalog at github.com/alirezarezvani/claude-skills
 * (review, debug, security, deep reading). Their Claude Code scripts and the other
 * few hundred skills are not copied: they call tools this phone does not have.
 */
object StarterSkills {
    private const val MARK = ".starter-written"

    val skills = mapOf(
        "android-debug" to """
            # Android debug on this phone

            Use this when an app crashes, drains battery, or a phone setting misbehaves.

            1. load_tools group=device. device_status shows battery, storage, memory, and whether root or Shizuku is on.
            2. With root or Shizuku, shizuku_exec runs: logcat -d -t 300 *:E, dumpsys battery, dumpsys meminfo PACKAGE, pm list packages -3, getprop ro.build.version.release.
            3. Without them, say which check needs root or Shizuku. Do not pretend a command ran.
            4. Filter logs to the package or the error. Quote at most 20 lines.
            5. open_settings takes the user to the right screen when the fix is a setting. The user changes it.
            6. Write what was found and one next step. Do not read another app's private files.
        """.trimIndent() + "\n",
        "book-notes" to """
            # Notes from a book

            Use this when the user asks to summarize, explain, or quiz from the open book.

            1. Work from the page text the reader sent. Do not invent quotes or page numbers.
            2. reader_note saves one short note on the current page.
            3. For a chapter summary, write 5 to 8 points in the user's language, each with its page.
            4. For terms, give the term, a one-line meaning, and the page.
            5. Numbers from the book go through calculate before any comparison.
        """.trimIndent() + "\n",
        "review" to """
            # Review a change

            Use this before saying a code change works.

            1. git_diff the files you changed. Quote the important hunk, not the whole diff.
            2. Run one check that would fail if the change were wrong: a test, a command, or reading the file back.
            3. Say what you checked and what you did not run.
            4. A failed command stays failed. Do not run it again unchanged.
        """.trimIndent() + "\n",
        "debug" to """
            # Find one fault

            Use this when something fails.

            1. Restate the failure in one sentence, from the output you actually saw.
            2. Write one hypothesis that could be wrong.
            3. Test it with one read or one command.
            4. If that check fails, change the hypothesis. Do not repeat the same command.
            5. The fix is the smallest edit that makes the check pass. Then use_skill review.
        """.trimIndent() + "\n",
        "security" to """
            # Keep secrets and other apps closed

            Use this when the task touches keys, logins, accounts, or another app.

            1. Never write a token, password, or key into a file, a skill, or the chat.
            2. Do not read another app's private files. Say so if the check needs that.
            3. Do not invent a permission Android will not grant.
            4. A host or command that already failed is not tried again.
        """.trimIndent() + "\n",
        "deep-read" to """
            # Read what is in front of you

            Use this when the user wants to study a book, a note, or a scheme.

            1. Use only the page, file, or picture that was opened. Do not fill gaps from memory.
            2. Separate what the text says from what you infer. Label the inference.
            3. A number in the text is quoted, not recalculated, unless calculate is asked.
            4. For a scheme, name the boxes and arrows that are drawn. Do not add steps that are not there.
            5. Leave a short list the user can check against the page.
        """.trimIndent() + "\n",
    )

    /** A starter skill the user deleted stays deleted: the marker lists names already written. */
    fun ensure(appDir: File) {
        appDir.mkdirs()
        val marker = File(appDir, MARK)
        val written = if (marker.isFile) marker.readLines().map { it.trim() }.toMutableSet() else mutableSetOf()
        var changed = false
        skills.forEach { (name, text) ->
            if (name in written) return@forEach
            val file = File(appDir, "$name.md")
            if (!file.isFile) file.writeText(text, Charsets.UTF_8)
            written += name
            changed = true
        }
        if (changed) marker.writeText(written.sorted().joinToString("\n") + "\n", Charsets.UTF_8)
    }
}
