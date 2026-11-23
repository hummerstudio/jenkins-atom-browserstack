#!/usr/bin/env bash
# Build, then run the atom the way the runner launches it (smoke-lib) and the full behaviour matrix (check.py).
#
#   ./tools/smoke.sh              # build + shell-level run + every behaviour/contract case
#   ./tools/smoke.sh --mutations  # ... and the mutation run (rebuilds the atom per planted bug, slower)
set -uo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/Users/tangming/tools/jdk-21.0.12.1+1/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

./build.sh || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

port=$((25000 + RANDOM % 900))
log="$(mktemp)"
ready="$(mktemp)"
python3 tools/fake_browserstack.py "$port" "$log" >"$ready" 2>&1 & server=$!
trap 'kill $server 2>/dev/null; wait $server 2>/dev/null' EXIT
for _ in $(seq 1 100); do
  grep -q "ready" "$ready" 2>/dev/null && break
  sleep 0.1
done
grep -q "ready" "$ready" || { cat "$ready" >&2; fail "the fake BrowserStack did not start"; }
api="http://127.0.0.1:$port"

# env: the plugin's variable set, derived offline
dir="$(mktemp -d)"
: >"$dir/input.json"
printf '%s' '{"accessKey": "bskey-123"}' >"$dir/.secrets.json"
run "$dir" action=env username=bsuser BUILD_TAG=jenkins-acme-42 >"$dir/atom.log" 2>&1
status "$dir" | grep -q success || fail "env: $(cat "$dir/output.json")"
[ "$(output "$dir" BROWSERSTACK_USERNAME)" = "bsuser-jenkins" ] || fail "env user: $(cat "$dir/output.json")"
[ "$(output "$dir" BROWSERSTACK_BUILD_NAME)" = "jenkins-acme-42" ] || fail "env build name: $(cat "$dir/output.json")"
[ "$(output "$dir" BROWSERSTACK_PLUGIN_INVOKED)" = "true" ] || fail "env invoked: $(cat "$dir/output.json")"
grep -q 'BROWSERSTACK_ACCESS_KEY=\*\*\*\*\*\*\*\*\*' "$dir/atom.log" || fail "env must log the key masked"

# upload: the real wire shape, against a server that checks it
dir2="$(mktemp -d)"
: >"$dir2/input.json"
printf '%s' '{"accessKey": "bskey-123"}' >"$dir2/.secrets.json"
head -c 4096 /dev/urandom >"$dir2/app.apk"
run "$dir2" action=upload username=bsuser "appPath=$dir2/app.apk" "apiUrl=$api" >"$dir2/atom.log" 2>&1
status "$dir2" | grep -q success || fail "upload: $(cat "$dir2/output.json")"
app_url="$(output "$dir2" appUrl)"
case "$app_url" in bs://*) ;; *) fail "upload app url: $(cat "$dir2/output.json")" ;; esac
[ "$(output "$dir2" BROWSERSTACK_APP_ID)" = "$app_url" ] || fail "upload app id: $(cat "$dir2/output.json")"

# report: four sessions, two of them count as errors
dir3="$(mktemp -d)"
: >"$dir3/input.json"
printf '%s' '{"accessKey": "bskey-123"}' >"$dir3/.secrets.json"
run "$dir3" action=report username=bsuser buildName=bs-build-ok "apiUrl=$api" >"$dir3/atom.log" 2>&1
status "$dir3" | grep -q success || fail "report: $(cat "$dir3/output.json")"
[ "$(output "$dir3" reportStatus)" = "Success" ] || fail "report status: $(cat "$dir3/output.json")"
[ "$(output "$dir3" totalSessions)" = "6" ] || fail "report sessions: $(cat "$dir3/output.json")"
[ "$(output "$dir3" totalErrors)" = "2" ] || fail "report errors: $(cat "$dir3/output.json")"
[ -s "$dir3/browserstack-artifacts/buildResults.json" ] || fail "report file missing"

# a build name nobody owns fails instead of producing an empty green report
dir4="$(mktemp -d)"
: >"$dir4/input.json"
printf '%s' '{"accessKey": "bskey-123"}' >"$dir4/.secrets.json"
run "$dir4" action=report username=bsuser buildName=typo-build "apiUrl=$api" >"$dir4/atom.log" 2>&1
python3 -c "import json,sys;d=json.load(open(sys.argv[1]));sys.exit(0 if d['errorCode']==41011 else 1)" \
  "$dir4/output.json" || fail "unknown build must be 41011: $(cat "$dir4/output.json")"

kill $server 2>/dev/null; wait $server 2>/dev/null; trap - EXIT

python3 tools/check.py all --jar "dist/browserstack/browserstack.jar" || exit 1
if [ "${1:-}" = "--mutations" ]; then
  python3 tools/mutations.py || exit 1
fi
