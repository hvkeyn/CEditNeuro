# Progress

Released: v0.45.0 through v0.65.0 (`capture_dump` records this phone into a pcap and `read_dump` names hosts and ports; see activeContext.md). Unit tests include `PacketDumpTest`. Unit tests include `RunAndPreviewTest`, `ToolTranscriptTest`, `SkillLibraryTest`, `ChatLinkTest`, `WebAppPackTest`, `HealthRecordTest`, `SkillAuditTest`, `DesignCatalogTest`, and `ReaderPlaceTest`.

Known gaps:
- Phone was checked on v0.65.0. Two short recordings wrote a pcap and a summary with no cookie or password. The VPN dialog was accepted and the tunnel closed itself. A public ping was not answered while ICMP was inside the tunnel. `ru-translate` was checked on v0.64.0. The reader on v0.63.0 kept page 4 of 1192. A wrapped site APK has not been installed as its own app.
- The file tree does not refresh by itself when files appear on disk; reselecting the project reloads it.
- `reference` not exercised against the live network on the phone.
- Only one shell process handle is kept; Stop acts on the last started user command.
- Old "Link closed" lines already in chats stay; new ones appear only after a real peer.
