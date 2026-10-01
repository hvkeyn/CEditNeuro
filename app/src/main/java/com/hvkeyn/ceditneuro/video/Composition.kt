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

    /** The page with this app as its runtime. play adds a loop and a control bar; render leaves the frame still. */
    fun hosted(html: String, play: Boolean): String {
        val first = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no\">" +
            "<script>window.__timelines=window.__timelines||{};window.__hfHost=true;</script>"
        val last = "<script>" + HARNESS + (if (play) "window.__hfPlay();" else "") + "</script>"
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
          function line() { return window.__timelines && window.__timelines[id]; }
          function length() {
            var d = parseFloat(root.getAttribute('data-duration'));
            if (d > 0) return d;
            var t = line();
            return t && t.duration ? t.duration() : 0;
          }
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
          window.__hfSize = function(w, h) { window.__hfVW = w; window.__hfVH = h; fit(); return 'ok'; };
          function media(el, t, s, on, playing) {
            if (el.tagName !== 'VIDEO' && el.tagName !== 'AUDIO') return;
            var local = t - s + (parseFloat(el.getAttribute('data-media-start')) || 0);
            if (!on) { if (!el.paused) el.pause(); return; }
            if (playing) {
              if (Math.abs(el.currentTime - local) > 0.3) el.currentTime = Math.max(0, local);
              if (el.paused) { var p = el.play(); if (p && p.catch) p.catch(function(){}); }
            } else {
              if (!el.paused) el.pause();
              if (Math.abs(el.currentTime - local) > 0.01) el.currentTime = Math.max(0, local);
            }
          }
          function show(t, playing) {
            var els = root.querySelectorAll('[data-start]');
            for (var i = 0; i < els.length; i++) {
              var el = els[i];
              if (el === root) continue;
              var s = parseFloat(el.getAttribute('data-start')) || 0;
              var d = parseFloat(el.getAttribute('data-duration'));
              var on = t >= s && (isNaN(d) || t < s + d);
              el.style.visibility = on ? '' : 'hidden';
              media(el, t, s, on, playing);
            }
          }
          function seek(t, playing) {
            var tl = line();
            if (tl) { if (tl.paused && !tl.paused()) tl.pause(); tl.seek(t, false); }
            show(t, playing);
          }
          window.__hfInfo = function() {
            fit();
            return JSON.stringify({ id: id, w: W, h: H, d: length(), tl: !!line() });
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
          window.__hfPlay = function() {
            fit();
            window.addEventListener('resize', fit);
            var bar = document.createElement('div');
            bar.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:2147483647;display:flex;align-items:center;gap:10px;padding:8px 12px;background:rgba(0,0,0,.6);color:#fff;font:14px sans-serif';
            var button = document.createElement('button');
            button.style.cssText = 'min-width:48px;min-height:40px;border:0;border-radius:6px;background:#fff;color:#000;font:16px sans-serif';
            var range = document.createElement('input');
            range.type = 'range'; range.min = '0'; range.step = '0.01'; range.style.cssText = 'flex:1;min-height:40px';
            var clock = document.createElement('span');
            bar.appendChild(button); bar.appendChild(range); bar.appendChild(clock);
            document.body.appendChild(bar);
            var playing = true, base = performance.now(), at = 0;
            function stamp(t) { var d = length(); range.max = String(d); range.value = String(t); clock.textContent = t.toFixed(1) + ' / ' + d.toFixed(1) + ' s'; button.textContent = playing ? '❚❚' : '▶'; }
            button.onclick = function() { playing = !playing; base = performance.now() - at * 1000; stamp(at); };
            range.oninput = function() { at = parseFloat(range.value) || 0; base = performance.now() - at * 1000; seek(at, false); stamp(at); };
            function tick(now) {
              var d = length();
              if (playing && d > 0) {
                at = (now - base) / 1000;
                if (at >= d) { at = 0; base = now; }
                seek(at, true);
                stamp(at);
              }
              requestAnimationFrame(tick);
            }
            seek(0, false); stamp(0);
            requestAnimationFrame(tick);
          };
        })();
    """.trimIndent()
}
