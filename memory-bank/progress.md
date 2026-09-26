# Progress

Released: v0.45.0 (skills, memory, session search), v0.47.0 (link crash fix, Study focus, phone tools, find in book), v0.48.0, v0.49.0, v0.50.0 (DeepSeek HTTP 400 on a reasoning-only turn, and repeated failed commands, URLs, and logins; see activeContext.md). Unit tests include `RunAndPreviewTest` and `ToolTranscriptTest`.

Known gaps:
- The file tree does not refresh by itself when files appear on disk; reselecting the project reloads it.
- `reference` not exercised against the live network on the phone.
- Only one shell process handle is kept; Stop acts on the last started user command.
- Old "Link closed" lines already in chats stay; new ones appear only after a real peer.
