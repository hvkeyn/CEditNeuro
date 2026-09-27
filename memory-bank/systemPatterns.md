# System patterns

- `ui/WorkspaceViewModel.kt` holds all state (`WorkspaceUiState`, one slice per project via `editProject`). Background threads post to Main before `editProject`.
- Tools implement `tools/Tool.kt`; registered in `buildAgentLoop` in the view model; grouped in `tools/ToolGroups.kt`; listed in `agent/Prompt.kt` and `tools/LoadToolsTool.kt`. A new tool touches all four plus `toolPhase` labels.
- `web_to_app` (device group) packs the `webviewshell` APK: it replaces the fixed-length package id and home-screen name, writes the page into assets, and signs with `apksig` and a key kept in the app's files. The agent then calls `install_apk`. The shell does not clone another app or change TLS.
- `agent/AgentLoop.kt`: failed-call dedup and read-only repeat skip (`READ_ONLY`).
- Skills: `agent/SkillLibrary.kt`, project scope `.ceditneuro/skills`, phone scope in app files. Built-ins in `agent/ResearchSkill.kt` (`ResearchSkill`, `StarterSkills` with a `.starter-written` marker so deleted starters stay deleted).
- `SecretText` rejects keys in anything the agent writes.
- Shell: `shell/DeviceShell.run(onProcess, onOutput)` gives live output and a process handle for Stop.
