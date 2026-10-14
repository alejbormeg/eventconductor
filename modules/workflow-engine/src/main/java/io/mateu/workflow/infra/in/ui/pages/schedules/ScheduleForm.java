package io.mateu.workflow.infra.in.ui.pages.schedules;

import io.mateu.uidl.annotations.EditableOnlyWhenCreating;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Help;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.MasterDetail;
import io.mateu.workflow.application.services.ScheduleService.ScheduleDraft;
import io.mateu.workflow.application.services.ScheduleService.ScheduleView;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdOptionsSupplier;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The create and edit form of a schedule created here: its id (fixed once created), the workflow it
 * starts, when, and with which variables.
 *
 * <p>Fields only, like {@code CreateProcessForm}: the {@code Schedules} Crud draws Create/Save and does
 * the work in {@link Schedules#create} and {@link Schedules#save}, where every rule is checked and the
 * page lands on the schedule's detail, with its next starts.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@FormLayout(columns = 1)
public class ScheduleForm {

    @NotNull
    @EditableOnlyWhenCreating
    @Label("Id")
    @Help("The schedule's name: letters, digits, '.', '_' and '-'. It never changes, and it is how the"
            + " cron.yaml entry you may later commit to git takes this schedule over.")
    String id;

    @Lookup(search = WorkflowDefinitionIdOptionsSupplier.class, label = WorkflowDefinitionIdLabelSupplier.class)
    @NotNull
    @Label("Workflow")
    String workflowId;

    @NotNull
    @Label("Cron")
    @Help("Spring cron: six fields, seconds first — \"0 0 9 * * MON-FRI\" is 09:00 on weekdays,"
            + " \"0 */15 * * * *\" every 15 minutes. Macros such as @daily work too.")
    String cron;

    @Label("Zone")
    @Help("An IANA zone such as Europe/Madrid. Empty: the engine's workflow.time.zone.")
    String zone;

    @MasterDetail(minHeightWhenDetailVisible = "16rem;")
    @Label("Variables")
    List<Variable> variables;

    /** The form for editing {@code view}. */
    static ScheduleForm of(ScheduleView view) {
        var form = new ScheduleForm();
        form.id = view.id();
        form.workflowId = view.workflowId();
        form.cron = view.cron();
        form.zone = view.zone();
        form.variables = new ArrayList<>(view.variables().entrySet().stream()
                .map(e -> new Variable(e.getKey(), e.getValue()))
                .toList());
        return form;
    }

    /** What the form says, for the service. */
    ScheduleDraft draft() {
        var map = new LinkedHashMap<String, String>();
        if (variables != null) {
            variables.forEach(v -> map.put(v.name(), v.value()));
        }
        return new ScheduleDraft(id, workflowId, cron, zone, map);
    }
}
