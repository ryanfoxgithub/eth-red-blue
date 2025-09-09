#!/usr/bin/env python3
# beacon_server.py — I host my APK and collect beacon POSTs from the phone.
# The design is deliberately tiny and dependency‑free so I can run it anywhere.

from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler  # stdlib HTTP server
import argparse, os, json, time, mimetypes                           # small stdlib helpers

# Where I write JSON Lines logs (one JSON object per line).
LOG_PATH = "beacons.jsonl"

# Default filename I expose at /app-debug.apk (can be overridden by --apk).
DEFAULT_APK = "../apps/locker-sim-android/app/build/outputs/apk/debug/app-debug.apk"


def log_event(evt: dict):
    """
    Append a JSON event to console and to LOG_PATH.
    - I inject a millisecond timestamp ("ts") on the server side so every record
      has a consistent clock even if the client clock is off.
    - I ensure the parent folder exists so paths like evidence/beacons.jsonl work.
    """
    evt = {"ts": int(time.time() * 1000), **evt}
    line = json.dumps(evt, ensure_ascii=False)
    print(line, flush=True)

    d = os.path.dirname(LOG_PATH)
    if d:  # If LOG_PATH includes a directory, make sure it exists.
        os.makedirs(d, exist_ok=True)

    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(line + "\n")


class Handler(BaseHTTPRequestHandler):
    # I stash the absolute APK path on the handler class itself.
    # Note: the "str | None" syntax requires Python 3.10+. On older Python,
    # I'd use: from typing import Optional; apk_path: Optional[str] = None
    apk_path: str | None = None

    # Small helper to send a 200 response with a body.
    def _ok(self, body: bytes, ctype: str = "text/plain; charset=utf-8"):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    # ---- HTTP methods ----

    def do_GET(self):
        # Simple health probe so I can curl /health in scripts.
        if self.path == "/health":
            return self._ok(b"ok")

        # Serve the APK at /app-debug.apk (or a path starting with it).
        if self.path.startswith("/app-debug.apk"):
            if not self.apk_path or not os.path.exists(self.apk_path):
                self.send_error(404, "APK not found")
                return

            size = os.path.getsize(self.apk_path)
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(size))
            # Content-Disposition makes browsers download instead of render.
            self.send_header("Content-Disposition", 'attachment; filename="app-debug.apk"')
            self.end_headers()

            # I stream the file in 64 KiB chunks so large APKs don't balloon memory.
            with open(self.apk_path, "rb") as f:
                for chunk in iter(lambda: f.read(64 * 1024), b""):
                    self.wfile.write(chunk)

            # Log the download event (client IP, path, size).
            log_event({
                "type": "download",
                "path": self.path,
                "remote": self.client_address[0],
                "size": size
            })
            return

        # Minimal landing page: shows a link to the APK if it exists.
        if self.path in ("/", "/index.html"):
            if self.apk_path and os.path.exists(self.apk_path):
                html = (
                    "<!doctype html><meta charset='utf-8'>"
                    "<title>LockerSim host</title>"
                    "<p>Server up. <a href='/app-debug.apk'>Download APK</a></p>"
                )
            else:
                html = "<!doctype html><meta charset='utf-8'><p>Server up.</p>"
            return self._ok(html.encode("utf-8"), "text/html; charset=utf-8")

        # Any other GETs just return "ok" so probes don’t 404.
        return self._ok(b"ok")

    def do_HEAD(self):
        """
        I support HEAD for the APK route so a browser can preflight
        size/type without downloading the full file.
        """
        if self.path.startswith("/app-debug.apk") and self.apk_path and os.path.exists(self.apk_path):
            size = os.path.getsize(self.apk_path)
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(size))
            self.end_headers()
        else:
            self.send_response(200)
            self.end_headers()

    def do_POST(self):
        """
        Beacons POST JSON here. I read Content-Length bytes and try to decode JSON.
        If parsing fails, I still record the raw payload string under "_raw".
        """
        n = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(n) if n > 0 else b""

        try:
            payload = json.loads(body.decode("utf-8", "ignore"))
        except Exception:
            payload = {"_raw": body.decode("utf-8", "ignore")}

        # I log path, remote IP, UA, and the parsed body.
        log_event({
            "type": "beacon",
            "path": self.path,
            "remote": self.client_address[0],
            "user_agent": self.headers.get("User-Agent", ""),
            "body": payload
        })
        self._ok(b"ok")


def main():
    # I rebind the module-level LOG_PATH based on --log.
    global LOG_PATH

    # Simple CLI so I can pick a different port, APK, or log file.
    parser = argparse.ArgumentParser(description="Simple LockerSim beacon/APK host")
    parser.add_argument("-p", "--port", type=int, default=8000,
                        help="listen port (default: 8000)")
    parser.add_argument("--apk", default=DEFAULT_APK,
                        help="path to APK served at /app-debug.apk")
    parser.add_argument("--log", default=LOG_PATH,
                        help="path to JSONL log (default: beacons.jsonl)")
    args = parser.parse_args()

    LOG_PATH = args.log
    # Stash the absolute APK path on the handler class (None disables the route).
    Handler.apk_path = os.path.abspath(args.apk) if args.apk else None

    # Ensure .apk has the correct MIME type in responses.
    mimetypes.add_type("application/vnd.android.package-archive", ".apk")

    # ThreadingHTTPServer handles each connection in a new thread.
    httpd = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"[+] Serving on 0.0.0.0:{args.port}")
    if Handler.apk_path:
        print(f"[+] APK route: /app-debug.apk -> {Handler.apk_path} "
              f"(exists: {os.path.exists(Handler.apk_path)})")

    try:
        httpd.serve_forever()  # I block here and handle requests until Ctrl+C.
    except KeyboardInterrupt:
        print("\n[!] Shutting down.")
    finally:
        httpd.server_close()   # Always close the socket cleanly.


if __name__ == "__main__":
    main()

