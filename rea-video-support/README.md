# REA-Video integration

Source: https://github.com/JuneYaooo/rea-video · Apache-2.0.

CEditNeuro has two routes:

## On the phone

```text
load_tools group=video
rea_video path=downloads/reference.mp4
```

`rea_video` is an offline first pass. It samples frames, splits visible cuts, exports `storyboard.jpg`, `shots/`, `breakdown.json`, `report.md`, `report.html`, and `remake.html`, and returns a remake plan. The connected model rewrites `remake.html`; `render_video` then turns it into an MP4 on the phone.

It does **not** claim whether a shot is live action, AI, 3D, 2D, code, or a screen recording. It does not read speech, music, BPM, OCR, or generate prompts. Those need the full Python CLI.

## On a PC

```bash
git clone https://github.com/JuneYaooo/rea-video && cd rea-video
pip install -e ".[ocr]"
rea-video decompose ref.mp4 -o out/ref
rea-video summary out/ref/breakdown.json
rea-video review out/ref
rea-video apply-review out/ref --by <name>
```

Requires Python 3.10+ and ffmpeg. A vision model is optional; review the storyboard and low-confidence shots yourself. Reports are Chinese; translate in the answer when needed.

## Rights

Rebuild the structure with footage, music, and likeness the user has rights to. Do not reuse the source video, bypass DRM/login/paywall, upload a private video to a third-party service, or call a paid generator without consent.
