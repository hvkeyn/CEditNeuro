# CEditNeuro

**Complex Editor Neuro** — a code editor for Android with a built-in agentic chat, aimed at
the Zed desktop workflow: edit files by hand, slide the agent panel open when you want a
change made, let it work, read the report, close the panel, keep editing.

The built-in provider is **DeepSeek** through its OpenAI-compatible API. Other providers can
be added in **Language models** settings. The workspace, the tools, and the conversation stay
on the device. API keys stay on the device too.

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
| Built-in shell (`run_command`, mksh/toybox) | done |
| FTP, FTPS, and SFTP (`remote_list`, `remote_read`, `remote_write`, `remote_put`, `remote_get`) | done |
| SSH command on the selected SFTP server (`ssh_exec`) and in-app site preview | done |
| Collapsible chat panel, shell, and in-app page preview | done |
| Tree-sitter highlighting for Java, Kotlin, Python, JSON, and XML | done |
| In-process completion and syntax diagnostics for those languages | done |
| TextMate grammars | **not yet** — blocked on record desugaring, see `docs/ARCHITECTURE.md` |
| One agent per open project, with its own shade card | done |
| Update check at the bottom of Language models settings | done — install starts only after confirmation |
| `install_jdk`, `install_android_sdk`, `install_runtime` from the Termux mirror | done |
| Zed-style per-edit Accept/Reject diffs | not yet — edits apply immediately |

## Requirements

- Android 8.0 (API 26) or newer, arm64 device recommended.
- JDK 17 or newer, Android SDK 35 to build.
- A DeepSeek API key.

## Build

```sh
# Point Gradle at your SDK if Android Studio has not done it already.
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Or just open the folder in Android Studio.

```sh
./gradlew assembleRelease
```

The release APK is about 38 MB. Most of that is the native tree-sitter libraries and JGit.
Published builds are on the [GitHub releases](https://github.com/hvkeyn/CEditNeuro/releases) page.

## First run

1. The app asks for all-files access. It is required: the app edits project folders on shared
   storage. This is why CEditNeuro is a sideloaded tool and not a Play Store app.
2. Tap **Choose folder** and point it at a project.
3. Open **Settings** (**Language models**) and paste your DeepSeek API key. The built-in
   provider defaults to `https://api.deepseek.com/v1` and `deepseek-flash`. The version name
   and **Check for updates** are at the bottom of that screen.
4. Tap the chat icon to slide the agent panel in. Opening another project does not stop the
   agent you left running.

## Built-in shell

`run_command` and the shell panel run `/system/bin/sh` inside this app. That is mksh plus
toybox (`ls`, `mkdir`, `grep`, `find`, and the other applets on the device). Nothing else
has to be installed.

This is not the Termux distribution. Termux packages expect the prefix
`/data/data/com.termux/files/usr`, so `pkg` and `apt` are not part of the shell. Git status
and diff still go through JGit. The agent can still install a JDK, the Android SDK tools,
and other runtimes into this app's toolchain with `install_jdk`, `install_android_sdk`, and
`install_runtime`. Those tools download Debian packages from the Termux mirror and unpack
them here.

## Several projects

Each open folder keeps its own chat and its own agent. Switching folders leaves the other
run going. A strip under the editor, and the project menu, show what the other projects are
doing.

The Android status shade shows one ongoing card per running project: the folder name, the
elapsed time, and that project's phase. **Stop** and **Continue** on a card apply only to
that project. When the last agent finishes, the foreground notification goes away. A stopped
or failed run leaves a separate card you can continue or swipe away.

On a short landscape screen the chat, shell, and page preview open across the editor instead
of docking into a column that is not on screen.

## Updates

**Check for updates** at the bottom of **Language models** compares this install with the
latest GitHub release. If a newer version is published, the app asks before it downloads.
Confirming starts the download and the system installer. An equal version is left as is.

## Layout

```
app/src/main/java/com/hvkeyn/ceditneuro/
  agent/            agent loop, message models, prompts
  agent/deepseek/   streaming chat-completions client
  data/             settings and API key storage
  shell/            built-in mksh/toybox shell
  tools/            the capabilities the agent can call
  ui/               Compose screens, editor surface, chat panel, shell panel
  workspace/        project root and path-safety rules
```

See `docs/ARCHITECTURE.md` for the design rationale and the roadmap.
