package io.mateu.workflow.infra.in.ui.pages.schedules;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.application.services.ScheduleService.ScheduleView;

/**
 * One row of the Schedules listing: which schedule, what it starts and when, where it is declared and
 * whether it fires.
 *
 * @param description the cron in words when it is a common shape, "—" otherwise
 * @param source      "Definition", "cron.yaml (git)", "UI/API"… — only UI/API ones are edited here
 */
public record ScheduleListRow(
        @Label("Id") String id,
        @Label("Workflow") String workflow,
        @Label("Cron") String cron,
        @Label("When") String description,
        @Label("Zone") String zone,
        @Label("Next start") String nextFire,
        @Label("Source") String source,
        @Label("Status") Status status
) implements Identifiable {

    public static ScheduleListRow of(ScheduleView view) {
        return new ScheduleListRow(view.id(), view.workflowId(), view.cron(),
                view.description() == null ? "—" : view.description(), ScheduleFormats.zone(view),
                ScheduleFormats.nextFire(view), view.sourceLabel(), ScheduleFormats.status(view.state()));
    }
}
