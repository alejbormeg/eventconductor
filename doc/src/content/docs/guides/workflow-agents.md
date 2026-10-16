---
title: Agents in workflows
description: Invoke a published IA catalogue agent with explicit input and durable execution.
---

An `AGENT` step invokes an agent already published from the IA catalogue's Git repository. The
catalogue owns its system prompt, model, allowed MCP/RAG/peer references and JSON output schema.
The workflow chooses the task and explicit input. Credentials never belong in a `.ec` file.

```yaml
- id: assess-case
  type: AGENT
  name: Assess synthetic case
  agent:
    ref: synthetic-case-assessor
    taskPrompt: Assess this case using the available evidence.
    input:
      caseId: "${caseId}"
      case: "${caseData}"
    mcp: [synthetic-case-data]
    outputVariable: assessment
  timeout: 300000
  retries: 0
  preconditionStepId: start
```

`agent.ref` and `agent.outputVariable` are required. `retries` must be `0` for AGENT: a worker failure
is final. Durable recovery and message redelivery retain the same invocation and confirmed tool
results; they do not repeat a failed paid model call. Explicitly restarting a stopped process
creates a new invocation. Input defaults to `{}`; capability selections
`mcp`, `rag`, `peers` inherit when absent and disable when `[]`. Selections can only narrow the
published agent's capabilities. The graphical editor lists the published catalogue and edits
the prompt, input, capability selections and output variable. Definition import fails if the
catalogue cannot be read or a reference/selection is invalid. The Maven plugin validates the
file's structure and expressions; it does not contact the catalogue.

The engine sends the generic `run-agent@1` task on `agent-calls`. Its only task variable is
`__agent`, containing the rendered explicit input, instruction, selections, output variable,
deadline, timeout budget and audit initiator. A retry retains the same invocation ID and rendered
request. Restarting a stopped process creates a new invocation. The worker validates the final
JSON schema and returns only the output variable. Read structured results in a guard with
`json:parse(assessment).decision == 'accepted'`.

Configure catalogue access on the engine with `WORKFLOW_AGENTS_CATALOGUE_URL`,
`WORKFLOW_AGENTS_TOKEN_URI`, `WORKFLOW_AGENTS_CLIENT_ID` and `WORKFLOW_AGENTS_CLIENT_SECRET`.
The catalogue adapter authenticates using client credentials. The editor's same-origin
`GET /api/workflow-agents` requires an authenticated caller and the gateway's editor permissions.
Keep that route behind the authenticated gateway when embedding the engine.

`REVIEW_REQUIRED` pauses the process when an external effect has an uncertain outcome. Automatic
retry, compensation and manual resume/restart remain held until the runtime's authenticated
reconciliation resolves it. A resumed non-timeout invocation renews its configured execution
budget; business deadlines remain absolute. An engine timeout first requests correlated
cancellation and waits for the worker to confirm effects are known; safe cancellation then
becomes native `TIMEOUT`, including `onTimeoutStepId` routing. No business variable is written
while held or on invalid JSON.

The updated engine and durable IA worker must be deployed together. Agent replies echo
`invocationId`, monotonically increasing `stateSequence`, and `reviewResolved: true` only when
reconciliation resumes a held invocation. Ordinary worker messages preserve their legacy shape.
