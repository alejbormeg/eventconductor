package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkflowAgentDefinitionValidationTest {
    private final WorkflowDefinitionValidator validator = new WorkflowDefinitionValidator();
    private final ObjectMapper json = new ObjectMapper();
    private final String definition = """
            {"name":"Synthetic assessment","steps":[
              {"id":"start","name":"Start","type":"START"},
              {"id":"assess","name":"Assess","type":"AGENT","preconditionStepId":"start",
               "agent":{"ref":"synthetic","outputVariable":"assessment","mcp":[],"input":{"id":"${caseId}"}}}
            ]}
            """;

    @Test void schemaSupportsTheAgentBlockAndRejectsUnknownConfiguration() throws Exception {
        var source = json.readTree(definition);
        assertThatNoException().isThrownBy(() -> validator.validateSource(source, "synthetic.ec"));
        var agent = (com.fasterxml.jackson.databind.node.ObjectNode) source.path("steps").get(1).path("agent");
        agent.put("model", "forbidden-workflow-model-override");
        assertThatThrownBy(() -> validator.validateSource(source, "synthetic.ec")).hasMessageContaining("model");
    }

    @Test void aMissingReferenceOrOutputVariableFailsAtImport() throws Exception {
        var source = json.readTree(definition);
        var agent = (com.fasterxml.jackson.databind.node.ObjectNode) source.path("steps").get(1).path("agent");
        agent.remove("ref");
        assertThatThrownBy(() -> validator.validateSource(source, "synthetic.ec")).hasMessageContaining("ref");
    }

    @Test void agentFailuresDoNotAdvertiseUnsupportedAutomaticRetries() throws Exception {
        var source = json.readTree(definition);
        var step = (com.fasterxml.jackson.databind.node.ObjectNode) source.path("steps").get(1);
        step.put("retries", 1);
        assertThatThrownBy(() -> validator.validateSource(source, "synthetic.ec")).hasMessageContaining("retries");
    }

    @Test void memoryModeChecksTheReferenceAgainstTheCatalogueAsTheJpaImportDoes() throws Exception {
        var parsed = io.mateu.core.infra.JsonSerializer.pojoFromJson(
                definition.replaceFirst("\\{", "{\"id\":\"synthetic\","),
                io.mateu.workflow.domain.aggregates.WorkflowDefinition.class);
        var noCatalogue = new io.mateu.workflow.infra.out.classpath.ClasspathWorkflowDefinitionRepository(
                new TaskReferenceResolver(new io.mateu.workflow.infra.out.classpath.ClasspathTaskContractRepository()));
        assertThatThrownBy(() -> noCatalogue.save(parsed)).hasMessageContaining("catalogue is not configured");

        io.mateu.workflow.application.out.WorkflowAgentCatalogue catalogue = () -> java.util.List.of(
                new io.mateu.workflow.application.out.WorkflowAgentCatalogue.Agent("other", "Other", null,
                        java.util.List.of(), null, null, java.util.Map.of("type", "object"), "r1"));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("catalogue", catalogue);
        var withCatalogue = new io.mateu.workflow.infra.out.classpath.ClasspathWorkflowDefinitionRepository(
                new TaskReferenceResolver(new io.mateu.workflow.infra.out.classpath.ClasspathTaskContractRepository()), null,
                beans.getBeanProvider(io.mateu.workflow.application.out.WorkflowAgentCatalogue.class));
        assertThatThrownBy(() -> withCatalogue.save(parsed)).hasMessageContaining("Invalid AGENT step 'assess'")
                .hasMessageContaining("Unknown published workflow agent: synthetic");
        assertThat(withCatalogue.save(parsed.withSteps(parsed.steps().stream()
                .map(step -> step.agent() == null ? step : step.withAgent(new io.mateu.workflow.domain.aggregates.AgentCall(
                        "other", null, null, null, null, null, "assessment"))).toList())))
                .isEqualTo("synthetic");
    }
}
