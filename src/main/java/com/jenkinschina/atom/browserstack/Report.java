package com.jenkinschina.atom.browserstack;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code browserStackReportAut}: read the Automate / App Automate sessions of one build name and write the same
 * {@code buildResults.json} the plugin's report page reads.
 *
 * <p>The plugin's step did three things: {@code GET /builds.json?build=<name>}, {@code GET
 * /builds/<hashed id>/sessions.json}, then a build action with an HTML table. An atom cannot add a build page — the
 * runner has no entry point for one — so the third part becomes a file in the workspace plus a report link to the
 * BrowserStack build. Everything that was derived rather than fetched is derived the same way, because the file is the
 * contract now: field names, the {@code unmarked} marker, {@code "-"} for a running session, the {@code
 * &source=jenkins_plugin} suffix on every session url, and the sort order (user mark ascending, then newest first).
 *
 * <p>Two behaviour changes, both about failing loudly:
 * <ul>
 *   <li>{@code builds.json?build=<name>&limit=1} returning zero builds is a user error (41011). The plugin logged "No
 *       build found by name" and produced an empty report, so a typo in {@code BROWSERSTACK_BUILD_NAME} looked like a
 *       green build with no sessions.
 *   <li>an existing build with no sessions is <em>not</em> an error: {@code reportStatus=Failed}, the step passes.
 *       That is the plugin's behaviour and it is the right one — a build name can legitimately have no sessions yet.
 * </ul>
 */
final class Report {

    /** {@code AutomateClient.BASE_URL}. */
    static final String DEFAULT_BASE = "https://api.browserstack.com/automate";

    private Report() {}

    static AtomResult run(AtomContext context, String base, String username, String accessKey, int timeoutSeconds)
            throws Exception {
        String buildName = Tools.expand(context, context.getParam("buildName", "")).trim();
        if (buildName.isEmpty()) {
            buildName = Tools.expand(context, context.getParam("BUILD_TAG", "")).trim();
        }
        if (buildName.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 41010,
                    "buildName is required, and there is no BUILD_TAG to fall back to");
        }

        HttpClient client = Http.client(timeoutSeconds);
        String buildsUrl = base + "/builds.json?build=" + encode(buildName) + "&limit=1";
        context.log("GET " + buildsUrl);
        Http.Resp buildsAnswer = Http.get(client, buildsUrl, username, accessKey, timeoutSeconds);
        if (!buildsAnswer.ok()) {
            return AtomResult.error(Http.typeFor(buildsAnswer.status()), 41016,
                    "cannot list builds for '" + buildName + "': " + buildsAnswer.describe());
        }
        List<Map<String, Object>> builds = nodes(buildsAnswer.body(), "automation_build");
        if (builds.size() != 1) {
            // getBuildByName() in the client library requires exactly one match and throws BuildNotFound otherwise
            return AtomResult.error(ErrorType.USER, 41011,
                    builds.isEmpty()
                            ? "no BrowserStack build named '" + buildName + "'"
                            : "the build name '" + buildName + "' matched " + builds.size() + " builds");
        }
        Map<String, Object> build = builds.get(0);
        String buildId = Tools.text(build, "hashed_id", "");
        if (buildId.isEmpty()) {
            return AtomResult.error(ErrorType.THIRD_PART, 41012,
                    "the build answer has no hashed_id, so its sessions cannot be listed");
        }
        long buildDuration = Tools.number(build, "duration", 0);

        String sessionsUrl = base + "/builds/" + buildId + "/sessions.json";
        context.log("GET " + sessionsUrl);
        Http.Resp sessionsAnswer = Http.get(client, sessionsUrl, username, accessKey, timeoutSeconds);
        if (!sessionsAnswer.ok()) {
            return AtomResult.error(Http.typeFor(sessionsAnswer.status()), 41013,
                    "cannot list sessions of build " + buildId + ": " + sessionsAnswer.describe());
        }
        List<Map<String, Object>> sessions = nodes(sessionsAnswer.body(), "automation_session");

        // the plugin read the build page off the first session it received, before sorting
        String buildBrowserUrl = sessions.isEmpty() ? null
                : Tools.buildBrowserUrl(Tools.text(sessions.get(0), "browser_url", null));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> session : sessions) {
            rows.add(row(session, buildName, buildBrowserUrl, buildDuration));
        }
        rows.sort(ORDER);

        Map<String, Object> aggregation = aggregate(rows, buildDuration);
        String status = rows.isEmpty() ? "Failed" : "Success";
        context.log("sessions=" + rows.size() + " errors=" + aggregation.get("totalErrors")
                + " status=" + status);

        String relative = context.getParam("outputDir", "browserstack-artifacts").trim();
        if (relative.isEmpty()) {
            relative = "browserstack-artifacts";
        }
        Path directory = context.getWorkspace() == null ? Path.of(relative) : context.getWorkspace().resolve(relative);
        Files.createDirectories(directory);
        Path file = directory.resolve("buildResults.json");
        Files.writeString(file, Json.write(rows), StandardCharsets.UTF_8);
        context.log("wrote " + rows.size() + " session(s) to " + relative + "/buildResults.json");

        AtomResult result = AtomResult.success("BrowserStack report: " + status)
                .data("operation", "report")
                .data("reportStatus", status)
                .data("buildId", buildId)
                .data("buildName", buildName)
                .data("totalSessions", String.valueOf(rows.size()))
                .data("totalErrors", String.valueOf(aggregation.get("totalErrors")))
                .data("buildDuration", String.valueOf(aggregation.get("buildDuration")))
                .data("reportFile", relative + "/buildResults.json")
                .artifact("buildResults", relative + "/buildResults.json");
        if (buildBrowserUrl != null) {
            result.data("buildBrowserUrl", buildBrowserUrl)
                    .reportUrl("browserstackReport", "BrowserStack Test Report", buildBrowserUrl);
        }
        return result;
    }

    /** One session as the plugin's {@code convertSessionToJsonObject} wrote it (plus an ISO date, not a Date object). */
    static Map<String, Object> row(Map<String, Object> session, String buildName, String buildBrowserUrl,
                                   long buildDuration) {
        String id = Tools.text(session, "hashed_id", "");
        String name = Tools.text(session, "name", id);
        String browserStackStatus = Tools.text(session, "browserstack_status", "");
        String status = Tools.text(session, "status", "");
        String device = Tools.text(session, "device", "");
        String createdAt = Tools.text(session, "created_at", null);
        String publicUrl = Tools.text(session, "public_url", "");

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("buildName", buildName);
        row.put("buildUrl", buildBrowserUrl == null ? "" : buildBrowserUrl);
        row.put("buildDuration", String.valueOf(buildDuration));
        row.put("browser", device.isEmpty() ? Tools.text(session, "browser", "") : device);
        row.put("os", Tools.text(session, "os", "") + " " + Tools.text(session, "os_version", ""));
        row.put("status", browserStackStatus);
        row.put("userMarked", browserStackStatus.equals(status) ? "unmarked" : status);
        row.put("duration", "running".equals(status) ? "-" : Tools.duration(Tools.number(session, "duration", 0)));
        row.put("createdAt", Tools.isoSessionDate(createdAt));
        row.put("createdAtReadable", Tools.readableSessionDate(createdAt));
        row.put("url", publicUrl + "&source=jenkins_plugin");
        return row;
    }

    /**
     * {@code SessionsSortingComparator}: ascending by the user's mark, and newest first inside a mark. The plugin
     * compared the two marks and returned their difference when the dates matched — a comparator that is not
     * transitive; here ties fall back to the session name so the file is stable across runs.
     */
    static final Comparator<Map<String, Object>> ORDER = (one, two) -> {
        String first = String.valueOf(one.get("userMarked")).toLowerCase(Locale.ROOT);
        String second = String.valueOf(two.get("userMarked")).toLowerCase(Locale.ROOT);
        int byMark = first.compareTo(second);
        if (byMark != 0) {
            return byMark;
        }
        int byDate = Tools.parseSessionDate(String.valueOf(one.get("createdAt")))
                .compareTo(Tools.parseSessionDate(String.valueOf(two.get("createdAt"))));
        if (byDate != 0) {
            return -byDate;
        }
        return String.valueOf(one.get("name")).compareTo(String.valueOf(two.get("name")));
    };

    private static Map<String, Object> aggregate(List<Map<String, Object>> rows, long buildDuration) {
        int errors = 0;
        for (Map<String, Object> row : rows) {
            if ("error".equals(row.get("status")) || "failed".equals(row.get("userMarked"))) {
                errors++;
            }
        }
        Map<String, Object> aggregation = new LinkedHashMap<>();
        aggregation.put("totalSessions", String.valueOf(rows.size()));
        aggregation.put("totalErrors", String.valueOf(errors));
        aggregation.put("buildDuration", Tools.duration(buildDuration));
        return aggregation;
    }

    /** BuildNode/SessionNode: the payload is wrapped in one key, sometimes in a second spelling. */
    private static List<Map<String, Object>> nodes(String body, String wrapper) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object parsed;
        try {
            parsed = Json.parse(body == null ? "null" : body);
        } catch (RuntimeException e) {
            return result;
        }
        if (!(parsed instanceof List<?> list)) {
            return result;
        }
        for (Object element : list) {
            if (!(element instanceof Map<?, ?>)) {
                continue;
            }
            Object inner = ((Map<?, ?>) element).get(wrapper);
            if (inner == null) {
                inner = ((Map<?, ?>) element).get("build");
            }
            if (inner == null) {
                inner = element;
            }
            if (inner instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                result.add(copy);
            }
        }
        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
