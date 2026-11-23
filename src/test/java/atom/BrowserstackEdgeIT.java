package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The browserstack atom's edges against a stand-in that answers by path: the upload retry, the ways an upload can fail,
 * the report of an empty build, the payload shapes the report has to survive, and every input it refuses by code.
 *
 * <p>The stand-in is a JDK {@link HttpServer} rather than the Python fake, because these cases are about what the atom
 * does with an answer — a 2xx without an app_url, a body that is not JSON, two builds with the same name — and the
 * answer has to be written here to be tested at all.
 */
class BrowserstackEdgeIT {

    private static HttpServer server;
    private static int port;
    private static String api;
    private static final AtomicInteger FLAKY = new AtomicInteger();

    private Atom atom;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        api = "http://127.0.0.1:" + port;
        server.createContext("/", BrowserstackEdgeIT::handle);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
        exchange.getRequestBody().readAllBytes();
        if (path.endsWith("/upload")) {
            upload(exchange, path);
            return;
        }
        if (path.endsWith("/builds.json")) {
            builds(exchange, query);
            return;
        }
        if (path.endsWith("/sessions.json")) {
            String id = path.replaceAll("^.*/builds/([^/]+)/sessions\\.json$", "$1");
            sessions(exchange, id);
            return;
        }
        send(exchange, 404, "{\"error\":\"no such endpoint\"}");
    }

    private static void upload(HttpExchange exchange, String path) throws IOException {
        if (path.startsWith("/flaky") && FLAKY.getAndIncrement() == 0) {
            send(exchange, 503, "{\"error\":\"try again\"}");
            return;
        }
        if (path.startsWith("/down")) {
            send(exchange, 503, "{\"error\":\"" + "service unavailable ".repeat(30) + "\"}");
            return;
        }
        if (path.startsWith("/reject401")) {
            send(exchange, 401, "{\"error\":\"invalid credentials\"}");
            return;
        }
        if (path.startsWith("/empty")) {
            send(exchange, 200, "{\"message\":\"no app_url in this answer\"}");
            return;
        }
        send(exchange, 200, "{\"app_url\":\"bs://app-1\",\"custom_id\":\"ignored\"}");
    }

    private static void builds(HttpExchange exchange, String query) throws IOException {
        String name = query.replaceAll(".*build=([^&]*)&?.*", "$1");
        switch (name) {
            case "garbage" -> send(exchange, 200, "not json at all");
            case "notlist" -> send(exchange, 200, "{\"a\": 1}");
            case "strings" -> send(exchange, 200, "[\"a\", 3]");
            case "noid" -> send(exchange, 200, "[{\"automation_build\": {\"name\": \"noid\", \"duration\": 1}}]");
            case "multi" -> send(exchange, 200, "[{\"automation_build\": {\"hashed_id\": \"a\", \"name\": \"m\"}},"
                    + "{\"automation_build\": {\"hashed_id\": \"b\", \"name\": \"m\"}}]");
            case "direct" -> send(exchange, 200, "[{\"hashed_id\": \"direct1\", \"name\": \"d\", \"duration\": 3725}]");
            case "empty" -> send(exchange, 200, "[{\"automation_build\": {\"hashed_id\": \"empty\", "
                    + "\"name\": \"empty\", \"duration\": 0}}]");
            case "sessions-boom" -> send(exchange, 200, "[{\"automation_build\": {\"hashed_id\": \"boom\", "
                    + "\"name\": \"sb\", \"duration\": 1}}]");
            case "garbage-sessions" -> send(exchange, 200, "[{\"automation_build\": {\"hashed_id\": \"garbage\", "
                    + "\"name\": \"gs\", \"duration\": 1}}]");
            case "forbidden" -> send(exchange, 403, "{\"error\":\"forbidden\"}");
            case "boom" -> send(exchange, 500, "{\"error\":\"boom\"}");
            default -> send(exchange, 200, "[]");
        }
    }

    private static void sessions(HttpExchange exchange, String id) throws IOException {
        switch (id) {
            case "boom" -> send(exchange, 500, "{\"error\":\"boom\"}");
            case "garbage" -> send(exchange, 200, "{oops");
            case "empty" -> send(exchange, 200, "[]");
            default -> {
                String body = "["
                        // first session has no browser_url: the build page cannot be derived, so every row's is empty
                        + "{\"automation_session\": {\"hashed_id\": \"r1\", \"name\": \"running one\", "
                        + "\"browserstack_status\": \"running\", \"status\": \"running\", \"duration\": null, "
                        + "\"created_at\": \"2026-01-02T05:00:00.000Z\", \"public_url\": \"https://bs/s/r1?t=1\"}},"
                        + "{\"automation_session\": {\"hashed_id\": \"p1\", \"name\": \"zeta\", "
                        + "\"browserstack_status\": \"passed\", \"status\": \"passed\", \"duration\": 30, "
                        + "\"browser\": \"chrome\", \"os\": \"Windows\", \"os_version\": \"11\", "
                        + "\"created_at\": \"2026-01-02T03:04:05.000Z\", \"public_url\": \"https://bs/s/p1?t=1\"}},"
                        + "{\"automation_session\": {\"hashed_id\": \"p2\", \"name\": \"alpha\", "
                        + "\"browserstack_status\": \"passed\", \"status\": \"passed\", \"duration\": 65, "
                        + "\"device\": \"Pixel 7\", \"os\": \"android\", \"os_version\": \"13\", "
                        + "\"created_at\": \"2026-01-02T03:04:05.000Z\", \"public_url\": \"https://bs/s/p2?t=1\"}},"
                        + "{\"automation_session\": {\"hashed_id\": \"e1\", \"name\": \"errored\", "
                        + "\"browserstack_status\": \"error\", \"status\": \"passed\", \"duration\": 90000, "
                        + "\"created_at\": \"not-a-date\", \"public_url\": \"https://bs/s/e1?t=1\"}}"
                        + "]";
                send(exchange, 200, body);
            }
        }
    }

    private static void send(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of().file(".secrets.json", "{\"accessKey\": \"bskey-123\"}");
    }

    private Atom report(String buildName) throws Exception {
        return atom.input("action", "report")
                .input("username", "bsuser")
                .input("buildName", buildName)
                .input("apiUrl", api);
    }

    @Test
    @DisplayName("env: a local identifier turns on BROWSERSTACK_LOCAL, and the rerun switches are passed through")
    void exportsTheLocalAndRerunVariables() throws Exception {
        AtomResult run = atom.withoutEnvironment()
                .input("action", "env")
                .input("username", "${BS_USER}")
                .input("BS_USER", "bsuser")
                .input("buildTag", "")
                .input("localIdentifier", "${LOCAL_ID}")
                .input("LOCAL_ID", "tunnel-7")
                .input("rerun", "failed")
                .input("rerunTests", "true")
                .run();

        run.assertOk();
        assertEquals("bsuser-jenkins", run.field("BROWSERSTACK_USERNAME"), "username expansion: " + run);
        assertEquals("true", run.field("BROWSERSTACK_LOCAL"));
        assertEquals("tunnel-7", run.field("BROWSERSTACK_LOCAL_IDENTIFIER"));
        assertEquals("failed", run.field("BROWSERSTACK_RERUN"));
        assertEquals("true", run.field("BROWSERSTACK_RERUN_TESTS"));
        assertEquals("", run.field("BROWSERSTACK_BUILD_NAME"), "there is no BUILD_TAG, so no build name");
        assertTrue(run.console().contains("no BUILD_TAG"), "the missing BUILD_TAG must be logged:\n" + run.console());
        assertEquals("bskey-123", run.field("BROWSERSTACK_ACCESSKEY"));
    }

    @Test
    @DisplayName("env: local=true without an identifier still sets BROWSERSTACK_LOCAL and nothing else")
    void exportsLocalWithoutAnIdentifier() throws Exception {
        AtomResult run = atom.input("action", "env")
                .input("username", "bsuser")
                .input("buildTag", "tag-1")
                .input("local", "true")
                .run();

        run.assertOk();
        assertEquals("true", run.field("BROWSERSTACK_LOCAL"));
        assertEquals("", run.field("BROWSERSTACK_LOCAL_IDENTIFIER"));
        assertEquals("tag-1", run.field("BROWSERSTACK_BUILD"));
        assertEquals("true", run.field("BROWSERSTACK_PLUGIN_INVOKED"));
    }

    @Test
    @DisplayName("the four input mistakes are refused by code before anything is uploaded or fetched")
    void refusesBadInput() throws Exception {
        AtomResult action = atom.input("action", "download").input("username", "bsuser").run();
        action.assertRefused();
        assertEquals("41001", action.field("errorCode"));

        AtomResult user = atom.input("action", "env").input("username", "  ").run();
        user.assertRefused();
        assertEquals("41002", user.field("errorCode"));

        AtomResult key = Atom.of().input("action", "env").input("username", "bsuser").run();
        key.assertRefused();
        assertEquals("41003", key.field("errorCode"), "a run without the credential file has no access key: " + key);

        AtomResult product = report("anything").input("product", "sauce").run();
        product.assertRefused();
        assertEquals("41020", product.field("errorCode"));
        assertTrue(product.field("message").contains("product must be one of automate, app-automate"),
                product.field("message"));
    }

    @Test
    @DisplayName("upload: the app path is required, has to exist, and has to be an .apk or an .ipa")
    void refusesBadAppPaths() throws Exception {
        AtomResult missing = atom.input("action", "upload").input("username", "bsuser").input("appPath", "").run();
        missing.assertRefused();
        assertEquals("41004", missing.field("errorCode"));
        assertTrue(missing.field("message").contains("appPath is required"), missing.field("message"));

        AtomResult absent = atom.input("action", "upload")
                .input("username", "bsuser")
                .input("appPath", "build/app.apk")
                .run();
        absent.assertRefused();
        assertEquals("41004", absent.field("errorCode"));
        assertTrue(absent.field("message").contains("no such file: build/app.apk"), absent.field("message"));

        AtomResult wrongType = atom.file("build/app.zip", "zip")
                .input("action", "upload")
                .input("username", "bsuser")
                .input("appPath", "build/app.zip")
                .run();
        wrongType.assertRefused();
        assertEquals("41005", wrongType.field("errorCode"));
        assertTrue(wrongType.field("message").contains("only .apk and .ipa"), wrongType.field("message"));
    }

    @Test
    @DisplayName("upload: a 503 is retried once and the second attempt succeeds")
    void retriesA503AndSucceeds() throws Exception {
        AtomResult run = atom.file("build/app.IPA", "ipa-bytes")
                .input("action", "upload")
                .input("username", "bsuser")
                .input("appPath", "build/app.IPA")
                .input("apiUrl", api + "/flaky")
                .run();

        run.assertOk();
        assertEquals("bs://app-1", run.field("appUrl"));
        assertEquals("2", run.field("uploadAttempts"), "the 503 must be retried: " + run);
        assertEquals("9", run.field("uploadedBytes"));
        assertEquals("200", run.field("httpStatus"));
    }

    @Test
    @DisplayName("upload: a server that stays down is a third-party failure after the second attempt")
    void refusesAnUploadThatKeepsFailing() throws Exception {
        AtomResult run = atom.file("build/app.apk", "apk")
                .input("action", "upload")
                .input("username", "bsuser")
                .input("appPath", "build/app.apk")
                .input("apiUrl", api + "/down")
                .run();

        run.assertRefused();
        assertEquals("41008", run.field("errorCode"));
        assertTrue(run.field("message").contains("after 2 attempt(s)"), run.field("message"));
        assertTrue(run.field("message").contains("HTTP 503"), run.field("message"));
        assertTrue(run.field("message").contains("…"), "a long error body must be truncated: " + run.field("message"));
    }

    @Test
    @DisplayName("upload: a 2xx without an app_url is a failure, not an empty app id")
    void refusesAnAnswerWithoutAnAppUrl() throws Exception {
        AtomResult run = atom.file("build/app.apk", "apk")
                .input("action", "upload")
                .input("username", "bsuser")
                .input("appPath", "build/app.apk")
                .input("apiUrl", api + "/empty")
                .run();

        run.assertRefused();
        assertEquals("41009", run.field("errorCode"));
        assertTrue(run.field("message").contains("without an app_url"), run.field("message"));
    }

    @Test
    @DisplayName("upload: a 401 is the user's key, not BrowserStack being down")
    void refusesAnUnauthorizedUploadAsAUserError() throws Exception {
        AtomResult run = atom.file("build/app.apk", "apk")
                .input("action", "upload")
                .input("username", "bsuser")
                .input("appPath", "build/app.apk")
                .input("apiUrl", api + "/reject401")
                .run();

        run.assertRefused();
        assertEquals("41007", run.field("errorCode"));
        assertTrue(run.field("message").contains("HTTP 401"), run.field("message"));
        assertTrue(run.field("message").contains("1 attempt(s)"), "a 401 must not be retried: " + run.field("message"));
    }

    @Test
    @DisplayName("upload: a dead socket is retried like a 5xx and reported with the socket error")
    void refusesAnUploadToADeadSocket() throws Exception {
        AtomResult run = atom.file("build/app.apk", "apk")
                .input("action", "upload")
                .input("username", "bsuser")
                .input("appPath", "build/app.apk")
                .input("apiUrl", "http://127.0.0.1:1")
                .run();

        run.assertRefused();
        assertEquals("41008", run.field("errorCode"));
        assertTrue(run.field("message").contains("ConnectException") || run.field("message").contains("failed"),
                "the socket error must be reported: " + run.field("message"));
    }

    @Test
    @DisplayName("report: an existing build with no sessions is Failed but the step passes, and the file is written")
    void reportsABuildWithoutSessions() throws Exception {
        AtomResult run = atom.input("action", "report")
                .input("username", "bsuser")
                .input("buildName", "empty")
                .input("apiUrl", api + "/")
                .input("outputDir", "")
                .run();

        run.assertOk();
        assertEquals("Failed", run.field("reportStatus"));
        assertEquals("0", run.field("totalSessions"));
        assertEquals("", run.field("buildBrowserUrl"), "there is no session, so no build page");
        assertTrue(run.exists("browserstack-artifacts/buildResults.json"), "the default outputDir must be used");
        assertEquals("[]", run.file("browserstack-artifacts/buildResults.json").strip());
    }

    @Test
    @DisplayName("report: BUILD_TAG is the fallback when buildName is empty")
    void fallsBackToTheBuildTag() throws Exception {
        AtomResult run = atom.input("action", "report")
                .input("username", "bsuser")
                .input("buildName", "")
                .input("BUILD_TAG", "empty")
                .input("apiUrl", api)
                .input("outputDir", "reports/bs")
                .run();

        run.assertOk();
        assertEquals("empty", run.field("buildName"));
        assertEquals("reports/bs/buildResults.json", run.field("reportFile"));
        assertTrue(run.exists("reports/bs/buildResults.json"));
    }

    @Test
    @DisplayName("report: without buildName and without BUILD_TAG there is nothing to look up")
    void refusesWithoutABuildName() throws Exception {
        AtomResult run = atom.withoutEnvironment()
                .input("action", "report")
                .input("username", "bsuser")
                .input("buildName", "")
                .input("apiUrl", api)
                .run();

        run.assertRefused();
        assertEquals("41010", run.field("errorCode"));
    }

    @Test
    @DisplayName("report: two builds with the same name is an error, not the first one silently")
    void refusesSeveralBuildsWithTheSameName() throws Exception {
        AtomResult run = report("multi").run();

        run.assertRefused();
        assertEquals("41011", run.field("errorCode"));
        assertTrue(run.field("message").contains("matched 2 builds"), run.field("message"));
    }

    @Test
    @DisplayName("report: a build answer without a hashed_id cannot be turned into a session list")
    void refusesABuildWithoutAHash() throws Exception {
        AtomResult run = report("noid").run();

        run.assertRefused();
        assertEquals("41012", run.field("errorCode"));
        assertTrue(run.field("message").contains("no hashed_id"), run.field("message"));
    }

    @Test
    @DisplayName("report: the builds endpoint's status decides the error type and code")
    void reportsTheBuildsEndpointFailure() throws Exception {
        AtomResult forbidden = report("forbidden").run();
        forbidden.assertRefused();
        assertEquals("41016", forbidden.field("errorCode"));
        assertTrue(forbidden.field("message").contains("cannot list builds for 'forbidden'"),
                forbidden.field("message"));

        AtomResult boom = report("boom").run();
        boom.assertRefused();
        assertEquals("41016", boom.field("errorCode"));
        assertTrue(boom.field("message").contains("HTTP 500"), boom.field("message"));
    }

    @Test
    @DisplayName("report: a failing sessions endpoint is reported with the build id")
    void reportsASessionsEndpointFailure() throws Exception {
        AtomResult run = report("sessions-boom").run();

        run.assertRefused();
        assertEquals("41013", run.field("errorCode"));
        assertTrue(run.field("message").contains("cannot list sessions of build boom"), run.field("message"));
    }

    @Test
    @DisplayName("report: a builds payload that is not JSON, or not a list, is 'no build found' instead of an exception")
    void refusesPayloadsThatAreNotLists() throws Exception {
        AtomResult garbage = report("garbage").run();
        garbage.assertRefused();
        assertEquals("41011", garbage.field("errorCode"));
        assertTrue(garbage.field("message").contains("no BrowserStack build named 'garbage'"),
                garbage.field("message"));

        AtomResult notList = report("notlist").run();
        notList.assertRefused();
        assertEquals("41011", notList.field("errorCode"));

        AtomResult strings = report("strings").run();
        strings.assertRefused();
        assertEquals("41011", strings.field("errorCode"));
    }

    @Test
    @DisplayName("report: a sessions payload that is not JSON is an empty session list, not a crash")
    void survivesASessionsPayloadThatIsNotJson() throws Exception {
        AtomResult garbage = report("garbage-sessions").input("outputDir", "s1").run();

        garbage.assertOk();
        assertEquals("0", garbage.field("totalSessions"));
        assertEquals("Failed", garbage.field("reportStatus"));
    }

    @Test
    @DisplayName("report: rows are sorted by mark then newest first, ties by name, and the plugin's shapes are kept")
    void writesThePluginRows() throws Exception {
        AtomResult run = report("direct").input("outputDir", "rows").run();

        run.assertOk();
        assertEquals("4", run.field("totalSessions"));
        assertEquals("1", run.field("totalErrors"));
        assertTrue(run.field("buildDuration").contains("01h 02m 05s"),
                "3725s must read like the plugin: " + run.field("buildDuration"));
        assertEquals("", run.field("buildBrowserUrl"), "the first session has no browser_url");

        Object parsed = Json.parse(run.file("rows/buildResults.json"));
        List<?> rows = (List<?>) parsed;
        assertEquals(4, rows.size());
        Map<?, ?> first = (Map<?, ?>) rows.get(0);
        assertEquals("errored", first.get("name"), "a user mark sorts before 'unmarked': " + rows);
        assertEquals("passed", first.get("userMarked"));
        assertEquals("1d 01h 00m 00s", first.get("duration"), "90000s must carry days: " + first);
        assertEquals("01 Jan 1970, 00:00 UTC", first.get("createdAtReadable"), "a bad date is the epoch: " + first);
        assertEquals("1970-01-01T00:00:00.000Z", first.get("createdAt"));
        assertEquals("", first.get("buildUrl"));

        Map<?, ?> second = (Map<?, ?>) rows.get(1);
        assertEquals("running one", second.get("name"), "newest first inside a mark: " + rows);
        assertEquals("unmarked", second.get("userMarked"));
        assertEquals("-", second.get("duration"), "a running session has no duration: " + second);
        assertTrue(String.valueOf(second.get("url")).endsWith("&source=jenkins_plugin"),
                "the plugin's source suffix is part of the contract: " + second);

        Map<?, ?> third = (Map<?, ?>) rows.get(2);
        assertEquals("alpha", third.get("name"), "a tie on the date falls back to the name: " + rows);
        assertEquals("01m 05s", third.get("duration"));
        assertEquals("Pixel 7", third.get("browser"), "a device name wins over the browser: " + third);
        assertEquals("android 13", third.get("os"));

        Map<?, ?> fourth = (Map<?, ?>) rows.get(3);
        assertEquals("zeta", fourth.get("name"));
        assertEquals("30s", fourth.get("duration"));
        assertEquals("chrome", fourth.get("browser"));
    }

    @Test
    @DisplayName("report: an apiUrl with a trailing slash is used as the base, not doubled")
    void acceptsAnApiUrlWithATrailingSlash() throws Exception {
        AtomResult run = atom.input("action", "report")
                .input("username", "bsuser")
                .input("buildName", "direct")
                .input("apiUrl", api + "/")
                .input("outputDir", "slash")
                .run();

        run.assertOk();
        assertTrue(run.exists("slash/buildResults.json"));
        assertTrue(run.console().contains("GET " + api + "/builds.json"),
                "the request must not go to a doubled slash:\n" + run.console());
    }
}
