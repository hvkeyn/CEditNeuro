package com.hvkeyn.ceditneuro.tools

/**
 * Host scripts for an extended display. Windows is the adb tunnel to 127.0.0.1.
 * The Linux script adds a headless output on Sway or Hyprland.
 * Neither path copies the main screen or downloads a display program.
 */
object SecondMonitor {

    fun acceptPage(url: String): Boolean {
        val clean = url.trim()
        if (clean.length !in 12..500) return false
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return false
        if (clean.contains("@") || clean.contains(" ") || clean.contains("\\")) return false
        val host = clean.substringAfter("://").substringBefore("/").substringBefore("?").substringBefore(":")
        if (host.isEmpty() || host.startsWith(".")) return false
        val bare = host.lowercase()
        // 127.0.0.1 is the computer when adb reverse is up. 0.0.0.0 is not a page.
        if (bare == "0.0.0.0" || bare == "::1") return false
        return true
    }

    fun size(raw: Int): Int = raw.coerceIn(320, 4096)

    fun linuxScript(width: Int, height: Int): String {
        val w = size(width)
        val h = size(height)
        return """
            #!/usr/bin/env python3
            # Extra display for this phone. Sway and Hyprland only. Not a copy of the main screen.
            import json
            import os
            import secrets
            import shutil
            import signal
            import socket
            import subprocess
            import sys
            import threading
            import time
            from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

            WIDTH = $w
            HEIGHT = $h
            PORT = 8791
            STATE = os.path.expanduser("~/.ceditneuro-second-monitor.json")
            ORIGIN_X = 0
            ORIGIN_Y = 0
            OUTPUT = ""
            KIND = ""
            HELD = False
            LAST_MOVE = 0.0
            PREFIX = ""
            FRAME = threading.Lock()

            def run(args):
                return subprocess.run(args, capture_output=True, text=True)

            def private(ip):
                parts = ip.split(".")
                if len(parts) != 4:
                    return False
                try:
                    a, b = int(parts[0]), int(parts[1])
                except ValueError:
                    return False
                return a == 10 or (a == 192 and b == 168) or (a == 172 and 16 <= b <= 31)

            def local_ip():
                sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                try:
                    sock.connect(("192.0.2.1", 9))
                    return sock.getsockname()[0]
                except OSError:
                    return ""
                finally:
                    sock.close()

            def read_state():
                try:
                    with open(STATE, "r", encoding="utf-8") as handle:
                        return json.load(handle)
                except (OSError, ValueError):
                    return None

            def write_state(data):
                with open(STATE, "w", encoding="utf-8") as handle:
                    json.dump(data, handle)
                try:
                    os.chmod(STATE, 0o600)
                except OSError:
                    pass

            def alive(pid):
                if pid <= 0:
                    return False
                try:
                    os.kill(pid, 0)
                    return True
                except OSError:
                    return False

            def rows_sway():
                proc = run(["swaymsg", "-t", "get_outputs"])
                if proc.returncode != 0:
                    return []
                try:
                    data = json.loads(proc.stdout)
                except ValueError:
                    return []
                return data if isinstance(data, list) else []

            def rows_hypr():
                proc = run(["hyprctl", "monitors", "-j"])
                if proc.returncode != 0:
                    return []
                try:
                    data = json.loads(proc.stdout)
                except ValueError:
                    return []
                return data if isinstance(data, list) else []

            def right_edge(monitors):
                edge = 0
                for mon in monitors:
                    if "rect" in mon:
                        rect = mon["rect"]
                        end = int(rect.get("x", 0)) + int(rect.get("width", 0))
                    else:
                        end = int(mon.get("x", 0)) + int(mon.get("width", 0))
                    if end > edge:
                        edge = end
                return edge

            def fresh(before, after):
                old = {row.get("name") for row in before}
                return [row.get("name") for row in after if row.get("name") and row.get("name") not in old]

            def unplug(kind, output):
                if not output:
                    return
                if kind == "sway":
                    run(["swaymsg", "output", output, "unplug"])
                elif kind == "hyprland":
                    run(["hyprctl", "output", "remove", output])

            def compositor():
                desk = ((os.environ.get("XDG_CURRENT_DESKTOP") or "") + " " + (os.environ.get("DESKTOP_SESSION") or "")).lower()
                if os.environ.get("SWAYSOCK") and shutil.which("swaymsg"):
                    return "sway"
                if os.environ.get("HYPRLAND_INSTANCE_SIGNATURE") and shutil.which("hyprctl"):
                    return "hyprland"
                if "sway" in desk and shutil.which("swaymsg"):
                    return "sway"
                if "hypr" in desk and shutil.which("hyprctl"):
                    return "hyprland"
                return ""

            def stop():
                state = read_state()
                if not state:
                    print("No extra display is running.")
                    return
                pid = int(state.get("pid") or 0)
                if alive(pid):
                    os.kill(pid, signal.SIGTERM)
                    for _ in range(30):
                        if not alive(pid):
                            break
                        time.sleep(0.1)
                if alive(pid):
                    print("The extra display is still running.")
                    sys.exit(1)
                unplug(state.get("kind") or "", state.get("output") or "")
                try:
                    os.remove(STATE)
                except OSError:
                    pass
                print("The extra display was removed.")

            def apply_pointer(msg):
                global HELD, LAST_MOVE
                try:
                    x = int(msg.get("x", -1))
                    y = int(msg.get("y", -1))
                except (TypeError, ValueError):
                    return
                if x < 0 or y < 0 or x >= WIDTH or y >= HEIGHT:
                    return
                kind_in = str(msg.get("kind") or "")
                now = time.monotonic()
                if kind_in == "move" and now - LAST_MOVE < 0.03:
                    return
                if kind_in == "move":
                    LAST_MOVE = now
                ax = ORIGIN_X + x
                ay = ORIGIN_Y + y
                if KIND == "sway":
                    run(["swaymsg", "seat", "seat0", "cursor", "set", str(ax), str(ay)])
                    if kind_in == "down" and not HELD:
                        run(["swaymsg", "seat", "seat0", "cursor", "press", "button1"])
                        HELD = True
                    elif kind_in == "up" and HELD:
                        run(["swaymsg", "seat", "seat0", "cursor", "release", "button1"])
                        HELD = False
                elif KIND == "hyprland":
                    run(["hyprctl", "dispatch", "movecursor", str(ax), str(ay)])
                    if kind_in == "down" and shutil.which("ydotool"):
                        run(["ydotool", "click", "0x40"])
                        HELD = True
                    elif kind_in == "up" and HELD and shutil.which("ydotool"):
                        run(["ydotool", "click", "0x80"])
                        HELD = False

            PAGE = '''<!DOCTYPE html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
            <style>
            html, body { margin: 0; height: 100%; background: #000; overflow: hidden; touch-action: none; }
            img { width: 100%; height: 100%; object-fit: contain; }
            </style></head><body>
            <img id="v" alt="">
            <script>
            var img = document.getElementById("v");
            var W = __W__;
            var H = __H__;
            var prefix = "__PREFIX__";
            function tick() { img.src = prefix + "/frame.png?t=" + Date.now(); }
            img.onload = function () { setTimeout(tick, 180); };
            img.onerror = function () { setTimeout(tick, 400); };
            tick();
            function point(ev) {
              var r = img.getBoundingClientRect();
              var nw = img.naturalWidth || W;
              var nh = img.naturalHeight || H;
              var scale = Math.min(r.width / nw, r.height / nh);
              if (scale <= 0) return null;
              var dw = nw * scale;
              var dh = nh * scale;
              var ox = r.left + (r.width - dw) / 2;
              var oy = r.top + (r.height - dh) / 2;
              var x = Math.round((ev.clientX - ox) / scale);
              var y = Math.round((ev.clientY - oy) / scale);
              if (x < 0 || y < 0 || x >= nw || y >= nh) return null;
              return {x: x, y: y};
            }
            function send(ev, kind) {
              var p = point(ev);
              if (!p) return;
              p.kind = kind;
              fetch(prefix + "/input", {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(p)});
            }
            img.addEventListener("pointerdown", function (ev) { img.setPointerCapture(ev.pointerId); send(ev, "down"); ev.preventDefault(); });
            img.addEventListener("pointermove", function (ev) { send(ev, "move"); ev.preventDefault(); });
            img.addEventListener("pointerup", function (ev) { send(ev, "up"); ev.preventDefault(); });
            img.addEventListener("pointercancel", function (ev) { send(ev, "up"); });
            </script></body></html>'''

            class Handler(BaseHTTPRequestHandler):
                def log_message(self, fmt, *args):
                    return

                def _send(self, code, ctype, data):
                    body = data if isinstance(data, bytes) else data.encode("utf-8")
                    self.send_response(code)
                    self.send_header("Content-Type", ctype)
                    self.send_header("Cache-Control", "no-store")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)

                def do_GET(self):
                    path = self.path.split("?", 1)[0]
                    if path in (PREFIX, PREFIX + "/"):
                        self._send(200, "text/html; charset=utf-8", PAGE)
                    elif path == PREFIX + "/frame.png":
                        with FRAME:
                            shot = subprocess.run(["grim", "-o", OUTPUT, "-"], capture_output=True)
                        if shot.returncode != 0 or not shot.stdout:
                            self.send_error(503)
                            return
                        self._send(200, "image/png", shot.stdout)
                    else:
                        self.send_error(404)

                def do_POST(self):
                    path = self.path.split("?", 1)[0]
                    if path != PREFIX + "/input":
                        self.send_error(404)
                        return
                    try:
                        length = int(self.headers.get("Content-Length", "0"))
                    except ValueError:
                        length = 0
                    if length < 2 or length > 400:
                        self.send_error(400)
                        return
                    try:
                        msg = json.loads(self.rfile.read(length).decode("utf-8"))
                    except ValueError:
                        self.send_error(400)
                        return
                    apply_pointer(msg)
                    self.send_response(204)
                    self.end_headers()

            class Server(ThreadingHTTPServer):
                allow_reuse_address = True
                daemon_threads = True

            def main():
                global ORIGIN_X, OUTPUT, KIND, PREFIX, PAGE
                if len(sys.argv) > 1 and sys.argv[1] == "stop":
                    stop()
                    return
                state = read_state()
                if state and alive(int(state.get("pid") or 0)):
                    print("address: " + str(state.get("address") or ""), flush=True)
                    print("state: " + STATE, flush=True)
                    print("Already running.")
                    return
                if state:
                    unplug(state.get("kind") or "", state.get("output") or "")
                    try:
                        os.remove(STATE)
                    except OSError:
                        pass
                KIND = compositor()
                if not KIND:
                    print("This desktop cannot add a virtual monitor. Sway and Hyprland can. GNOME, KDE, and a plain session cannot. Nothing was mirrored.")
                    sys.exit(2)
                if not shutil.which("grim"):
                    print("grim is not installed, so the extra display was not added.")
                    sys.exit(2)
                ip = local_ip()
                if not private(ip):
                    print("This computer is not on a local network. The extra display was not started.")
                    sys.exit(2)
                before = rows_sway() if KIND == "sway" else rows_hypr()
                edge = right_edge(before)
                if KIND == "sway":
                    created = run(["swaymsg", "create_output"])
                    after = rows_sway()
                else:
                    created = run(["hyprctl", "output", "create", "headless"])
                    after = rows_hypr()
                names = fresh(before, after)
                if created.returncode != 0 or not names:
                    print(created.stdout or created.stderr)
                    print("This desktop did not add a virtual monitor.")
                    sys.exit(2)
                OUTPUT = names[-1]
                ORIGIN_X = edge
                if KIND == "sway":
                    placed = run(["swaymsg", "output", OUTPUT, "resolution", str(WIDTH) + "x" + str(HEIGHT), "position", str(edge), "0", "enable"])
                else:
                    mode = OUTPUT + "," + str(WIDTH) + "x" + str(HEIGHT) + "@60," + str(edge) + "x0,1"
                    placed = run(["hyprctl", "keyword", "monitor", mode])
                if placed.returncode != 0:
                    unplug(KIND, OUTPUT)
                    print(placed.stdout or placed.stderr)
                    print("This desktop did not add a virtual monitor.")
                    sys.exit(2)
                with FRAME:
                    shot = subprocess.run(["grim", "-o", OUTPUT, "-"], capture_output=True)
                if shot.returncode != 0 or not shot.stdout.startswith(b"\x89PNG"):
                    unplug(KIND, OUTPUT)
                    print("The extra display was added but no picture could be taken. It was removed.")
                    sys.exit(2)
                token = secrets.token_urlsafe(18)
                PREFIX = "/m/" + token
                PAGE = PAGE.replace("__W__", str(WIDTH)).replace("__H__", str(HEIGHT)).replace("__PREFIX__", PREFIX)
                server = None
                port = 0
                for candidate in range(PORT, PORT + 5):
                    try:
                        server = Server(("0.0.0.0", candidate), Handler)
                        port = candidate
                        break
                    except OSError:
                        server = None
                if server is None:
                    unplug(KIND, OUTPUT)
                    print("No local port was free. The extra display was removed.")
                    sys.exit(2)
                address = "http://" + ip + ":" + str(port) + PREFIX + "/"
                write_state({
                    "pid": os.getpid(),
                    "output": OUTPUT,
                    "kind": KIND,
                    "address": address,
                })
                print("address: " + address, flush=True)
                print("state: " + STATE, flush=True)
                if KIND == "hyprland" and not shutil.which("ydotool"):
                    print("The picture is on. Clicks need ydotool, which is not installed.")

                def handle_term(signum, frame):
                    server.shutdown()

                signal.signal(signal.SIGTERM, handle_term)
                signal.signal(signal.SIGINT, handle_term)
                try:
                    server.serve_forever()
                finally:
                    server.server_close()
                    unplug(KIND, OUTPUT)
                    current = read_state()
                    if current and int(current.get("pid") or 0) == os.getpid():
                        try:
                            os.remove(STATE)
                        except OSError:
                            pass

            if __name__ == "__main__":
                main()
        """.trimIndent() + "\n"
    }

    fun windowsScript(): String = """
        ¤ErrorActionPreference = 'Stop'
        if (¤args -contains 'stop') {
            Write-Output 'The extra display is the adb tunnel. Stop that host on the computer. Nothing was downloaded.'
            exit 0
        }
        Write-Output 'Windows uses the open adb tunnel. The page is http://127.0.0.1:8791/ . Do not download a display program. Do not invent an address.'
        exit 0
    """.trimIndent().replace("¤", "$") + "\n"
}
