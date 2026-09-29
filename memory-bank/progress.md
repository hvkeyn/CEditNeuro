# Progress

Released: v0.45.0 through v0.69.0 (`research_run` keeps a deep study in order until its cites match the quotes; see activeContext.md). Unit tests include `ResearchRunTest`. Unit tests include `RunAndPreviewTest`, `ToolTranscriptTest`, `SkillLibraryTest`, `ChatLinkTest`, `WebAppPackTest`, `HealthRecordTest`, `SkillAuditTest`, `DesignCatalogTest`, and `ReaderPlaceTest`.

Known gaps:
- The reader finger fix is in this tree: left, right, center, swipe, and the volume key were checked on the phone before v0.69.0. A scheme page was not opened in that check.

- Phone was checked on v0.67.0: HTTP works during a recording, and `net_audit` with and without sweep. The recorder does not retransmit or honor the client's window, so a large download during a recording may stall. The agent chat was not run against the model.
- Phone was checked on v0.66.0: a recording through the main-thread path finished without a crash. The agent chat itself was not run against the model for this check.
- Phone was checked on v0.65.0. Two short recordings wrote a pcap and a summary with no cookie or password. The VPN dialog was accepted and the tunnel closed itself. A public ping was not answered while ICMP was inside the tunnel. `ru-translate` was checked on v0.64.0. The reader on v0.63.0 kept page 4 of 1192. A wrapped site APK has not been installed as its own app.
- The file tree does not refresh by itself when files appear on disk; reselecting the project reloads it.
- `reference` not exercised against the live network on the phone.
- Only one shell process handle is kept; Stop acts on the last started user command.
- Old "Link closed" lines already in chats stay; new ones appear only after a real peer.
