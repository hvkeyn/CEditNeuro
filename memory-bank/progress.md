# Progress

Released: v0.45.0 through v0.75.0 (`frontend-design` rejects a bland draft: one mood, two type faces, no empty chips; see activeContext.md). Unit tests include `DesignCatalogTest` and `VideoBriefTest`. Unit tests include `ResearchRunTest`. Unit tests include `RunAndPreviewTest`, `ToolTranscriptTest`, `SkillLibraryTest`, `ChatLinkTest`, `WebAppPackTest`, `HealthRecordTest`, `SkillAuditTest`, and `ReaderPlaceTest`.

Known gaps:
- v0.75.0 is released. A bland screen is a failed draft. The Google design guide is quoted from the page. Google AI Studio is not opened.
- `video-notes` ships in v0.71.0 with search, a channel, a playlist, and a batch of three. A live chat against a video was not run. `frontend-design` on the phone gains the surface steps when this build starts.
- The reader finger fix is in this tree: left, right, center, swipe, and the volume key were checked on the phone before v0.69.0. A scheme page was not opened in that check.

- Phone was checked on v0.67.0: HTTP works during a recording, and `net_audit` with and without sweep. The recorder does not retransmit or honor the client's window, so a large download during a recording may stall. The agent chat was not run against the model.
- Phone was checked on v0.66.0: a recording through the main-thread path finished without a crash. The agent chat itself was not run against the model for this check.
- Phone was checked on v0.65.0. Two short recordings wrote a pcap and a summary with no cookie or password. The VPN dialog was accepted and the tunnel closed itself. A public ping was not answered while ICMP was inside the tunnel. `ru-translate` was checked on v0.64.0. The reader on v0.63.0 kept page 4 of 1192. A wrapped site APK has not been installed as its own app.
- The file tree does not refresh by itself when files appear on disk; reselecting the project reloads it.
- `reference` not exercised against the live network on the phone.
- Only one shell process handle is kept; Stop acts on the last started user command.
- Old "Link closed" lines already in chats stay; new ones appear only after a real peer.
