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
}
