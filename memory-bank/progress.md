# Progress

Released: v0.45.0 through v0.57.0 (`web_to_app` builds a signed WebView APK from a URL or local HTML; see activeContext.md). Unit tests include `RunAndPreviewTest`, `ToolTranscriptTest`, `SkillLibraryTest`, `ChatLinkTest`, and `WebAppPackTest`.

Known gaps:
- `web_to_app` is covered by `WebAppPackTest` (signed APK, Cyrillic label). It has not been installed on the phone yet.
- The file tree does not refresh by itself when files appear on disk; reselecting the project reloads it.
- `reference` not exercised against the live network on the phone.
- Only one shell process handle is kept; Stop acts on the last started user command.
- Old "Link closed" lines already in chats stay; new ones appear only after a real peer.
