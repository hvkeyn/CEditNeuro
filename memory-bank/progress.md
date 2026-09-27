# Progress

Released: v0.45.0 through v0.62.0 (packages the commit already on main; see activeContext.md). Unit tests include `RunAndPreviewTest`, `ToolTranscriptTest`, `SkillLibraryTest`, `ChatLinkTest`, `WebAppPackTest`, `HealthRecordTest`, `SkillAuditTest`, and `DesignCatalogTest`.

Known gaps:
- Phone was checked on v0.62.0. The existing projects are still listed, and frontend-design is still in the skills menu. A wrapped site APK has not been installed as its own app.
- The file tree does not refresh by itself when files appear on disk; reselecting the project reloads it.
- `reference` not exercised against the live network on the phone.
- Only one shell process handle is kept; Stop acts on the last started user command.
- Old "Link closed" lines already in chats stay; new ones appear only after a real peer.
