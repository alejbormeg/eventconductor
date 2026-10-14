package io.mateu.workflow.domain.services;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A cron expression in words, for the shapes people actually write — "Every day at 09:00",
 * "MON-FRI at 08:30", "Every 15 minutes". Anything else has no description rather than a wrong one:
 * the expression itself is always shown next to it. Pure.
 */
public final class CronDescriptions {

    private static final Pattern NUMBER = Pattern.compile("\\d{1,2}");
    private static final Pattern EVERY = Pattern.compile("(?:\\*|0)/(\\d{1,2})");
    private static final Pattern DAYS = Pattern.compile("[A-Za-z]{3}(?:[-,][A-Za-z]{3})*");
    private static final Map<String, String> MACROS = Map.of(
            "@yearly", "Every year, on January 1st at 00:00",
            "@annually", "Every year, on January 1st at 00:00",
            "@monthly", "Every month, on the 1st at 00:00",
            "@weekly", "Every week, on Sunday at 00:00",
            "@daily", "Every day at 00:00",
            "@midnight", "Every day at 00:00",
            "@hourly", "Every hour, on the hour");

    private CronDescriptions() {
    }

    /** The expression in words, or empty when it is not one of the shapes described. */
    public static Optional<String> describe(String cron) {
        if (cron == null || cron.isBlank()) {
            return Optional.empty();
        }
        var trimmed = cron.trim();
        if (trimmed.startsWith("@")) {
            return Optional.ofNullable(MACROS.get(trimmed.toLowerCase(Locale.ROOT)));
        }
        var f = trimmed.split("\\s+");
        if (f.length != 6) {
            return Optional.empty();
        }
        String s = f[0], m = f[1], h = f[2], dom = f[3], mon = f[4], dow = f[5];
        boolean anyDom = any(dom), anyMon = any(mon), anyDow = any(dow);
        if (!anyMon) {
            return Optional.empty();
        }
        if (anyDom && anyDow) {
            if (all(s, m, h)) return Optional.of("Every second");
            if (any(m) && any(h) && EVERY.matcher(s).matches()) return Optional.of("Every " + every(s) + " seconds");
            if (is(s) && any(m) && any(h)) return Optional.of("Every minute");
            if (is(s) && EVERY.matcher(m).matches() && any(h)) return Optional.of("Every " + every(m) + " minutes");
            if (is(s) && is(m) && any(h)) return Optional.of("Every hour at minute " + Integer.parseInt(m));
            if (is(s) && is(m) && EVERY.matcher(h).matches()) {
                return Optional.of("Every " + every(h) + " hours at minute " + Integer.parseInt(m));
            }
        }
        if (!(is(s) && is(m) && is(h))) {
            return Optional.empty();
        }
        var at = " at " + time(s, m, h);
        if (anyDom && anyDow) return Optional.of("Every day" + at);
        if (anyDom && DAYS.matcher(dow).matches()) return Optional.of(dow.toUpperCase(Locale.ROOT) + at);
        if (is(dom) && anyDow) return Optional.of("Day " + Integer.parseInt(dom) + " of every month" + at);
        if ("L".equalsIgnoreCase(dom) && anyDow) return Optional.of("Last day of every month" + at);
        return Optional.empty();
    }

    private static boolean any(String field) {
        return "*".equals(field) || "?".equals(field);
    }

    private static boolean all(String... fields) {
        for (var field : fields) {
            if (!"*".equals(field)) return false;
        }
        return true;
    }

    private static boolean is(String field) {
        return NUMBER.matcher(field).matches();
    }

    private static int every(String field) {
        var matcher = EVERY.matcher(field);
        matcher.matches();
        return Integer.parseInt(matcher.group(1));
    }

    private static String time(String s, String m, String h) {
        var hm = String.format("%02d:%02d", Integer.parseInt(h), Integer.parseInt(m));
        return Integer.parseInt(s) == 0 ? hm : hm + String.format(":%02d", Integer.parseInt(s));
    }
}
