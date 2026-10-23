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
| **Video** | A reel editor for a video already in the project. Drag a block along a track or onto another video or audio track. Add an effect or a transition, then render an MP4 on the phone. |
| **Download** | A film, a clip, or a stream from a page is saved into the project. A direct MP4, an HLS playlist including AES-128, or a site yt-dlp can read. A browser extension sends the current tab to the agent. |
| **Agent** | Tool calling, skills, project memory, and a Doctor report. The model is not trained. The harness around it can change. It can also write a motion reel and render it. |
| **Read** | txt, Markdown, HTML, FB2, and EPUB, with pages, notes, bookmarks, and read-aloud. |
| **Phone** | Install an APK, turn a web page into an app, a timer, mail, FTP, and SFTP. |
| **Study** | A checked research report, a chart or a card drawn in the chat, a network audit that matches trails, and notes from a public video page. |

DeepSeek is built in, through its API or a sign-in on this phone. The API model is `deepseek-flash`, shown as DeepSeek V4.1 Flash 1017. Settings can fetch the models a host is offering, including another OpenAI-compatible service and a local server with no key. Qwen web chat is the same idea: sign in once inside the app, no key and no computer. Other providers can be added in Settings.

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

## Video

Open a video and tap **Edit**. The reel keeps picture tracks and sound tracks in one file next to the video. Hold a block and drag it along its track, or onto another track of the same kind. An empty track at the edge takes a block onto a new layer. Effects cover fade, gray, warm, zoom, mute, blur, and flip. Transitions are a hard cut, a dissolve, a wipe, and a dip to black. Save writes the same reel. Render encodes an MP4 in the project. While a reel gathers pictures and sound, it also searches one Scrolltide template and keeps only the motion that page printed. A layer starts at its first frame and fades in. It does not pop on, and it does not cover a layer that is still in the shot. The catalog is not stored. The agent can also shrink a jpg, png, webp, or mp4 that is already in the project. The phone's own encoder does that. CompressO is not installed.

## Download

Ask the agent for a film or a clip, paste a video page, or send the page from the browser extension. `download_video` saves it under `downloads/` in the project, then the video tab opens it and the reel editor can cut it.

- A direct MP4, WebM, or MOV is streamed to disk and resumes from its `.part`.
- An HLS playlist is resolved on the phone, including AES-128 segments. An MPEG-TS result is remuxed to MP4 with the phone's own extractor, so it plays here.
- A page yt-dlp knows goes through yt-dlp. The first such page may install Python and yt-dlp into the app's toolchain; this happens on the phone and does not install a second app.
- A DRM-protected, paid, or login-only video is refused. The app does not bypass DRM or log in for the user.
- A site that offers only separate picture and sound tracks is refused when yt-dlp is the engine. An HLS master with a separate audio group saves the picture track and says so; the phone has no ffmpeg to join them.

**[Browser extension](tools/extension)** · The popup sends the current tab to the phone. It calls the app on `127.0.0.1:27100–27120` and needs the token from `/token`. The app also listens for a `vidbee://download?url=` link and for the Android share sheet, so the VidBee extension and any browser can hand off a page. The bridge is loopback-only, and a request from a web page is refused.

## Skills

The agent loads Markdown procedures from `CEditNeuro/skills` on this phone and from `.ceditneuro/skills` in the project. It can also search a public catalog, check a downloaded file, and save only the adapted procedure. **[SKILLS.md](SKILLS.md)** is the complete catalog: all 31 skills, their triggers and tools, the file contract, the activation path, and how to move one to another agent.

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
