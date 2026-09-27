# System patterns

- `ui/WorkspaceViewModel.kt` holds all state (`WorkspaceUiState`, one slice per project via `editProject`). Background threads post to Main before `editProject`.
- Tools implement `tools/Tool.kt`; registered in `buildAgentLoop` in the view model; grouped in `tools/ToolGroups.kt`; listed in `agent/Prompt.kt` and `tools/LoadToolsTool.kt`. A new tool touches all four plus `toolPhase` labels.
- `web_to_app` (device group) packs the `webviewshell` APK: it replaces the fixed-length package id and home-screen name, writes the page into assets, and signs with `apksig` and a key kept in the app's files. The agent then calls `install_apk`. The shell does not clone another app or change TLS.
- `agent/AgentLoop.kt`: failed-call dedup and read-only repeat skip (`READ_ONLY`).
- Skills: `agent/SkillLibrary.kt`, project scope `.ceditneuro/skills`, phone scope in app files. Built-ins in `agent/ResearchSkill.kt` (`ResearchSkill`, `StarterSkills` with a `.starter-written` marker so deleted starters stay deleted). `use_skill` with `on` false switches a skill off without deleting the file.
- Health: skill `health`, tools `health_log`, `health_panel`, `health_trend`, and `health_index` in the `health` group. Rows live in `.ceditneuro/health/readings.tsv`, which space sync skips. A row is below or above only against the range saved with it. Trend math is min, max, mean, and last-versus-first. An existing `health.md` gains those steps; a deleted file stays deleted. The tools do not name a disease.
- Skills from the public directory: group `skills`, tools `find_skills` and `review_skill`. Search is `https://skills.sh/api/search`. The file is fetched only from `raw.githubusercontent.com` paths built from `owner/repo` and the skill id. `SkillAudit` blocks a dangerous prompt, a hidden command, malware, and a key before `save_skill` or `use_skill`. The adapted text is what may be saved. A deleted starter stays deleted.
- `SecretText` rejects keys in anything the agent writes.
- Shell: `shell/DeviceShell.run(onProcess, onOutput)` gives live output and a process handle for Stop.
