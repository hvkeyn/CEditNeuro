# CEditNeuro Video Handoff

A small browser extension that sends the video on the current tab to CEditNeuro on the
phone. The phone's agent then downloads it into the open project. It is the browser
companion the download tool needs: the page stays in the browser, the file lands in the
project, and the phone does the work.

The endpoint shape follows the [VidBee](https://github.com/nexmoe/VidBee) extension
(MIT): `/status`, `/token`, and `/video-info` on a loopback port. VidBee's own extension
can therefore discover this app too; this folder adds `/download` so one click starts the
phone agent.

## Install

### On a phone browser that supports extensions

1. Open the extensions page of that browser and turn on developer mode.
2. Choose **Load unpacked** and pick this `tools/extension` folder.
3. Open a video page, tap the CEditNeuro icon, choose a quality, then **Download on the
   phone**.

### On a computer (Chrome, Edge, Chromium)

1. Connect the phone by USB and run:
   ```sh
   adb forward tcp:27100 tcp:27100
   ```
   This makes `127.0.0.1:27100` on the computer reach the phone's loopback port.
2. Open `chrome://extensions`, turn on developer mode, choose **Load unpacked**, and pick
   `tools/extension`.
3. Open a video page and use the popup.

If CEditNeuro picks a different port (27101–27120), forward that one instead. The popup
prints the port it found.

## What it calls

| Endpoint | Purpose |
| --- | --- |
| `GET /status` | Is the phone app there, and on which port? |
| `GET /token` | A per-process token. `/video-info` and `/download` need it. |
| `GET /video-info?url=&token=` | Title, duration, thumbnail, and formats. |
| `GET /download?url=&quality=&token=` | Starts one agent run that downloads the link. |

A request from a web page (not an extension) is refused, so a site cannot read the token
through the wildcard CORS answer. `/status` stays open for discovery.

`?url=` on `popup.html` is a self-check: it lets the popup run as an ordinary page when a
test needs a fixed tab address. A real popup reads the active tab through `activeTab`.

## Limits

- The phone must be on and CEditNeuro must be open. The app listens on `127.0.0.1` only.
- A DRM, paid, or login-only video is refused by the phone. This extension does not bypass
  it.
- The phone needs a project folder open. If the agent is busy, the link waits and starts
  after the current run.
