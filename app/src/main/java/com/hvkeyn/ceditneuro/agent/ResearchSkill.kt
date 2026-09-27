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
        "web-to-app" to """
            # Turn a page into an app

            Use this when the user wants a website or an HTML file as an installable Android app.

            1. Call load_tools group=device, then web_to_app.
            2. Pass name and either url (http or https) or html (a project file, or a folder that contains index.html).
            3. The tool writes an APK and returns its path and package id. Call install_apk with that path.
            4. Ask the user to confirm the system installer. Do not open the new app and tap through it.
            5. The same name and the same page update the installed app. A different name installs a second app.
            6. Do not copy another installed app, change its package, or wrap a store app. Do not change TLS, hide the device, or add a proxy.
            7. A local folder is stored inside the APK and works offline. A url opens live and needs a network.
        """.trimIndent() + "\n",
        "find-skills" to """
            # Find a skill for a task

            Use this when the user wants something this chat cannot do yet, or asks to find or install a skill. Switch it off when the task is not about skills.

            1. Call load_tools group=skills, then find_skills. Describe the task in plain words. Do not guess a package name.
            2. Pick one match. Call review_skill with its source and skill. For a skill already on this phone, pass name and scope instead.
            3. review_skill looks for a dangerous prompt, a hidden command, malware, a broken file, and a key. A blocked result is not saved and not switched on. Tell the user the finding. Do not repeat the remote sentences.
            4. A clean result may be saved with save_skill scope=app, using only the adapted text review_skill printed. Do not paste the remote file.
            5. The adapted skill uses tools this app already has. Do not run npx, apt, sudo, or a downloaded program.
            6. When the search is finished, call use_skill name=find-skills scope=app on=false.
        """.trimIndent() + "\n",
        "health" to """
            # Read a health file

            Use this when the user asks about a lab sheet, a scan report, or a family health record. Switch it off when the task is not about health.

            1. Call load_tools group=health.
            2. Read only the file the user named. Do not invent a number, a range, or a date.
            3. Save each result with health_log: person, date, name, value, unit, low, high, topic, and source as the file path. topic is the specialty printed on the sheet. Leave low, high, and topic empty when the sheet does not print them.
            4. Call health_panel for that person. Quote only the rows the tool printed.
            5. For one named test over time, call health_trend. Quote its min, max, mean, and whether the last number is higher or lower than the first. health_index lists every saved test. Do not fill gaps.
            6. A value marked below or above is outside the range printed on the sheet. Say that, and say a doctor should see it. Do not name a disease from a number. Do not say the person has cancer. Quote a diagnosis only when that sentence is already in the file. Do not combine tests into a new disease name.
            7. If the file is a scan report, quote the impression already written there. Do not add findings that are not in the text.
            8. The record stays in this project and is not copied to the other phone. Do not upload it.
            9. When the health task is finished, call use_skill name=health scope=app on=false.
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
        extendHealth(appDir)
    }

    /** Phones that already saved the first health skill gain the trend steps. A deleted file stays deleted. */
    private fun extendHealth(appDir: File) {
        val file = File(appDir, "health.md")
        if (!file.isFile) return
        val current = file.readText(Charsets.UTF_8)
        if (current.contains("health_trend")) return
        file.writeText(
            current.trimEnd() + "\n\n" +
                "For one named test over time, call health_trend. Quote its min, max, mean, and whether the last number is higher or lower than the first. health_index lists every saved test.\n" +
                "Pass topic to health_log when the sheet names a specialty.\n" +
                "Quote a diagnosis only when that sentence is already in the file. Do not combine tests into a new disease name.\n",
            Charsets.UTF_8,
        )
    }
}
