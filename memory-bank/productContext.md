# Product context

- Editor and file tree per project folder; chat and shell stay with each folder.
- Agent chat with work focus (Edit, Build, Remote, Study), skills, project memory, doctor report.
- The agent can turn a website or a local HTML folder into a signed WebView app and hand it to the system installer.
- Reader for txt, Markdown, HTML, FB2, EPUB: pages, find, bookmarks, notes, pen layer, book chat, read aloud. The last page is kept on this phone after the reader closes or the app exits.
- Shell inside the app (toybox, app toolchain) plus a Root switch that runs through Shizuku or su.
- Link: two phones share a 6-digit code; lead and support agents exchange tasks and files.
- Study: research log, report (md + PDF), SVG figure, `research_plot`, `calculate`, `reference`.
- Health: the agent turns on a skill for a lab sheet or a scan report, stores each printed number, marks it against the range on that sheet, and can show the trend of one test. It does not diagnose.
- Skills: when a task needs a skill this phone does not have, the agent searches by plain words, checks the file, and saves only a short adapted procedure. A dangerous prompt, a backdoor, malware, or a key is not saved.
- Design: for a product screen the agent looks up a public design system, plans one mood and two type faces, and writes one HTML page in the project. A bland draft is rejected. Google AI Studio is not opened.
- A video or motion graphic is the `hyperframes` skill: one HTML composition, timing only from the printed core page. This phone does not install a renderer and does not claim an MP4 was encoded.
- Android apps: `android-app` builds with the project's Gradle task and installs that apk. `android-debug` checks the feature on the screen. A unit test is not that screen. `android-compose` and `android-ui` keep Compose and the screen rules. A public page from android/skills is quoted. `android-apk` reads an APK before it is trusted and does not copy its code.
- Translation: `ru-translate` turns an article, a book, a game, or a program into Russian beside the original. The source stays. The agent does not stop until the state file says pass, and the Russian has to read as a person wrote it.
- Network map: `capture_dump` records this phone's public IPv4 into a pcap and stops by itself. `read_dump` names hosts and ports. `net-map` draws only what those fields support. Private ranges stay outside the recording. A ping can fail until the recording stops, because ICMP is not forwarded.
