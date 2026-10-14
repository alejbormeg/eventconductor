package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.application.services.ScheduleService.ScheduleView;
import io.mateu.workflow.infra.in.ui.pages.schedules.ScheduleFormats;

/**
 * One row in the "Schedules" list of a definition: a scheduled start, wherever it was declared — the
 * definition's {@code cronExpression}, an entry of its {@code schedules}, a {@code cron.yaml} entry or a
 * schedule created from the Schedules page targeting it.
 *
 * <p>The id is a link to the schedule's own page, where it is paused, resumed, run now and — when it
 * was created there — edited. Relative ({@code ../schedules/<id>}), because this row is drawn under
 * {@code <base>/workflow/definitions/<definition>} whichever console the definition was opened in.
 */
public record ScheduleRow(
        @Hidden String id,
        @Label("Id") @Stereotype(FieldStereotype.html) String link,
        @Label("Declared in") String declaredIn,
        @Label("Cron") String cron,
        @Label("Zone") String zone,
        @Label("Variables") String variables,
        @Label("Next start") String nextFire,
        @Label("Status") Status status
) implements Identifiable {

    /** A row for {@code view}. */
    public static ScheduleRow of(ScheduleView view) {
        var href = "../schedules/" + ScheduleFormats.pathSegment(view.id());
        return new ScheduleRow(view.id(),
                "<a href=\"" + ScheduleFormats.escape(href) + "\">" + ScheduleFormats.escape(view.id()) + "</a>",
                view.declaredIn(), view.cron(), ScheduleFormats.zone(view), ScheduleFormats.variables(view),
                ScheduleFormats.nextFire(view), ScheduleFormats.status(view.state()));
    }
}
