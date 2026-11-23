package com.jenkinschina.atom.browserstack;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * {@code browserstackAppUploader}: upload a .apk/.ipa to App Automate and hand the resulting {@code bs://…} url to the
 * next step as {@code BROWSERSTACK_APP_ID}.
 *
 * <p>Wire shape copied from {@code automate-client-java 0.7} ({@code AppAutomateClient#uploadApp}) rather than from the
 * docs, because the plugin's users are the library's users:
 * <ul>
 *   <li>{@code POST <app-automate base>/upload}, basic auth {@code username:accessKey};
 *   <li>{@code multipart/form-data} with the literal boundary {@code __END_OF_PART__}, one part named {@code file}
 *       carrying the app's bytes and its canonical file name — a server that keys on the field name still finds it;
 *   <li>the answer must be JSON with a non-empty {@code app_url}, which is the value the SDK returns as the app id;
 *   <li>at most two attempts, retrying only on {@code 5xx}, on no status at all, or on an empty {@code app_url}
 *       (the library throws {@code statusCode 0} for that, and the plugin retried it too).
 * </ul>
 *
 * <p>One deviation, on purpose: the plugin logs {@code "App Id empty. ABORTING!!!"} and lets the block continue without
 * {@code BROWSERSTACK_APP_ID}, so the build fails later inside the tests with no hint about the upload. Here the upload
 * failure is the failure, with a code that says which part of it broke.
 */
final class Upload {

    /** {@code AppAutomateClient.BASE_URL}; {@code apiUrl} overrides it for a proxy or an on-prem appliance. */
    static final String DEFAULT_BASE = "https://api-cloud.browserstack.com/app-automate";

    /** The library's own boundary, unpicked on purpose: it is part of what the server sees today. */
    static final String BOUNDARY = "__END_OF_PART__";

    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 1000L;

    private Upload() {}

    static AtomResult run(AtomContext context, String base, String username, String accessKey, int timeoutSeconds)
            throws Exception {
        String appPath = Tools.expand(context, context.getParam("appPath", "")).trim();
        if (appPath.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 41004, "appPath is required: the path of the .apk or .ipa to upload");
        }
        Path file = resolve(context, appPath);
        if (file == null || !Files.isRegularFile(file)) {
            return AtomResult.error(ErrorType.USER, 41004, "no such file: " + appPath);
        }
        Path canonical = file.toRealPath();
        String fileName = canonical.getFileName().toString();
        // the library canonicalises first, so a symlink called app.apk pointing at app.zip is refused
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".apk") && !lower.endsWith(".ipa")) {
            return AtomResult.error(ErrorType.USER, 41005,
                    "BrowserStack accepts only .apk and .ipa, not '" + fileName + "'");
        }
        long size = Files.size(canonical);
        byte[] body = multipart(fileName, Files.readAllBytes(canonical));

        HttpClient client = Http.client(timeoutSeconds);
        String url = base + "/upload";
        Http.Resp last = null;
        int attempt = 0;
        for (attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            context.log("uploading " + fileName + " (" + size + " bytes) to " + url + ", attempt " + attempt
                    + " of " + MAX_ATTEMPTS);
            last = Http.postMultipart(client, url, body, BOUNDARY, username, accessKey, timeoutSeconds);
            String appUrl = last.ok() ? appUrl(last.body()) : null;
            if (appUrl != null) {
                context.log(fileName + " uploaded: " + appUrl);
                return AtomResult.success("uploaded " + fileName + " to App Automate")
                        .data("operation", "upload")
                        .data("appUrl", appUrl)
                        // plain, not sensitive: this is the value the next step reads as an env var
                        .data("BROWSERSTACK_APP_ID", appUrl)
                        .data("uploadedBytes", String.valueOf(size))
                        .data("uploadAttempts", String.valueOf(attempt))
                        .data("httpStatus", String.valueOf(last.status()));
            }
            // a 2xx with no app_url is the library's AppAutomateException(status 0), which the plugin retried
            boolean retryable = last.retryable() || last.ok();
            context.log("attempt " + attempt + " failed: " + (last.ok()
                    ? "the answer carries no app_url" : last.describe()));
            if (!retryable || attempt == MAX_ATTEMPTS) {
                break;
            }
            Thread.sleep(RETRY_DELAY_MS);
        }

        if (last != null && last.ok()) {
            return AtomResult.error(ErrorType.THIRD_PART, 41009,
                    "BrowserStack answered 2xx without an app_url: " + last.describe());
        }
        ErrorType type = last == null ? ErrorType.THIRD_PART
                : (last.retryable() ? ErrorType.THIRD_PART : Http.typeFor(last.status()));
        int code = last != null && last.retryable() ? 41008 : 41007;
        return AtomResult.error(type, code,
                "app upload failed after " + Math.max(attempt, 1) + " attempt(s): "
                        + (last == null ? "no answer" : last.describe()));
    }

    /** Absolute, or relative to the workspace, the way every other file input in this runner behaves. */
    private static Path resolve(AtomContext context, String appPath) {
        Path given = Path.of(appPath);
        if (given.isAbsolute()) {
            return given;
        }
        Path workspace = context.getWorkspace();
        return workspace == null ? given : workspace.resolve(appPath);
    }

    /** {@code app_url} from the upload answer, or {@code null} when the body is not that shape. */
    static String appUrl(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Object parsed = Json.parse(body);
            if (!(parsed instanceof Map<?, ?>)) {
                return null;
            }
            String value = Tools.text(parsed, "app_url", null);
            return value == null || value.isBlank() ? null : value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The exact part headers the library produced: one part, named {@code file}, {@code multipart/form-data} as the
     * part's own content type (odd, but it is what {@code FileContent("multipart/form-data", file)} emits today).
     */
    static byte[] multipart(String fileName, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 512);
        write(out, "--" + BOUNDARY + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n");
        write(out, "Content-Type: multipart/form-data\r\n\r\n");
        out.writeBytes(content);
        write(out, "\r\n--" + BOUNDARY + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
