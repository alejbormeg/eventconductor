---
title: Starting on and Waiting for Domain Events
description: Event triggers start a workflow when a domain event arrives; a WAIT_FOR_MESSAGE with onEvent waits for one. CloudEvents, the same destinations PUBLISH_EVENT publishes to, each event used once.
---

[`PUBLISH_EVENT`](/guides/publishing-events/) lets a process speak to other systems. This is the other direction: a definition can **listen** to the domain events of other systems — or of its own.

- An **event trigger** starts a process when an event arrives — like a [schedule](/guides/starting-a-process/#schedules-with-parameters), but on an event instead of a clock.
- A **`WAIT_FOR_MESSAGE` with `onEvent`** waits until an event for *this* process arrives.

Events are **CloudEvents 1.0** and arrive on a **destination** — the logical name `workflow.events.destinations.<name>.topic` maps to a topic, the same names `PUBLISH_EVENT` uses. A definition never names a topic.

## Event triggers

```yaml
id: booking-onboarding
name: Booking onboarding
triggers:
  - id: on-booking-created               # optional; derived from the type when absent
    destination: bookings                # → workflow.events.destinations.bookings.topic
    type: com.acme.booking.created       # CloudEvents type; '*' is a wildcard: com.acme.booking.*
    filter: "data.channel == 'web'"      # optional JEXL over the event, must be true
    businessKey: "booking-${data.bookingId}"   # optional; default derived from the event
    variables:                           # optional; default: data's top-level members
      bookingId: "${data.bookingId}"
      nights: "${data.nights}"
steps:
  - id: start
    type: START
    name: Start
  # …
```

| Field | Required | Meaning |
|---|---|---|
| `id` | no | The trigger's name, unique in the definition (letters, digits, `.`, `_`, `-`). A pause is kept under it and the default business key carries it. Absent: the type (`com.acme.booking.created`; a second trigger with the same type gets `-2`). |
| `destination` | yes | A logical destination. |
| `type` | yes | The CloudEvents type to match, exactly or with `*` wildcards. A lone `*` matches every event on the destination, including plain records without a type. |
| `filter` | no | A JEXL boolean over the event, without `${}`. Only `true` lets the event through; an expression that fails counts as false and is recorded as an error. |
| `businessKey` | no | A text template over the event. Absent: `<definition>-evt-<trigger>-<16 hex of SHA-256(source, id)>`, the same for every delivery of one event. |
| `variables` | no | The started process's initial variables, each a [payload template](/guides/payload-templates/) over the event. Absent: every top-level member of `data` (when it is a JSON object). `{}`: none. A non-string value becomes its JSON text, as process variables are strings. |

A trigger starts a process **as the engine itself** (like cron: the definition's `requiredScopes`/`requiredRoles` do not apply), through the same door as cron, so sharding places it like any creation. Lifecycle, as for schedules:

- the definition must be **ACTIVE** — a DISABLED or ARCHIVED definition starts nothing;
- a **paused definition** still creates the process, already PAUSED;
- a **paused trigger** starts nothing, and resuming it does not replay what arrived meanwhile.

### What a filter and a template see

| Name | Value |
|---|---|
| `id`, `source`, `type`, `subject`, `time`, `datacontenttype` | The CloudEvents attributes |
| `key` | The Kafka record key (or the `key` given to the REST/MCP inject) |
| `destination` | The destination it arrived on |
| `data` | The payload, parsed as JSON — objects are maps, so `data.bookingId`, `data.guest.name` — or its text when it is not JSON |
| `extensions` | Any other attribute (`extensions.tenant`) |

Templates and filters run in the same sandbox as every other expression in a definition.

## Waiting for a domain event

```yaml
- id: wait-payment
  type: WAIT_FOR_MESSAGE
  name: Wait for the payment
  preconditionStepId: confirm
  messageName: payment-captured          # still required: the message the event becomes
  correlationExpression: bookingId       # over the process, as for any wait
  deadline: { date: "${checkinDate}", offset: -P1D }
  onTimeoutStepId: cancel-booking
  onEvent:
    destination: payments
    type: com.acme.payment.captured
    correlationKey: "${data.bookingId}"  # optional; default the record key, else the subject
    filter: "data.status == 'CAPTURED'"  # optional
    variables:                           # optional; default: data's top-level members
      paymentId: "${data.paymentId}"
```

An event matching `onEvent` is turned into the step's message — `messageName`, with the correlation key and variables it renders — and dispatched exactly like a message from the [message API](/guides/starting-a-process/). The step completes when that key equals its `correlationExpression` over the process. Everything a wait does stays as it is: the indexed subscription, `timeout`, `deadline`, `onTimeoutStepId`, compensation — and the message API can still complete the wait by `messageName` (handy in tests).

The default correlation key is the record key: a `PUBLISH_EVENT` keyed by the booking id (`key: "${bookingId}"`) is waited for with `correlationExpression: bookingId` and no `correlationKey` at all.

Like any message, an event that arrives before the step waits is **not buffered**: it correlates with nothing.

## Each event is used once

The same event — the same CloudEvents `source` and `id` — **never starts two processes through one trigger, and is never correlated twice** (not even into a later wait of the same process on the same message), however often it is delivered and through whichever channel:

- After a trigger started a process or a wait got its message, the engine records a **receipt** for that receiver and event (`inbound_event_receipt` with `workflow.persistence=jpa`, in memory otherwise). A redelivery finds it and does nothing — it is reported as a duplicate.
- The receipt is written after the start or the message was handed over, so a failure to hand over is retried. The short window in between (a crash, two copies at once) is closed by the **default business key**, derived from the event: the second creation collapses into the first.
- An author-given `businessKey` is honoured as given — so two *different* events that render the same key start one process.
- Receipts are kept for `workflow.events.receipts.retention` (7 days); past it, a redelivery is a new event.
- An event whose filter or templates fail records an **error** receipt and that receiver does nothing; a later delivery is tried again.

## How events arrive

**Kafka mode.** The engine consumes the topics of the destinations that some trigger or `onEvent` names, in one consumer group (`workflow.events.consumer.group`), so each event is used by one pod. The set is recomputed every `workflow.events.consumer.refresh-interval` — a git import that adds a trigger is listened to without a restart. A new group starts at `latest`: turning a trigger on does not replay the topic's history. Records are read as CloudEvents in **binary** mode (`ce_*` headers — what `PUBLISH_EVENT` writes by default), **structured** mode (`application/cloudevents+json`), or as **plain** records (id `<topic>-<partition>-<offset>`, source `kafka://<topic>`, no type — only a `type: '*'` trigger matches them). A destination named by a definition but configured without a topic is logged and not consumed.

```yaml
workflow:
  events:
    destinations:
      bookings: { topic: booking-events }
      payments: { topic: payment-events }
    consumer:
      group: eventconductor-inbound-events
```

**Embedded mode.** Hand the engine an event yourself — call `InboundEventService.receive(new InboundEvent(...))`, or publish the `InboundEvent` as a Spring application event:

```java
applicationEventPublisher.publishEvent(new InboundEvent(
        "bookings", eventId, "pms", "com.acme.booking.created", null, null,
        bookingId, "application/json", json, Map.of()));
```

A `PUBLISH_EVENT` of this engine is **looped back** too (`workflow.events.loopback`, default `true`): one workflow's event can start or wake another, as it would through Kafka.

**REST** (both modes) — for tests and for systems that cannot publish to Kafka:

```http
POST /workflow/api/events/bookings?key=B-1
Content-Type: application/json

{"specversion": "1.0", "id": "evt-123", "source": "pms", "type": "com.acme.booking.created",
 "data": {"bookingId": "B-1", "nights": 2, "channel": "web"}}
```

The answer says what the event did: `receipts` (each `STARTED` with its business key, `DELIVERED` with the message and key, or `ERROR` with the reason) and `duplicates`. Same optional `X-Api-Key` as the message API. **MCP**: `publishInboundEvent` does the same.

## Operating triggers

- **Workflow → Event triggers** lists every trigger — workflow, destination and its topic, type, filter, business key, variables, status — and pauses or resumes the selected ones. **Workflow → Received events** shows the latest receipts: what each event started, delivered, or why it could not be used. A definition's detail page has a **Triggers** tab.
- REST: `GET /workflow/api/triggers[?workflowId=]`, `GET /workflow/api/triggers/{workflowId}/{triggerId}`, `POST …/pause`, `POST …/resume`, `GET /workflow/api/events/received[?workflowId=&limit=]`.
- MCP: `listEventTriggers`, `pauseEventTrigger`, `resumeEventTrigger`, `listReceivedEvents`, `publishInboundEvent`.
- Metric: `eventconductor.events.received{destination, outcome}` (`STARTED`, `DELIVERED`, `ERROR`, `DUPLICATE`).
- The graph shows a ⚡ on the START node of a triggered workflow, and `⚡ <type> ← <destination>` above a wait fed by an event.

## Limits

- An event that matches nothing leaves no receipt (a busy topic would fill the table with noise); the REST/MCP inject says what happened.
- Waits are matched against each definition's **current** declaration. A process still running an older version whose `onEvent` changed since is completed through its `messageName` by the message API.
- A trigger is declared in its definition; there is no `events.yaml` and no trigger created from the UI.
