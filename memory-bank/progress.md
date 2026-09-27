# Progress

Released: v0.45.0 through v0.54.0 (a scheme or photo opens full screen and pinches to zoom; the agent turns on every skill that fits the task; see activeContext.md). Unit tests include `RunAndPreviewTest`, `ToolTranscriptTest`, `SkillLibraryTest`, and `ChatLinkTest`.

Known gaps:
- The file tree does not refresh by itself when files appear on disk; reselecting the project reloads it.
- `reference` not exercised against the live network on the phone.
- Only one shell process handle is kept; Stop acts on the last started user command.
- Old "Link closed" lines already in chats stay; new ones appear only after a real peer.
