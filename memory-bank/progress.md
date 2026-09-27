# Progress

Released: v0.45.0 through v0.55.0 (the menu button hides the project list so the file list can grow; see activeContext.md). Unit tests include `RunAndPreviewTest`, `ToolTranscriptTest`, `SkillLibraryTest`, and `ChatLinkTest`.

Known gaps:
- The file tree does not refresh by itself when files appear on disk; reselecting the project reloads it.
- `reference` not exercised against the live network on the phone.
- Only one shell process handle is kept; Stop acts on the last started user command.
- Old "Link closed" lines already in chats stay; new ones appear only after a real peer.
