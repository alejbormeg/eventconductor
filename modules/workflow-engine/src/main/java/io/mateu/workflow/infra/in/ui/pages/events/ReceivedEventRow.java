package io.mateu.workflow.infra.in.ui.pages.events;

import io.mateu.uidl.annotations.Details;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.domain.aggregates.EventReceipt;

/**
 * One row of the Received events page: what one receiver did with one domain event — started a
 * process (its business key), delivered it as a message to a waiting step (message and key), or could
 * not use it (the reason, opened under the row).
 */
public record ReceivedEventRow(
        @Label("Id") String id,
        @Label("Received") String received,
        @Label("Destination") String destination,
        @Label("Type") String type,
        @Label("Event id") String eventId,
        @Label("Source") String source,
        @Label("Workflow") String workflow,
        @Label("Receiver") String receiver,
        @Label("Business key / key") String key,
        @Label("Outcome") Status outcome,
        @Details String detail
) implements Identifiable {

    public static ReceivedEventRow of(EventReceipt receipt) {
        var key = receipt.kind() == EventReceipt.Kind.TRIGGER ? receipt.businessKey() : receipt.correlationKey();
        return new ReceivedEventRow(receipt.receiptKey(), EventFormats.received(receipt), receipt.destination(),
                EventFormats.orDash(receipt.eventType()), receipt.eventId(), receipt.eventSource(),
                EventFormats.orDash(receipt.workflowId()), receipt.receiver(), EventFormats.orDash(key),
                EventFormats.outcome(receipt.outcome()), receipt.detail());
    }
}
