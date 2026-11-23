package com.jenkinschina.atom.browserstack;

import com.jenkinschina.atom.sdk.AtomContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The formatting and expansion rules the plugin's {@code Tools} class owns, copied rather than approximated.
 *
 * <p>These are not cosmetic: {@code durationToHumanReadable} and the {@code dd MMM yyyy, HH:mm} stamp go into
 * {@code buildResults.json}, which the Jenkins plugin's own report page reads. An atom that rendered "1m 5s" where the
 * plugin renders "01m 05s" would make the same build look different depending on which side produced the file.
 */
final class Tools {

    /** {@code Tools.BUILD_URL_PATTERN} — the build page is the session URL with the last two segments cut off. */
    static final Pattern BUILD_URL_PATTERN =
            Pattern.compile("(https?://[\\w-.]+/builds/\\w+)/sessions/\\w+");

    /**
     * {@code Tools.SESSION_DATE_FORMAT} was a {@link java.text.SimpleDateFormat} in the JVM's default zone; the plugin
     * then wrote " UTC" after the value regardless. BrowserStack emits {@code ...Z}, so the atom pins UTC and says so.
     */
    private static final DateTimeFormatter SESSION_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    /** {@code Tools.READABLE_DATE_FORMAT}. */
    private static final DateTimeFormatter READABLE_DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private Tools() {}

    /** {@code Tools.maskString}: one asterisk per character, so the length is not a secret either. */
    static String mask(String value) {
        return value == null ? "" : "*".repeat(value.length());
    }

    /** {@code Tools.durationToHumanReadable}, branch for branch — the plugin's report page parses this shape. */
    static String duration(long duration) {
        int seconds = (int) (duration % 60);
        duration /= 60;
        int minutes = (int) (duration % 60);
        duration /= 60;
        int hours = (int) (duration % 24);
        int days = (int) (duration / 24);

        if (days == 0) {
            if (hours == 0) {
                if (minutes == 0) {
                    return String.format(Locale.ENGLISH, "%02ds", seconds);
                }
                return String.format(Locale.ENGLISH, "%02dm %02ds", minutes, seconds);
            }
            return String.format(Locale.ENGLISH, "%02dh %02dm %02ds", hours, minutes, seconds);
        }
        return String.format(Locale.ENGLISH, "%dd %02dh %02dm %02ds", days, hours, minutes, seconds);
    }

    /** BrowserStack's {@code created_at} as an instant; the epoch when it is missing or in another shape. */
    static Instant parseSessionDate(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return LocalDateTime.parse(createdAt, SESSION_DATE).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    /** {@code <readable date> UTC}, the string the plugin's report page shows. */
    static String readableSessionDate(String createdAt) {
        return READABLE_DATE.format(parseSessionDate(createdAt)) + " UTC";
    }

    /** The ISO form the atom stores in {@code buildResults.json} (the plugin stored a {@code java.util.Date}). */
    static String isoSessionDate(String createdAt) {
        return SESSION_DATE.format(parseSessionDate(createdAt));
    }

    /** {@code buildName} out of a session's {@code browser_url}, or {@code null} when the URL is not that shape. */
    static String buildBrowserUrl(String sessionBrowserUrl) {
        if (sessionBrowserUrl == null) {
            return null;
        }
        Matcher matcher = BUILD_URL_PATTERN.matcher(sessionBrowserUrl);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * {@code ${NAME}} from the job's parameters or environment, so {@code task.json} defaults can say
     * {@code ${BUILD_TAG}} the way a Jenkinsfile does. An unresolved name is left alone rather than becoming "".
     */
    static String expand(AtomContext context, String value) {
        if (value == null || value.indexOf("${") < 0) {
            return value == null ? "" : value;
        }
        Matcher matcher = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}").matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = context.getParam(name);
            if (replacement == null) {
                replacement = System.getenv(name);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** A string from a parsed JSON object, trimmed, or {@code fallback} when it is missing/null/empty. */
    static String text(Object node, String key, String fallback) {
        if (!(node instanceof java.util.Map<?, ?> map)) {
            return fallback;
        }
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    /** A long from a JSON value that may be a number, a numeric string or absent. */
    static long number(Object node, String key, long fallback) {
        if (!(node instanceof java.util.Map<?, ?> map)) {
            return fallback;
        }
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
