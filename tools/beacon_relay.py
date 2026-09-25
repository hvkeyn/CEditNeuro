#!/usr/bin/env python3
"""Beacon. Forwards short lines between phones that share a code. No shell access."""
import socket
import threading

HOST = "0.0.0.0"
PORT = 8756
rooms = {}
lock = threading.Lock()


def room(code):
    with lock:
        return rooms.setdefault(code, set())


def handle(conn):
    conn.settimeout(30)
    peer = None
    code = None
    try:
        hello = conn.makefile("r", encoding="utf-8", newline="\n").readline(200).strip()
        parts = hello.split(" ", 2)
        if len(parts) != 3 or parts[0] != "JOIN" or not parts[1].isdigit() or len(parts[1]) != 6:
            conn.close()
            return
        code = parts[1]
        name = "".join(ch for ch in parts[2] if ch.isalnum())[:24] or "phone"
        peer = (conn, name)
        room(code).add(peer)
        hello = f"P {name} HERE\n".encode()
        with lock:
            others = [item for item in rooms.get(code, ()) if item is not peer]
        for other, _ in others:
            try:
                other.sendall(hello)
            except OSError:
                pass
        reader = conn.makefile("r", encoding="utf-8", newline="\n")
        while True:
            line = reader.readline(80_000)
            if not line:
                break
            line = line.strip()
            if line.startswith("T ") and len(line) <= 300:
                payload = f"P {name} {line[2:]}\n".encode()
            elif line.startswith("F ") and len(line) <= 70_000:
                payload = f"P {name} FILE {line[2:]}\n".encode()
            else:
                continue
            with lock:
                targets = [item for item in rooms.get(code, ()) if item is not peer]
            for other, _ in targets:
                try:
                    other.sendall(payload)
                except OSError:
                    pass
    except OSError:
        pass
    finally:
        if code and peer:
            with lock:
                rooms.get(code, set()).discard(peer)
        try:
            conn.close()
        except OSError:
            pass


def main():
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen(40)
    while True:
        conn, _ = server.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()
