package com.jenkinschina.atom.browserstack;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;

/**
 * The half of {@code BrowserStackBuildWrapper} that does not need a tunnel: the variables the plugin exports into the
 * build so the test process picks up the credentials and the build name without any test code reading a Jenkinsfile.
 *
 * <p>Output keys are the variable names verbatim, because the runner exports string outputs under their own key. That
 * is what makes this an atom at all: the plugin owned a block ({@code browserstack(credentialsId) { ... }}) and could
 * set env vars for the body; an atom step returns, so the runner's environment contributor sets them for every later
 * step instead. Same effect on the test process, one step instead of a block.
 *
 * <p>Two names differ from the obvious, and both are the plugin's:
 * <ul>
 *   <li>{@code BROWSERSTACK_USERNAME} is {@code <username>-jenkins}, not the bare username. BrowserStack uses the
 *       suffix to attribute sessions to the CI integration; dropping it changes what the account's own dashboard sees.
 *   <li>Both {@code BROWSERSTACK_ACCESSKEY} and {@code BROWSERSTACK_ACCESS_KEY} are set, and both {@code
 *       BROWSERSTACK_USER}/{@code BROWSERSTACK_USERNAME}, because different BrowserStack SDK versions read different
 *       spellings.
 * </ul>
 *
 * <p>Nothing here talks to BrowserStack: the plugin never validated the key at this point either, so the action is a
 * pure function of its inputs and can be checked without a server.
 */
final class Env {

    private Env() {}

    static AtomResult run(AtomContext context, String username, String accessKey) {
        String buildTag = Tools.expand(context, context.getParam("buildTag", "")).trim();
        if (buildTag.isEmpty()) {
            buildTag = Tools.expand(context, context.getParam("BUILD_TAG", "")).trim();
        }

        String user = username + "-jenkins";
        AtomResult result = AtomResult.success("exported the BrowserStack environment")
                .data("operation", "env")
                .data("BROWSERSTACK_USER", user)
                .data("BROWSERSTACK_USERNAME", user)
                // not isSensitive: the runner keeps sensitive outputs off the environment, and the whole point is that
                // the next step sees this one. The console prints the mask, not the key.
                .data("BROWSERSTACK_ACCESSKEY", accessKey)
                .data("BROWSERSTACK_ACCESS_KEY", accessKey);

        context.log("BROWSERSTACK_USERNAME=" + user);
        context.log("BROWSERSTACK_ACCESS_KEY=" + Tools.mask(accessKey));

        if (!buildTag.isEmpty()) {
            result.data("BROWSERSTACK_BUILD", buildTag)
                    .data("BROWSERSTACK_BUILD_NAME", buildTag)
                    .data("BROWSERSTACK_PLUGIN_INVOKED", "true");
            context.log("BROWSERSTACK_BUILD_NAME=" + buildTag);
        } else {
            context.log("no BUILD_TAG in this build: BROWSERSTACK_BUILD_NAME is left unset, as the plugin did");
        }

        String localIdentifier = Tools.expand(context, context.getParam("localIdentifier", "")).trim();
        if (context.getBooleanParam("local", false) || !localIdentifier.isEmpty()) {
            result.data("BROWSERSTACK_LOCAL", "true");
            context.log("BROWSERSTACK_LOCAL=true");
        }
        if (!localIdentifier.isEmpty()) {
            result.data("BROWSERSTACK_LOCAL_IDENTIFIER", localIdentifier);
            context.log("BROWSERSTACK_LOCAL_IDENTIFIER=" + localIdentifier);
        }
        String rerun = Tools.expand(context, context.getParam("rerun", "")).trim();
        if (!rerun.isEmpty()) {
            result.data("BROWSERSTACK_RERUN", rerun);
        }
        String rerunTests = Tools.expand(context, context.getParam("rerunTests", "")).trim();
        if (!rerunTests.isEmpty()) {
            result.data("BROWSERSTACK_RERUN_TESTS", rerunTests);
        }
        return result;
    }
}
