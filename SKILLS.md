# CEditNeuro skills

A complete snapshot of every skill available in CEditNeuro **0.99.31 (versionCode 147)**, taken on **2026-10-22**, updated **2026-10-24**, from the phone's app-scope folder and the built-in source.

**33 skills:** 31 starter skills, 1 built-in (`research`), 1 user skill (`photo-ocr`). All are app scope on this phone; no project-scope skill was present in the checked projects.

This file is a catalog and a porting guide. The app does not load `SKILLS.md`; it loads the `.md` files in the two skill folders described below. The JSON at the end is the same inventory in a machine-readable form.

> This is the complete **list**, with each skill's trigger, tools, and shape. It is not a verbatim copy of every procedure: the executable text is the `.md` file in the skill folder. Use `adb pull /storage/emulated/0/CEditNeuro/skills` (see [Port a skill](#port-a-skill-to-another-agent)) to take the full files with you, and keep this catalog beside them. A summary can drift; the file is the source.

## Contents

- [How a skill is stored](#how-a-skill-is-stored)
- [The file contract](#the-file-contract)
- [Discovery, activation, and context](#discovery-activation-and-context)
- [Lifecycle and safety](#lifecycle-and-safety)
- [Use a skill](#use-a-skill)
- [Port a skill to another agent](#port-a-skill-to-another-agent)
- [Tool map](#tool-map)
- [Catalog](#catalog)
- [Every skill](#every-skill)
- [Machine-readable index](#machine-readable-index)

## How a skill is stored

A skill is one UTF-8 Markdown file. There is no database and no package format.

| Scope | Folder | Visible in | Written by |
| --- | --- | --- | --- |
| app / phone | `/storage/emulated/0/CEditNeuro/skills/<name>.md` | every project on this phone | `ResearchSkill.ensure` on first start, `save_skill`, or a manual copy |
| project | `<project>/.ceditneuro/skills/<name>.md` | that project only | `save_skill` with `scope=project`, or a manual copy |
| built-in source | `app/src/main/java/com/hvkeyn/ceditneuro/agent/ResearchSkill.kt` | compiled into the APK | the developer |

The app-scope folder is public shared storage, not the app's private data folder. A file manager or `adb pull` can copy it. The project-scope folder travels with the project; `.ceditneuro` is the app's own metadata folder.

`SkillLibrary` reads the project folder first, then the app folder, and sorts by scope and name. The same name may exist in both scopes; the active key is always `scope:name`, so `project:review` and `app:review` are different switches.

There is no count limit on how many skills may be on at once. Context is the real limit, not a hard cap.

## The file contract

```markdown
# Short title

Use this when the user asks for <the task>. <One or two more sentences.>

1. First step, with a tool name in backticks.
2. Second step.
3. ...
```

| Rule | Value |
| --- | --- |
| Encoding | UTF-8 |
| File name | `<name>.md` |
| Name | lowercase `a-z`, `0-9`, `-`; 2–40 characters; must start with a letter |
| Maximum size | **12,000 characters** per file (`SkillLibrary.MAX_CHARS`) |
| Catalog summary | the first line starting with `Use this` (case-insensitive), or the first non-empty line; 140 characters |
| Front matter | optional YAML-style `---` block; it must be closed. The audit strips it when adapting a remote skill |
| Code fences | balanced; an unclosed fence is reported |
| Hidden characters | zero-width characters are a finding |
| Secrets | a key, token, password, or private key blocks the skill |
| Package installers | a line that needs `npx`, `npm install`, `apt`, `sudo`, `brew`, or `pkg install` is marked unfit. It is not blocking, but it is removed from an adapted remote skill and should not be in a portable skill |
| Tool names | exact names from the target's tool registry; a skill never adds a tool or a permission |

The first `Use this when...` paragraph is the trigger. It is what `list_skills` and the setup prompt show, and it is what a routing layer should match. Keep it concrete: "Use this when a film, a movie, a clip, a stream, or a video page must be saved into the project", not "Use this for videos".

A skill may be edited by hand. The app reads the file on every `use_skill`, so an edit takes effect on the next activation.

## Discovery, activation, and context

| Step | Tool | What happens |
| --- | --- | --- |
| See what exists | `list_skills` | one line per file: `name (scope[ on]): summary` |
| Read one | `read_skill name=... scope=...` | full text, audited and capped at 12,000 characters |
| Switch on | `use_skill name=... scope=...` (`on` defaults to true) | the course text is returned now and injected into the setup prompt for later turns |
| Switch off | `use_skill name=... scope=... on=false` | removes that one key; the file stays |
| Create or replace | `save_skill name=... text=... scope=...` | audits, writes, switches on |
| Extend | `append_skill name=... text=... scope=...` | appends after a blank line, audits, switches on |
| Delete | `delete_skill name=... scope=...` | deletes the file and switches it off |

The setup prompt receives:

- a **catalog** of every skill, up to 8,000 characters (`SkillLibrary.catalog`);
- the **full text** of every active skill, each capped at 12,000 characters (`ACTIVE_SKILL_CHARS`).

The system prompt tells the model: "Skills available. Before other work, call use_skill for each one that fits this task. Any number can stay on." A skill is a procedure, not a tool: it does not add a tool, a permission, a network route, or a key.

The active set is saved in the project's chat session. The UI shows one chip per active skill. Switch a skill off when the task no longer matches; a long procedure left on spends context on every turn.

## Lifecycle and safety

1. **First start.** `ResearchSkill.ensure(appDir)` writes `research.md` from `ResearchSkill.TEXT`, then `StarterSkills.ensure(appDir)` writes every starter from `StarterSkills.skills` and records the names in `.starter-written`.
2. **Deleted starter stays deleted.** A name in `.starter-written` is never written again. This is why a user can delete a starter skill without the next update bringing it back.
3. **Upgrades.** `extendResearch` and the `extend*` functions add only missing lines to an existing starter. They do not overwrite a user's edits.
4. **Remote skills.** `find_skills` searches `skills.sh`; `review_skill` downloads one candidate, audits it, and returns only a short adapted procedure (title + `Use this when...` + checked text clipped to 2,000 characters). `save_skill` stores only that adapted text. A blocked file is not returned and not saved.
5. **Audit.** `SkillAudit` blocks a prompt-injection sentence, a hidden command, a secret, a malware-shaped line, hidden zero-width characters, and a broken file. It marks a package-installer line as unfit. The audit runs on save and on read.
6. **No escalation.** A skill cannot grant a permission or install a tool. If a step needs a tool this app does not have, the skill must say so and stop.
7. **No secrets.** Do not put a key, token, cookie, password, or private key in a skill. The audit will block it, and `SecretText` checks the same while saving.

## Use a skill

The normal loop:

```text
list_skills
use_skill name=video-download scope=app
load_tools group=video          # if the procedure names a group tool
download_video url=... quality=best
use_skill name=video-download scope=app on=false
```

Rules that matter in practice:

- Call `use_skill` for every skill whose `Use this when` line matches. Any number can be on.
- Do not call `use_skill` again for a skill that is already on; the setup prompt contains it.
- Load the group before calling a group tool. `load_tools` reports the tools this build actually registered.
- Follow the procedure; do not invent a step from memory. If the task leaves the skill's subject, switch it off.
- A skill with no tool names is still useful: it carries the order of work, the refusal rule, and the shape of the answer.

## Port a skill to another agent

A skill is portable Markdown. The procedure and the trigger travel; the tool names and the storage rules do not.

Checklist:

1. **Pick the scope.** App-wide if every project needs it; project-local if it belongs to one folder. In another agent, map this to its own global and workspace folders.
2. **Copy one file.** Keep the `# Title` and the `Use this when...` paragraph. Rename the file to the target's naming rule.
3. **Map the tools.** Replace every `load_tools group=...` and tool name with the target's equivalent. The [Tool map](#tool-map) is the contract this app provides. If a needed equivalent does not exist, rewrite that step or stop the skill.
4. **Map the paths.** `project`, `.ceditneuro`, `research/`, `video/`, `learn/`, and `downloads/` are this app's layout. Change them to the target's workspace layout.
5. **Remove app-only claims.** `install_apk`, `shizuku_exec`, `web_to_app`, `second_monitor`, `capture_dump`, and the Android screen checks do not exist on a desktop agent. Do not leave a step that pretends they do.
6. **Add the trigger.** If the target has a system prompt or router, add one line that maps the user's task to this skill. The app does this in `Prompt.kt`; a copied skill without a trigger is a file the model may never open.
7. **Keep it short.** Stay under 12,000 characters. A skill that spends 3,000 characters on background and 9,000 on steps is a bad trade; keep the procedure.
8. **Audit.** Run the same checks: no secrets, no hidden commands, no `npx`/`apt`/`sudo`, balanced fences, a concrete trigger.
9. **Test one task.** Ask the target to do the task with the skill on, check that it called the mapped tools, and switch it off. A skill is not integrated until one real task followed it.

What travels well:

- the order of work, the refusal rules, the shape of the answer, and the checklist;
- the trigger line and the short summary;
- the names of this app's core tools when the target has the same tools (`read_file`, `write_file`, `edit_file`, `grep`, `glob`, `run_command`, `git_diff`, `http_request`).

What needs work:

- tools from `build`, `device`, `remote`, `desk`, `health`, `learn`, `crew`, and `harness` groups;
- the Android-only procedures;
- the user skill `photo-ocr` (see its warning);
- paths and state files.

Export the current files:

```sh
adb pull /storage/emulated/0/CEditNeuro/skills ./ceditneuro-skills
```

The built-in source is `ResearchSkill.kt`. The phone folder is the current state, including any edit the user made and the user skill `photo-ocr.md`. A fresh install regenerates the starters, not `photo-ocr`.

## Tool map

The tools below are this app's names. A port must provide an equivalent or rewrite the step.

### Core, on every request

`list_dir`, `read_file`, `write_file`, `edit_file`, `grep`, `glob`, `mkdir`, `delete_path`, `move_path`, `git_status`, `git_diff`, `run_command`, `zip_paths`, `http_request`, `set_timer`, `load_tools`, `reader_note`, `reader_sketch`, `list_skills`, `read_skill`, `use_skill`, `save_skill`, `append_skill`, `delete_skill`, `remember`, `search_sessions`, `chart`, `layout`, `network_card`, `list_models`.

### Optional groups

| Group | Tools |
| --- | --- |
| build | install_jdk, install_android_sdk, install_runtime, install_program, install_module |
| device | install_apk, web_to_app, uninstall_apk, net_info, shizuku_exec, fetch_system_layout, execute_system_action, device_status, list_apps, open_settings, clipboard, open_file |
| remote | remote_connect, remote_list, remote_read, remote_write, remote_put, remote_get, space_sync, ssh_exec, browse_page, second_monitor, remote_mkdir, remote_delete, remote_rename |
| desk | notifications, notification_reply, notification_dismiss, mail_list, mail_read, mail_send |
| study | research_log, research_report, research_figure, research_plot, research_run, video_brief, web_search, calculate, reference, capture_dump, read_dump, net_audit |
| health | health_log, health_panel, health_trend, health_index |
| skills | find_skills, review_skill, scan_skill |
| design | design_system (declared, not registered; `load_tools design` says the group has no tools) |
| debug | debug_case |
| video | render_video, edit_video, download_video, compress_media, sound_effect, make_music, voiceover, web_search |
| learn | learn |
| crew | crew |
| harness | harness_rrsi |

`load_tools group=<name>` puts the group's schemas into the next request. A group member that is not registered in this build is not offered. `design` is the known example: `frontend-design` falls back to `http_request`.

## Catalog

Alphabetical. `Origin`: **starter** = written by `StarterSkills`; **builtin** = written by `ResearchSkill`; **user** = saved on the phone, not in the APK.

| # | Skill | Origin | Use when | Group(s) | Chars |
| --- | --- | --- | --- | --- | --- |
| 1 | [android-apk](#android-apk) | starter | an APK must be read before it is trusted | build | 6,665 |
| 2 | [android-app](#android-app) | starter | a new Android/KMP app, a feature, or its architecture | build | 3,077 |
| 3 | [android-compose](#android-compose) | starter | Compose, Navigation 3, an XML migration, edge-to-edge | — | 1,715 |
| 4 | [android-debug](#android-debug) | starter | an app crashes or a phone setting misbehaves | device, debug | 1,942 |
| 5 | [android-ui](#android-ui) | starter | how an Android screen should look | — | 774 |
| 6 | [book-notes](#book-notes) | starter | summarize, explain, or quiz from the open book | — | 462 |
| 7 | [crew](#crew) | starter | other phones are on the link and work can be split | crew | 1,051 |
| 8 | [debug](#debug) | starter | something fails on a short path | — | 479 |
| 9 | [deep-read](#deep-read) | starter | study a book, note, or scheme in front of you | — | 494 |
| 10 | [diff-tour](#diff-tour) | starter | a diff must be explained visually or handed off | device | 8,983 |
| 11 | [dreams](#dreams) | starter | a dream or nightmare is told and asks to be read | — | 4,404 |
| 12 | [find-skills](#find-skills) | starter | find or install a skill for a task | skills | 960 |
| 13 | [frontend-design](#frontend-design) | starter | a landing page, cabinet, form, or product screen | design | 4,804 |
| 14 | [health](#health) | starter | a lab sheet, scan report, or family health record | health | 1,361 |
| 15 | [hyperframes](#hyperframes) | starter | a video, animation, title card, or HyperFrames composition | video | 11,930 |
| 16 | [learn](#learn) | starter | learn a subject, be taught, or practice | learn | 2,501 |
| 17 | [net-map](#net-map) | starter | a network must be drawn from packets | study | 5,603 |
| 18 | [network-study](#network-study) | starter | a network concept or topology must be taught with a drawing | study, device | 6,531 |
| 19 | [photo-ocr](#photo-ocr) | user | a photo arrived and the message has only paths | — | 1,491 |
| 20 | [research](#research) | builtin | a study question, a claim to check, a measured build | study | 4,340 |
| 21 | [reverse-engineering](#reverse-engineering) | starter | a program must be understood to its real algorithm or a backdoor | build, device, study, remote | 10,747 |
| 22 | [review](#review) | starter | before saying a code change works | — | 364 |
| 23 | [rrsi](#rrsi) | starter | improve the agent's own harness | harness | 1,749 |
| 24 | [ru-translate](#ru-translate) | starter | translate into Russian or polish Russian | — | 4,809 |
| 25 | [second-monitor](#second-monitor) | starter | the phone becomes an extra monitor | remote | 1,556 |
| 26 | [security](#security) | starter | the task touches keys, logins, accounts, or another app | — | 415 |
| 27 | [skill-security](#skill-security) | starter | a skill must be checked for vulnerabilities before install | skills | 6,001 |
| 28 | [systematic-debugging](#systematic-debugging) | starter | a failure needs a cause before a fix | debug | 2,095 |
| 29 | [understand-anything](#understand-anything) | starter | a repository or docs tree must be understood as a whole | device | 11,086 |
| 30 | [video-download](#video-download) | starter | a film, clip, stream, or video page must be saved | video | 1,615 |
| 31 | [video-notes](#video-notes) | starter | the point of a YouTube/RuTube/Yandex/Dzen video | study | 1,679 |
| 32 | [web-reach](#web-reach) | starter | read a link, page, article, repo, thread, or feed | study | 3,454 |
| 33 | [web-to-app](#web-to-app) | starter | a website or HTML file must become an Android app | device | 793 |

## Every skill

### android-apk

**Scope:** app · **Origin:** starter · **File:** `android-apk.md` · **Size:** 6,665 chars

**Use when:** an APK must be read before it is trusted: a release you built, a colleague's test build, or an app that looks hostile. Work in phases, so a cheap check stops you before an hour of decompiling.

**Tools:** `install_android_sdk`, `install_jdk`, `install_module`, `install_program`, `run_command`, `grep`, `http_request` (group `build`).

**Shape:** fingerprint (`sha256sum`, `unzip -l`, framework by native library) → manifest, permissions, persistence → decompile with apktool/jadx → calls and endpoints → R8 names and strings → native libraries → outside view → verdict (normal/suspicious/malicious). A test copy is allowed in a separate folder; raw material is deleted after the check.

### android-app

**Scope:** app · **Origin:** starter · **File:** `android-app.md` · **Size:** 3,077 chars

**Use when:** a new Android or Kotlin Multiplatform app, a feature in one, or its architecture, data, network, storage, Gradle, or tests.

**Tools:** `install_jdk`, `install_android_sdk`, `run_command`, `install_apk`, `http_request` (group `build`); `use_skill android-debug` for the screen check.

**Shape:** read the project before adding a library; keep screen/domain/data apart; repository is the error boundary; Room vs DataStore; suspend + Flow; one-shot events; DI already in the project; build with the project's own Gradle task; install the APK; check the screen; read `github.com/android/skills` only for the step that page printed.

### android-compose

**Scope:** app · **Origin:** starter · **File:** `android-compose.md` · **Size:** 1,715 chars

**Use when:** Jetpack Compose, a screen, Navigation 3, an XML layout migration, or edge-to-edge.

**Tools:** `http_request`; no group.

**Shape:** hoisted state, stable keys, `contentPadding` rather than parent padding, `enableEdgeToEdge`, `adjustResize`, one Scaffold padding, 48 dp targets, system back, Navigation 3 typed routes, one screen at a time for XML migration. Read the public Android skill page for the exact step; the project's theme wins.

### android-debug

**Scope:** app · **Origin:** starter · **File:** `android-debug.md` · **Size:** 1,942 chars

**Use when:** an app crashes, a feature of an app you built does the wrong thing, or a phone setting misbehaves.

**Tools:** `device_status`, `shizuku_exec`, `fetch_system_layout`, `execute_system_action`, `install_apk`, `uninstall_apk`, `open_settings`, `debug_case`, `run_command` (groups `device`, `debug`).

**Shape:** load `device` and `debug`; `device_status`; start the real activity with `shizuku_exec am start`; `fetch_system_layout`; one tap at a center the dump printed; dump again; logcat filtered to the package; fix; rebuild; install; repeat the same screen check. A unit test is not the screen.

### android-ui

**Scope:** app · **Origin:** starter · **File:** `android-ui.md` · **Size:** 774 chars

**Use when:** the task is how an Android screen should look, or the user asks for a phone mockup.

**Tools:** `http_request`; `use_skill frontend-design`.

**Shape:** one job per screen, one primary button, separate empty/loading/error states, body 14sp, target 48dp, system bars and back, reduced motion, a mockup is frames in one HTML file. The project's design wins. Turn the skill off after the mockup.

### book-notes

**Scope:** app · **Origin:** starter · **File:** `book-notes.md` · **Size:** 462 chars

**Use when:** the user asks to summarize, explain, or quiz from the open book.

**Tools:** `reader_note`, `calculate`.

**Shape:** work only from the page text the reader sent; `reader_note` for one short note; a chapter summary is 5–8 points with pages; terms are term + one line + page; numbers go through `calculate`.

### crew

**Scope:** app · **Origin:** starter · **File:** `crew.md` · **Size:** 1,051 chars

**Use when:** other phones are on this link and the work can be split.

**Tools:** `crew` (group `crew`); load `crew`.

**Shape:** `list` → `add` independent pieces → `claim` one open id → post `FACT`, `FAIL`, or `DONE` → mark `done`. There is no lead; do not duplicate a claimed or done task; do not wait for an assignment. Turn it off when the shared task is over.

### debug

**Scope:** app · **Origin:** starter · **File:** `debug.md` · **Size:** 479 chars

**Use when:** something fails.

**Tools:** no specific tool; `use_skill review`; `systematic-debugging` for a failure longer than one line.

**Shape:** restate the failure from the output you saw; one hypothesis; one read or command; change the hypothesis if it fails; smallest fix; `use_skill review`. Do not patch before a long case says pass.

### deep-read

**Scope:** app · **Origin:** starter · **File:** `deep-read.md` · **Size:** 494 chars

**Use when:** the user wants to study a book, a note, or a scheme.

**Tools:** `calculate`; the reader/attachment context.

**Shape:** only the opened page/file/picture; separate what the text says from inference and label the inference; quote a number instead of recalculating unless asked; for a scheme name the boxes and arrows that are drawn; end with a short checklist.

### diff-tour

**Scope:** app · **Origin:** starter · **File:** `diff-tour.md` · **Size:** 8,983 chars

**Use when:** a branch, a commit, a pull request, a working-tree diff, or a change just written must be explained visually, reviewed, or handed to someone who will own it.

**Tools:** `git_status`, `git_diff`, `read_file`, `grep`, `run_command`, `write_file`, `open_file`, `layout`, `load_tools` (group `device` for `open_file`).

**Shape:** collect the real diff and the surrounding code; state the purpose and the promises; map core, mechanical, test, config, and docs; write a diff-tour v1 `tour.json` with chapters, questions, file roles, code, diff, flow, and timeline; draw a compact `layout` card in the chat; write a self-contained `tour.html` with inline CSS and no script, and open it; label claims **Verified**, **From the code**, or **Senior view**; report findings as must, suggestion, or nit. The diff-tour TUI is a PC tool; the phone produces the JSON for it and renders the same tour as HTML.

### dreams

**Scope:** app · **Origin:** starter · **File:** `dreams.md` · **Size:** 4,404 chars

**Use when:** the user tells a dream, a nightmare, or a recurring dream and asks what it means, or asks for a psychologist to talk a dream through.

**Tools:** `write_file`, `edit_file` (only for a requested journal).

**Shape:** Jungian, but every meaning is a hypothesis. Drama structure; images/people on subject and object levels; amplification, not a dream dictionary; archetypes only when the image carries one; emotional background; compensation; two or three real-life possibilities; two or three small steps; headings. Not a diagnosis. Suicide, self-harm, violence, or a post-trauma recurring nightmare first gets a live therapist or crisis line. The dreamer's own sense has the last word.

### find-skills

**Scope:** app · **Origin:** starter · **File:** `find-skills.md` · **Size:** 960 chars

**Use when:** the user wants something this chat cannot do yet, or asks to find or install a skill.

**Tools:** `find_skills`, `review_skill`, `save_skill` (group `skills`).

**Shape:** `find_skills` with plain words → pick one → `review_skill` → only the adapted text may be `save_skill` → turn off. A blocked result is not saved and not switched on. No `npx`, `apt`, `sudo`, or downloaded program.

### frontend-design

**Scope:** app · **Origin:** starter · **File:** `frontend-design.md` · **Size:** 4,804 chars

**Use when:** the user wants a landing page, a cabinet, a form, or another product screen.

**Tools:** `http_request`, `write_file`; `design_system` is declared in group `design` but not registered, so the skill falls back to `http_request`.

**Shape:** one job for the screen; a short plan with four to six color roles, two type roles, one layout sentence, one signature element; `write_file` one HTML page with a plain style block; system or Google fonts; no package, MCP, or image generator; read Refero / awesome-design-md / component.gallery / kinetics.colorion.co / scrolltide.co / the Google guide only for a step that page printed; reject the generic purple-gradient/glass/emoji template. A video of the motion uses `hyperframes`.

### health

**Scope:** app · **Origin:** starter · **File:** `health.md` · **Size:** 1,361 chars

**Use when:** the user asks about a lab sheet, a scan report, or a family health record.

**Tools:** `health_log`, `health_panel`, `health_trend`, `health_index` (group `health`).

**Shape:** read only the named file; save each printed value with its unit and range; `health_panel`; a value outside the sheet's range is reported as such and a doctor is suggested; no disease name from a number; quote a scan impression only as written; the record stays in the project; turn off when done.

### hyperframes

**Scope:** app · **Origin:** starter · **File:** `hyperframes.md` · **Size:** 11,930 chars (near the cap)

**Use when:** the user wants a video, an animation, a motion graphic, a title card, a slideshow, or a HyperFrames composition.

**Tools:** `render_video`, `edit_video`, `sound_effect`, `make_music`, `voiceover`, `web_search`, `http_request`, `write_file`, `edit_file` (group `video`).

**Shape:** one HTML composition; a beat list with start, duration, one idea, entrance; read the printed HyperFrames core/house-style/composition/animation pages when available, else use the local bar; one palette, two faces; materials and facts only when the scene uses them, with a printed free license and a credit line; audio tags with volume lanes; `render_video`; a first render is a draft, three variations of the weakest beat, one kept; `video/plan.md` and `video/receipt.md`; frame 0, fade-in, no pop, no layer covering another. No FFmpeg, Node, or renderer install; no upload unless asked.

### learn

**Scope:** app · **Origin:** starter · **File:** `learn.md` · **Size:** 2,501 chars

**Use when:** the user wants to learn a subject, be taught, or practice until they can explain why something follows.

**Tools:** `learn` (group `learn`), `http_request`, `web_search`.

**Shape:** Locate → Weave → Build; `learn action=save` before teaching; one link at a time; a check is `action=frame`; the learner answers before `action=assess`; choice or open verdict (`accurate`, `needs-repair`, `knowledge-gap`); confidence optional; `action=load` on resume; `action=due` before the notebook; notebook/notes/diagrams; verify an uncertain fact with a page. Write in the user's language.

### net-map

**Scope:** app · **Origin:** starter · **File:** `net-map.md` · **Size:** 5,603 chars

**Use when:** the user wants a network drawn from packets.

**Tools:** `net_audit`, `capture_dump`, `read_dump`, `research_figure` (group `study`).

**Shape:** `net_audit` for this phone's own Wi-Fi; `capture_dump` for its public conversations, or `read_dump` for a saved pcap; walk packets in order; Ethernet, DHCP, ARP, CDP/LLDP, routing advertisements, TCP handshakes; audit block (answered/silent, cleartext, plain DNS, QUIC, a secret field in the clear — never the value); `research/net-map.md` and `research/net-audit.md`; trail match against `research/trails.txt`; one small SVG with `research_figure`; say what the dump does not show. Never print a payload, cookie, or password.

### network-study

**Scope:** app · **Origin:** starter · **File:** `network-study.md` · **Size:** 6,531 chars

**Use when:** a network concept, topology, packet path, subnet, or troubleshooting lesson should be taught with a drawing in the chat.

**Tools:** `net_audit`, `capture_dump`, `read_dump`, `write_file`, `network_card`, `open_file`, `load_tools` (groups `study`, `device`).

**Shape:** one idea per card; ask for a prediction; call `network_card` with 1–8 devices and their links; the tool draws the glyphs, labels, subnet boundary, legend, and packet path and returns the `html` block, which is included unchanged; optionally write an interactive `network.html` canvas and open it; use `net_audit`, `capture_dump`, and `read_dump` for observed data, and `net-map` for a pcap map. Phone adaptation of Computer-Network (MIT): the chat card is the lesson, the browser canvas is the larger builder. No scan or device exposure without consent.

### photo-ocr

**Scope:** app · **Origin:** user (not in the APK) · **File:** `photo-ocr.md` on the phone only · **Size:** 1,491 chars

**Use when:** the user attaches a photo or screenshot and the message contains only paths: the model receives the path and metadata, not the image.

**Tools:** `run_command` (Python upload), `read_file`, `remote_delete` (group `remote`); external services: `litterbox.catbox.moe` and `api.ocr.space`.

**Shape:** inspect the file → upload it to litterbox for one hour → OCR.space with a key from the project's `ocr.py` → read `ParsedResults[].ParsedText` → delete the remote copy → ask for a closer fragment if a wide photo loses small text.

**Warning:** this skill uploads the image to a public host and sends the URL to an OCR service. It needs OCR keys that live in a project (`ocr.py`), not in the skill. Do not copy the keys into a catalog, a chat, or another agent. For a private image, do not run it without the user's explicit agreement. It is user-specific and is not recreated by a fresh install. `remote_delete` is in the `remote` group and the procedure does not call `load_tools`; load that group for the cleanup step or skip the remote copy.

### research

**Scope:** app · **Origin:** builtin (`ResearchSkill.TEXT`) · **File:** `research.md` · **Size:** 4,340 chars

**Use when:** a study question, a check of a claim, or building something that has to be measured.

**Tools:** `research_log`, `research_report`, `research_figure`, `research_plot`, `research_run`, `reference`, `calculate`, `chart`, `open_file`, `http_request`, `grep` (group `study`).

**Shape:** short path — question, hypothesis, evidence, check, build if needed, report, figure/plot. Deep path — `research_run open` with tier `light` or `full`; plan; sources with locator/quote/claim; light 3–8 independent sources, full 8–24; tension; one draft then patches; critics (cite; independence and gap for full); cite each attributed sentence; `pass` only when every gate holds. One agent; no research package; no invented DOI/citation/measurement. Turn off when `pass` prints.

### reverse-engineering

**Scope:** app · **Origin:** starter · **File:** `reverse-engineering.md` · **Size:** 10,747 chars

**Use when:** a program, an APK or DEX, a native library, a firmware image, or a script must be understood beyond its screen: recover the real algorithm, a hidden branch, a key, a protocol, a packer, or a backdoor inserted by a developer or by someone redistributing the software.

**Tools:** `install_android_sdk`, `install_jdk`, `install_module`, `install_program`, `shizuku_exec`, `install_apk`, `open_file`, `capture_dump`, `read_dump`, `net_audit`, `remote_put`, `ssh_exec`, `run_command`, `read_file`, `write_file`, `edit_file`, `grep`, `http_request` (groups `build`, `device`, `study`, `remote`).

**Shape:** authorization and provenance, hashes, and isolation first; map the format and the Android layers; recover entry points, call graph, constants, triggers, algorithms, native code, and the dynamic behavior; use Paranoia on a PC for PE, ELF, and Mach-O (not DEX, no ARM32, no debugger, partial pseudocode); compare the official and redistributed builds; prove a backdoor by a trigger and an action path; report evidence, test vectors, indicators, confidence, and what was not run. A suspected malware sample is not run on this daily phone; without a disposable device, stop at static analysis.

### review

**Scope:** app · **Origin:** starter · **File:** `review.md` · **Size:** 364 chars

**Use when:** before saying a code change works.

**Tools:** `git_diff`, `read_file`, `run_command`.

**Shape:** `git_diff` the changed files and quote the important hunk; run one check that would fail if the change were wrong; say what was checked and what was not; a failed command stays failed and is not rerun unchanged.

### rrsi

**Scope:** app · **Origin:** starter · **File:** `rrsi.md` · **Size:** 1,749 chars

**Use when:** the user asks the agent to improve its own prompts, skills, tools, memory, or control notes.

**Tools:** `harness_rrsi`, `delete_skill` (group `harness`).

**Shape:** Doctor report as the only measurement; one hypothesis naming one component and one printed failure; `harness_rrsi action=screen` for a leak; change the harness; let a run finish; a new Doctor report; `action=judge`; prune a component that is listed; the model weights are not trained. Do not invent a score.

### ru-translate

**Scope:** app · **Origin:** starter · **File:** `ru-translate.md` · **Size:** 4,809 chars

**Use when:** the user wants a text translated into Russian, or Russian text polished: an article, a book, a game, or a program.

**Tools:** `read_file`, `write_file`, `edit_file`, `run_command`, `reader_note`, `reference`.

**Shape:** `.ceditneuro/translate/state.md` with kind, paths, level, next batch, pass; never overwrite the original; canon note (`ты`/`вы`, one term one word, what stays printed); batches of about forty lines or one chapter; preserve tags/placeholders; fix a class of hole across the file; P0–P6; three versions of every stiff line; article/book pass P5, game/program P6; turn off when state says pass.

### second-monitor

**Scope:** app · **Origin:** starter · **File:** `second-monitor.md` · **Size:** 1,556 chars

**Use when:** the user wants this phone as an extra monitor for a Windows or Linux computer they own.

**Tools:** `second_monitor`, `remote_connect`, `remote_write`, `remote_read`, `ssh_exec` (group `remote`).

**Shape:** Windows — the existing adb tunnel at `http://127.0.0.1:8791/`; `second_monitor action=open` with that exact address, never an invented one. Linux — Sway or Hyprland only; `remote_connect` to the server already in Settings; write the printed script unchanged; run it with `nohup`, redirect to `second-monitor.log`, read the state path; open the printed address; stop with the same script and `stop`. Do not download a display program, ask for a password in chat, print the address, or mirror the main screen.

### security

**Scope:** app · **Origin:** starter · **File:** `security.md` · **Size:** 415 chars

**Use when:** the task touches keys, logins, accounts, or another app.

**Tools:** none.

**Shape:** never write a token, password, or key into a file, skill, or chat; read another app's files when the task needs them (the shell can); do not invent an Android permission; a failed host or command is not tried again.

### skill-security

**Scope:** app · **Origin:** starter · **File:** `skill-security.md` · **Size:** 6,001 chars

**Use when:** a skill must be checked for vulnerabilities, compared with another skill, or judged safe to install.

**Tools:** `scan_skill`, `review_skill`, `find_skills`, `save_skill`, `load_tools` (group `skills`).

**Shape:** load `skills`; `scan_skill` a saved skill, a pasted text, or a remote candidate after `review_skill`; read the 0–100 score, severity, recommendation, findings with lines and fixes, and the strengths; explain SAFE, CAUTION, or DO_NOT_INSTALL; say what a static scan did not check. It is our local static scanner: no execution, no upload, no LLM, no live CVE lookup.

### systematic-debugging

**Scope:** app · **Origin:** starter · **File:** `systematic-debugging.md` · **Size:** 2,095 chars

**Use when:** something fails: a test, a build, a crash, or behavior the user did not expect.

**Tools:** `debug_case`, `run_command`, `read_file`, `grep`, `git_diff` (group `debug`).

**Shape:** `debug_case open` → reproduce with the same check and `result=fail` (or `flaky`, find the condition) → facts from `git_diff`, `read_file`, `grep` → one hypothesis → one test (`supports`/`rules-out`) → cause → smallest fix → verify with the same check and `pass`; after three failed fixes stop and question the design; `use_skill review`; turn off. An Android screen uses `android-debug` as the same verify check.

### understand-anything

**Scope:** app · **Origin:** starter · **File:** `understand-anything.md` · **Size:** 11,086 chars

**Use when:** a repository, a subsystem, or a documentation or wiki tree must be understood as a whole: architecture, dependencies, layers, a guided reading order, or the impact of a change.

**Tools:** `list_dir`, `glob`, `read_file`, `grep`, `run_command`, `git_status`, `git_diff`, `write_file`, `open_file`, `layout`, `load_tools` (group `device` for `open_file`).

**Shape:** resolve the project root and commit; skip generated, vendor, and secret files; bound the pass to 300 files and 1,500 nodes; inventory files and manifests; add file, function, class, config, service, endpoint, table, and document nodes with stable IDs; add imports, contains, calls, depends_on, configures, deploys, and documents edges; assign layers; build a 5–10 step tour; write `.ua/knowledge-graph.json`; draw a static `.ua/understand.html` and a `layout` card. It is the phone adaptation of Understand Anything (MIT); the original Node, pnpm, tree-sitter, five-agent, React dashboard is a PC tool and is not installed here.

### video-download

**Scope:** app · **Origin:** starter · **File:** `video-download.md` · **Size:** 1,615 chars

**Use when:** the user wants a film, a movie, a clip, a stream, or a video page saved into the project. A note about a video is `video-notes`; this skill saves the file.

**Tools:** `download_video` (group `video`).

**Shape:** load `video`; a direct `.mp4`, `.webm`, `.m3u8`, or `.mpd`, or a page URL, goes to `download_video`; `quality` best/1080/720/480/360/worst/audio; folder defaults to `downloads`; a direct file and HLS are read on the phone (AES-128, TS remuxed to MP4); a page yt-dlp knows may install Python and yt-dlp once; no ffmpeg, no DRM/paywall/login bypass; a browser extension/share/`vidbee://` link arrives as the first message. Long download: do not cancel or start a second. Report path, bytes, format, seconds; open the file.

### video-notes

**Scope:** app · **Origin:** starter · **File:** `video-notes.md` · **Size:** 1,679 chars

**Use when:** the user sends a YouTube, RuTube, Yandex, or Dzen video and wants the point, the important ideas, or whether it is worth watching.

**Tools:** `video_brief`, `calculate` (group `study`).

**Shape:** `video_brief` the page; do not download or open a stream/m3u8/mp4/googlevideo host; use only the printed title, description, and captions; if `captions is no`, say the picture was not seen; write Смысл / Важные мысли (3–7) / Вывод / Стоит ли смотреть; numbers via `calculate`; search/channel/playlist return titles only; no transcript service or API key; turn off.

### web-reach

**Scope:** app · **Origin:** starter · **File:** `web-reach.md` · **Size:** 3,454 chars

**Use when:** the user shares a link, wants a page, article, repository, forum thread, or feed read, or asks to look something up, search the web, or see what people say about a topic.

**Tools:** `http_request`, `web_search`, `video_brief` (group `study`).

**Shape:** say which route served each page (direct, reader, API, `video_brief`); content is the page text, not HTTP 200, a title list, a login wall, or a check page; direct `http_request` → `https://r.jina.ai/URL` with `Accept: text/plain` for a script-heavy/anti-bot page → stop; search via `web_search`; GitHub API without a token; HN/V2EX APIs; RSS; video → `video-notes`; Reddit/X/Instagram/Facebook/LinkedIn/XiaoHongShu need a login and are not logged into; no cookies/token/password; turn off when done.

### web-to-app

**Scope:** app · **Origin:** starter · **File:** `web-to-app.md` · **Size:** 793 chars

**Use when:** the user wants a website or an HTML file as an installable Android app.

**Tools:** `web_to_app`, `install_apk` (group `device`).

**Shape:** `web_to_app` with name + `url` or `html` (project file or folder with `index.html`); it writes an APK and returns the path/package id; `install_apk`; the user confirms the system installer; same name + same page updates, a different name installs a second app; a local folder works offline, a URL needs network; no clone, package change, TLS change, hide, or proxy.

## Machine-readable index

The JSON below is the same inventory. `origin` is `starter`, `builtin`, or `user`; `scope` is the app's storage scope; `source` points at the built-in Kotlin object or the phone file. `requires_skills` names another skill the procedure tells the agent to switch on; it is not a tool. `LIMITS` and `TOOL_GROUPS` are constants of this app, not of a skill.

```json
{
  "schema": 1,
  "app": "CEditNeuro 0.99.31 (versionCode 147)",
  "generated": "2026-10-24",
  "skill_count": 33,
  "limits": {
    "skill_chars": 12000,
    "active_skill_chars": 12000,
    "catalog_chars": 8000,
    "name_chars": 40,
    "summary_chars": 140,
    "remote_adapted_chars": 2000
  },
  "tool_groups": {
    "core": ["list_dir", "read_file", "write_file", "edit_file", "grep", "glob", "mkdir", "delete_path", "move_path", "git_status", "git_diff", "run_command", "zip_paths", "http_request", "set_timer", "load_tools", "reader_note", "reader_sketch", "list_skills", "read_skill", "use_skill", "save_skill", "append_skill", "delete_skill", "remember", "search_sessions", "chart", "layout", "network_card", "list_models"],
    "build": ["install_jdk", "install_android_sdk", "install_runtime", "install_program", "install_module"],
    "device": ["install_apk", "web_to_app", "uninstall_apk", "net_info", "shizuku_exec", "fetch_system_layout", "execute_system_action", "device_status", "list_apps", "open_settings", "clipboard", "open_file"],
    "remote": ["remote_connect", "remote_list", "remote_read", "remote_write", "remote_put", "remote_get", "space_sync", "ssh_exec", "browse_page", "second_monitor", "remote_mkdir", "remote_delete", "remote_rename"],
    "desk": ["notifications", "notification_reply", "notification_dismiss", "mail_list", "mail_read", "mail_send"],
    "study": ["research_log", "research_report", "research_figure", "research_plot", "research_run", "video_brief", "web_search", "calculate", "reference", "capture_dump", "read_dump", "net_audit"],
    "health": ["health_log", "health_panel", "health_trend", "health_index"],
    "skills": ["find_skills", "review_skill", "scan_skill"],
    "design": ["design_system"],
    "debug": ["debug_case"],
    "video": ["render_video", "edit_video", "download_video", "compress_media", "sound_effect", "make_music", "voiceover", "web_search"],
    "learn": ["learn"],
    "crew": ["crew"],
    "harness": ["harness_rrsi"]
  },
  "skills": [
    {"name": "android-apk", "scope": "app", "origin": "starter", "trigger": "read an APK before it is trusted", "groups": ["build"], "tools": ["install_android_sdk", "install_jdk", "install_module", "install_program", "run_command", "grep", "http_request"], "chars": 6665, "file": "android-apk.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "android-app", "scope": "app", "origin": "starter", "trigger": "a new Android or Kotlin Multiplatform app, a feature, or its architecture", "groups": ["build"], "tools": ["install_jdk", "install_android_sdk", "run_command", "install_apk", "http_request"], "requires_skills": ["android-debug", "android-apk"], "chars": 3077, "file": "android-app.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "android-compose", "scope": "app", "origin": "starter", "trigger": "Jetpack Compose, a screen, Navigation 3, XML migration, edge-to-edge", "groups": [], "tools": ["http_request"], "chars": 1715, "file": "android-compose.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "android-debug", "scope": "app", "origin": "starter", "trigger": "an app crashes or a phone setting misbehaves", "groups": ["device", "debug"], "tools": ["device_status", "shizuku_exec", "fetch_system_layout", "execute_system_action", "install_apk", "uninstall_apk", "open_settings", "debug_case", "run_command"], "requires_skills": ["systematic-debugging"], "chars": 1942, "file": "android-debug.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "android-ui", "scope": "app", "origin": "starter", "trigger": "how an Android screen should look", "groups": [], "tools": ["http_request"], "requires_skills": ["frontend-design"], "chars": 774, "file": "android-ui.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "book-notes", "scope": "app", "origin": "starter", "trigger": "summarize, explain, or quiz from the open book", "groups": [], "tools": ["reader_note", "calculate"], "chars": 462, "file": "book-notes.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "crew", "scope": "app", "origin": "starter", "trigger": "other phones are on the link and work can be split", "groups": ["crew"], "tools": ["crew"], "chars": 1051, "file": "crew.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "debug", "scope": "app", "origin": "starter", "trigger": "something fails on a short path", "groups": [], "tools": [], "requires_skills": ["review", "systematic-debugging"], "chars": 479, "file": "debug.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "deep-read", "scope": "app", "origin": "starter", "trigger": "study a book, note, or scheme in front of you", "groups": [], "tools": ["calculate"], "chars": 494, "file": "deep-read.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "diff-tour", "scope": "app", "origin": "starter", "trigger": "a diff must be explained visually or handed off", "groups": ["device"], "tools": ["git_status", "git_diff", "read_file", "grep", "run_command", "write_file", "open_file", "layout", "load_tools"], "chars": 8983, "file": "diff-tour.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "dreams", "scope": "app", "origin": "starter", "trigger": "a dream or nightmare is told and asks to be read", "groups": [], "tools": ["write_file", "edit_file"], "chars": 4404, "file": "dreams.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "find-skills", "scope": "app", "origin": "starter", "trigger": "find or install a skill for a task", "groups": ["skills"], "tools": ["find_skills", "review_skill", "save_skill"], "chars": 960, "file": "find-skills.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "frontend-design", "scope": "app", "origin": "starter", "trigger": "a landing page, cabinet, form, or product screen", "groups": ["design"], "tools": ["http_request", "write_file", "design_system"], "requires_skills": ["hyperframes", "web-to-app"], "chars": 4804, "file": "frontend-design.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "health", "scope": "app", "origin": "starter", "trigger": "a lab sheet, scan report, or family health record", "groups": ["health"], "tools": ["health_log", "health_panel", "health_trend", "health_index"], "chars": 1361, "file": "health.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "hyperframes", "scope": "app", "origin": "starter", "trigger": "a video, animation, title card, or HyperFrames composition", "groups": ["video"], "tools": ["render_video", "edit_video", "sound_effect", "make_music", "voiceover", "web_search", "http_request", "write_file", "edit_file"], "chars": 11930, "file": "hyperframes.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "learn", "scope": "app", "origin": "starter", "trigger": "learn a subject, be taught, or practice", "groups": ["learn"], "tools": ["learn", "http_request", "web_search"], "chars": 2501, "file": "learn.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "net-map", "scope": "app", "origin": "starter", "trigger": "a network must be drawn from packets", "groups": ["study"], "tools": ["net_audit", "capture_dump", "read_dump", "research_figure"], "chars": 5603, "file": "net-map.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "network-study", "scope": "app", "origin": "starter", "trigger": "a network concept or topology must be taught with a drawing", "groups": ["study", "device"], "tools": ["net_audit", "capture_dump", "read_dump", "write_file", "network_card", "open_file", "load_tools"], "chars": 6531, "file": "network-study.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "photo-ocr", "scope": "app", "origin": "user", "trigger": "a photo arrived and the message has only paths", "groups": [], "tools": ["run_command", "read_file", "remote_delete"], "chars": 1491, "file": "photo-ocr.md", "source": "phone:CEditNeuro/skills/photo-ocr.md"},
    {"name": "research", "scope": "app", "origin": "builtin", "trigger": "a study question, a claim to check, a measured build", "groups": ["study"], "tools": ["research_log", "research_report", "research_figure", "research_plot", "research_run", "reference", "calculate", "chart", "open_file", "http_request", "grep"], "requires_skills": ["deep-read", "review", "debug", "security"], "chars": 4340, "file": "research.md", "source": "ResearchSkill.kt#TEXT"},
    {"name": "reverse-engineering", "scope": "app", "origin": "starter", "trigger": "a program must be understood to its real algorithm or a backdoor", "groups": ["build", "device", "study", "remote"], "tools": ["install_android_sdk", "install_jdk", "install_module", "install_program", "shizuku_exec", "install_apk", "open_file", "capture_dump", "read_dump", "net_audit", "remote_put", "ssh_exec", "run_command", "read_file", "write_file", "edit_file", "grep", "http_request"], "requires_skills": ["android-apk"], "chars": 10747, "file": "reverse-engineering.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "review", "scope": "app", "origin": "starter", "trigger": "before saying a code change works", "groups": [], "tools": ["git_diff", "read_file", "run_command"], "chars": 364, "file": "review.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "rrsi", "scope": "app", "origin": "starter", "trigger": "improve the agent's own harness", "groups": ["harness"], "tools": ["harness_rrsi", "delete_skill"], "chars": 1749, "file": "rrsi.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "ru-translate", "scope": "app", "origin": "starter", "trigger": "translate into Russian or polish Russian", "groups": [], "tools": ["read_file", "write_file", "edit_file", "run_command", "reader_note", "reference"], "chars": 4809, "file": "ru-translate.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "second-monitor", "scope": "app", "origin": "starter", "trigger": "the phone becomes an extra monitor", "groups": ["remote"], "tools": ["second_monitor", "remote_connect", "remote_write", "remote_read", "ssh_exec"], "chars": 1556, "file": "second-monitor.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "security", "scope": "app", "origin": "starter", "trigger": "the task touches keys, logins, accounts, or another app", "groups": [], "tools": [], "chars": 415, "file": "security.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "skill-security", "scope": "app", "origin": "starter", "trigger": "a skill must be checked for vulnerabilities before install", "groups": ["skills"], "tools": ["scan_skill", "review_skill", "find_skills", "save_skill", "load_tools"], "chars": 6001, "file": "skill-security.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "systematic-debugging", "scope": "app", "origin": "starter", "trigger": "a failure needs a cause before a fix", "groups": ["debug"], "tools": ["debug_case", "run_command", "read_file", "grep", "git_diff"], "requires_skills": ["review", "android-debug"], "chars": 2095, "file": "systematic-debugging.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "understand-anything", "scope": "app", "origin": "starter", "trigger": "a repository or docs tree must be understood as a whole", "groups": ["device"], "tools": ["list_dir", "glob", "read_file", "grep", "run_command", "git_status", "git_diff", "write_file", "open_file", "layout", "load_tools"], "chars": 11086, "file": "understand-anything.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "video-download", "scope": "app", "origin": "starter", "trigger": "a film, clip, stream, or video page must be saved", "groups": ["video"], "tools": ["download_video"], "chars": 1615, "file": "video-download.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "video-notes", "scope": "app", "origin": "starter", "trigger": "the point of a YouTube or RuTube or Yandex or Dzen video", "groups": ["study"], "tools": ["video_brief", "calculate"], "chars": 1679, "file": "video-notes.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "web-reach", "scope": "app", "origin": "starter", "trigger": "read a link, page, article, repo, thread, or feed", "groups": ["study"], "tools": ["http_request", "web_search", "video_brief"], "requires_skills": ["video-notes"], "chars": 3454, "file": "web-reach.md", "source": "ResearchSkill.kt#StarterSkills"},
    {"name": "web-to-app", "scope": "app", "origin": "starter", "trigger": "a website or HTML file must become an Android app", "groups": ["device"], "tools": ["web_to_app", "install_apk"], "chars": 793, "file": "web-to-app.md", "source": "ResearchSkill.kt#StarterSkills"}
  ]
}
```
