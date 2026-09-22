# CEditNeuro

**Complex Editor Neuro** — a code editor for Android with a built-in agentic chat, aimed at
the Zed desktop workflow: edit files by hand, slide the agent panel open when you want a
change made, let it work, read the report, close the panel, keep editing.

The agent runs on **DeepSeek** through its OpenAI-compatible API. Everything else — the
workspace, the tools, the conversation — stays on the device.

## Status

Milestone 1 (skeleton) is complete: the project builds as a normal Android app and every
layer is wired end to end.

| Area | State |
| --- | --- |
| Gradle project, version catalog, wrapper | done |
| Workspace + path-safe file access | done |
| File tree, tabs, editor surface | done |
| Agent loop with tool calling | done |
| DeepSeek streaming client (SSE, reasoning, tool calls) | done |
| Tools: read / write / edit / list / grep / glob | done |
| Tools: git status, git diff (JGit) | done |
| Shell via Termux (`run_command`) | done, optional |
| Collapsible chat panel | done |
| Syntax highlighting (TextMate grammars) | **not yet** — blocked on record desugaring, see `docs/ARCHITECTURE.md` |
| LSP, tree-sitter | not yet |
| Zed-style per-edit Accept/Reject diffs | not yet — edits apply immediately |

## Requirements

- Android 8.0 (API 26) or newer, arm64 device recommended.
- JDK 17 or newer, Android SDK 35 to build.
- A DeepSeek API key.
- *Optional, for builds and tests:* [Termux](https://f-droid.org/packages/com.termux/) with
  `allow-external-apps=true` in `~/.termux/termux.properties`, plus `termux-setup-storage`.

## Build

```sh
# Point Gradle at your SDK if Android Studio has not done it already.
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Or just open the folder in Android Studio.

The debug APK is around 20 MB, mostly `material-icons-extended` and JGit. Trimming the icon
dependency to the handful actually used, or building a minified release, brings that down.

## First run

1. The app asks for all-files access. It is required: the app edits project folders on shared
   storage, and Termux must see the same paths. This is why CEditNeuro is a sideloaded tool
   and not a Play Store app.
2. Tap **Choose folder** and point it at a project.
3. Open **Settings** and paste your DeepSeek API key. Base URL and model default to
   `https://api.deepseek.com` and `deepseek-flash`.
4. Tap the chat icon to slide the agent panel in.

## Why Termux for commands

An Android app sandbox ships no toolchain: no compiler, no `git` binary, no package manager.
Rather than pretend otherwise, `run_command` delegates to Termux through its `RUN_COMMAND`
intent. When Termux is absent the tool is not registered at all, the chat header says
"Termux not found — edits only", and the agent falls back to reading and editing files.

## Layout

```
app/src/main/java/com/hvkeyn/ceditneuro/
  agent/            agent loop, message models, prompts
  agent/deepseek/   streaming chat-completions client
  data/             settings and API key storage
  tools/            the capabilities the agent can call
  ui/               Compose screens, editor surface, chat panel
  workspace/        project root and path-safety rules
```

See `docs/ARCHITECTURE.md` for the design rationale and the roadmap.
