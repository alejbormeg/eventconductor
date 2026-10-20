package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.WorkflowAgentCatalogue;
import io.mateu.workflow.domain.aggregates.AgentCall;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class WorkflowAgentCatalogueTest {
    private final WorkflowAgentCatalogue catalogue = () -> List.of(new WorkflowAgentCatalogue.Agent("synthetic", "Synthetic", null,
            List.of("read-case"), List.of(), List.of("peer"), Map.of("type", "object"), "git-revision"));

    @Test void selectionsCanOnlyNarrowPublishedCapabilities() {
        catalogue.validate(new AgentCall("synthetic", null, null, null, null, null, "out"));
        catalogue.validate(new AgentCall("synthetic", null, null, List.of(), List.of(), List.of("peer"), "out"));
        assertThatThrownBy(() -> catalogue.validate(new AgentCall("synthetic", null, null, List.of("write-case"), null, null, "out")))
                .hasMessageContaining("unauthorized mcp");
        assertThatThrownBy(() -> catalogue.validate(new AgentCall("unpublished", null, null, null, null, null, "out")))
                .hasMessageContaining("Unknown published");
    }
}
