package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The browserstack atom, started the way the runner starts it, against the fake BrowserStack in {@code tools/}.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made — the exported environment with the key masked, an upload
 * that really is multipart with the app's bytes, a build report that counts infrastructure errors, and an unknown build
 * failing by code instead of producing an empty green report — written as JUnit.
 */
class BrowserstackIT {

    private static Process server;
    private static Path serverLog;
    private static String api;

    private Atom atom;

    @BeforeAll
    static void startFakeBrowserStack() throws Exception {
        Path module = module();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        serverLog = Files.createTempFile("browserstack-fake-", ".log");
        server = new ProcessBuilder("python3", module.resolve("tools/fake_browserstack.py").toString(),
                Integer.toString(port), serverLog.toString())
                .redirectErrorStream(true)
                .redirectOutput(serverLog.toFile())
                .start();
        api = "http://127.0.0.1:" + port;
        waitForPort(port);
    }

    @AfterAll
    static void stopFakeBrowserStack() throws Exception {
        if (server != null) {
            server.destroy();
            server.waitFor();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of().file(".secrets.json", "{\"accessKey\": \"bskey-123\"}");
    }

    @Test
    @DisplayName("env exports the plugin's variables, with -jenkins on the user and the key masked in the log")
    void exportsTheEnvironment() throws Exception {
        AtomResult run = atom.input("action", "env")
                .input("username", "bsuser")
                .input("BUILD_TAG", "jenkins-acme-42")
                .run();

        run.assertOk();
        assertEquals("bsuser-jenkins", run.field("BROWSERSTACK_USERNAME"), "the plugin's CI suffix:\n" + run);
        assertEquals("jenkins-acme-42", run.field("BROWSERSTACK_BUILD_NAME"));
        assertEquals("true", run.field("BROWSERSTACK_PLUGIN_INVOKED"));
        assertTrue(run.console().contains("BROWSERSTACK_ACCESS_KEY=*********"),
                "the key must be logged masked:\n" + run.console());
    }

    @Test
    @DisplayName("upload sends the app as multipart and reports the bs:// app url")
    void uploadsAnApp() throws Exception {
        Path app = Files.createDirectories(module().resolve("target/it")).resolve("app.apk");
        Files.write(app, "A".repeat(4096).getBytes(StandardCharsets.UTF_8));

        AtomResult run = atom.input("action", "upload")
                .input("username", "bsuser")
                .input("appPath", app.toString())
                .input("apiUrl", api)
                .run();

        run.assertOk();
        String appUrl = run.field("appUrl");
        assertTrue(appUrl.startsWith("bs://"), "the upload should return a bs:// url, not '" + appUrl + "':\n" + run);
        assertEquals(appUrl, run.field("BROWSERSTACK_APP_ID"));
    }

    @Test
    @DisplayName("report counts six sessions, two of them errors, and writes the report file")
    void reportsABuild() throws Exception {
        AtomResult run = atom.input("action", "report")
                .input("username", "bsuser")
                .input("buildName", "bs-build-ok")
                .input("apiUrl", api)
                .run();

        run.assertOk();
        assertEquals("Success", run.field("reportStatus"), "report:\n" + run);
        assertEquals("6", run.field("totalSessions"));
        assertEquals("2", run.field("totalErrors"));
        assertTrue(run.exists("browserstack-artifacts/buildResults.json"),
                "the report file is missing:\n" + run.console());
        assertTrue(run.file("browserstack-artifacts/buildResults.json").length() > 0, "the report file is empty");
    }

    @Test
    @DisplayName("a build name nobody owns fails with the plugin's error code, not with an empty green report")
    void refusesAnUnknownBuild() throws Exception {
        AtomResult run = atom.input("action", "report")
                .input("username", "bsuser")
                .input("buildName", "typo-build")
                .input("apiUrl", api)
                .run();

        run.assertRefused();
        assertEquals("41011", run.field("errorCode"), "an unknown build is 41011:\n" + run);
    }

    private static Path module() {
        return Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));
    }

    private static void waitForPort(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("the fake BrowserStack did not start: " + Files.readString(serverLog));
    }
}
