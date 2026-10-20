package io.mateu.workflowdist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.usecases.directoryimport.ImportWorkflowDefinitionsFromDirectoryUseCase;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-38 — AGENT steps over a real broker and PostgreSQL. The engine reads the IA catalogue over HTTP with client
 * credentials (a local stand-in) when the definition is imported; the invocation travels on {@code agent-calls}
 * with its {@code invocationId} and the request sealed (the process declares a sensitive variable the agent may
 * read); the runtime — a plain Kafka consumer and producer here — puts it under review, reconciles it and completes
 * it on {@code upstream}. A reply that names another invocation, and a redelivered completion, change nothing.
 */
class Dist38AgentCallsOverKafkaTest extends AbstractDistTest {

    static final String CARD = "4111111111111111";
    static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicInteger CATALOGUE_READS = new AtomicInteger();
    static HttpServer catalogue;
    static ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void startPods() throws Exception {
        catalogue = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        catalogue.createContext("/token", exchange -> {
            var form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var ok = form.contains("grant_type=client_credentials") && form.contains("client_secret=dist-secret");
            var body = (ok ? "{\"access_token\":\"dist-token\"}" : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ok ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        catalogue.createContext("/agents", exchange -> {
            CATALOGUE_READS.incrementAndGet();
            var ok = "Bearer dist-token".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            var body = (ok ? "[{\"id\":\"claims-assessor\",\"name\":\"Claims assessor\",\"description\":\"\","
                    + "\"mcp\":[\"crm\"],\"rag\":[],\"peers\":[],\"outputSchema\":{\"type\":\"object\"},\"revision\":\"r1\"}]"
                    : "[]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ok ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        catalogue.start();
        var base = "http://127.0.0.1:" + catalogue.getAddress().getPort();

        DistInfra.createTopics(List.of("agent-calls"));
        orchestrator = DistInfra.startOrchestrator(Map.of(
                "workflow.encryption.keys.dist", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                "workflow.agents.catalogue-url", base + "/agents",
                "workflow.agents.token-uri", base + "/token",
                "workflow.agents.client-id", "dist-engine",
                "workflow.agents.client-secret", "dist-secret"));
        // Imported here, not under workflows/: the suite's other pods have neither the keys nor a catalogue.
        var dir = Files.createTempDirectory("dist38");
        try (var in = Dist38AgentCallsOverKafkaTest.class.getResourceAsStream("/dist38/dist-agent.json")) {
            Files.copy(in, dir.resolve("dist-agent.json"));
        }
        var result = orchestrator.getBean(ImportWorkflowDefinitionsFromDirectoryUseCase.class).handle(List.of(dir.toString()));
        assertThat(result.errors()).isEmpty();
        assertThat(CATALOGUE_READS.get()).as("the reference was checked against the catalogue").isPositive();
    }

    @AfterAll
    static void stopPods() {
        orchestrator.close();
        catalogue.stop(0);
        var jdbc = DistInfra.jdbc();
        jdbc.update("DELETE FROM workflow_definition_version WHERE definition_id = 'dist-agent'");
        jdbc.update("DELETE FROM workflow_definition_entity WHERE id = 'dist-agent'");
    }

    @Test
    void anInvocationIsHeldReconciledAndCompletedOverTheBroker() throws Exception {
        createProcess("dist-agent", "dist38-1", new Variable("cardNumber", CARD), new Variable("claimId", "C-1"));
        await().atMost(DEFAULT_TIMEOUT).until(() -> processStatus("dist38-1").isPresent());
        var processId = processId("dist38-1");

        // What the runtime reads off agent-calls: run-agent@1, its invocation, the request sealed.
        var raw = rawRecord("agent-calls", processId);
        assertThat(raw).doesNotContain(CARD).contains("run-agent@1").contains("ec:enc:v1:dist:");
        var task = JSON.readTree(raw);
        var taskExecutionId = task.path("taskExecutionId").asText();
        var invocationId = task.path("invocationId").asText();
        var attempt = task.path("attempt").asInt();
        assertThat(invocationId).isNotBlank();
        assertThat(DistInfra.jdbc().queryForObject("SELECT agent_request FROM step_execution_entity WHERE id = ?",
                String.class, taskExecutionId)).startsWith("ec:enc:v1:dist:").doesNotContain(CARD);

        // The runtime asks for a review: the process is held.
        reply(taskExecutionId, processId, TaskStatus.REVIEW_REQUIRED, attempt, invocationId, false, 1L);
        awaitProcessStatus("dist38-1", "PAUSED", DEFAULT_TIMEOUT);
        assertThat(stepStatuses("dist38-1")).containsEntry("assess", "REVIEW_REQUIRED");

        // Another invocation's completion is not this one's.
        reply(taskExecutionId, processId, TaskStatus.COMPLETED, attempt, "another-invocation", false, 9L,
                new Variable("assessment", "{\"decision\":\"stale\"}"));
        // It reconciles, then completes.
        reply(taskExecutionId, processId, TaskStatus.RUNNING, attempt, invocationId, true, 2L);
        reply(taskExecutionId, processId, TaskStatus.COMPLETED, attempt, invocationId, false, 3L,
                new Variable("assessment", "{\"decision\":\"accept\"}"));
        awaitProcessCompleted("dist38-1");
        assertThat(assessment(processId)).isEqualTo("{\"decision\":\"accept\"}");

        // Kafka redelivers a completion it could not confirm: nothing changes.
        reply(taskExecutionId, processId, TaskStatus.COMPLETED, attempt, invocationId, false, 3L,
                new Variable("assessment", "{\"decision\":\"again\"}"));
        DistInfra.flushProducer();
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(assessment(processId)).isEqualTo("{\"decision\":\"accept\"}"));
        assertThat(processStatus("dist38-1")).contains("COMPLETED");
    }

    private static void reply(String taskExecutionId, String processId, TaskStatus status, int attempt,
                              String invocationId, boolean reviewResolved, Long sequence, Variable... output) {
        DistInfra.publishUpstream(new TaskStatusChanged(taskExecutionId, status, List.of(output), processId, false,
                attempt, invocationId, reviewResolved, sequence));
    }

    private static String assessment(String processId) {
        return orchestrator.getBean(ProcessRepository.class).findById(processId).orElseThrow().getVariables().stream()
                .filter(v -> "assessment".equals(v.name())).map(v -> v.value()).findFirst().orElse(null);
    }

    private static String rawRecord(String topic, String processId) {
        var props = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, DistInfra.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dist38-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(topic));
            var deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() != null && record.value().contains(processId)) {
                        return record.value();
                    }
                }
            }
        }
        throw new AssertionError("No record for " + processId + " on " + topic);
    }
}
