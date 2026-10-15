package io.mateu.workflowdist;

import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-32 — domain events in, over Kafka. A CloudEvent in binary content mode produced onto a
 * destination's topic starts the workflow whose trigger names it — once, however many times it is
 * delivered, and whichever of two pods consumes it — and an event on another topic completes the
 * process's onEvent wait, correlated by the record key.
 */
class Dist32InboundEventsOverKafkaTest extends AbstractDistTest {

    private static final String ORDERS = "dist32-order-events";
    private static final String PAYMENTS = "dist32-payment-events";

    static ConfigurableApplicationContext podA;
    static ConfigurableApplicationContext podB;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureStarted();
        DistInfra.createTopics(List.of(ORDERS, PAYMENTS));
        Map<String, Object> config = Map.of(
                "workflow.events.destinations.dist32-orders.topic", ORDERS,
                "workflow.events.destinations.dist32-payments.topic", PAYMENTS,
                // The topics exist before the pods subscribe; reading them from the start keeps the test
                // from racing the group's first assignment.
                "workflow.events.consumer.auto-offset-reset", "earliest",
                "workflow.events.consumer.group", "dist32");
        podA = DistInfra.startOrchestrator(config);
        podB = DistInfra.startOrchestrator(config);
    }

    @AfterAll
    static void stopPods() {
        podA.close();
        podB.close();
    }

    private static void produce(String topic, String key, String id, String type, String data) {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, DistInfra.kafkaBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        try (var producer = new KafkaProducer<byte[], byte[]>(props)) {
            var record = new ProducerRecord<>(topic, key.getBytes(StandardCharsets.UTF_8), data.getBytes(StandardCharsets.UTF_8));
            record.headers().add("ce_specversion", bytes("1.0"));
            record.headers().add("ce_id", bytes(id));
            record.headers().add("ce_source", bytes("dist32/test"));
            record.headers().add("ce_type", bytes(type));
            record.headers().add("contentType", bytes("application/json"));
            producer.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private int processesWithKey(String businessKey) {
        return DistInfra.jdbc().queryForObject("SELECT count(*) FROM process_entity WHERE business_key = ?",
                Integer.class, businessKey);
    }

    @Test
    void anEventOnTheTopicStartsOneProcessAndAnotherCompletesItsWait() throws Exception {
        produce(ORDERS, "O-1", "order-1", "com.acme.order.placed", "{\"orderId\":\"O-1\"}");
        // The same event again, as a redelivery or a second producer would send it.
        produce(ORDERS, "O-1", "order-1", "com.acme.order.placed", "{\"orderId\":\"O-1\"}");

        await().atMost(DEFAULT_TIMEOUT).until(() -> processStatus("dist32-O-1").isPresent());
        await().atMost(Duration.ofSeconds(60)).until(() -> "PENDING".equals(stepStatuses("dist32-O-1").get("wait-payment")));

        // Correlated by the record key (no correlationKey template): another order's payment does nothing.
        produce(PAYMENTS, "O-other", "payment-0", "com.acme.payment.captured", "{\"paymentId\":\"P-0\"}");
        produce(PAYMENTS, "O-1", "payment-1", "com.acme.payment.captured", "{\"paymentId\":\"P-1\"}");
        awaitProcessCompleted("dist32-O-1");

        Thread.sleep(2_000);
        assertThat(processesWithKey("dist32-O-1")).isEqualTo(1);
        assertThat(DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM inbound_event_receipt WHERE event_id = 'order-1' AND outcome = 'STARTED'",
                Integer.class)).isEqualTo(1);
    }
}
