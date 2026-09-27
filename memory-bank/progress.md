# Progress

Released: v0.45.0 through v0.64.0 (`ru-translate` keeps going until the Russian reads as a person wrote it; see activeContext.md). Unit tests include `RunAndPreviewTest`, `ToolTranscriptTest`, `SkillLibraryTest`, `ChatLinkTest`, `WebAppPackTest`, `HealthRecordTest`, `SkillAuditTest`, `DesignCatalogTest`, and `ReaderPlaceTest`.

Known gaps:
- Phone was checked on v0.64.0. `ru-translate` is in the skills menu and switches on and off. The reader on v0.63.0 kept page 4 of 1192. A wrapped site APK has not been installed as its own app.
- The file tree does not refresh by itself when files appear on disk; reselecting the project reloads it.
- `reference` not exercised against the live network on the phone.
- Only one shell process handle is kept; Stop acts on the last started user command.
- Old "Link closed" lines already in chats stay; new ones appear only after a real peer.
