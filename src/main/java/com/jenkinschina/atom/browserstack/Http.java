package com.jenkinschina.atom.browserstack;

import com.jenkinschina.atom.sdk.ErrorType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * The atom's whole network surface: one JDK client, GET and a multipart POST, basic auth, and a response that knows
 * whether it arrived at all.
 *
 * <p>Status {@code 0} is the plugin's own convention for "no HTTP status" — {@code AppUploaderHelper} retries on
 * {@code statusCode >= 500 || statusCode == 0}, so a refused connection and a 503 are the same kind of event here too.
 */
final class Http {

    /** Tells a BrowserStack account owner which tool made the call. The plugin sent no user agent at all. */
    static final String USER_AGENT = "jenkins-atom-browserstack";

    private Http() {}

    static HttpClient client(int timeoutSeconds) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
    }

    static Resp get(HttpClient client, String url, String username, String accessKey, int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET();
        authorize(builder, username, accessKey);
        return send(client, builder.build());
    }

    static Resp postMultipart(HttpClient client, String url, byte[] body, String boundary, String username,
                              String accessKey, int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        authorize(builder, username, accessKey);
        return send(client, builder.build());
    }

    /** {@code BrowserStackClient}: {@code Basic base64(username + ":" + accessKey)}. */
    private static void authorize(HttpRequest.Builder builder, String username, String accessKey) {
        String pair = username + ":" + accessKey;
        builder.header("Authorization",
                "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
    }

    private static Resp send(HttpClient client, HttpRequest request) {
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Resp(response.statusCode(), response.body(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resp(0, null, "interrupted: " + e.getMessage());
        } catch (Exception e) {
            // a refused connection, an unresolvable host, a proxy that answered garbage: no status, one message
            return new Resp(0, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 4xx is the user's problem (wrong key, no such build); 5xx or a dead socket is BrowserStack's.
     */
    static ErrorType typeFor(int status) {
        return status >= 400 && status < 500 ? ErrorType.USER : ErrorType.THIRD_PART;
    }

    record Resp(int status, String body, String error) {

        boolean ok() {
            return status >= 200 && status < 300;
        }

        /** Retryable the way the plugin retried: a server-side failure or no answer at all. */
        boolean retryable() {
            return status == 0 || status >= 500;
        }

        String describe() {
            if (error != null) {
                return error;
            }
            String body = this.body == null ? "" : this.body.strip();
            return "HTTP " + status + (body.isEmpty() ? "" : ": " + (body.length() > 300 ? body.substring(0, 300) + "…" : body));
        }
    }
}
