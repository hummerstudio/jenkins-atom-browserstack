#!/usr/bin/env python3
"""A stand-in for the two BrowserStack APIs this atom talks to, started once per smoke run.

    fake_browserstack.py <port> <log>     # prints "ready <port>" once it is listening

It is a stand-in, not a mock: it verifies the things BrowserStack actually verifies before it answers, so a passing run
means the atom sent the right thing rather than that the fake was agreeable.

  * **auth** — every endpoint wants ``Authorization: Basic base64(user:key)``. A wrong or missing header is a 401, which
    is how the suite proves the credentials reached the wire instead of being dropped somewhere in the atom.
  * **upload** — ``POST <base>/upload`` must be ``multipart/form-data`` with a part named ``file`` that carries a
    filename, a body whose sha256 matches the file on disk, and a length the log records. The answer is
    ``{"app_url": "bs://…"}``; the ``/flaky`` prefix answers 503 once and then 200, ``/empty`` answers 200 without an
    app_url, ``/reject401`` answers 401, ``/strings`` answers with extra fields the atom must ignore.
  * **reports** — ``GET <base>/builds.json?build=<name>`` and ``GET <base>/builds/<id>/sessions.json`` answer with the
    ``automation_build`` / ``automation_session`` envelopes the real API uses. The payload depends on the build name, so
    one process serves every case: ``bs-build-ok`` (four sessions: failed / passed-marked / running / error),
    ``bs-build-empty`` (build, no sessions), ``bs-build-multi`` (two builds with the same name), anything else (none).

Each request is appended to <log> as one JSON line — method, path, query, auth header, content type, parsed parts with
their sha256, and the status the fake answered with — so the checks read what happened rather than what the atom says
happened.
"""
import base64
import hashlib
import http.server
import json
import sys
import urllib.parse

PORT, LOG = int(sys.argv[1]), sys.argv[2]
USER, KEY = "bsuser", "bskey-123"
BOUNDARY_SEEN = {}

BUILDS = {
    "bs-build-ok": {"hashed_id": "abcdef123", "name": "bs-build-ok", "duration": 3725, "status": "done"},
    "bs-build-empty": {"hashed_id": "empty0001", "name": "bs-build-empty", "duration": 0, "status": "done"},
    # a build the API answers without a hashed_id: the atom cannot list sessions and must say why
    "bs-build-noid": {"name": "bs-build-noid", "duration": 1, "status": "done"},
    # a build whose sessions endpoint answers 404
    "bs-build-404": {"hashed_id": "boom404", "name": "bs-build-404", "duration": 1, "status": "done"},
}
SESSIONS = {
    "abcdef123": [
        {   # user marked failed
            "hashed_id": "s1", "name": "checkout on iPad", "device": "iPad 9th", "os": "ios", "os_version": "15",
            "browserstack_status": "failed", "status": "failed", "duration": 125,
            "created_at": "2026-01-02T03:04:05.000Z",
            "browser_url": "https://automate.browserstack.com/builds/abcdef123/sessions/s1",
            "public_url": "https://automate.browserstack.com/sessions/s1?public_token=AAA",
        },
        {   # unmarked, newer than s3
            "hashed_id": "s2", "name": "", "browser": "chrome", "os": "Windows", "os_version": "11",
            "browserstack_status": "passed", "status": "passed", "duration": 65,
            "created_at": "2026-01-02T04:04:05.000Z",
            "browser_url": "https://automate.browserstack.com/builds/abcdef123/sessions/s2",
            "public_url": "https://automate.browserstack.com/sessions/s2?public_token=BBB",
        },
        {   # still running: no duration, and the report must show "-"
            "hashed_id": "s3", "name": "search smoke", "browser": "firefox", "os": "OS X", "os_version": "Ventura",
            "browserstack_status": "running", "status": "running", "duration": None,
            "created_at": "2026-01-02T05:04:05.000Z",
            "browser_url": "https://automate.browserstack.com/builds/abcdef123/sessions/s3",
            "public_url": "https://automate.browserstack.com/sessions/s3?public_token=CCC",
        },
        {   # infrastructure error the user marked passed: status=error, userMarked=passed
            "hashed_id": "s4", "name": "upload flow", "device": "Pixel 7", "os": "android", "os_version": "13",
            "browserstack_status": "error", "status": "passed", "duration": 3600,
            "created_at": "2026-01-01T00:00:00.000Z",
            "browser_url": "https://automate.browserstack.com/builds/abcdef123/sessions/s4",
            "public_url": "https://automate.browserstack.com/sessions/s4?public_token=DDD",
        },
        {   # a pass the user overrode to failed: status=passed, userMarked=failed, counted as an error
            "hashed_id": "s5", "name": "login flow", "browser": "safari", "os": "OS X", "os_version": "Monterey",
            "browserstack_status": "passed", "status": "failed", "duration": 90,
            "created_at": "2026-01-02T06:00:00.000Z",
            "browser_url": "https://automate.browserstack.com/builds/abcdef123/sessions/s5",
            "public_url": "https://automate.browserstack.com/sessions/s5?public_token=EEE",
        },
        {   # BrowserStack and the user both say failed: recorded as unmarked, and the plugin does not count it
            "hashed_id": "s6", "name": "cart flow", "browser": "edge", "os": "Windows", "os_version": "10",
            "browserstack_status": "failed", "status": "failed", "duration": 10,
            "created_at": "2026-01-02T02:00:00.000Z",
            "browser_url": "https://automate.browserstack.com/builds/abcdef123/sessions/s6",
            "public_url": "https://automate.browserstack.com/sessions/s6?public_token=FFF",
        },
    ],
    "empty0001": [],
}


def log(entry):
    with open(LOG, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry, ensure_ascii=False) + "\n")


def parse_multipart(body, content_type):
    """Returns (boundary, parts) where a part is name/filename/type/sha256/len — the atom's claim, checked here."""
    marker = "boundary="
    if marker not in content_type:
        return None, []
    boundary = content_type.split(marker, 1)[1].split(";")[0].strip().strip('"')
    parts = []
    for chunk in body.split(b"--" + boundary.encode()):
        if chunk in (b"", b"--\r\n", b"--", b"\r\n"):
            continue
        chunk = chunk.lstrip(b"\r\n")
        if chunk.startswith(b"--"):
            continue
        head, _, content = chunk.partition(b"\r\n\r\n")
        if content.endswith(b"\r\n"):
            content = content[:-2]
        headers = {}
        for line in head.decode("utf-8", "replace").split("\r\n"):
            if ":" in line:
                key, _, value = line.partition(":")
                headers[key.strip().lower()] = value.strip()
        disposition = headers.get("content-disposition", "")
        name = disposition.split('name="', 1)[1].split('"', 1)[0] if 'name="' in disposition else ""
        filename = disposition.split('filename="', 1)[1].split('"', 1)[0] if 'filename="' in disposition else ""
        parts.append({
            "name": name,
            "filename": filename,
            "content_type": headers.get("content-type", ""),
            "len": len(content),
            "sha256": hashlib.sha256(content).hexdigest(),
        })
    return boundary, parts


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def record(self, status, extra=None):
        entry = {
            "method": self.command,
            "path": urllib.parse.urlparse(self.path).path,
            "query": dict(urllib.parse.parse_qsl(urllib.parse.urlparse(self.path).query)),
            "authorization": self.headers.get("Authorization", ""),
            "content_type": self.headers.get("Content-Type", ""),
            "status": status,
        }
        entry.update(extra or {})
        log(entry)
        return entry

    def answer(self, status, payload):
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        return status

    def authorized(self):
        expected = "Basic " + base64.b64encode(("%s:%s" % (USER, KEY)).encode()).decode()
        return self.headers.get("Authorization", "") == expected

    def do_GET(self):
        self.handle_any()

    def do_POST(self):
        self.handle_any()

    def handle_any(self):
        path = urllib.parse.urlparse(self.path).path
        query = dict(urllib.parse.parse_qsl(urllib.parse.urlparse(self.path).query))
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length else b""

        if not self.authorized():
            self.record(401)
            return self.answer(401, {"error": "unauthorized"})

        if path.endswith("/upload"):
            boundary, parts = parse_multipart(body, self.headers.get("Content-Type", ""))
            extra = {"boundary": boundary, "parts": parts}
            prefix = path[: -len("/upload")]
            if prefix.endswith("/reject401"):
                self.record(401, extra)
                return self.answer(401, {"error": "invalid credentials"})
            if prefix.endswith("/flaky"):
                BOUNDARY_SEEN["flaky"] = BOUNDARY_SEEN.get("flaky", 0) + 1
                if BOUNDARY_SEEN["flaky"] == 1:
                    self.record(503, extra)
                    return self.answer(503, {"error": "service unavailable"})
            if prefix.endswith("/down"):
                self.record(503, extra)
                return self.answer(503, {"error": "still unavailable"})
            if prefix.endswith("/empty"):
                self.record(200, extra)
                return self.answer(200, {"message": "no app_url in this answer"})
            self.record(200, extra)
            return self.answer(200, {
                "app_url": "bs://" + hashlib.sha256(body).hexdigest()[:32],
                "app_name": "app",
                "custom_id": "ignored",
            })

        if path.endswith("/builds.json"):
            name = query.get("build", "")
            if name == "bs-build-forbidden":
                self.record(403, {"builds": 0})
                return self.answer(403, {"error": "forbidden"})
            if name == "bs-build-multi":
                payload = [{"automation_build": BUILDS["bs-build-ok"]}, {"automation_build": BUILDS["bs-build-empty"]}]
            elif name in BUILDS:
                payload = [{"automation_build": BUILDS[name]}]
            else:
                payload = []
            self.record(200, {"builds": len(payload)})
            return self.answer(200, payload)

        if path.endswith("/sessions.json"):
            build_id = path.split("/builds/")[-1].split("/")[0]
            sessions = SESSIONS.get(build_id)
            if sessions is None:
                self.record(404)
                return self.answer(404, {"error": "build not found"})
            self.record(200, {"sessions": len(sessions)})
            return self.answer(200, [{"automation_session": session} for session in sessions])

        self.record(404)
        return self.answer(404, {"error": "no such endpoint"})


server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
open(LOG, "a", encoding="utf-8").close()
print("ready %d" % PORT, flush=True)
server.serve_forever()
