# CineCode support

Upstream: https://github.com/sarpstar/cinecode · MIT.

The upstream kit is a desktop film system: Node 22+, Python, ffmpeg, headless Chrome, three.js, six-gate Film mode, and a −14 LUFS master. It is **not installed on this phone**.

CEdiNeuro uses the direction rules only:

```text
use_skill cinecode
write one HTML composition
load_tools group=video
render_video path=film.html out=film.mp4
```

On the phone:

- one self-contained HTML composition;
- CSS/Canvas/JS, no three.js or Node;
- `render_video` encoder;
- no loudness normalization, so no `−14 LUFS` claim;
- open the MP4 and report its real path, duration, size, and frame count.

For the full kit, use the user's own computer and follow upstream `SKILL.md`. Do not copy its scripts into a project or install Node/ffmpeg/Chrome on this phone.
