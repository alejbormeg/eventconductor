package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowDefinitionValidatorTest {

    private WorkflowDefinitionValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        validator = new WorkflowDefinitionValidator();
        validator.init();
    }

    private Step startStep() {
        return new Step("start", null, StepType.START, "Start", null, null, null, null, false, null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    /** An ACTION step hanging off the START entry point (the roots rule requires one). */
    private Step actionStep(String id) {
        return new Step(id, null, StepType.ACTION, "Step " + id, null, "start", null, null, false, "my-topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    @Test
    void validDefinitionPassesValidation() {
        var wd = new WorkflowDefinition("wd-1", "Test Workflow", 1, "desc",  false, 0, false, null, 0, List.of(startStep(), actionStep("s1")));

        assertThatNoException().isThrownBy(() -> validator.validate(wd));
    }

    @Test
    void validCronExpressionPassesValidation() {
        var wd = new WorkflowDefinition("wd-1", "Test Workflow", 1, "desc",  false, 0, false, "0 0 9 * * MON-FRI", 0, List.of(startStep(), actionStep("s1")));

        assertThatNoException().isThrownBy(() -> validator.validate(wd));
    }

    @Test
    void invalidCronExpressionIsRejected() {
        var wd = new WorkflowDefinition("wd-1", "Test Workflow", 1, "desc",  false, 0, false, "not a cron", 0, List.of(startStep(), actionStep("s1")));

        assertThatThrownBy(() -> validator.validate(wd))
                .isInstanceOf(WorkflowDefinitionValidator.WorkflowDefinitionValidationException.class)
                .hasMessageContaining("not a cron");
    }

    @Test
    void schedulesWithZoneAndVariablesPassValidationAlongsideCronExpression() {
        var wd = new WorkflowDefinition("wd-1", "Test Workflow", 1, "desc", false, 0, false, "0 0 9 * * MON-FRI", 0,
                List.of(startStep(), actionStep("s1")))
                .withSchedules(List.of(
                        new io.mateu.workflow.domain.aggregates.CronSchedule("0 0 18 * * *", "Europe/Madrid",
                                java.util.Map.of("region", "EMEA")),
                        new io.mateu.workflow.domain.aggregates.CronSchedule("@daily")));

        assertThatNoException().isThrownBy(() -> validator.validate(wd));
    }

    @Test
    void scheduleWithInvalidCronOrZoneIsRejected() {
        var wd = new WorkflowDefinition("wd-1", "Test Workflow", 1, "desc", false, 0, false, null, 0,
                List.of(startStep(), actionStep("s1")))
                .withSchedules(List.of(
                        new io.mateu.workflow.domain.aggregates.CronSchedule("0 0 18 * * *", "Europe/Madrid", null),
                        new io.mateu.workflow.domain.aggregates.CronSchedule("0 9 * * *", "Nowhere/Land", null)));

        assertThatThrownBy(() -> validator.validate(wd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schedules[1]: '0 9 * * *' is not a valid cron expression")
                .hasMessageContaining("schedules[1]: 'Nowhere/Land' is not a valid time zone")
                .hasMessageNotContaining("schedules[0]");
    }

    @Test
    void schedulesRoundTripThroughYamlWithScalarVariablesReadAsText() throws Exception {
        var yaml = """
                id: wd-1
                name: Test
                steps:
                  - id: start
                    type: START
                    name: Start
                schedules:
                  - cron: "0 0 9 * * *"
                    zone: Europe/Madrid
                    variables:
                      region: EMEA
                      copies: 2
                      urgent: true
                """;
        var mapper = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
        validator.validateSource(mapper.readTree(yaml), "inline");
        var wd = mapper.readValue(yaml, WorkflowDefinition.class);

        org.assertj.core.api.Assertions.assertThat(wd.schedules()).containsExactly(
                new io.mateu.workflow.domain.aggregates.CronSchedule("0 0 9 * * *", "Europe/Madrid",
                        java.util.Map.of("region", "EMEA", "copies", "2", "urgent", "true")));
        assertThatNoException().isThrownBy(() -> validator.validate(wd));
        // A definition that declares none does not grow a `schedules` key on export.
        org.assertj.core.api.Assertions.assertThat(new ObjectMapper().writeValueAsString(wd.withSchedules(null)))
                .doesNotContain("schedules");
    }

    @Test
    void aMisspelledScheduleFieldIsCaughtBySourceValidation() throws Exception {
        var yaml = """
                name: Test
                steps:
                  - id: start
                    type: START
                    name: Start
                schedules:
                  - cron: "0 0 9 * * *"
                    zon: Europe/Madrid
                """;
        var tree = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(yaml);

        assertThatThrownBy(() -> validator.validateSource(tree, "typo.yaml"))
                .isInstanceOf(WorkflowDefinitionValidator.WorkflowDefinitionValidationException.class)
                .hasMessageContaining("zon");
    }

    @Test
    void definitionWithSelfPreconditionFailsInvariantCheck() {
        Step selfPrecondition = new Step("s1", null, StepType.ACTION, "Step", null, "s1", null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var wd = new WorkflowDefinition("wd-1", "Test", 1, "desc",  false, 0, false, null, 0, List.of(selfPrecondition));

        assertThatThrownBy(() -> validator.validate(wd))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void definitionWithExecutionCapsPassesSchemaValidation() {
        Step capped = new Step("s1", null, StepType.ACTION, "Step s1", null, "start", null, null, false, "my-topic",
                null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 3, null);
        var wd = new WorkflowDefinition("wd-1", "Capped", 1, "desc",  false, 0, false, null, 100, List.of(startStep(), capped));

        assertThatNoException().isThrownBy(() -> validator.validate(wd));
    }

    @Test
    void definitionWithPreconditionCycleIsRejected() {
        Step s1 = new Step("s1", null, StepType.ACTION, "S1", null, "s2", null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        Step s2 = new Step("s2", null, StepType.ACTION, "S2", null, "s1", null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var wd = new WorkflowDefinition("wd-1", "Cyclic", 1, "desc",  false, 0, false, null, 0, List.of(s1, s2));

        assertThatThrownBy(() -> validator.validate(wd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void definitionWithMissingNameFailsSchemaValidation() {
        var wd = new WorkflowDefinition("wd-1", null, 1, "desc",  false, 0, false, null, 0, List.of(startStep(), actionStep("s1")));

        assertThatThrownBy(() -> validator.validate(wd))
                .isInstanceOf(WorkflowDefinitionValidator.WorkflowDefinitionValidationException.class);
    }
}
