# Active context

Current: v0.52.0. With no validated network, or after a DNS failure, the composer shows a no-signal icon and "No connection. The agent cannot work." Send, Continue, and dictated send stay off until the network changes. A link in the agent chat opens the project file: books and notes in the reader, SVG and other previews in the editor, PDF in another app, web links in the browser. SVG fences, raw `<svg>`, and `.svg` paths draw in the chat. A Markdown note keeps an SVG or image figure and the reader draws it.

v0.51.0. save_skill, append_skill, and use_skill switch that skill on for the chat (at most 3). Checked on the phone: photo-ocr was off, the agent switched it on, and it stayed on after the run.

v0.49.0:
- Editor Run: `.py` and `.sh` get a Run button above the code (`runCommandFor` / `runActiveFile` in WorkspaceViewModel). Output streams into the Shell panel; Stop kills it. Python is installed on first run through `InstallRuntimeTool`.
- Previews (`ui/editor/FilePreview.kt`): images open in a zoomable view, SVG and HTML render in a WebView with JS off; a Code/Preview toggle switches back to text.
- Skills are toggles, not text: ticks in the Skills menu, pill reads "Skills N", chips above the composer ("name · on", "· working" while the agent runs, tap to switch off). Active skills (max 3, "scope:name", saved in `StoredSession.activeSkills`) go into the setup prompt through `buildSetupPrompt(activeSkills=…)`, 4000 chars each; the chat logs "Skills on for this run: …" at run start.
- Reader chrome: two rows — close, title with page and chapter, bookmark; then eight labelled icons (Index, Find, Listen, Text, Pen, Layer, Notes, Ask), active mode highlighted, notes badge. Pen dock: labelled tools, Done first, Clear asks "Sure?" on the first tap, icons for Smaller/Larger/Rotate/Delete.

Tests: `RunAndPreviewTest` (4).
