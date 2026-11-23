# browserstack

One BrowserStack step: export the `BROWSERSTACK_*` environment, upload an `.apk`/`.ipa` to App Automate, and summarise a
build's sessions into `buildResults.json`. It replaces the step-shaped part of the `browserstack-integration` plugin
(1,489 installs); the rest of that plugin is Jenkins extension points, and this file says which and why.

## What the plugin actually is (unpacked, not guessed)

`browserstack-integration.hpi` 1.2.26 (`WEB-INF/lib/browserstack-integration.jar`, 97 classes) against
`jenkinsci/browserstack-integration-plugin` at `22dfca2` (`pom.xml` 1.2.26-SNAPSHOT). Three things are `WorkflowStep`
subclasses — that is the whole pipeline surface:

| plugin step | function name | shape | this atom |
|---|---|---|---|
| `BrowserStackPipelineStep` | `browserstack` | **block**: exports env, optionally starts the Local tunnel, stops it in a `TailCall` | `action: env` (variables only) |
| `AppUploaderStep` | `browserstackAppUploader` | **block**: uploads the app, sets `BROWSERSTACK_APP_ID` for the body | `action: upload` |
| `BrowserStackReportStep` | `browserStackReportAut` | plain step: fetch build + sessions, add a build action | `action: report` (JSON file + report link) |

Everything else in the jar is a Jenkins surface with no step equivalent: `BrowserStackBuildWrapper` (freestyle block),
`AppUploaderBuilder` (builder), `BrowserStackReportPublisher`, `BrowserStackCypressReportPublisher`,
`BrowserStackTestReportPublisher`, `AutomateTestDataPublisher` (build pages and `TestResult`s), the quality-dashboard /
observability half (`qualityDashboard/*`, `observability/*`), the Google Analytics tracker, and the
`BrowserStackCredentials` credentials type.

## `env`

`BrowserStackBuildWrapperOperations#buildEnvVars`, branch for branch:

- `BROWSERSTACK_USER` and `BROWSERSTACK_USERNAME` are `<username>-jenkins`. The suffix is not decoration: BrowserStack
  uses it to attribute sessions to the CI integration, so dropping it changes what the account's dashboard shows.
- `BROWSERSTACK_ACCESSKEY` and `BROWSERSTACK_ACCESS_KEY` both carry the key, because BrowserStack SDK versions disagree
  about the spelling. The console prints a mask, never the key.
- `BROWSERSTACK_BUILD` and `BROWSERSTACK_BUILD_NAME` (plus `BROWSERSTACK_PLUGIN_INVOKED=true`) are set **only** when
  there is a `BUILD_TAG` — the plugin put them inside `if (buildTag != null)`, and a step that invented a build name
  where the plugin left it empty would produce sessions the plugin's own report page cannot find.
- `BROWSERSTACK_LOCAL`, `BROWSERSTACK_LOCAL_IDENTIFIER`, `BROWSERSTACK_RERUN`, `BROWSERSTACK_RERUN_TESTS` are exported
  when given. `BROWSERSTACK_LOCAL=true` claims a tunnel is up; the atom does not start one (see below), so pair it with
  your own `BrowserStackLocal` process, which is what the identifier is for.

The action never touches the network: the plugin did not validate the key at this point either, which is why this half
can be checked without any server.

## `upload`

Wire shape from `automate-client-java 0.7` (`AppAutomateClient#uploadApp`), because the plugin's users are that
library's users:

- `POST https://api-cloud.browserstack.com/app-automate/upload`, basic auth `username:accessKey`;
- `multipart/form-data`, boundary `__END_OF_PART__`, one part named `file` carrying the app's canonical file name and
  its bytes — a server that keys on the field name still finds it;
- the answer must be JSON with a non-empty `app_url` (a `bs://…` id); extra fields are ignored;
- at most two attempts, retrying only on `5xx`, on no answer at all, or on an empty `app_url` — the library throws
  `statusCode 0` for that last one, and `AppUploaderHelper` retried it too;
- the extension check runs on the **canonical** path, so a symlink called `app.apk` pointing at a `.zip` is refused.

One deliberate deviation: the plugin logs `App Id empty. ABORTING!!!` and lets the block continue without
`BROWSERSTACK_APP_ID`, so the build fails later inside the tests with no hint about the upload. Here the upload failure
is the failure, with a code that says which part broke (`41005` extension, `41007` rejected, `41008` retried out,
`41009` no `app_url`).

## `report`

`GET <base>/builds.json?build=<name>&limit=1`, then `GET <base>/builds/<hashed_id>/sessions.json`, then the rows the
plugin's `convertSessionToJsonObject` produced: `name`, `buildName`, `buildUrl`, `buildDuration`, `browser`, `os`,
`status`, `userMarked`, `duration`, `createdAt`, `createdAtReadable`, `url` (`…&source=jenkins_plugin`).

- `buildUrl` comes from the **first session as received**, before sorting, because that is what the plugin did.
- `browser` is the device when the session has one, else the browser; `os` is `os + " " + os_version`.
- `userMarked` is `unmarked` when the user's mark equals BrowserStack's verdict, else the user's mark; a running session
  shows `duration: "-"`.
- the sort is the plugin's: user mark ascending, newest first inside a mark.
- `totalErrors` counts `status == error` **or** `userMarked == failed`. Note the consequence, which is the plugin's and
  is kept: a session that BrowserStack and the user both call `failed` is recorded as `unmarked` and is *not* counted as
  an error. `tools/check.py` pins that quirk with a case of its own, so "fixing" it silently would fail the suite.
- the file goes to `browserstack-artifacts/buildResults.json` in the workspace and is declared as an artifact; the build
  page gets a report link to the BrowserStack build instead of the plugin's HTML table (an atom cannot add a build page).
- `builds.json` matching zero builds is a user error (`41011`). The plugin logged "No build found by name" and produced
  an empty green report, which is how a typo in `BROWSERSTACK_BUILD_NAME` looks like a passing build.
- an existing build with no sessions is **not** an error: `reportStatus=Failed`, the step passes, the file is `[]`.

## Not ported

- **The Local tunnel.** `browserstack(...)` is a block: it downloads `BrowserStackLocal`, starts it, and stops it from a
  body callback when the body ends. An atom is one process that exits; there is no hook after the following steps, so
  there is nothing to stop the tunnel with. Splitting it into `start`/`stop` actions would leave a daemon behind after
  every aborted build, which is worse than not having it.
- **The publishers and the build pages.** `BrowserStackReportPublisher`, the Cypress and test-report publishers, the
  `AutomateTestDataPublisher` and the freestyle wrapper all render UI or attach `TestResult`s inside the Jenkins JVM.
- **The quality dashboard / observability half** (`quality-engineering-insights.browserstack.com`, `QEI_URL`,
  `BROWSERSTACK_GRR_REGION`). It reports a Jenkins instance's job history to BrowserStack's product, keyed to a job that
  an atom does not know about. `apiUrl` covers the one thing that was operationally useful in it — pointing at
  `api-eu-only.browserstack.com` — without the rest.
- **The analytics tracker** (`google-analytics-java`, `PluginsTracker`): telemetry about the plugin, not about the build.

## What is verified, and what is not

`tools/check.py` runs 32 cases against `tools/fake_browserstack.py`, a stand-in that enforces what BrowserStack enforces
before answering: basic auth on every endpoint, a multipart part named `file` whose payload sha256 must match the file on
disk, and the `automation_build` / `automation_session` envelopes. It covers the retry paths (503 then 200, 503 twice,
401 never retried, 2xx without `app_url`), the refusal paths (missing file, `.zip`, a symlink to a `.zip`, no build by
that name, two builds with that name, no `hashed_id`, 404 on sessions, 403 on builds), and the report file itself — the
rows are recomputed from the fixtures by a second implementation and compared field by field.

Nothing here has been run against `api.browserstack.com`: that needs an account with App Automate and a real `.apk`.
The wire shape is the client library's, checked against its source, not against a live server. Treat the first real run
as the test of that assumption.

    ./tools/smoke.sh              # build + run the three actions + the whole matrix
    ./tools/smoke.sh --mutations  # ... and prove the suite catches five planted bugs
