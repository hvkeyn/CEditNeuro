package com.hvkeyn.ceditneuro.agent

import java.io.File

/** Built-in procedure. It is read only when a study task needs it, so ordinary edits stay short. */
object ResearchSkill {
    const val NAME = "research"

    private const val CHECKS =
        "Numbers go through calculate. A fact, paper, or DOI goes through reference before it is cited.\n" +
            "open_file shows research/report.pdf to the user when the report is ready."

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
        7. One scheme is research_figure, a single small SVG.

        Do not invent a DOI, a citation, or a measurement. Write the report in the user's language.
    """.trimIndent() + "\n\n" + CHECKS + "\n"

    /** Writes the default once. An existing file keeps the user's edits and only gains the missing check lines. */
    fun ensure(appDir: File) {
        val file = File(appDir, "$NAME.md")
        if (!file.isFile) {
            appDir.mkdirs()
            file.writeText(TEXT, Charsets.UTF_8)
            return
        }
        val current = file.readText(Charsets.UTF_8)
        if (!current.contains("calculate")) file.writeText(current.trimEnd() + "\n\n" + CHECKS + "\n", Charsets.UTF_8)
    }
}
