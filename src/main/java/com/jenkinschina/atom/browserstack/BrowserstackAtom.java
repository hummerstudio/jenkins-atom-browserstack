package com.jenkinschina.atom.browserstack;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.util.List;
import java.util.Locale;

/**
 * The three things {@code browserstack-integration} does that survive outside the Jenkins JVM: export the BrowserStack
 * environment, upload an app to App Automate, and summarise a build's sessions.
 *
 * <p>What did <em>not</em> survive is not an omission, it is the shape of the plugin: the rest of it is Jenkins
 * extension points, not steps.
 * <ul>
 *   <li>{@code BrowserStackBuildWrapper} and {@code browserstack(...)} own a <em>block</em>: they start the Local tunnel
 *       binary before the body and stop it in a tail-callback. An atom is one process that exits; there is no hook that
 *       runs after the following steps, so the tunnel cannot be started and later stopped. The workaround people
 *       actually use — start the binary yourself with {@code BROWSERSTACK_LOCAL=…} — still works, and {@code env} gives
 *       it the identifier.
 *   <li>the publishers ({@code BrowserStackReportPublisher}, {@code BrowserStackCypressReportPublisher},
 *       {@code BrowserStackTestReportPublisher}) and the {@code AutomateTestDataPublisher} render build pages and attach
 *       {@code TestResult}s; the runner has neither.
 *   <li>the quality dashboard / observability half talks to {@code quality-engineering-insights.browserstack.com} on
 *       behalf of a Jenkins instance, keyed to a job's history. Out of scope for a step.
 * </ul>
 *
 * <p>{@code action} is the only selector, which keeps the form honest: {@code appPath} is not offered to someone
 * exporting env vars, and {@code buildName} is not offered to someone uploading an app.
 */
public final class BrowserstackAtom implements AtomTask {

    /** The actions this atom knows, in the order the form offers them. */
    static final List<String> ACTIONS = List.of("env", "upload", "report");

    private static final List<String> PRODUCTS = List.of("automate", "app-automate");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String action = context.getParam("action", "env").trim().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            return AtomResult.error(ErrorType.USER, 41001,
                    "action must be one of " + String.join(", ", ACTIONS) + ", not '" + action + "'");
        }

        String username = Tools.expand(context, context.getParam("username", "")).trim();
        if (username.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 41002, "username is required (the BrowserStack account name)");
        }
        String accessKey = accessKey(context);
        if (accessKey.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 41003, "accessKey is required (Account > Settings > Access Key)");
        }
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 30), 300));

        return switch (action) {
            case "env" -> Env.run(context, username, accessKey);
            // the plugin's uploader always went to App Automate, whatever the credentials were
            case "upload" -> Upload.run(context, base(context, "app-automate"), username, accessKey, timeoutSeconds);
            case "report" -> {
                String product = product(context);
                if (product == null) {
                    yield AtomResult.error(ErrorType.USER, 41020,
                            "product must be one of " + String.join(", ", PRODUCTS) + ", not '"
                                    + context.getParam("product", "") + "'");
                }
                yield Report.run(context, base(context, product), username, accessKey, timeoutSeconds);
            }
            default -> AtomResult.error(ErrorType.USER, 41001,
                    "action must be one of " + String.join(", ", ACTIONS) + ", not '" + action + "'");
        };
    }

    /**
     * The access key is a declared secret, so the runner hands it over in {@code .secrets.json} rather than in
     * {@code input.json}; {@code input.json} is still accepted for a hand-run atom and for the smoke suite.
     */
    static String accessKey(AtomContext context) {
        String value = context.getSecret("accessKey");
        if (value == null || value.isBlank()) {
            value = context.getParam("accessKey", "");
        }
        return value == null ? "" : value.trim();
    }

    /** {@code automate} unless asked otherwise. */
    static String product(AtomContext context) {
        String product = context.getParam("product", "automate").trim().toLowerCase(Locale.ROOT);
        if (product.isEmpty()) {
            return "automate";
        }
        return PRODUCTS.contains(product) ? product : null;
    }

    /**
     * {@code apiUrl} wins over the product's default: it is the only way to point the atom at a proxy, a recording
     * stand-in in a smoke run, or the {@code api-eu-only} hosts the plugin reached through the GRR region setting.
     */
    static String base(AtomContext context, String product) {
        String override = Tools.expand(context, context.getParam("apiUrl", "")).trim();
        if (!override.isEmpty()) {
            return override.endsWith("/") ? override.substring(0, override.length() - 1) : override;
        }
        return "app-automate".equals(product) ? Upload.DEFAULT_BASE : Report.DEFAULT_BASE;
    }
}
