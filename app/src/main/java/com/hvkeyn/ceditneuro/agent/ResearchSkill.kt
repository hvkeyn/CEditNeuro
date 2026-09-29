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

    private val DEEP = """
        Deep path. Use this when the question needs several sources: a survey, a comparison, or a claim that has to be checked against pages. A single file or a single measurement stays on the short path.

        Do not stop, and do not ask to continue, until research_run says pass. If the chat ends, the next chat calls research_run action=status and continues at the printed step. The sources stay in research/sources.md. Search them with grep before fetching the same page again.

        Stay on this one agent. Do not install a research package and do not run pip, npx, or a downloaded program. Do not start another agent.

        1. load_tools group=study. research_run action=open with the question copied from the user and tier=light or tier=full. light is a fact, a list, or a comparison. full is an argument that could be wrong. A request for a dissertation is still tier=full on this phone: one report, then stop. Do not promise tens of thousands of words or hundreds of sources.
        2. action=plan with the atomic questions separated by |.
        3. Each source is action=source with title, locator, quote, and claim. The locator is a URL, a DOI, an arXiv id, or a project path that a tool printed in this run. reference searches wiki, arxiv, and doi. http_request reads one public page and only that page: a ClinicalTrials.gov study, an EDGAR filing, an Open Library book record, or a statistics table. Quote only words that tool returned. A second copy of the same title or the same locator is a reprint and does not count. light stops at 8 independent sources. full stops at 24. Do not fetch past the cap.
        4. full only: action=tension names two sources that disagree, or text=none after those two were compared and they agree.
        5. action=draft writes the report once, in the user's language. Later changes are action=patch of one exact span. Do not write the report over from scratch.
        6. Critics, one at a time, action=critic. light needs name=cite. full also needs name=independence and name=gap. cite asks whether each quoted sentence is in that source. independence asks whether a reprint was counted twice. gap names the one source that would overturn the draft, or says none was found.
        7. action=cite for each sentence you attribute: the source id, the sentence, and supports=yes or no. supports=yes is refused when the sentence does not share the quote's words or a number the quote printed. A reprint is not cited as a second witness.
        8. action=pass. It refuses while a step is missing, a cited sentence is unsupported, or the independent sources are under 3 for light and 8 for full.
        9. research_report copies the finished draft into research/report.md. open_file shows research/report.pdf when that file exists. When pass is printed, use_skill name=research scope=app on=false.

        A number goes through calculate. Do not invent a DOI, a citation, or a measurement.
    """.trimIndent()

    private val TEXT = """
        # Research

        Use this for a study question, a check of a claim, building something that has to be measured, or a deep look across sources.

        Stay on this one agent. Do not start another agent.

        Short path, for one claim or one measurement:
        1. Restate the question in the user's language. research_log kind=question.
        2. Write one hypothesis that could be wrong. research_log kind=hypothesis.
        3. Check it with a project file, one command, or one page. research_log kind=evidence and include the path or URL you actually opened.
        4. Say what the check showed. research_log kind=check. A number that no tool printed is unchecked.
        5. If the task is to build, change the project, then research_log kind=build.
        6. research_report writes the short result: question, what was checked, a markdown table when numbers exist, and what is still open.
        7. A chart of numbers is research_plot; send only the data. A scheme is research_figure, a single small SVG.

        Do not invent a DOI, a citation, or a measurement. Write the report in the user's language.
    """.trimIndent() + "\n\n" + DEEP + "\n\n" + ADDITIONS.joinToString("\n") { it.second } + "\n"

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
        extendResearch(file)
        StarterSkills.ensure(appDir)
    }

    /** Phones that saved the short research skill gain the deep run. A deleted file stays deleted. */
    private fun extendResearch(file: File) {
        if (!file.isFile) return
        val current = file.readText(Charsets.UTF_8)
        if (current.contains("research_run")) return
        if (!current.contains("research_log kind=question")) return
        val softened = current.replace(
            "Stay on this one agent. Do not start another agent and do not fetch a pile of papers.",
            "Stay on this one agent. Do not start another agent.",
        )
        file.writeText(softened.trimEnd() + "\n\n" + DEEP + "\n", Charsets.UTF_8)
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
            6. Write what was found and one next step.
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
            6. A failure that is more than one line uses systematic-debugging. Do not patch before that case says pass.
        """.trimIndent() + "\n",
        "systematic-debugging" to """
            # Find the cause before the fix

            Use this when something fails: a test, a build, a crash, or behavior the user did not expect. Switch it off when the task is not a failure.

            Do not stop, and do not ask to continue, until debug_case says pass. A guess is not a cause. A change that hides the symptom is not a fix.

            1. Call load_tools group=debug. Call debug_case action=open with the symptom in one sentence and the steps that should show it.
            2. Reproduce. Run the check the user saw fail: a test the project already has, run_command, or read_file of the error. Call debug_case action=reproduce with that check and result=fail. If it does not fail, stop. Do not invent a fix for a failure you did not see. If it fails only sometimes, result=flaky, find the condition, and reproduce again. Do not guess.
            3. Facts before theories. git_diff for what changed. read_file the stack frame. grep the bad value back to the line that created it. Each one is debug_case action=fact with source set to the tool and the path. Quote the line. Do not invent a line number.
            4. One hypothesis: debug_case action=hypothesis. Then one smallest check, action=test with that id and result=supports or rules-out. A ruled-out idea is not patched. A new idea is a new hypothesis. Do not change two things in one test.
            5. The cause is one sentence, action=cause, and only after a test supports it. Say where the bad value starts, not only where it crashes.
            6. The fix is the smallest edit of that cause. No extra cleanup. action=fix names that one edit.
            7. Run the same check that failed. action=verify with that same check. result=pass is the only way the case says pass. A different check does not count. If it still fails, return to the facts. After three failed fixes the tool stops you: question the design with the user, and do not try a fourth patch.
            8. Say what you ran and what you did not run. A failed command stays failed. Then use_skill review, and use_skill name=systematic-debugging scope=app on=false.
        """.trimIndent() + "\n",
        "security" to """
            # Keep secrets safe and reach the data you need

            Use this when the task touches keys, logins, accounts, or another app.

            1. Never write a token, password, or key into a file, a skill, or the chat.
            2. Read another app's files when the task needs them. The shell can open them; do not refuse the read.
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
        "video-notes" to """
            # Notes from a video

            Use this when the user sends a YouTube, RuTube, Yandex, or Dzen video, or asks to find YouTube videos, a channel's latest uploads, or a playlist. Switch it off when the task is not a video.

            Do not sign up for a transcript service and do not store an API key for one. This phone reads the public page. Do not download the video. Do not open a stream, an m3u8, an mp4, or a googlevideo host. Do not call http_request on those.

            1. Call load_tools group=study. One page is video_brief with the page URL. RuTube, Yandex, and Dzen are this step only.
            2. Use only the title, the description, and the captions the tool printed. Captions are cut, so a long video is the start of the public captions, not a viewing of the picture. Do not invent a scene, a quote, a speaker's face, or a number. If captions is no, say the picture was not seen and judge only the description.
            3. Write in the user's language, in this order. Смысл: one short paragraph of what the video is actually about. Важные мысли: 3 to 7 points, each tied to a caption sentence or the description. Skip a point the text does not support. Вывод: what a viewer should take away. Стоит ли смотреть: yes, only a part, or no. Name what is interesting and what is filler, a repeat, or an advertisement. If the text is thin, say there is not enough to judge the picture.
            4. A topic is video_brief action=search query= the user's words. At most 8 titles. A channel is action=channel with the @handle, the channel URL, or the UC id. A playlist is action=playlist with the playlist URL or the PL id, at most 12 titles. Those lines are titles only. Open at most 3 of them with video_brief before judging the meaning.
            5. Several pages at once: video_brief action=batch urls= up to 3 page URLs separated by |. Then one comparison, and the same four parts for each video that had captions.
            6. A number in the note goes through calculate. Do not store a cookie or a token from the page.
            7. When the note is written, call use_skill name=video-notes scope=app on=false.
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
        "frontend-design" to """
            # Design a page

            Use this when the user wants a landing page, a cabinet, a form, or another product screen.

            1. Call load_tools group=design. Restate who the page is for and the one job of the screen.
            2. If they name a design system, or ask how a known company builds screens, call design_system with that name. Quote only the swatches the tool printed. If load_tools says the group has no tools, http_request that system's public page instead. For type, spacing, and corners, http_request the page URL and quote only what that page printed. Do not invent a hex and call it official. Do not copy a company's product.
            3. For an Apple-like feel, hold to purpose, agency, responsibility, familiarity, flexibility, simplicity, craft, and delight. A press answers at once. Motion follows the finger and can be stopped. Honor reduced motion. Do not add a library this phone cannot run.
            4. Before code, write a short plan in the chat: four to six color roles, two type roles, one layout sentence, and one signature element that belongs to this brief. If that plan would fit any other product, change it.
            5. write_file one HTML page in the project, with a plain style block in the file. Use system fonts. No package install and no remote script. The editor preview shows html and htm.
            6. Name the file in backticks so the user can open it. If they want an installable app, use_skill web-to-app and follow it.
            7. When the page is written, call use_skill name=frontend-design scope=app on=false.
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
        "net-map" to """
            # Map a network from a dump

            Use this when the user wants a network drawn from packets. Switch it off when the task is not a dump.

            A file already in the project is read with read_dump. A recording of this phone is capture_dump: it shows a notification, asks for the VPN once, writes a pcap, and stops by itself. ICMP is recorded and is not forwarded, so a ping can fail until the recording stops. TCP and UDP keep working, and both directions are written, so an answered call shows a SYN and a SYN-ACK. Private ranges are not recorded. Do not record another device. Do not scan the live network by hand.

            On a capture_dump recording the phone's own address is shown as 203.0.113.2 and the reply packets are rebuilt by the recorder, so a reply's TTL is always 64 and is not the real remote hop count. On a dump the user saved from real hardware, the TTL is real. Say which kind of dump you are reading.

            The read_dump and capture_dump summary ends with an audit block: how many TCP calls were answered, which calls got no answer, cleartext flows (http, ftp, telnet, and the like), plain DNS counts, QUIC flow counts, and whether a secret field (a cookie, an authorization header, or a password) rode in the clear. Use it: an unanswered call is a closed or filtered port, a cleartext flow is a risk to name, and a secret in the clear is a finding. The value of a secret is never printed; report only that it was present.

            1. Call load_tools group=study. For this phone's own network, call net_audit first: it names the link, the gateway, DNS, private DNS, open gateway ports, and devices that answer SSDP or mDNS. Pass sweep=true only when the user wants the devices on their Wi-Fi listed. Then call capture_dump for the internet conversations and use the file it names. For a dump the user already saved, call read_dump. Do not install a sniffer. Do not invent a field. A capture on this phone shows this phone's public IPv4 conversations, not a mirror of someone else's LAN; the LAN comes from net_audit.
            2. Walk the packets in order. Write research/net-map.md as you go: each line is a fact with the packet number, or a guess marked as a guess. A missing reply is a fact too.
            3. Ethernet. Source and destination MAC. A broadcast or multicast destination is not a second device.
            4. DHCP. Client MAC and option 12 name. Do not draw a requested address until the ACK. From the ACK, take the client address, mask, gateway, and DNS. The server's own IP and MAC are the offerer.
            5. TTL is a guess of the sender, not a proof. 128 often means Windows, 64 often means Unix, 255 often means a network device. A reply that is lower than that start value has crossed that many routers. Say the guess and the number you subtracted.
            6. ARP. Opcode 1 asks, opcode 2 answers. A request with no answer means that host is unconfirmed. Draw it dashed.
            7. CDP or LLDP. Device name, platform, the port that sent the packet, and the addresses on that port. The Ethernet source MAC belongs to that port. Two IP addresses on one MAC and one port are subinterfaces of one physical port, so a switch is likely between the hosts that share it. IP prefixes are networks attached to that device.
            8. A routing advertisement (RIP on UDP 520 to 224.0.0.9, or another routing protocol in the dump). A route with metric 1 and next hop 0.0.0.0 is directly connected. The speaker does not advertise the network it is already speaking into.
            9. TCP. A SYN names who called which port. Port 80 or 443 is a web server. The handshake is confirmed only when the other side answers with a SYN-ACK. On a saved dump from real hardware, the answer's TTL, compared with 64 or 128, estimates the hops; on a capture_dump recording the reply TTL is synthetic, so do not read hops from it.
            10. Audit. From the audit block, write research/net-audit.md: answered ports (open), calls with no answer (closed or filtered), every cleartext flow and the risk of it, plain DNS that reveals the sites visited, and any secret field seen in the clear. Rank findings high, medium, low. A cleartext login or a secret in the clear is high. Recommend the fix in one line each (use TLS, close the port, use encrypted DNS). Do not overstate: an unanswered call is not proof a port is closed if the recording itself dropped the traffic.
            11. Do not copy a cookie, a token, a password, or a page body into the note, the diagram, the audit, or the chat. Say that a private field was present and leave the value out.
            12. Call research_figure name=net-map with one small SVG. Boxes are devices. Lines are links. Labels are only values a packet printed. Guesses stay in parentheses. Unconfirmed hosts are dashed. A new fact is not drawn until the packet that supports it has been read.
            13. End with what the dump does not show. Do not say the whole network was seen. When the diagram and the audit are written, call use_skill name=net-map scope=app on=false.
        """.trimIndent() + "\n",
        "ru-translate" to """
            # Translate into Russian

            Use this when the user wants a text translated into Russian, or Russian text polished: an article, a book, a game, or a program. Switch it off when the task is not translation.

            Do not stop before the state file says pass. A filled file is not a finished translation. The Russian must read as a person wrote it.

            1. Write `.ceditneuro/translate/state.md` and update it after every batch: kind, source paths, level, the next batch, and pass yes or no. While pass is no, do that next batch in this same run. Do not ask the user to continue. Do not end the turn with a plan. Do not jump back to the first line. A failed tool is not retried with the same arguments.
            2. Do not overwrite the original. Write Russian beside it, in a ru folder or a name ending in .ru. A game or a program adds a Russian slot and does not replace English, Japanese, or Korean.
            3. Before the first batch, write a canon note: who speaks to whom, ты or вы for each addressee, one Russian word per term, and what stays as printed (person names, formulas, code names, URLs, hex colors, format tags). For a speaker with many lines, add three voice notes.
            4. Translate in order, about forty lines or one chapter, with the previous and next line in view. Keep tags as they are: newline codes, {0}, %s, %d, and backslash color codes. Do not finish a sentence the source cut off. Do not add a plot event, a citation, or a brand.
            5. One source meaning stays one Russian word, inflected. Two Russian names for one term is a hole. One Russian sentence on two different source lines is a hole. Fix that class across the file, then check the level again. Do not retranslate the whole work.
            6. On each batch, read the source beside the Russian. Fail a line when a fact changed, the word is the wrong sense, a person would not say it, the voice or gender is wrong, the neighbors do not connect, a tag moved, or the line does the wrong job. Awkward but grammatical still fails.
            7. After the source language is gone, still fix English word order, «свою руку» when the source did not contrast possession, a verb turned into a noun to save space, and a jump between ты and вы. Cut a stamp, not the fact: смесь гордости и страха, едва слышным шёпотом, маяк надежды, осуществить, важно отметить. Do not move a scene or change an ending to sound human. Ellipsis is …. A dash in speech is —. Quotes are « » and inner quotes are „ “. Use ё.
            8. A stiff line gets three new Russian versions. Keep the one a person would say that still matches the source. Do not keep the first draft because it is close.
            9. Climb in the same run. Do not say done at P0, P1, or P2.
            P0. Tags match. No leftover sentence in the source language.
            P1. One address form per person. Gender matches the speaker and the person spoken to. «ты нашли» is wrong. After a digit, раз or раза matches the number, never раз(а). An adjective matches its noun.
            P2. A button is a short imperative: Начать, Продолжить, Сохранить, Выйти. Options is Настройки. Controls is Управление. A setting label is a noun. Do not pad a button to sound literary.
            P3. The line is speech or clear prose, not a gloss. Check the whole dialogue, not a short sample. Three versions on every stiff line.
            P4. Book or game: retell from the original, then from the Russian. Article: one sentence of the claim, checked against the source. Fix a hole, then retell again.
            P5. Read three samples from the start, the middle, and the end. If one fails, fix that kind through the file and read three new samples.
            P6. Game or program: search the Russian files for a user-visible sentence still in the source language. The search is done only when it is empty. Do not ask for a screenshot. Do not say a screen was checked.
            10. Article. Keep authors, years, formulas, units, figure numbers, and the citation as printed. Translate the argument, the abstract, and the captions. The reference list stays a bibliography. Pass is P5.
            11. Book. Chapters in order, and do not stop between them. The translation is a file. reader_note is only a margin note. Pass is P5.
            12. Game. Dialogue is a chain. A button is an order. A settings screen is neutral. A nameplate is not the word inside the line. Casual speech to the player is ты. A system line with no character stays neutral. Wait as a guard is Защита. Wait as skip is Ждать. Escape is Побег. End Turn is Конец хода. Do not translate sprite ids, file names, or hex colors. Add the menu item Русский. Pass is P6.
            13. Program. Translate strings a person sees, in the locale file. Translate a comment only when asked. Do not rename a function, a variable, or a key. A placeholder stays where it is. Run a test the project already has. A failed command stays failed. Pass is P6.
            14. When the state file says pass, call use_skill name=ru-translate scope=app on=false.
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
        extendTranslate(appDir)
        extendNetMap(appDir)
        extendDesign(appDir)
        extendDebug(appDir)
        extendSecurity(appDir)
        extendVideo(appDir)
    }

    /** Phones that saved the short debug skill learn to hand a real failure to the full case. */
    private fun extendDebug(appDir: File) {
        val file = File(appDir, "debug.md")
        if (!file.isFile) return
        val current = file.readText(Charsets.UTF_8)
        if (current.contains("systematic-debugging")) return
        if (!current.contains("The fix is the smallest edit")) return
        file.writeText(skills.getValue("debug"), Charsets.UTF_8)
    }

    /** Phones that saved the first security skill may read another app's data for a task. */
    private fun extendSecurity(appDir: File) {
        val file = File(appDir, "security.md")
        if (!file.isFile) return
        val current = file.readText(Charsets.UTF_8)
        if (!current.contains("Do not read another app's private files")) return
        file.writeText(skills.getValue("security"), Charsets.UTF_8)
    }

    /** Phones that saved the first video skill also learn search, a channel, a playlist, and a batch of three. */
    private fun extendVideo(appDir: File) {
        val file = File(appDir, "video-notes.md")
        if (!file.isFile) return
        val current = file.readText(Charsets.UTF_8)
        if (current.contains("action=search")) return
        if (!current.contains("video_brief")) return
        file.writeText(
            current.trimEnd() + "\n\n" +
                "Find path. A topic is video_brief action=search. A channel is action=channel. " +
                "A playlist is action=playlist. Up to 3 pages are action=batch urls separated by |. " +
                "Those list lines are titles only. Open a video before judging its meaning. " +
                "Do not sign up for a transcript service and do not store an API key for one. " +
                "Captions are cut, so a long video is the start of the public captions, not a viewing of the picture.\n",
            Charsets.UTF_8,
        )
    }

    /** Phones that saved the first design skill learn what to do when design_system is not built in. */
    private fun extendDesign(appDir: File) {
        val file = File(appDir, "frontend-design.md")
        if (!file.isFile) return
        val current = file.readText(Charsets.UTF_8)
        if (current.contains("group has no tools")) return
        if (!current.contains("call design_system")) return
        file.writeText(
            current.trimEnd() + "\n\nIf load_tools says the group has no tools, http_request that system's public page instead.\n",
            Charsets.UTF_8,
        )
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

    /** Phones that saved an earlier network skill gain the reply and audit steps. */
    private fun extendNetMap(appDir: File) {
        val file = File(appDir, "net-map.md")
        if (!file.isFile) return
        val current = file.readText(Charsets.UTF_8)
        if (current.contains("audit block")) return
        if (!current.contains("Do not record another device")) return
        file.writeText(skills.getValue("net-map"), Charsets.UTF_8)
    }

    /** Phones that saved the first translation skill gain the full cycle. A user-edited file stays as they left it. */
    private fun extendTranslate(appDir: File) {
        val file = File(appDir, "ru-translate.md")
        if (!file.isFile) return
        val current = file.readText(Charsets.UTF_8)
        if (current.contains("state file says pass")) return
        if (!current.contains("Do not say the translation is done before P5")) return
        file.writeText(skills.getValue("ru-translate"), Charsets.UTF_8)
    }
}
