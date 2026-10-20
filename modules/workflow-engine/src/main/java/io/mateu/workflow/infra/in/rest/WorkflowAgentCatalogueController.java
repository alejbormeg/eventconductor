package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.WorkflowAgentCatalogue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Editor projection. Host application authentication applies, as for the workflow editor itself. */
@RestController
public final class WorkflowAgentCatalogueController {
    private final ObjectProvider<WorkflowAgentCatalogue> catalogue;
    private final io.mateu.workflow.security.CallerResolver caller;
    public WorkflowAgentCatalogueController(ObjectProvider<WorkflowAgentCatalogue> catalogue,
            io.mateu.workflow.security.CallerResolver caller) { this.catalogue = catalogue; this.caller = caller; }
    @GetMapping("/api/workflow-agents")
    public List<WorkflowAgentCatalogue.Agent> list() {
        var actor = caller.current();
        if (actor == null || actor.subject() == null || actor.subject().isBlank())
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        var configured = catalogue.getIfAvailable();
        if (configured == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Workflow agent catalogue is not configured");
        return configured.list();
    }
}
