package com.hvkeyn.ceditneuro.video

/**
 * A HyperFrames composition is ordinary HTML: a root with data-composition-id and a
 * paused GSAP timeline on window.__timelines. This app is the runtime. It creates the
 * registry before the page scripts run, so the page does not start its own player,
 * then shows the clip at the playhead and drives the timeline.
 */
object Composition {
    data class Info(val id: String, val width: Int, val height: Int, val seconds: Double)

    private val ROOT = Regex("""<[a-zA-Z][^>]*\bdata-composition-id\s*=\s*["']([^"']+)["'][^>]*>""")
    private val VIEWPORT = Regex("""<meta[^>]*name\s*=\s*["']viewport["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val CLIP = Regex("""<[a-zA-Z][^>]*\bdata-start\s*=\s*["']([\d.]+)["'][^>]*>""")

    fun isComposition(html: String): Boolean = ROOT.containsMatchIn(html)

    fun parse(html: String): Info? {
        val root = ROOT.find(html) ?: return null
        val tag = root.value
        val width = attr(tag, "data-width")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: 1920
        val height = attr(tag, "data-height")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: 1080
        val declared = attr(tag, "data-duration")?.toDoubleOrNull()?.takeIf { it > 0 }
        val seconds = declared ?: CLIP.findAll(html).maxOfOrNull { match ->
            val start = match.groupValues[1].toDoubleOrNull() ?: 0.0
            start + (attr(match.value, "data-duration")?.toDoubleOrNull() ?: 0.0)
        }?.takeIf { it > 0 } ?: 0.0
        return Info(root.groupValues[1], width, height, seconds)
    }

    /** The page with this app as its runtime. play adds a loop and a player bar; render leaves the frame still. */
    fun hosted(html: String, play: Boolean): String {
        val first = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no\">" +
            "<script>window.__timelines=window.__timelines||{};window.__hfHost=true;</script>"
        val last = "<script>" + HARNESS + (if (play) PLAYER + "window.__hfPlay();" else "") + "</script>"
        val page = VIEWPORT.replace(html, "")
        val head = Regex("""<head[^>]*>""", RegexOption.IGNORE_CASE).find(page)
        val withFirst = if (head != null) {
            page.substring(0, head.range.last + 1) + first + page.substring(head.range.last + 1)
        } else {
            first + page
        }
        val close = withFirst.lastIndexOf("</body>", ignoreCase = true)
        return if (close >= 0) withFirst.substring(0, close) + last + withFirst.substring(close) else withFirst + last
    }

    /** Output size: the composition's aspect, the long side at most [maxSide], both even. */
    fun outputSize(info: Info, maxSide: Int): Pair<Int, Int> {
        val long = maxOf(info.width, info.height).toDouble()
        val scale = if (long > maxSide) maxSide / long else 1.0
        fun even(value: Double) = (value.toInt() / 2 * 2).coerceAtLeast(2)
        return even(info.width * scale) to even(info.height * scale)
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)

    private val HARNESS = """
        (function(){
          var root = document.querySelector('[data-composition-id]');
          if (!root) return;
          var id = root.getAttribute('data-composition-id');
          var W = +root.getAttribute('data-width') || 1920;
          var H = +root.getAttribute('data-height') || 1080;
          var hf = window.__hf = { speed: 1, muted: false };
          function line() { return window.__timelines && window.__timelines[id]; }
          function length() {
            var d = parseFloat(root.getAttribute('data-duration'));
            if (d > 0) return d;
            var t = line();
            return t && t.duration ? t.duration() : 0;
          }
          hf.length = length;
          function fit() {
            var html = document.documentElement, body = document.body;
            var vw = window.__hfVW || window.innerWidth, vh = window.__hfVH || window.innerHeight;
            html.style.margin = '0'; html.style.overflow = 'hidden'; html.style.background = '#000';
            html.style.width = vw + 'px'; html.style.height = vh + 'px';
            body.style.margin = '0'; body.style.overflow = 'hidden'; body.style.transform = 'none';
            body.style.position = 'fixed'; body.style.left = '0'; body.style.top = '0';
            body.style.width = vw + 'px'; body.style.height = vh + 'px';
            root.style.position = 'absolute'; root.style.left = '0'; root.style.top = '0';
            root.style.width = W + 'px'; root.style.height = H + 'px';
            var s = Math.min(vw / W, vh / H) || 1;
            root.style.transformOrigin = '0 0';
            root.style.transform = 'translate(' + (vw - W * s) / 2 + 'px,' + (vh - H * s) / 2 + 'px) scale(' + s + ')';
          }
          hf.fit = fit;
          window.__hfSize = function(w, h) { window.__hfVW = w; window.__hfVH = h; fit(); return 'ok'; };
          function start(el) {
            var s = parseFloat(el.getAttribute('data-start')) || 0;
            if (el.getAttribute('data-hf-media-start-basis') === 'global') return s;
            for (var p = el.parentElement; p && p !== root; p = p.parentElement) {
              if (p.hasAttribute('data-composition-id')) s += parseFloat(p.getAttribute('data-start')) || 0;
            }
            return s;
          }
          function lane(el) {
            if (el.__hfLane !== undefined) return el.__hfLane;
            el.__hfLane = null;
            try {
              var a = JSON.parse(el.getAttribute('data-automation') || 'null');
              var lanes = (a && a.lanes) || [];
              for (var i = 0; i < lanes.length; i++) {
                if (lanes[i].target === 'volume' && lanes[i].points && lanes[i].points.length) {
                  el.__hfLane = lanes[i].points.map(function(p) { return [+p.t || 0, +p.v]; }).sort(function(x, y) { return x[0] - y[0]; });
                }
              }
            } catch (e) {}
            return el.__hfLane;
          }
          function laneAt(points, t) {
            if (!points) return 1;
            if (t <= points[0][0]) return points[0][1];
            for (var i = 1; i < points.length; i++) {
              if (t <= points[i][0]) {
                var a = points[i - 1], b = points[i], span = b[0] - a[0];
                return span <= 0 ? b[1] : a[1] + (b[1] - a[1]) * (t - a[0]) / span;
              }
            }
            return points[points.length - 1][1];
          }
          function media(el, t, s, on, playing) {
            if (el.tagName !== 'VIDEO' && el.tagName !== 'AUDIO') return;
            var rate = parseFloat(el.getAttribute('data-playback-rate')) || 1;
            var local = (t - s) * rate + (parseFloat(el.getAttribute('data-media-start')) || 0);
            if (!on) { if (!el.paused) el.pause(); return; }
            if (el.tagName === 'AUDIO') {
              var v = parseFloat(el.getAttribute('data-volume'));
              v = (isNaN(v) ? 1 : v) * laneAt(lane(el), t - s);
              el.volume = Math.max(0, Math.min(1, v));
              el.muted = hf.muted;
            }
            var want = Math.max(0.0625, Math.min(16, rate * hf.speed));
            if (el.playbackRate !== want) el.playbackRate = want;
            if (playing) {
              if (Math.abs(el.currentTime - local) > 0.3) el.currentTime = Math.max(0, local);
              if (el.paused) { var p = el.play(); if (p && p.catch) p.catch(function(){}); }
            } else {
              if (!el.paused) el.pause();
              if (Math.abs(el.currentTime - local) > 0.01) el.currentTime = Math.max(0, local);
            }
          }
          function timed() {
            var list = Array.prototype.slice.call(root.querySelectorAll('[data-start]'));
            var loose = document.querySelectorAll('audio[data-start],video[data-start]');
            for (var i = 0; i < loose.length; i++) if (!root.contains(loose[i])) list.push(loose[i]);
            return list;
          }
          function show(t, playing) {
            var els = timed();
            for (var i = 0; i < els.length; i++) {
              var el = els[i];
              if (el === root) continue;
              var s = start(el);
              var d = parseFloat(el.getAttribute('data-duration'));
              var on = t >= s && (isNaN(d) || t < s + d);
              if (el.tagName !== 'AUDIO') el.style.visibility = on ? '' : 'hidden';
              media(el, t, s, on, playing);
            }
          }
          function seek(t, playing) {
            var tl = line();
            if (tl) { if (tl.paused && !tl.paused()) tl.pause(); tl.seek(t, false); }
            show(t, playing);
          }
          hf.seek = seek;
          hf.pauseMedia = function() {
            var list = document.querySelectorAll('audio,video');
            for (var i = 0; i < list.length; i++) if (!list[i].paused) list[i].pause();
          };
          window.__hfInfo = function() {
            fit();
            return JSON.stringify({ id: id, w: W, h: H, d: length(), tl: !!line() });
          };
          window.__hfMedia = function() {
            var out = [], list = document.querySelectorAll('audio');
            for (var i = 0; i < list.length; i++) {
              var el = list[i];
              if (el.hasAttribute('muted')) continue;
              var src = el.getAttribute('src');
              if (!src) { var so = el.querySelector('source[src]'); if (so) src = so.getAttribute('src'); }
              if (!src) continue;
              var d = parseFloat(el.getAttribute('data-duration'));
              var v = parseFloat(el.getAttribute('data-volume'));
              out.push({
                id: el.id || ('audio-' + i),
                src: src,
                url: el.currentSrc || el.src || '',
                start: start(el),
                duration: isNaN(d) ? null : d,
                mediaStart: parseFloat(el.getAttribute('data-media-start')) || 0,
                volume: isNaN(v) ? 1 : v,
                rate: parseFloat(el.getAttribute('data-playback-rate')) || 1,
                lane: lane(el) || []
              });
            }
            return JSON.stringify(out);
          };
          window.__hfFrame = function(t) { fit(); seek(t, false); return 'ok'; };
          window.__hfPending = function() {
            var n = 0, list = root.querySelectorAll('video,img');
            for (var i = 0; i < list.length; i++) {
              var el = list[i];
              if (el.style.visibility === 'hidden') continue;
              if (el.tagName === 'VIDEO' && (el.seeking || el.readyState < 2)) n++;
              if (el.tagName === 'IMG' && !el.complete) n++;
            }
            if (document.fonts && document.fonts.status === 'loading') n++;
            return n;
          };
        })();
    """.trimIndent()

    private val PLAYER = """
        window.__hfPlay = function() {
          var hf = window.__hf;
          if (!hf) return;
          hf.fit();
          window.addEventListener('resize', hf.fit);
          document.addEventListener('fullscreenchange', function() { setTimeout(hf.fit, 50); stamp(); });
          var ICON = {
            play: 'M8 5v14l11-7z',
            pause: 'M6 5h4v14H6zM14 5h4v14h-4z',
            restart: 'M6 6h2v12H6zM9.5 12l8.5 6V6z',
            back: 'M11 18V6l-8.5 6zM12 12l8.5 6V6z',
            fwd: 'M4 18l8.5-6L4 6zM13 6v12l8.5-6z',
            loop: 'M7 7h10v3l4-4-4-4v3H5v6h2zM17 17H7v-3l-4 4 4 4v-3h12v-6h-2z',
            sound: 'M3 9v6h4l5 5V4L7 9zM16.5 12A4.5 4.5 0 0 0 14 8v8a4.5 4.5 0 0 0 2.5-4z',
            mute: 'M3 9v6h4l5 5V4L7 9zM16 9l5 6M21 9l-5 6',
            full: 'M7 14H5v5h5v-2H7zM5 10h2V7h3V5H5zM17 17h-3v2h5v-5h-2zM14 5v2h3v3h2V5z',
            exit: 'M5 16h3v3h2v-5H5zM8 8H5v2h5V5H8zM14 19h2v-3h3v-2h-5zM16 8V5h-2v5h5V8z'
          };
          function svg(name) {
            var stroke = name === 'mute' ? ' stroke="#fff" stroke-width="2"' : '';
            return '<svg viewBox="0 0 24 24" width="24" height="24" fill="#fff"' + stroke + '><path d="' + ICON[name] + '"/></svg>';
          }
          var css = document.createElement('style');
          css.textContent =
            '#hf-bar{position:fixed;left:0;right:0;bottom:0;z-index:2147483647;padding:6px 8px 8px;' +
            'background:linear-gradient(transparent,rgba(0,0,0,.85) 35%);color:#fff;font:13px sans-serif;transition:opacity .25s}' +
            '#hf-bar.hide{opacity:0;pointer-events:none}' +
            '#hf-bar .row{display:flex;align-items:center;gap:2px}' +
            '#hf-bar button{min-width:40px;height:40px;border:0;border-radius:20px;background:transparent;color:#fff;font:600 13px sans-serif;display:flex;align-items:center;justify-content:center;padding:0 6px}' +
            '#hf-bar button:active{background:rgba(255,255,255,.18)}' +
            '#hf-bar button.on{background:rgba(255,255,255,.22)}' +
            '#hf-bar input{flex:1;min-width:0;height:28px;margin:0;accent-color:#4c8dff}' +
            '#hf-bar .clock{margin-left:8px;white-space:nowrap;font-variant-numeric:tabular-nums}' +
            '#hf-bar .grow{flex:1}' +
            '@media (max-width:420px){#hf-restart{display:none}#hf-bar button{min-width:36px;padding:0 4px}}' +
            '#hf-speed{position:fixed;right:8px;bottom:96px;z-index:2147483647;background:rgba(20,20,20,.95);border-radius:10px;padding:4px;display:none}' +
            '#hf-speed button{display:block;width:72px;height:36px;border:0;background:transparent;color:#fff;font:14px sans-serif;border-radius:6px}' +
            '#hf-speed button.on{background:#4c8dff}';
          document.head.appendChild(css);
          var bar = document.createElement('div');
          bar.id = 'hf-bar';
          bar.innerHTML =
            '<div class="row"><input id="hf-range" type="range" min="0" step="0.01" value="0"><span class="clock" id="hf-clock">0:00 / 0:00</span></div>' +
            '<div class="row">' +
            '<button id="hf-restart" title="Start">' + svg('restart') + '</button>' +
            '<button id="hf-back" title="Back 5 s">' + svg('back') + '</button>' +
            '<button id="hf-toggle" title="Play">' + svg('pause') + '</button>' +
            '<button id="hf-fwd" title="Forward 5 s">' + svg('fwd') + '</button>' +
            '<span class="grow"></span>' +
            '<button id="hf-rate" title="Speed">1×</button>' +
            '<button id="hf-loop" class="on" title="Loop">' + svg('loop') + '</button>' +
            '<button id="hf-mute" title="Sound">' + svg('sound') + '</button>' +
            '<button id="hf-full" title="Full screen">' + svg('full') + '</button>' +
            '</div>';
          document.body.appendChild(bar);
          var menu = document.createElement('div');
          menu.id = 'hf-speed';
          var SPEEDS = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 2];
          menu.innerHTML = SPEEDS.map(function(s) { return '<button data-s="' + s + '">' + s + '×</button>'; }).join('');
          document.body.appendChild(menu);
          function el(id) { return document.getElementById(id); }
          var range = el('hf-range'), clock = el('hf-clock'), toggle = el('hf-toggle');
          var playing = true, loop = true, at = 0, last = 0, idle = 0;
          function mmss(t) { t = Math.max(0, t); var m = Math.floor(t / 60), s = Math.floor(t % 60); return m + ':' + (s < 10 ? '0' : '') + s; }
          function stamp() {
            var d = hf.length();
            range.max = String(d); range.value = String(at);
            clock.textContent = mmss(at) + ' / ' + mmss(d);
            toggle.innerHTML = svg(playing ? 'pause' : 'play');
            el('hf-rate').textContent = hf.speed + '×';
            el('hf-loop').className = loop ? 'on' : '';
            el('hf-mute').innerHTML = svg(hf.muted ? 'mute' : 'sound');
            el('hf-full').innerHTML = svg(document.fullscreenElement ? 'exit' : 'full');
            var items = menu.querySelectorAll('button');
            for (var i = 0; i < items.length; i++) items[i].className = (+items[i].getAttribute('data-s') === hf.speed) ? 'on' : '';
          }
          function wake() { idle = performance.now(); bar.classList.remove('hide'); }
          function go(t) { var d = hf.length(); at = Math.max(0, Math.min(d, t)); hf.seek(at, false); if (!playing) hf.pauseMedia(); stamp(); wake(); }
          function setPlaying(on) {
            var d = hf.length();
            if (on && at >= d - 0.01) at = 0;
            playing = on; last = 0;
            if (!on) { hf.seek(at, false); hf.pauseMedia(); }
            stamp(); wake();
          }
          toggle.onclick = function(e) { e.stopPropagation(); setPlaying(!playing); };
          el('hf-restart').onclick = function(e) { e.stopPropagation(); go(0); };
          el('hf-back').onclick = function(e) { e.stopPropagation(); go(at - 5); };
          el('hf-fwd').onclick = function(e) { e.stopPropagation(); go(at + 5); };
          el('hf-loop').onclick = function(e) { e.stopPropagation(); loop = !loop; stamp(); wake(); };
          el('hf-mute').onclick = function(e) { e.stopPropagation(); hf.muted = !hf.muted; hf.seek(at, playing); stamp(); wake(); };
          el('hf-rate').onclick = function(e) { e.stopPropagation(); menu.style.display = menu.style.display === 'block' ? 'none' : 'block'; wake(); };
          menu.onclick = function(e) {
            e.stopPropagation();
            var s = parseFloat(e.target.getAttribute('data-s'));
            if (s) { hf.speed = s; hf.seek(at, playing); }
            menu.style.display = 'none'; stamp(); wake();
          };
          el('hf-full').onclick = function(e) {
            e.stopPropagation();
            if (document.fullscreenElement) { document.exitFullscreen(); }
            else if (document.documentElement.requestFullscreen) { document.documentElement.requestFullscreen().catch(function(){}); }
            wake();
          };
          bar.onclick = function(e) { e.stopPropagation(); wake(); };
          range.oninput = function() { go(parseFloat(range.value) || 0); };
          var lastTap = 0;
          document.addEventListener('click', function(e) {
            if (bar.contains(e.target) || menu.contains(e.target)) return;
            menu.style.display = 'none';
            var now = performance.now();
            if (now - lastTap < 300) { el('hf-full').onclick(e); lastTap = 0; return; }
            lastTap = now;
            if (bar.classList.contains('hide')) wake(); else setPlaying(!playing);
          });
          function tick(now) {
            var d = hf.length();
            if (playing && d > 0) {
              if (last) at += (now - last) / 1000 * hf.speed;
              last = now;
              if (at >= d) {
                if (loop) { at = 0; hf.seek(0, false); }
                else { at = d; playing = false; hf.seek(at, false); hf.pauseMedia(); }
              }
              if (playing) hf.seek(at, true);
              stamp();
              if (now - idle > 2500 && menu.style.display !== 'block') bar.classList.add('hide');
            } else {
              last = 0;
            }
            requestAnimationFrame(tick);
          }
          hf.seek(0, false); stamp(); wake();
          requestAnimationFrame(tick);
        };
    """.trimIndent()
}
