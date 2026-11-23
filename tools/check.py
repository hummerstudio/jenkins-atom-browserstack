#!/usr/bin/env python3
"""Behaviour and contract checks for the browserstack atom.

Nothing here trusts the atom. Every case runs the real jar the way the runner launches it (``input.json`` +
``.secrets.json`` in a throwaway directory, ``java -cp browserstack.jar …AtomRunner``) against a stand-in BrowserStack
that checks the credentials, the multipart part and the file's sha256 before it answers. Expectations are recomputed
from the inputs — the ``-jenkins`` suffix, the duration strings, the sort order, the row shapes, the error count — by a
second implementation, not read back out of the atom's own output, and the report is compared against the file the atom
wrote as well as against its outputs (a passing output with an empty file is the failure mode this exists to catch).

The last cases are offline and check the contract itself: that ``task.json`` declares every field the Java reads and
nothing more, that the ``rely`` expressions show exactly the documented fields for each action, that no output shadows
an input, and that every error code the Java raises is documented.

    python3 tools/check.py all --jar dist/browserstack/browserstack.jar
    python3 tools/check.py list
    python3 tools/check.py run report_rows --jar dist/browserstack/browserstack.jar
"""
import argparse
import base64
import hashlib
import json
import os
import pathlib
import re
import socket
import subprocess
import sys
import tempfile
import time

HERE = pathlib.Path(__file__).resolve().parent.parent
FAKE = HERE / "tools" / "fake_browserstack.py"
USER = "bsuser"
KEY = "bskey-123"
ACTIONS = ["env", "upload", "report"]

# ------------------------------------------------------------------ the fixtures, recomputed here on purpose
# The fake serves these; this file derives what the report must contain from them with its own code. Duplicating the
# rules is the point: two implementations that disagree fail the suite instead of agreeing on a bug.
BUILD_DURATION = 3725
BUILD_ID = "abcdef123"
SESSIONS = [
    {"hashed_id": "s1", "name": "checkout on iPad", "device": "iPad 9th", "os": "ios", "os_version": "15",
     "browserstack_status": "failed", "status": "failed", "duration": 125,
     "created_at": "2026-01-02T03:04:05.000Z",
     "browser_url": "https://automate.browserstack.com/builds/abcdef123/sessions/s1",
     "public_url": "https://automate.browserstack.com/sessions/s1?public_token=AAA"},
    {"hashed_id": "s2", "name": "", "browser": "chrome", "os": "Windows", "os_version": "11",
     "browserstack_status": "passed", "status": "passed", "duration": 65,
     "created_at": "2026-01-02T04:04:05.000Z",
     "browser_url": "https://automate.browserstack.com/builds/abcdef123/sessions/s2",
     "public_url": "https://automate.browserstack.com/sessions/s2?public_token=BBB"},
    {"hashed_id": "s3", "name": "search smoke", "browser": "firefox", "os": "OS X", "os_version": "Ventura",
     "browserstack_status": "running", "status": "running", "duration": None,
     "created_at": "2026-01-02T05:04:05.000Z",
     "browser_url": "https://automate.browserstack.com/builds/abcdef123/sessions/s3",
     "public_url": "https://automate.browserstack.com/sessions/s3?public_token=CCC"},
    {"hashed_id": "s4", "name": "upload flow", "device": "Pixel 7", "os": "android", "os_version": "13",
     "browserstack_status": "error", "status": "passed", "duration": 3600,
     "created_at": "2026-01-01T00:00:00.000Z",
     "browser_url": "https://automate.browserstack.com/builds/abcdef123/sessions/s4",
     "public_url": "https://automate.browserstack.com/sessions/s4?public_token=DDD"},
    {"hashed_id": "s5", "name": "login flow", "browser": "safari", "os": "OS X", "os_version": "Monterey",
     "browserstack_status": "passed", "status": "failed", "duration": 90,
     "created_at": "2026-01-02T06:00:00.000Z",
     "browser_url": "https://automate.browserstack.com/builds/abcdef123/sessions/s5",
     "public_url": "https://automate.browserstack.com/sessions/s5?public_token=EEE"},
     {"hashed_id": "s6", "name": "cart flow", "browser": "edge", "os": "Windows", "os_version": "10",
     "browserstack_status": "failed", "status": "failed", "duration": 10,
     "created_at": "2026-01-02T02:00:00.000Z",
     "browser_url": "https://automate.browserstack.com/builds/abcdef123/sessions/s6",
     "public_url": "https://automate.browserstack.com/sessions/s6?public_token=FFF"},
]
MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


# ------------------------------------------------------------------ the stand-in server, one process for the whole run

class Fake:
    port = 0
    log = None
    proc = None


def free_port():
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


def start_fake():
    Fake.port = free_port()
    Fake.log = pathlib.Path(tempfile.mkdtemp(prefix="bs-log-")) / "requests.log"
    Fake.log.touch()
    Fake.proc = subprocess.Popen([sys.executable, str(FAKE), str(Fake.port), str(Fake.log)],
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    line = Fake.proc.stdout.readline()
    if not line.startswith("ready"):
        raise SystemExit("the fake BrowserStack did not start: %r" % line)


def stop_fake():
    if Fake.proc is not None:
        Fake.proc.terminate()
        try:
            Fake.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:  # noqa: PERF203 - a hung fake must not hide the result
            Fake.proc.kill()


def requests():
    text = Fake.log.read_text(encoding="utf-8") if Fake.log.exists() else ""
    return [json.loads(line) for line in text.splitlines() if line.strip()]


def reset():
    Fake.log.write_text("", encoding="utf-8")


def api(suffix=""):
    return "http://127.0.0.1:%d%s" % (Fake.port, suffix)


# ------------------------------------------------------------------ independent recomputation

def human(seconds):
    """The plugin's Tools.durationToHumanReadable, written again from its branches."""
    seconds = int(seconds or 0)
    sec = seconds % 60
    minutes = (seconds // 60) % 60
    hours = (seconds // 3600) % 24
    days = seconds // 86400
    if days == 0:
        if hours == 0:
            return "%02ds" % sec if minutes == 0 else "%02dm %02ds" % (minutes, sec)
        return "%02dh %02dm %02ds" % (hours, minutes, sec)
    return "%dd %02dh %02dm %02ds" % (days, hours, minutes, sec)


def stamp(created_at):
    """'2026-01-02T03:04:05.000Z' → ('2026-01-02T03:04:05.000Z', '02 Jan 2026, 03:04 UTC')."""
    match = re.match(r"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})\.\d{3}Z$", created_at or "")
    if not match:
        return "1970-01-01T00:00:00.000Z", "01 Jan 1970, 00:00 UTC"
    year, month, day, hour, minute, second = match.groups()
    readable = "%s %s %s, %s:%s UTC" % (day, MONTHS[int(month) - 1], year, hour, minute)
    return "%s-%s-%sT%s:%s:%s.000Z" % (year, month, day, hour, minute, second), readable


def epoch(created_at):
    match = re.match(r"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})", created_at or "")
    if not match:
        return 0
    year, month, day, hour, minute, second = (int(part) for part in match.groups())
    # days since the epoch, enough to order two stamps in the same year without pulling in a calendar library
    days = (year - 1970) * 365 + (month - 1) * 31 + day
    return (((days * 24) + hour) * 60 + minute) * 60 + second


def expected_row(session):
    status = session["browserstack_status"]
    created, readable = stamp(session.get("created_at"))
    return {
        "name": session.get("name") or session["hashed_id"],
        "buildName": "bs-build-ok",
        "buildUrl": "https://automate.browserstack.com/builds/abcdef123",
        "buildDuration": str(BUILD_DURATION),
        "browser": session.get("device") or session.get("browser", ""),
        "os": "%s %s" % (session.get("os", ""), session.get("os_version", "")),
        "status": status,
        "userMarked": "unmarked" if status == session["status"] else session["status"],
        "duration": "-" if session["status"] == "running" else human(session.get("duration")),
        "createdAt": created,
        "createdAtReadable": readable,
        "url": session["public_url"] + "&source=jenkins_plugin",
    }


def expected_rows():
    rows = [expected_row(session) for session in SESSIONS]
    rows.sort(key=lambda row: (row["userMarked"], -epoch(row["createdAt"]), row["name"]))
    return rows


def expected_errors():
    return sum(1 for row in expected_rows() if row["status"] == "error" or row["userMarked"] == "failed")


# ------------------------------------------------------------------ running the atom

def java_bin():
    home = os.environ.get("JAVA_HOME")
    return os.path.join(home, "bin", "java") if home else "java"


def run_atom(jar, params, secrets=None, files=None):
    jar = os.path.abspath(jar)
    directory = tempfile.mkdtemp(prefix="bs-")
    for name, content in (files or {}).items():
        path = os.path.join(directory, name)
        os.makedirs(os.path.dirname(path), exist_ok=True) if os.path.dirname(path) else None
        with open(path, "wb") as handle:
            handle.write(content)
    json.dump(params, open(os.path.join(directory, "input.json"), "w"))
    json.dump({"accessKey": KEY} if secrets is None else secrets,
              open(os.path.join(directory, ".secrets.json"), "w"))
    env = dict(os.environ)
    env.update({"atom_data_dir": directory, "atom_data_input": "input.json",
                "atom_data_output": "output.json", "atom_workspace": directory})
    proc = subprocess.run([java_bin(), "-cp", jar, "com.jenkinschina.atom.sdk.AtomRunner"],
                          cwd=directory, env=env, capture_output=True, text=True, timeout=180)
    if not os.path.exists(os.path.join(directory, "output.json")):
        raise AssertionError("the atom wrote no output.json: %s\n%s" % (proc.returncode, proc.stderr[-2000:]))
    return json.load(open(os.path.join(directory, "output.json"))), proc, directory


def data(out, key):
    return (out.get("data", {}).get(key) or {}).get("value", "")


def keys(out):
    return set((out.get("data") or {}).keys())


def message(out):
    return out.get("message") or ""


def ok(out):
    """Everything that must hold for a successful run: status, and no error classification sneaking in."""
    assert out.get("status") == "success", out
    return out


CASES = {}


def case(fn):
    CASES[fn.__name__] = fn
    return fn


# ================================================================== env

@case
def env_derives_the_plugins_variables(jar):
    """The exact variable set of BrowserStackBuildWrapperOperations.buildEnvVars, including the -jenkins suffix."""
    reset()
    out = ok(run_atom(jar, {"action": "env", "username": USER, "BUILD_TAG": "jenkins-acme-42"})[0])
    assert keys(out) == {"operation", "BROWSERSTACK_USER", "BROWSERSTACK_USERNAME", "BROWSERSTACK_ACCESSKEY",
                         "BROWSERSTACK_ACCESS_KEY", "BROWSERSTACK_BUILD", "BROWSERSTACK_BUILD_NAME",
                         "BROWSERSTACK_PLUGIN_INVOKED"}, keys(out)
    assert data(out, "BROWSERSTACK_USER") == USER + "-jenkins", out
    assert data(out, "BROWSERSTACK_USERNAME") == USER + "-jenkins", out
    assert data(out, "BROWSERSTACK_ACCESSKEY") == KEY, out
    assert data(out, "BROWSERSTACK_ACCESS_KEY") == KEY, out
    assert data(out, "BROWSERSTACK_BUILD") == "jenkins-acme-42", out
    assert data(out, "BROWSERSTACK_BUILD_NAME") == "jenkins-acme-42", out
    assert data(out, "BROWSERSTACK_PLUGIN_INVOKED") == "true", out
    assert data(out, "operation") == "env", out


@case
def env_without_a_build_tag_exports_only_credentials(jar):
    """The plugin only set BROWSERSTACK_BUILD* inside the `if (buildTag != null)` branch; so does the atom."""
    out = ok(run_atom(jar, {"action": "env", "username": USER})[0])
    assert keys(out) == {"operation", "BROWSERSTACK_USER", "BROWSERSTACK_USERNAME", "BROWSERSTACK_ACCESSKEY",
                         "BROWSERSTACK_ACCESS_KEY"}, keys(out)
    assert "BROWSERSTACK_PLUGIN_INVOKED" not in keys(out), keys(out)


@case
def env_expands_and_opt_ins(jar):
    """${BUILD_TAG} resolves from the build environment, and the optional variables arrive only when asked for."""
    out = ok(run_atom(jar, {"action": "env", "username": USER, "buildTag": "${BUILD_TAG}", "BUILD_TAG": "job-7",
                            "local": "true", "localIdentifier": "tunnel-1", "rerun": "failed",
                            "rerunTests": "LoginTest"})[0])
    assert data(out, "BROWSERSTACK_BUILD_NAME") == "job-7", out
    assert data(out, "BROWSERSTACK_LOCAL") == "true", out
    assert data(out, "BROWSERSTACK_LOCAL_IDENTIFIER") == "tunnel-1", out
    assert data(out, "BROWSERSTACK_RERUN") == "failed", out
    assert data(out, "BROWSERSTACK_RERUN_TESTS") == "LoginTest", out


@case
def env_touches_no_network(jar):
    """The plugin never validated the key here either: env is a pure function of its inputs."""
    reset()
    ok(run_atom(jar, {"action": "env", "username": USER, "BUILD_TAG": "jenkins-acme-42"})[0])
    assert requests() == [], requests()


@case
def env_never_logs_the_key(jar):
    reset()
    _, proc, _ = run_atom(jar, {"action": "env", "username": USER})
    assert KEY not in proc.stdout, "the access key reached the console"
    assert "*" * len(KEY) in proc.stdout, proc.stdout


# ================================================================== upload

APP = bytes(range(256)) * 20


@case
def upload_sends_the_plugins_multipart(jar):
    """Part name `file`, the library's boundary, the file's own name and its bytes — checked by sha256 at the server."""
    reset()
    out = ok(run_atom(jar, {"action": "upload", "username": USER, "appPath": "app.apk", "apiUrl": api()},
                      files={"app.apk": APP})[0])
    record = requests()
    assert len(record) == 1, record
    entry = record[0]
    assert entry["method"] == "POST" and entry["path"] == "/upload", entry
    assert entry["authorization"] == "Basic " + base64.b64encode(("%s:%s" % (USER, KEY)).encode()).decode(), entry
    assert entry["content_type"].startswith("multipart/form-data; boundary=__END_OF_PART__"), entry
    assert entry["boundary"] == "__END_OF_PART__", entry
    assert len(entry["parts"]) == 1, entry
    part = entry["parts"][0]
    assert part["name"] == "file", part
    assert part["filename"] == "app.apk", part
    assert part["len"] == len(APP), part
    assert part["sha256"] == hashlib.sha256(APP).hexdigest(), part
    assert data(out, "appUrl").startswith("bs://"), out
    assert data(out, "BROWSERSTACK_APP_ID") == data(out, "appUrl"), out
    assert data(out, "uploadedBytes") == str(len(APP)), out
    assert data(out, "uploadAttempts") == "1", out
    assert data(out, "httpStatus") == "200", out
    assert data(out, "operation") == "upload", out


@case
def upload_relative_path_resolves_against_the_workspace(jar):
    out = ok(run_atom(jar, {"action": "upload", "username": USER, "appPath": "dist/app.ipa", "apiUrl": api()},
                      files={"dist/app.ipa": b"ipa-bytes"})[0])
    assert data(out, "uploadedBytes") == "9", out
    assert requests()[-1]["parts"][0]["filename"] == "app.ipa", requests()[-1]


@case
def upload_retries_once_on_503(jar):
    """AppUploaderHelper retried 5xx once, a second after the first attempt; so does the atom."""
    reset()
    out = ok(run_atom(jar, {"action": "upload", "username": USER, "appPath": "app.apk",
                            "apiUrl": api("/flaky")}, files={"app.apk": APP})[0])
    record = requests()
    assert [entry["status"] for entry in record] == [503, 200], record
    assert data(out, "uploadAttempts") == "2", out


@case
def upload_gives_up_after_two_5xx(jar):
    reset()
    out = run_atom(jar, {"action": "upload", "username": USER, "appPath": "app.apk",
                         "apiUrl": api("/down")}, files={"app.apk": APP})[0]
    assert out["status"] == "error" and out["errorCode"] == 41008, out
    assert out.get("errorType") == 2, out
    assert len(requests()) == 2, requests()
    assert "503" in message(out), message(out)


@case
def upload_does_not_retry_a_4xx(jar):
    reset()
    out = run_atom(jar, {"action": "upload", "username": USER, "appPath": "app.apk",
                         "apiUrl": api("/reject401")}, files={"app.apk": APP})[0]
    assert out["status"] == "error" and out["errorCode"] == 41007, out
    assert out.get("errorType") == 1, out
    assert len(requests()) == 1, requests()


@case
def upload_rejects_bad_credentials_at_the_server(jar):
    reset()
    out = run_atom(jar, {"action": "upload", "username": USER, "appPath": "app.apk", "apiUrl": api()},
                   secrets={"accessKey": "wrong-key"}, files={"app.apk": APP})[0]
    assert out["status"] == "error" and out["errorCode"] == 41007, out
    assert out.get("errorType") == 1, out
    assert len(requests()) == 1, requests()


@case
def upload_fails_when_the_answer_has_no_app_url(jar):
    """The library threw AppAutomateException(status 0) on an empty app_url, which the plugin retried."""
    reset()
    out = run_atom(jar, {"action": "upload", "username": USER, "appPath": "app.apk",
                         "apiUrl": api("/empty")}, files={"app.apk": APP})[0]
    assert out["status"] == "error" and out["errorCode"] == 41009, out
    assert len(requests()) == 2, requests()


@case
def upload_refuses_a_non_apk_ipa(jar):
    reset()
    out = run_atom(jar, {"action": "upload", "username": USER, "appPath": "app.zip", "apiUrl": api()},
                   files={"app.zip": b"zip"})[0]
    assert out["status"] == "error" and out["errorCode"] == 41005, out
    assert requests() == [], "a refused file must not be uploaded"


@case
def upload_refuses_a_missing_file(jar):
    reset()
    out = run_atom(jar, {"action": "upload", "username": USER, "appPath": "nope.apk", "apiUrl": api()})[0]
    assert out["status"] == "error" and out["errorCode"] == 41004, out
    assert requests() == []


@case
def upload_canonicalises_before_checking_the_extension(jar):
    """A symlink named app.apk pointing at a .zip: the library canonicalises first, so this must be refused."""
    directory = tempfile.mkdtemp(prefix="bs-link-")
    target = os.path.join(directory, "payload.zip")
    link = os.path.join(directory, "app.apk")
    open(target, "wb").write(b"zip-in-disguise")
    os.symlink(target, link)
    reset()
    out = run_atom(jar, {"action": "upload", "username": USER, "appPath": link, "apiUrl": api()})[0]
    assert out["status"] == "error" and out["errorCode"] == 41005, out
    assert requests() == []


# ================================================================== report

@case
def report_rows_match_the_plugins_file(jar):
    """The written file, field by field, against rows recomputed from the sessions."""
    reset()
    out, _, directory = run_atom(jar, {"action": "report", "username": USER, "buildName": "bs-build-ok",
                                       "apiUrl": api()})
    ok(out)
    rows = json.load(open(os.path.join(directory, "browserstack-artifacts", "buildResults.json")))
    assert rows == expected_rows(), json.dumps(rows, indent=2)
    assert data(out, "reportStatus") == "Success", out
    assert data(out, "totalSessions") == "6", out
    assert data(out, "totalErrors") == str(expected_errors()) == "2", out
    assert data(out, "buildId") == BUILD_ID, out
    assert data(out, "buildDuration") == human(BUILD_DURATION) == "01h 02m 05s", out
    assert data(out, "buildBrowserUrl") == "https://automate.browserstack.com/builds/abcdef123", out
    assert data(out, "reportFile") == "browserstack-artifacts/buildResults.json", out
    assert data(out, "operation") == "report", out


@case
def report_sorts_by_user_mark_then_newest(jar):
    _, _, directory = run_atom(jar, {"action": "report", "username": USER, "buildName": "bs-build-ok",
                                     "apiUrl": api()})
    rows = json.load(open(os.path.join(directory, "browserstack-artifacts", "buildResults.json")))
    assert [(row["name"], row["userMarked"]) for row in rows] == [
        ("login flow", "failed"), ("upload flow", "passed"), ("search smoke", "unmarked"),
        ("s2", "unmarked"), ("checkout on iPad", "unmarked"), ("cart flow", "unmarked")], rows
    # the plugin's own quirk, kept: a session BrowserStack and the user both call "failed" is recorded as
    # "unmarked" (the user did not override anything) and therefore does NOT count as an error
    failed = [row for row in rows if row["name"] == "checkout on iPad"][0]
    assert (failed["status"], failed["userMarked"]) == ("failed", "unmarked"), failed


@case
def report_running_session_has_no_duration(jar):
    _, _, directory = run_atom(jar, {"action": "report", "username": USER, "buildName": "bs-build-ok",
                                     "apiUrl": api()})
    rows = json.load(open(os.path.join(directory, "browserstack-artifacts", "buildResults.json")))
    running = [row for row in rows if row["name"] == "search smoke"][0]
    assert running["duration"] == "-", running
    assert running["createdAtReadable"] == "02 Jan 2026, 05:04 UTC", running
    assert running["url"].endswith("&source=jenkins_plugin"), running
    failed = [row for row in rows if row["name"] == "checkout on iPad"][0]
    assert failed["browser"] == "iPad 9th" and failed["os"] == "ios 15", failed


@case
def report_empty_build_is_a_failed_report_not_an_error(jar):
    """The plugin logged Failed and let the step pass; an empty report is not a broken pipeline."""
    reset()
    out, _, directory = run_atom(jar, {"action": "report", "username": USER, "buildName": "bs-build-empty",
                                       "apiUrl": api()})
    ok(out)
    assert data(out, "reportStatus") == "Failed", out
    assert data(out, "totalSessions") == "0", out
    assert json.load(open(os.path.join(directory, "browserstack-artifacts", "buildResults.json"))) == [], \
        "an empty build must still produce an (empty) file"


@case
def report_missing_build_is_a_user_error(jar):
    """The plugin produced a silent empty report for a typo; the atom refuses."""
    reset()
    out = run_atom(jar, {"action": "report", "username": USER, "buildName": "typo-build", "apiUrl": api()})[0]
    assert out["status"] == "error" and out["errorCode"] == 41011, out
    assert out.get("errorType") == 1, out
    assert [entry["path"] for entry in requests()] == ["/builds.json"], requests()


@case
def report_duplicate_build_name_is_refused(jar):
    """getBuildByName() requires exactly one match; two builds with one name throw BuildNotFound."""
    out = run_atom(jar, {"action": "report", "username": USER, "buildName": "bs-build-multi", "apiUrl": api()})[0]
    assert out["status"] == "error" and out["errorCode"] == 41011, out
    assert "matched 2" in message(out), message(out)


@case
def report_build_without_hashed_id_is_reported(jar):
    out = run_atom(jar, {"action": "report", "username": USER, "buildName": "bs-build-noid", "apiUrl": api()})[0]
    assert out["status"] == "error" and out["errorCode"] == 41012, out


@case
def report_sessions_failure_is_reported(jar):
    out = run_atom(jar, {"action": "report", "username": USER, "buildName": "bs-build-404", "apiUrl": api()})[0]
    assert out["status"] == "error" and out["errorCode"] == 41013, out
    assert "404" in message(out), message(out)


@case
def report_builds_failure_is_reported(jar):
    out = run_atom(jar, {"action": "report", "username": USER, "buildName": "bs-build-forbidden",
                         "apiUrl": api()})[0]
    assert out["status"] == "error" and out["errorCode"] == 41016, out
    assert out.get("errorType") == 1, out


@case
def report_needs_a_build_name(jar):
    reset()
    out = run_atom(jar, {"action": "report", "username": USER, "apiUrl": api()})[0]
    assert out["status"] == "error" and out["errorCode"] == 41010, out
    assert requests() == []


@case
def report_falls_back_to_the_build_tag(jar):
    _, _, directory = run_atom(jar, {"action": "report", "username": USER, "BUILD_TAG": "bs-build-ok",
                                     "apiUrl": api()})
    assert os.path.exists(os.path.join(directory, "browserstack-artifacts", "buildResults.json")), "no report"


# ================================================================== routing and inputs

@case
def unknown_action_and_product_are_refused(jar):
    reset()
    out = run_atom(jar, {"action": "deploy", "username": USER, "apiUrl": api()})[0]
    assert out["status"] == "error" and out["errorCode"] == 41001, out
    out = run_atom(jar, {"action": "report", "username": USER, "product": "selenium", "buildName": "x"})[0]
    assert out["status"] == "error" and out["errorCode"] == 41020, out
    assert requests() == [], requests()


@case
def credentials_are_required(jar):
    reset()
    out = run_atom(jar, {"action": "env"})[0]
    assert out["status"] == "error" and out["errorCode"] == 41002, out
    out = run_atom(jar, {"action": "env", "username": USER}, secrets={})[0]
    assert out["status"] == "error" and out["errorCode"] == 41003, out
    assert requests() == []


# ================================================================== the contract in task.json

VISIBLE = {
    "env": ["action", "username", "accessKey", "apiUrl", "timeoutSeconds", "buildTag", "local", "localIdentifier",
            "rerun", "rerunTests"],
    "upload": ["action", "username", "accessKey", "apiUrl", "timeoutSeconds", "appPath"],
    "report": ["action", "username", "accessKey", "apiUrl", "timeoutSeconds", "product", "buildName", "outputDir"],
}


def task_json():
    return json.load(open(HERE / "task.json", encoding="utf-8"))


def java_sources():
    return {path.name: path.read_text(encoding="utf-8")
            for path in (HERE / "src" / "main" / "java" / "com" / "jenkinschina" / "atom" / "browserstack").glob("*.java")}


@case
def contract_task_json_declares_what_the_java_reads(_jar=None):
    """Every field the Java reads is declared, and every declared field is read — no dead input, no implicit one."""
    task = task_json()
    sources = "\n".join(java_sources().values())
    read = set(re.findall(r'get(?:Param|BooleanParam|IntParam|Secret|Credential)\(\s*"([^"]+)"', sources))
    read = {name for name in read if not name.isupper()}  # BUILD_TAG style lookups are the build environment
    declared = set(task["input"]) | set(task.get("x-jenkins", {}).get("secrets", {}))
    assert read - declared == set(), "the Java reads fields task.json does not declare: %s" % sorted(read - declared)
    assert declared - read == set(), "task.json declares fields nothing reads: %s" % sorted(declared - read)


def visible_fields(task, action):
    """Evaluate the rely expressions for one action value, the way the runner's renderer does."""
    shown = set()
    for name, spec in task["input"].items():
        rely = spec.get("rely")
        if not rely:
            shown.add(name)
            continue
        operation = rely.get("operation", "AND")
        verdicts = []
        for expression in rely.get("expression", []):
            value = action if expression.get("key") == "action" else ""
            if "regex" in expression:
                verdicts.append(bool(re.search(expression["regex"], value)))
            else:
                verdicts.append(value == expression.get("value", ""))
        if operation == "AND":
            shown.add(name) if all(verdicts) else None
        elif operation == "OR":
            shown.add(name) if any(verdicts) else None
        elif operation == "NOT":
            shown.add(name) if not any(verdicts) else None
        else:
            raise AssertionError("unknown rely operation %r on %s" % (operation, name))
    return shown


@case
def contract_rely_shows_exactly_the_documented_fields(_jar=None):
    task = task_json()
    for action in ACTIONS:
        assert visible_fields(task, action) == set(VISIBLE[action]), \
            "%s shows %s" % (action, sorted(visible_fields(task, action) ^ set(VISIBLE[action])))
    for name in task["input"]:
        assert [action for action in ACTIONS if name in VISIBLE[action]], "input %s is hidden for every action" % name


@case
def contract_no_output_shadows_an_input(_jar=None):
    task = task_json()
    shadow = set(task["output"]) & set(task["input"])
    assert shadow == set(), "outputs shadow inputs: %s" % sorted(shadow)


@case
def contract_error_codes_are_documented_and_used(_jar=None):
    task = task_json()
    documented = set(task.get("x-jenkins", {}).get("errors", {}))
    sources = "\n".join(java_sources().values())
    used = set(re.findall(r"\b(410\d\d)\b", sources))
    assert used - documented == set(), "codes raised but not documented: %s" % sorted(used - documented)
    assert documented - used == set(), "codes documented but never raised: %s" % sorted(documented - used)


# ================================================================== runner

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", nargs="?", default="all", choices=["all", "list", "run"])
    parser.add_argument("name", nargs="?")
    parser.add_argument("--jar", default=str(HERE / "dist" / "browserstack" / "browserstack.jar"))
    args = parser.parse_args()

    if args.command == "list":
        for name in CASES:
            print(name)
        return 0

    selected = list(CASES) if args.command == "all" else [args.name]
    if args.command == "run" and args.name not in CASES:
        raise SystemExit("no such case: %s" % args.name)

    needs_server = any(name not in {"contract_task_json_declares_what_the_java_reads",
                                    "contract_rely_shows_exactly_the_documented_fields",
                                    "contract_no_output_shadows_an_input",
                                    "contract_error_codes_are_documented_and_used"} for name in selected)
    failures = []
    if needs_server:
        start_fake()
    try:
        for name in selected:
            started = time.time()
            try:
                CASES[name](args.jar)
                print("ok   %-58s %5.1fs" % (name, time.time() - started))
            except AssertionError as e:
                failures.append(name)
                print("FAIL %-58s %s" % (name, str(e)[:1500]))
            except Exception as e:  # noqa: BLE001 - a crash in one case must not hide the others
                failures.append(name)
                print("FAIL %-58s %s: %s" % (name, type(e).__name__, str(e)[:1500]))
    finally:
        if needs_server:
            stop_fake()

    print("%d/%d cases passed" % (len(selected) - len(failures), len(selected)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
