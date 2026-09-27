# Active context

Current: v0.59.0. The health skill also has `health_trend` (min, max, mean, and whether the last number is higher or lower) and `health_index` (every saved test and its latest value). `topic` is the specialty printed on the sheet. A phone that already had the health skill gains these steps; a deleted skill stays deleted. The record stays in `.ceditneuro/health` and is not copied to the other phone. This does not diagnose and does not turn several tests into a new disease name.

v0.58.0. The agent turns on the health skill for a lab sheet or a scan report, saves each printed number with `health_log`, and `health_panel` marks it against the range on that sheet. `use_skill` with `on` false turns the skill off without deleting it.

v0.57.0. The agent can build a signed WebView app from a website or a project HTML folder (`web_to_app` in the device group, skill `web-to-app`) and install it with `install_apk`. The shell is the `webviewshell` module. Package id and home-screen name are fixed-length slots. The signing key stays in the app's private files. This does not clone another app, spoof TLS, or hide the device.

v0.56.0. The paperclip offers Camera, Photos, and Files. The menu button hides and shows the project list and Choose folder, so the file list can use that height. Choose folder is a full-width button with a folder icon. File rows, tabs, and the top bar use a 44 dp touch size, an 8 dp spacing step, and a tinted selection. Chat and shell sit on a raised surface. The agent reads the skill list in the setup prompt and calls use_skill for every skill that fits the task. Any number can stay on; ticking one in the menu no longer drops another. A tap on a scheme opens it full screen (checked on the phone, in the reader and in the editor). The scheme is a WebView, so the pinch is the WebView's own zoom, up to 8×, and the close control sits above the view. A photo uses the Compose pinch (1×–8×, drag, reset at 1×). The ink layer does not take taps while reading, and the page-turn row sits behind the page so a scheme tap is not a page turn. Four phone skills were added from the useful part of alirezarezvani/claude-skills (MIT): review, debug, security, and deep-read. They are short procedures for this app's tools. The other few hundred skills in that catalog call Claude Code tools this phone does not have, so they are not installed.

v0.53.0. Scheme files are named in backticks (`scheme.svg`) and live under the project, not only at its root. The reader embeds those names, and the chat draws the file it finds. SVG is loaded as base64 so `url(#…)` arrows survive, and the frame follows the scheme's own width and height. Checked on the phone: the study note shows the scheme on the next page, and the chat draws the charts under the file names.

v0.52.0. With no validated network, or after a DNS failure, the composer shows a no-signal icon and "No connection. The agent cannot work." Send, Continue, and dictated send stay off until the network changes. A link in the agent chat opens the project file: books and notes in the reader, SVG and other previews in the editor, PDF in another app, web links in the browser.

v0.51.0. save_skill, append_skill, and use_skill switch that skill on for the chat (at most 3). Checked on the phone: photo-ocr was off, the agent switched it on, and it stayed on after the run.

v0.49.0:
- Editor Run: `.py` and `.sh` get a Run button above the code (`runCommandFor` / `runActiveFile` in WorkspaceViewModel). Output streams into the Shell panel; Stop kills it. Python is installed on first run through `InstallRuntimeTool`.
- Previews (`ui/editor/FilePreview.kt`): images open in a zoomable view, SVG and HTML render in a WebView with JS off; a Code/Preview toggle switches back to text.
- Skills are toggles, not text: ticks in the Skills menu, pill reads "Skills N", chips above the composer ("name · on", "· working" while the agent runs, tap to switch off). Active skills (max 3, "scope:name", saved in `StoredSession.activeSkills`) go into the setup prompt through `buildSetupPrompt(activeSkills=…)`, 4000 chars each; the chat logs "Skills on for this run: …" at run start.
- Reader chrome: two rows — close, title with page and chapter, bookmark; then eight labelled icons (Index, Find, Listen, Text, Pen, Layer, Notes, Ask), active mode highlighted, notes badge. Pen dock: labelled tools, Done first, Clear asks "Sure?" on the first tap, icons for Smaller/Larger/Rotate/Delete.

Tests: `RunAndPreviewTest` (4).
