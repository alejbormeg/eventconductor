package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.workflow.application.out.ExternalEventPublisher;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Embedded mode: a PUBLISH_EVENT's event, relayed off the outbox, handed to the application's
 * {@link ExternalEventPublisher} — and, unless {@code workflow.events.loopback=false}, received by
 * this engine's own event triggers and {@code onEvent} waits. Absent in kafka mode, where the relay sends it straight to the
 * destination's topic and it never comes back to a handler.
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
@RequiredArgsConstructor
public class ExternalEventRequestedHandler implements DomainEventHandler<ExternalEventRequested> {

    private final ExternalEventPublisher externalEventPublisher;
    /** Lazily: the inbound service reaches the engine's doors, which reach this handler. */
    private final org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.services.InboundEventService> inboundEventService;
    private final io.mateu.workflow.infra.config.EventDestinationsProperties eventDestinationsProperties;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return ExternalEventRequested.class;
    }

    @Override
    public void handle(ExternalEventRequested event) {
        externalEventPublisher.publish(event);
        // Loopback: what this engine publishes can start or wake one of its own workflows, as it would
        // through Kafka. After the publish, so the application sees its event whatever happens here;
        // a failure here retries the whole event, and the receipts keep the second pass from acting twice.
        if (eventDestinationsProperties.isLoopback()) {
            inboundEventService.getObject().receive(io.mateu.workflow.domain.services.InboundEvents.fromPublished(event));
        }
    }
}
