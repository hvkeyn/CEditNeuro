# Progress

Released: v0.45.0 through v0.50.0, v0.51.0 (a saved skill switches on and is followed; see activeContext.md). Unit tests include `RunAndPreviewTest`, `ToolTranscriptTest`, and `SkillLibraryTest`.

Known gaps:
- The file tree does not refresh by itself when files appear on disk; reselecting the project reloads it.
- `reference` not exercised against the live network on the phone.
- Only one shell process handle is kept; Stop acts on the last started user command.
- Old "Link closed" lines already in chats stay; new ones appear only after a real peer.
