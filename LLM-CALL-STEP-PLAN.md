# Plan: `LLM_CALL` step — a model call as a workflow step

> Status: **DECIDED (2026-10-15)** — the engine calls the model directly (no external agent);
> implementation on `feat/llm-call-step`. Built like `HTTP_CALL` (see `HTTP-AND-EVENT-STEPS-PLAN.md`).

## 1. Goal

Stop writing workers whose only job is "render a prompt, call a model, put the answer in a variable".
Classification, extraction, summarization and drafting become one declarative step:

```yaml
- id: triage
  type: LLM_CALL
  name: Triage the complaint
  preconditionStepId: start
  timeout: PT60S
  retries: 2
  llm:
    connection: claude                 # workflow.llm.connections.claude — provider, model, key
    # model: claude-opus-5-5           # optional override of the connection's model
    system: "You triage hotel guest complaints. Answer only with the requested JSON."
    prompt: |
      Guest ${guestName} (stay ${bookingId}) wrote:
      ${complaint}
    maxTokens: 1024                    # optional; default the connection's
    # temperature: 0.2                 # optional; only for models that accept it
    responseSchema:                    # optional: structured output, parsed and validated
      type: object
      required: [category, urgency]
      properties:
        category: { type: string, enum: [room, billing, staff, other] }
        urgency:  { type: integer, minimum: 1, maximum: 5 }
    output:                            # process variable → JEXL over {text, json, usage, model, finishReason}
      category: "json.category"
      urgency: "json.urgency"
    # outputVariable: answer           # the text (or, with responseSchema, the JSON) into one variable
    # usageVariable: triageUsage       # {model, inputTokens, outputTokens, totalTokens} into a variable
```

Decided in conversation (2026-10-15):

| # | Decision |
|---|---|
| A | Name `LLM_CALL`; one nested `llm:` block, like `http:` and `event:`. |
| B | The engine calls the model itself — through a **built-in worker task** `llm-call@1`, exactly as `HTTP_CALL` runs `http-call@1`: never inside the step-over. |
| C | Prompts are the existing JEXL **text templates** (`Templates.renderText`), rendered in the engine at `start()`; validation parses them at build and on import. |
| D | Models are **named connections** in configuration (`workflow.llm.connections.<name>`); the API key is configuration (env var / K8s secret), never in a definition. |
| E | Spring AI `ChatModel` (2.0.1, already in the BOM for MCP); Anthropic and OpenAI-compatible providers, both optional dependencies. |
| F | Output mapping is the `HTTP_CALL` one: JEXL expressions over the result, here `{text, json, usage, model, finishReason}`. |

## 2. What exists to build on

- `HTTP_CALL` end to end: `HttpCall` record + `Step.http`, `HttpRequestRenderer` (render in `start()`,
  `__http` task variable, `problems()` for import), `HttpCallRules` (definition-analysis, shared with the
  Maven plugin), `StepTimeoutDefaults` / stalled-step watch / `canTimeOut`, default topic `http-calls`,
  `modules/worker-http` (auto-configured `TaskRegistration`), `apps/http-worker-standalone-app`.
- The **final-failure** signal (`TaskFailure(code, reason, retryable=false)` → `nonRetryable`), so a
  provider 4xx is not retried while a 429/5xx/timeout spends the step's `retries`.
- `Templates` (shared) and `TemplateSyntax` (definition-analysis).
- `spring-ai-model` 2.0.1 (`ChatModel`, `Prompt`, `ChatOptions`, `StructuredOutputChatOptions`,
  `Usage`); `spring-ai-anthropic` / `spring-ai-openai` 2.0.1 build their SDK clients from options
  (`apiKey`, `baseUrl`, `timeout`, `maxRetries`).
- `json-schema-validator` 3.0.7 (already managed) for the structured output.

## 3. Design

### 3.1 The `llm` block (`LlmCall`)

| Field | Meaning |
|---|---|
| `connection` | **required** — `workflow.llm.connections.<name>` |
| `model` | optional override of the connection's model |
| `system` | optional text template — the system prompt |
| `prompt` | **required** text template — the user message |
| `temperature` | optional, 0–2; omitted unless set (current Claude models reject non-default sampling) |
| `maxTokens` | optional, > 0; default the connection's (4096) |
| `responseSchema` | optional JSON Schema (object) — the answer must be a JSON document valid against it |
| `output` | variable → JEXL over `{text, json, usage, model, finishReason}` |
| `outputVariable` | variable that receives the text (with `responseSchema`: the JSON document) |
| `usageVariable` | variable that receives `{model, inputTokens, outputTokens, totalTokens}` |

At least one of `output` and `outputVariable` (an answer nobody keeps is a definition error).
No `${secret:…}` anywhere in the block, and no credential field (`apiKey`, `api-key`, `token`): secrets
never go into a prompt or a definition.

### 3.2 Engine side

`StepExecution.start()` renders `system` and `prompt` with `LlmRequestRenderer` (same `TemplateContext` as
every template) into the task variable `__llm` (`connection, model, system, prompt, temperature,
maxTokens, responseSchema, output, outputVariable, usageVariable`) and dispatches
`TaskExecutionRequested(taskId = llm-call@1)`; default Kafka topic **`llm-calls`** (a step's `topic`
overrides). The step is then any worker step: timeout (the default step timeout applies, as for
`HTTP_CALL`), retries, compensation, cancellation, the stalled-step watch, `deadline`.

*Fix found while doing this:* `StartStepExecutionUseCase` dispatched with `step.topic()`, so in kafka mode
an `HTTP_CALL` without `topic` went to `downstream` instead of `http-calls` (cancellation already used
`StepExecution.topic()`). It now uses `StepExecution.topic()`, which knows both defaults.

### 3.3 `modules/worker-llm` — the built-in `llm-call@1`

- `LlmWorkerProperties` (`workflow.llm`): `topic` (default `llm-calls`), `connections.<name>`:
  `provider` (`anthropic` | `openai`), `model`, `base-url`, `api-key`, `temperature`, `max-tokens`
  (4096), `timeout` (PT60S), `structured-output` (`prompt` | `native`).
- `ChatModelFactory` → one Spring AI `ChatModel` per connection, cached; provider SDK retries off
  (`maxRetries 0`) so the step's `retries` are the only retry policy and every attempt is visible. The
  `ObservationRegistry` and `MeterRegistry` are handed to the model so Spring AI's own
  `gen_ai.client.operation` / `gen_ai.client.token.usage` observations are recorded.
  `spring-ai-anthropic` and `spring-ai-openai` are **optional** dependencies; a connection whose
  provider is not on the classpath fails the step with `LLM_CONFIG` (final).
- `LlmCallHandler`: builds the `Prompt` (system + user), per-call options from the connection's
  defaults (`mutate()`), calls the model, then:
  - with `responseSchema`: `prompt` mode appends the schema and "answer with one JSON document" to the
    system prompt; `native` mode also passes it as the provider's output schema. The answer is
    parsed (a Markdown fence is tolerated) and validated; an answer that does not parse or validate
    fails the step with **`LLM_INVALID_OUTPUT`** and the validation errors — retryable (another
    sample may be valid), so `retries` apply;
  - maps `output` / `outputVariable` / `usageVariable`.
- **Failures.** A provider error carrying an HTTP status (Anthropic and OpenAI SDK exceptions expose
  `statusCode()`): 400/401/403/404/413/422 → `LLM_<status>`, **final**; 408/409/429/5xx → `LLM_<status>`,
  retryable. Timeouts / I/O → `LLM_IO`, retryable. Missing connection / provider / key → `LLM_CONFIG`,
  final. Prompts and answers are never logged (personal data); the log line has connection, model,
  tokens, duration and outcome.

### 3.4 Observability

Besides Spring AI's observations (`gen_ai_client_operation_seconds_*`, `gen_ai_client_token_usage_total`):

- Timer **`eventconductor.llm.call`** (`eventconductor_llm_call_seconds_*`), tags `workflow`, `step`,
  `connection`, `model`, `outcome` (`success` | `error` | `invalid_output`).
- Counter **`eventconductor.llm.tokens`** (`eventconductor_llm_tokens_total`), tags `workflow`, `step`,
  `connection`, `model`, `type` (`input` | `output`).

Token usage on the execution: there is no output-metadata slot on a step execution today (a worker
reply carries variables and a log line), so usage goes into `usageVariable` when asked for — it then
shows in the process view like any variable — and into the metrics always.

### 3.5 Kafka deployment

`apps/llm-worker-standalone-app` = `worker-kafka` + `worker-llm` + both providers, on topic `llm-calls`,
so model egress (and its API keys) live in pods of their own; built, scanned and pushed by the release
workflow with the others. Embedded deployments add `worker-llm` (and a provider) to their classpath.

### 3.6 Tooling and docs

Schema (`llm` block, enum, `if type == LLM_CALL then required llm`), `LlmCallRules` +
`SpecValidator`, graph (violet node, "sparkle" glyph, `connection · model` badge, read-only "LLM"
field), IDE plugins pick the schema and the bundle up at build time, guide `llm-calls`, step types,
configuration, observability, Kafka topics, AI reference files, CHANGELOG, an example `.ec`.

## 4. Tests

- **Unit**: `LlmCallRulesTest`, `LlmRequestRendererTest`, `SpecValidatorTest` (valid/invalid blocks,
  templates, secret refusal), `LlmCallHandlerTest` with a stub `ChatModel` (text, structured output,
  fenced JSON, invalid output, 4xx final vs 429/5xx retryable, I/O, unknown connection, metrics and
  usage, model override and options).
- **E2E (embedded)**: `LlmCallDispatchE2eTest` (rendered request reaches `llm-call@1`; final failure
  not retried; ordinary failure retried) and `LlmCallE2eTest` (the real handler through
  `TaskDispatcher` with a stub model: structured answer → variables → REPLY; invalid output retried
  then failed).
- No real provider calls in CI.

## 5. Phases

- [x] **P0 — Plan.** This document.
- [x] **P1 — Engine side.** DSL, schema, rules (engine + plugin), rendering, dispatch, topic fix.
- [x] **P2 — `worker-llm`** + `apps/llm-worker-standalone-app`, metrics.
- [x] **P3 — Tooling & docs.** Graph, docs, CHANGELOG, example.

## 6. Not done / later

- Tool calling, multi-turn conversations, streaming, images/documents as input.
- Per-connection rate limiting / token budgets (today: the step's retries and the provider's 429).
- A dist-e2e over Kafka (the transport is `worker-kafka`'s, already covered — same reasoning as HTTP).
