# Progress

Released: v0.45.0 (skills, memory, session search), v0.47.0 (link crash fix, Study focus, phone tools, find in book), v0.48.0, v0.49.0 (run .py/.sh, image and SVG preview, skill toggles, icon reader menu; see activeContext.md). Unit tests include `RunAndPreviewTest`.

Known gaps:
- The "Skills on for this run" chat line was not checked with a real agent run (costs tokens).
- The file tree does not refresh by itself when files appear on disk; reselecting the project reloads it.
- `reference` not exercised against the live network on the phone.
- Only one shell process handle is kept; Stop acts on the last started user command.
- Old "Link closed" lines already in chats stay; new ones appear only after a real peer.
