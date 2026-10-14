package io.mateu.workflow.infra.in.ui.pages.schedules;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.MainFilter;
import io.mateu.workflow.application.services.ScheduleService.ScheduleState;
import io.mateu.workflow.domain.aggregates.ScheduleOrigin;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdOptionsSupplier;

/**
 * What the Schedules listing can be narrowed by. The search box matches the id, workflow, cron,
 * source and variables.
 *
 * @param workflowId which workflow they start
 * @param origin     where they are declared: DEFINITION, CRON_FILE or API (created here)
 * @param state      whether they fire
 */
public record ScheduleFilters(
        @MainFilter @Label("Workflow")
        @Lookup(search = WorkflowDefinitionIdOptionsSupplier.class, label = WorkflowDefinitionIdLabelSupplier.class)
        String workflowId,
        @MainFilter @Label("Declared in") ScheduleOrigin origin,
        @MainFilter @Label("Status") ScheduleState state) {
}
