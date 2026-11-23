#!/usr/bin/env python3
"""Plant one bug at a time in the atom and prove the checks catch it.

A passing smoke suite means nothing if it passes for a broken atom, so each mutation below is a single textual change to
the real source; the atom is rebuilt from the mutated tree and the named case has to fail. If a mutation survives, the
suite has a hole. The set covers all three actions and picks the details that are easy to "clean up" by accident: the
CI attribution suffix BrowserStack reads, the multipart field name, the retry rule, the report's sort direction and what
counts as an error.

    python3 tools/mutations.py
"""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent.parent
SRC = pathlib.Path("src/main/java/com/jenkinschina/atom/browserstack")

MUTATIONS = [
    ("the CI attribution suffix is dropped from BROWSERSTACK_USERNAME",
     "Env.java", 'String user = username + "-jenkins";', "String user = username;",
     "env_derives_the_plugins_variables"),
    ("the upload part is named app instead of file",
     "Upload.java", 'name=\\"file\\"', 'name=\\"app\\"',
     "upload_sends_the_plugins_multipart"),
    ("a 4xx is retried like a server error",
     "Http.java", "return status == 0 || status >= 500;", "return status == 0 || status >= 400;",
     "upload_does_not_retry_a_4xx"),
    ("the report sorts oldest first instead of newest first",
     "Report.java", "return -byDate;", "return byDate;",
     "report_sorts_by_user_mark_then_newest"),
    ("totalErrors counts a failed status instead of the user's mark",
     "Report.java", '"failed".equals(row.get("userMarked"))', '"failed".equals(row.get("status"))',
     "report_rows_match_the_plugins_file"),
]


def sdk_jar():
    # a sibling checkout locally, a subdirectory on CI (the workflow checks the SDK out inside the repo)
    for root in (HERE.parent / "jenkins-atom-sdk", HERE / "jenkins-atom-sdk"):
        candidates = sorted((root / "target").glob("jenkins-atom-sdk-*.jar"))
        if candidates:
            return candidates[-1]
    sys.exit("no SDK jar: build ../jenkins-atom-sdk first")


def build(tree):
    env = dict(os.environ)
    env["SDK_JAR"] = str(sdk_jar())
    proc = subprocess.run(["./build.sh"], cwd=tree, env=env, capture_output=True, text=True)
    if proc.returncode != 0:
        return None, proc.stderr[-1500:]
    return tree / "dist" / "browserstack" / "browserstack.jar", proc.stdout


def main():
    failures = 0
    for name, filename, old, new, case_name in MUTATIONS:
        tree = pathlib.Path(tempfile.mkdtemp(prefix="bs-mut-")) / "atom"
        shutil.copytree(HERE, tree, ignore=shutil.ignore_patterns("dist", ".git", "__pycache__"))
        source = tree / SRC / filename
        text = source.read_text()
        if old not in text:
            print("SKIP  %s: the mutation no longer matches %s" % (name, filename))
            failures += 1
            continue
        source.write_text(text.replace(old, new, 1))
        jar, output = build(tree)
        if jar is None:
            print("SKIP  %s: the mutated atom does not build: %s" % (name, output))
            failures += 1
            continue
        proc = subprocess.run([sys.executable, str(HERE / "tools" / "check.py"), "run", case_name, "--jar", str(jar)],
                              capture_output=True, text=True, cwd=str(HERE))
        caught = proc.returncode != 0
        print("%s  %s (%s) %s" % ("caught" if caught else "MISSED", name, case_name,
                                  "" if caught else proc.stdout.strip().splitlines()[:2]))
        if not caught:
            failures += 1
    print("%d/%d mutations caught" % (len(MUTATIONS) - failures, len(MUTATIONS)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
