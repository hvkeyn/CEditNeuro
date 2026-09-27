# Progress

Released: v0.45.0 through v0.58.0 (health skill logs a lab sheet and marks each number against the printed range; see activeContext.md). Unit tests include `RunAndPreviewTest`, `ToolTranscriptTest`, `SkillLibraryTest`, `ChatLinkTest`, `WebAppPackTest`, and `HealthRecordTest`.

Known gaps:
- `web_to_app` is covered by `WebAppPackTest` (signed APK, Cyrillic label). It has not been installed on the phone yet.
- The file tree does not refresh by itself when files appear on disk; reselecting the project reloads it.
- `reference` not exercised against the live network on the phone.
- Only one shell process handle is kept; Stop acts on the last started user command.
- Old "Link closed" lines already in chats stay; new ones appear only after a real peer.
