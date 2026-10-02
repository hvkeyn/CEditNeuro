# CEditNeuro

A code editor, a book reader, a shell, and an agent. On one Android phone.

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/hvkeyn/CEditNeuro/releases/latest)
[![Release](https://img.shields.io/github/v/release/hvkeyn/CEditNeuro?logo=github)](https://github.com/hvkeyn/CEditNeuro/releases/latest)
[![API](https://img.shields.io/badge/minSdk-26-blue)](https://github.com/hvkeyn/CEditNeuro)

Edit a project by hand. Open the agent when you want a change made. Read the report, close the panel, keep editing. The workspace, the tools, and the conversation stay on the device. API keys stay on the device too.

**[Download the latest APK](https://github.com/hvkeyn/CEditNeuro/releases/latest)** · [All releases](https://github.com/hvkeyn/CEditNeuro/releases)

## What you can do

| | |
| --- | --- |
| **Edit** | File tree, tabs, tree-sitter highlighting for Java, Kotlin, Python, JSON, and XML. Run an open `.py` or `.sh` from the editor. |
| **Agent** | Tool calling, skills, project memory, and a Doctor report. The model is not trained. The harness around it can change. |
| **Read** | txt, Markdown, HTML, FB2, and EPUB, with pages, notes, bookmarks, and read-aloud. |
| **Phone** | Install an APK, turn a web page into an app, a timer, mail, FTP, and SFTP. |
| **Study** | A checked research report, a chart from numbers, and notes from a public video page. |

DeepSeek is built in, through its API or a sign-in on this phone. Qwen web chat is the same idea: sign in once inside the app, no key and no computer. Other providers can be added in Settings.

## Install

1. Download the APK from [the latest release](https://github.com/hvkeyn/CEditNeuro/releases/latest).
2. Allow install from this source and confirm the system installer.
3. Grant all-files access. The app edits project folders on shared storage, which is why it is sideloaded and not a Play Store app.
4. Tap **Choose folder** and point it at a project.
5. Open **Settings**. Paste a DeepSeek API key, or use **DeepSeek Web** / **Qwen Web** and sign in on the page.

**Check for updates** sits at the bottom of Language models. A newer release asks before it downloads.

## First hour

- The chat icon slides the agent in. The paperclip attaches a photo or a file.
- **Doctor** reads saved sessions and lists mistakes that repeat, including the same miss in the chats and texts. The report keeps the kind of miss, not the sentence.
- Long project and skill menus stay on screen and scroll.
- Opening another project does not stop the agent you left running.
- The menu button hides the project list so the file list can use the height.

## Build

JDK 17 and Android SDK 35.

```sh
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleRelease
```

The release APK is in `app/build/outputs/apk/release/`. Most of its size is the tree-sitter libraries and JGit.

## What stays on the phone

Settings, the API key, saved servers, and each project's chat are copied to `CEditNeuro/profiles` on shared storage. Uninstalling the app deletes its private files. The next install loads that profile after all-files access is allowed.

That folder contains the API key and server passwords. Do not copy it into a repository or a public release.

## Shell

`run_command` is mksh and toybox inside this app. It is not Termux: there is no `pkg` or `apt`. Git status and diff go through JGit. A JDK, the Android SDK tools, and other runtimes can be unpacked into this app's toolchain from the Termux mirror.

An absolute path is a real directory on this phone. `HOME` is this app's private directory. `DOWNLOAD` is the public Downloads folder.

## Reading

Long-press a file and choose **Read**, or tap the book icon. Pages fit the screen. Pictures inside FB2, EPUB, and HTML get their own page. **Pen**, **Notes**, **Contents**, and **Book** stay with that file. PNG, JPG, WebP, GIF, BMP, SVG, and HTML also open as a preview, with a switch back to the text.

## Layout

```
app/src/main/java/com/hvkeyn/ceditneuro/
  agent/        loop, prompts, skills
  data/         settings and the API key
  shell/        mksh and toybox
  tools/        what the agent can call
  ui/           Compose screens
  workspace/    project root and path rules
```

Design notes live in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Not in this build

TextMate grammars are not wired yet. Edits apply immediately. There is no Zed-style Accept or Reject on each hunk.
