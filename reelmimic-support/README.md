# ReelMimic support

Upstream: https://github.com/edenfunf/reelmimic · MIT.

ReelMimic upstream is a local web studio, not a skill for this phone:

- Node 22.18+ and a Vite/React server;
- Python 3.10+ with OpenCV, librosa, scenedetect, Pillow, yt-dlp;
- FFmpeg and Chrome;
- Claude Code or Codex CLI;
- a workflow server with up to six agent workers;
- projects under `projects/<id>` and an HTTP/SSE UI.

It does not run inside CEdiNeuro.

## Phone adaptation

```text
use_skill reelmimic
rea_video path=reference.mp4
# read storyboard + timing; write an original 2D plan
# one HTML/CSS/SVG/Canvas animation
load_tools group=video
render_video path=film.html out=film.mp4
```

Supported code styles: vector/motion graphics, watercolor look, crayon look, pixel art, paper cut-out, whiteboard, anime cel look.

Limits: no frame-by-frame painting engine, no six workers, no FFmpeg/Chrome, no real people, no reuse of source footage/characters/music. The MP4 is a code animation, not ReelMimic's painted output.

For the full studio, use a PC and the upstream `install.sh`/`start.sh`; do not install Node/Python here.
