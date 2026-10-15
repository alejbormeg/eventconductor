# Plan: domain events in — event triggers and waiting for a domain event

> Status: **DECIDED (2026-10-15)** — the owner asked for both halves (triggers "like the crons", and
> a wait that can subscribe to another system's events); the open choices below were settled in this
> document and implemented on `feat/domain-event-triggers`.

## 1. Goal

`PUBLISH_EVENT` lets a process speak to other systems. This is the other direction: let a definition
**listen**.

- *"Start a `booking-onboarding` process whenever `com.acme.booking.created` arrives on `bookings`."* —
  an **event trigger**, declared on the definition next to `cronExpression` / `schedules`.
- *"Wait here until the payment service says `com.acme.payment.captured` for **this** booking."* — a
  `WAIT_FOR_MESSAGE` whose message comes from a **domain-event topic**, not from someone calling the
  message API.

Both are **CloudEvents 1.0** and both name a **destination** — the same logical name
`workflow.events.destinations.<name>.topic` that `PUBLISH_EVENT` publishes to. A definition never names
a topic (the rule that keeps a definition imported from git out of the engine's own topics).

| # | Decision |
|---|---|
| A | Triggers are a definition-level list, `triggers: [...]`, beside `schedules`. |
| B | Waiting reuses `WAIT_FOR_MESSAGE`: an optional `onEvent` block turns a matching domain event into the step's message. No new step type. |
| C | One ingest path for every channel: Kafka consumer (kafka mode), in-process API + Spring event + PUBLISH_EVENT loopback (embedded), REST and MCP (both modes). |
| D | Idempotency by CloudEvents `(source, id)` per receiver, persisted; default business keys derived from the event so a redelivery collapses even before the receipt is written. |
| E | Same lifecycle semantics as schedules: a disabled/archived definition starts nothing, a paused definition's starts are born PAUSED, a paused trigger starts nothing and does not replay what it missed. |

## 2. What exists to build on

- **Starting by itself**: `CronStarts` builds a `ProcessCreationRequested(defId, businessKey, vars, null,
  AuthorizationContext.SYSTEM)` and hands it to `IngressRouter.route` (sharding-aware placement; the
  creation idempotency guard in `CreateProcessUseCase` collapses a repeated business key). Only
  definitions whose `status().accceptsNewInstances()`; a paused definition still creates, PAUSED.
  Schedule pauses live apart (`SchedulePauseRepository`, `cron_schedule_pause`) so imports don't erase them.
- **Correlation**: a `MessageReceived(name, correlationKey, variables)` dispatched through
  `MessageDispatcher` (upstream, shared `messages` topic, or the `MessageRouter`) is matched by
  `CorrelateMessageUseCase` against the **materialised** `(messageName, correlationKey)` of waiting step
  executions (indexed, bloom-filtered per shard, re-checked under the process lock by
  `CompleteMessageStepHandler`). The step's key is its `correlationExpression` over the process.
- **Events out**: `PUBLISH_EVENT` → `ExternalEventRequested` (outbox) → kafka: `ExternalEventSender`
  (binary / structured / plain CloudEvents), embedded: `ExternalEventPublisher` (default: Spring
  `ExternalEventPublished`). `EventDestinationsProperties` holds the destinations.
- **Templates and expressions**: `Templates` (shared; typed leaves, sandboxed JEXL), `TemplateSyntax`
  (definition-analysis, JDK-only, used by the Maven plugin), `JEXLEvaluator` (fail-closed).
- **Validation in one place**: `WorkflowDefinition.checkInvariants` + `SpecValidator` sharing
  `definition-analysis` rules (`ScheduleRules` is the model), schema-first `workflow-definition-schema.json`.

## 3. Design

### 3.1 Event triggers (definition)

```yaml
triggers:
  - id: on-booking-created            # optional, unique in the definition; derived from type when absent
    destination: bookings             # logical name → workflow.events.destinations.bookings.topic
    type: com.acme.booking.created    # CloudEvents type; '*' wildcards allowed (com.acme.booking.*)
    filter: "data.channel == 'web'"   # optional JEXL over the event, must be true
    businessKey: "booking-${data.bookingId}"   # optional template; default derived from the event (§3.4)
    variables:                        # optional templates → initial variables; default: data's top-level members
      bookingId: "${data.bookingId}"
      nights: "${data.nights}"
```

**What a template / filter sees** (the *event context*): `id`, `source`, `type`, `subject`, `time`,
`key` (the Kafka record key, or the key given to the REST/MCP inject), `destination`,
`datacontenttype`, `data` (parsed JSON — objects are maps, so `data.bookingId` — or the raw text), and
`extensions` (any other CloudEvents attribute). Variables render to strings as process variables are
(a non-string leaf becomes its JSON text).

**Starting**: for every definition that accepts new instances (ACTIVE) and every trigger whose
destination and type match, not paused, whose filter holds: render key and variables and route a
`ProcessCreationRequested` as `SYSTEM` through `IngressRouter` — exactly the door cron uses. A paused
definition creates it PAUSED (the cron rule). A template or filter that fails records an `ERROR`
receipt and starts nothing (never a process with half its variables).

*Rejected:* a separate `events.yaml` like `cron.yaml`. A trigger is about one definition and travels
with it; nothing yet asks for triggers created from the UI (the schedule case), so the file/UI ownership
machinery is not worth copying now. The listing and pause/resume are there; creation from the UI is not.

### 3.2 Waiting for a domain event — `WAIT_FOR_MESSAGE.onEvent`

```yaml
- id: wait-payment
  type: WAIT_FOR_MESSAGE
  name: Wait for the payment
  messageName: payment-captured        # still required: the message the event becomes
  correlationExpression: bookingId     # over the process, as today
  onEvent:
    destination: payments
    type: com.acme.payment.captured
    correlationKey: "${data.bookingId}"   # optional template over the event; default ${key}, else ${subject}
    filter: "data.status == 'OK'"         # optional JEXL over the event
    variables:                            # optional; default: data's top-level members
      paymentId: "${data.paymentId}"
```

**Why not a new step type, and why not reuse the `event` block**: everything a wait already does —
the indexed `(messageName, correlationKey)` subscription, shard routing, the bloom filter, `deadline`,
`timeout`, `onTimeoutStepId`, the re-check under the process lock, compensation — stays exactly as it
is if the event is turned into the step's message at the edge. `onEvent` is that adapter: a domain
event matching it is dispatched as `MessageReceived(messageName, rendered correlationKey, rendered
variables)` through `MessageDispatcher`, the way the message API does. A new step type would have
duplicated all of it. The step keeps `messageName`, so a test or a system that cannot publish to Kafka
can still complete the wait through the message API. The `event` block is `PUBLISH_EVENT`'s: its
templates are over the **process**; `onEvent`'s are over the **event**. One block whose every field
changes meaning with the step type is what the schema and the validator cannot check, so it is a
separate record with a separate name.

Matching subscriptions are taken from the current declaration of every definition (any status: a
disabled definition's running processes still wait). Two steps that turn one event into the same
`(messageName, key, variables)` dispatch it once.

### 3.3 Channels — one ingest

`InboundEventService.receive(InboundEvent)` is the only place an event is matched, de-duplicated and
turned into starts and messages. It returns what it did (`started`, `delivered`, `duplicates`, `errors`).

- **Kafka mode** — `InboundEventKafkaConsumer`: one Spring Kafka listener container, consumer group
  `workflow.events.consumer.group` (default `eventconductor-inbound-events`), subscribed to the topics
  of the destinations that some definition's trigger or `onEvent` names **and** that are configured
  with a topic. The set is recomputed every `workflow.events.consumer.refresh-interval` (PT30S) and the
  container restarted when it changes, so a git import adding a trigger is picked up without a restart.
  Records are decoded as CloudEvents **binary** (`ce_*` headers) or **structured**
  (`application/cloudevents+json`), else **plain** (id `<topic>-<partition>-<offset>`, source
  `kafka://<topic>`, no type — only a `type: '*'` trigger matches it). A new group starts at `latest`
  (`workflow.events.consumer.auto-offset-reset`): enabling triggers does not replay a topic's history.
  An undecodable record is logged and skipped; a failure to dispatch (broker down) is retried with a
  fixed back-off, so offsets are committed only after the starts/messages were handed over.
- **Embedded mode** — the application calls `InboundEventService.receive(...)` (or publishes an
  `InboundEvent` as a Spring application event). And a `PUBLISH_EVENT` published by this engine is
  **looped back** (`workflow.events.loopback`, default true in embedded mode): the event one
  process publishes can start or wake another, as it would through Kafka. Safe against double delivery
  by the idempotency below.
- **REST** — `POST /workflow/api/events/{destination}` with a structured CloudEvent
  (`{specversion?, id, source, type, subject?, time?, data, key?}`), same `X-Api-Key` as the message API.
  Answers 200 with the ingest result (both modes: the ingest runs on the request). **MCP** —
  `publishInboundEvent` does the same.

### 3.4 Idempotency

- **Receipts**: one row per *receiver* and event — `(receiver, event_source, event_id)` unique, where
  the receiver is `trigger:<definition>/<trigger>` or `wait:<messageName>/<correlationKey>`. Before a
  receiver acts the ingest checks for its receipt; after it acted it records one (`STARTED` with the
  business key, `DELIVERED`, or `ERROR` with the reason). So the same event neither starts two processes
  for one trigger nor is correlated twice — not even into a later wait of the same process on the same
  message.
- **The window between acting and recording** (a crash, a concurrent duplicate) is closed downstream:
  the **default business key** of a trigger start is `<definition>-evt-<trigger>-<sha256(source, id)
  :16>`, so a repeated creation collapses in the creation idempotency guard; a repeated message finds the
  step already completed. An author-given `businessKey` is honoured as given (and also collapses repeats
  — with the documented consequence that two *different* events rendering the same key start one process).
- **Retention**: receipts are purged after `workflow.events.receipts.retention` (P7D) by every pod
  (idempotent delete), like sync invocations.
- Storage: in memory for `workflow.persistence=memory`; `inbound_event_receipt` for jpa (**V39**).

### 3.5 Lifecycle and operations

- Trigger pause/resume by `<definition>/<trigger>` in `event_trigger_pause` (V39), surviving re-imports
  (the schedule-pause rule). Events that arrive while paused are not replayed on resume.
- **Admin UI**: a **Triggers** tab on the definition detail view (hidden when the definition has none):
  trigger id, destination → topic, type, filter, business key, variables, status. A **Workflow → Events**
  page: every trigger in the engine (pause/resume selected) and a **Received events** page: the recent
  receipts — when, destination, type, id, source, receiver, outcome, business key / process.
- **REST**: `GET /workflow/api/triggers[?workflowId=]`, `POST /workflow/api/triggers/{workflowId}/{triggerId}/pause|resume`,
  `GET /workflow/api/events/received?limit=`, `POST /workflow/api/events/{destination}`.
- **MCP**: `listEventTriggers`, `pauseEventTrigger`, `resumeEventTrigger`, `listReceivedEvents`,
  `publishInboundEvent`.
- Metrics: `eventconductor.events.received{destination,outcome}`.

### 3.6 Validation, schema, tooling

- `EventTriggerRules` (definition-analysis, shared by engine and Maven plugin): destination and type
  required; id syntax (the schedule id pattern) and uniqueness; `onEvent` only on `WAIT_FOR_MESSAGE`;
  templates parse (`TemplateSyntax`). Filters are JEXL-checked on both sides. A destination unknown to
  configuration is **not** an import error (the plugin cannot know it, and the engine may be configured
  after import); the consumer logs it and the Triggers tab shows "no topic configured".
- Schema: `triggers` (`$defs/EventTrigger`) and step `onEvent` (`$defs/EventWait`), with descriptions.
- Graph: a ⚡ badge on the START node listing the event types (hover), the `onEvent` summary as the
  WAIT_FOR_MESSAGE node's caption, and a read-only "Event source" field in its panel.
- Persistence: `workflow_definition_entity.triggers_json` (V39); in the version hash only when present
  (no spurious version bump for existing definitions).

## 4. Tests

- **Unit**: event context and rendering (typed `data`, defaults, `correlationKey` fallback, filters
  true/false/failing, wildcard types, derived ids and keys); CloudEvents decoding (binary, structured,
  plain); `EventTriggerRules`; invariants; `SpecValidator`; the detail view's tab; the MCP tools.
- **E2E (embedded)**: a trigger starts a process with mapped variables; the same event again starts
  nothing (receipt) — and a duplicate with an author business key; a filter that does not hold; a
  disabled definition and a paused trigger start nothing; a `PUBLISH_EVENT` looped back starts another
  definition; a wait completed by a domain event (correlated by payload), not by one for another key,
  and the same event not correlated twice into a second wait.
- **JPA e2e** twin for the receipt table and the pause table.
- **Dist (Kafka)**: DIST-32 — a binary CloudEvent produced onto the destination's topic starts one
  process; produced twice, still one; a waiting process completed by an event on another topic.

## 5. Phases

- [x] **P1 — Model & rules.** `EventTrigger`, `EventWait`, `EventTriggerRules`, schema, invariants,
  persistence (V39), versioning hash, importer rebuilds, Maven plugin.
- [x] **P2 — Ingest.** `InboundEvent`, event context, `InboundEventService`, receipts, pauses, loopback,
  Spring-event API; e2e.
- [x] **P3 — Channels.** Kafka consumer, REST, MCP; dist test.
- [x] **P4 — UI & graph.** Triggers tab, Events / Received events pages, graph badge and field.
- [x] **P5 — Docs.** Guide *"Starting on and waiting for domain events"*, step types, configuration,
  REST/MCP references, AI files, example, CHANGELOG.

## 6. Decisions (recommendation taken)

1. **New step type vs `onEvent`** — `onEvent` (§3.2): reuse beats duplication; the wait machinery is
   the hard part and it is already there.
2. **`messageName` stays required with `onEvent`** — it names the message the event becomes, keeps the
   REST/MCP message door open for that wait, and leaves `StepExecution` and the routing untouched.
3. **Unmatched events are not recorded** (only receivers that acted or failed) — a busy topic with many
   types would otherwise fill the receipts table with noise; the REST/MCP inject answers what happened.
4. **Older versions** — waits are matched against each definition's current declaration. A process
   running an older version whose `onEvent` was changed since is still completed by its message name
   through the message API. Recorded as a known limitation.
5. **Plain (non-CloudEvents) topics** — consumable through `type: '*'`; id from topic/partition/offset.
