package io.mateu.workflow.infra.in.ui.pages.events;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.MainFilter;
import io.mateu.workflow.application.services.EventTriggerService.TriggerState;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdOptionsSupplier;

/** What the Events page can be narrowed by; the search box matches workflow, trigger, destination, type, filter and key. */
public record EventTriggerFilters(
        @MainFilter @Label("Workflow")
        @Lookup(search = WorkflowDefinitionIdOptionsSupplier.class, label = WorkflowDefinitionIdLabelSupplier.class)
        String workflowId,
        @MainFilter @Label("Status") TriggerState state) {
}
