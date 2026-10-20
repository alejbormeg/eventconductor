package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.TestRunMutex;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ProcessSnapshotReader;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.usecases.process.create.CreateProcessCommand;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import io.mateu.workflow.security.FlowAuthorizationService;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Starts one manual instance from a validated document. Never publishes or edits a definition. */
public final class WorkflowTestRunService {
    private final ProcessRepository processes;
    private final WorkflowDefinitionRepository definitions;
    private final CreateProcessUseCase creator;
    private final TestRunMutex locks;
    private final ProcessSnapshotReader snapshots;
    private final boolean flowAuthorization;

    public WorkflowTestRunService(ProcessRepository processes, WorkflowDefinitionRepository definitions,
                                 CreateProcessUseCase creator, TestRunMutex locks,
                                 ProcessSnapshotReader snapshots, boolean flowAuthorization) {
        this.processes = processes;
        this.definitions = definitions;
        this.creator = creator;
        this.locks = locks;
        this.snapshots = snapshots;
        this.flowAuthorization = flowAuthorization;
    }

    public record Started(Process process, boolean created) {}

    public Started start(String runId, WorkflowDefinition definition, List<Variable> variables,
                         String requestDigest, AuthorizationContext caller) {
        if (runId == null || !UUID.fromString(runId).toString().equals(runId))
            throw new IllegalArgumentException("runId must be a canonical UUID.");
        InputLimits.checkIdentifier(definition.id(), "definition.id");
        InputLimits.checkNamedValues(variables, Variable::name, Variable::value, "test run");
        definition.checkInvariants();
        authorize(definition, caller);
        definitions.findById(definition.id()).ifPresent(published -> authorize(published, caller));
        var outcome = new AtomicReference<Started>();
        locks.run(runId, () -> {
            var previous = processes.findById(runId).orElse(null);
            if (previous != null) {
                if (!previous.isTestRun() || !Objects.equals(requestDigest, previous.getTestRunRequestDigest()))
                    throw new Conflict("runId already belongs to a different request. Use a new runId.");
                authorize(snapshots.read(previous.getWorkflowDefinitionJson()), caller);
                outcome.set(new Started(previous, false));
                return;
            }
            if (!definition.status().accceptsNewInstances())
                throw new Conflict("The document does not accept new instances: " + definition.status());
            creator.handle(new CreateProcessCommand(runId, definition.id(), "ide-test:" + runId,
                    variables, null, caller, null, null, definition, true, requestDigest));
            var created = processes.findById(runId).orElseThrow(() -> new Conflict("The test run could not be created."));
            outcome.set(new Started(created, true));
        });
        return outcome.get();
    }

    private void authorize(WorkflowDefinition definition, AuthorizationContext caller) {
        if (!flowAuthorization) return;
        var decision = FlowAuthorizationService.authorize(caller, definition.requiredScopes(), definition.requiredRoles());
        if (!decision.allowed()) throw FlowAuthorizationDeniedException.of("run workflow test",
                caller == null ? null : caller.subject(), decision.missingScopes(), decision.missingRoles());
    }

    public static final class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }
}
