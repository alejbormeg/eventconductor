package io.mateu.workflow.infra.in.ui.pages.schedules;

import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.application.services.ScheduleService.ScheduleState;
import io.mateu.workflow.application.services.ScheduleService.ScheduleView;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/** How the schedule pages write a schedule's parts: one place, so the listing, the detail and the definition tab agree. */
public final class ScheduleFormats {

    private static final DateTimeFormatter NEXT_FIRE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx '('VV')'");

    private ScheduleFormats() {
    }

    /** The state as a badge. */
    public static Status status(ScheduleState state) {
        return switch (state) {
            case ACTIVE -> new Status(StatusType.SUCCESS, "Active");
            case PAUSED -> new Status(StatusType.WARNING, "Paused");
            case WORKFLOW_INACTIVE -> new Status(StatusType.NONE, "Workflow inactive");
            case INVALID -> new Status(StatusType.DANGER, "Invalid");
        };
    }

    /** An upcoming start (ISO-8601 with zone, as the service gives it) in the zone it is read in. */
    public static String fire(String isoZoned) {
        return NEXT_FIRE.format(ZonedDateTime.parse(isoZoned));
    }

    /** The next start, or "—". */
    public static String nextFire(ScheduleView view) {
        return view.nextFires().isEmpty() ? "—" : fire(view.nextFires().get(0));
    }

    /** The zone it is read in, saying so when it is not its own. */
    public static String zone(ScheduleView view) {
        if (view.zone() != null) {
            return view.zone();
        }
        if (view.effectiveZone() == null) {
            return "—";
        }
        return view.effectiveZone() + (view.declaredIn().endsWith("(cronExpression)") ? " (JVM)" : " (default)");
    }

    /** name=value, in the order written; "—" for none. */
    public static String variables(ScheduleView view) {
        return view.variables().isEmpty() ? "—"
                : view.variables().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(", "));
    }

    /**
     * An id as a URL path segment: the unreserved characters ({@code A-Z a-z 0-9 - . _ ~}, which is all
     * a schedule id is made of) as they are, anything else percent-encoded.
     */
    public static String pathSegment(String id) {
        var out = new StringBuilder();
        for (byte b : id.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    /** Text made safe to put inside HTML. */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
