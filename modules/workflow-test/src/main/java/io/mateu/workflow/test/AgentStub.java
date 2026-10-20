package io.mateu.workflow.test;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the agent runtime answers an {@code AGENT} step's {@code run-agent@1} in a test — no catalogue, no
 * runtime, no model. Each invocation (a step's, or one per forEach item) takes the next answer; the last one
 * repeats, as for {@link TaskStub}. Every reply names the invocation, as the real runtime's do.
 *
 * <pre>{@code
 * runtime.whenAgent("assess").thenOutput("{\"decision\":\"accept\"}");
 *
 * // A review: the step is REVIEW_REQUIRED and the process PAUSED until the runtime reconciles it,
 * // which resolveAgentReview does — with the next answer of the script.
 * runtime.whenAgent("assess").thenReviewRequired().thenOutput("{\"decision\":\"refer\"}");
 * runtime.resolveAgentReview(process, "assess");
 * }</pre>
 *
 * <p>The rendered request is recorded ({@link #requests()}: {@code agentRef}, {@code taskPrompt}, {@code input},
 * {@code outputVariable} …). The output is checked as the engine checks it — one variable, the step's
 * {@code outputVariable}, valid JSON — not against the agent's output schema, which is the runtime's.
 */
public final class AgentStub {

    /** One scripted answer of the runtime. */
    sealed interface Answer {
    }

    /** The agent finished: its result, a JSON document, becomes the step's {@code outputVariable}. */
    record Output(String json) implements Answer {
    }

    /** The agent failed; AGENT failures are final (the step declares {@code retries: 0}). */
    record Failure(String code, String message) implements Answer {
    }

    /** The outcome is uncertain: the step goes REVIEW_REQUIRED and the process PAUSED. */
    record ReviewRequired() implements Answer {
    }

    /** The runtime confirms it stopped the invocation (after a cancellation, or a timeout's hold). */
    record Cancelled() implements Answer {
    }

    /** The runtime says nothing more: the invocation stays in flight (until its timeout holds it). */
    record NoReply() implements Answer {
    }

    private final String stepId;
    private final List<Answer> answers = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    AgentStub(String stepId) {
        this.stepId = stepId;
    }

    /** The agent completes with this JSON document as its result. */
    public AgentStub thenOutput(String json) {
        answers.add(new Output(json));
        return this;
    }

    /** The agent completes with this value (a map, a list …) as its result, written as JSON. */
    public AgentStub thenOutput(Object value) {
        answers.add(new Output(value instanceof String text ? text : Values.text(value)));
        return this;
    }

    /** The runtime asks for a review: the step is held until {@link WorkflowTestRuntime#resolveAgentReview}. */
    public AgentStub thenReviewRequired() {
        answers.add(new ReviewRequired());
        return this;
    }

    /** The agent fails with {@code code} (and an optional message). */
    public AgentStub thenFail(String code) {
        return thenFail(code, null);
    }

    /** The agent fails with {@code code: message}. */
    public AgentStub thenFail(String code, String message) {
        answers.add(new Failure(code, message));
        return this;
    }

    /** The runtime confirms the invocation was stopped — what resolves a hold after a cancel or a timeout. */
    public AgentStub thenCancelled() {
        answers.add(new Cancelled());
        return this;
    }

    /** The runtime never answers: the step stays in flight, and its timeout holds it for review. */
    public AgentStub thenNoReply() {
        answers.add(new NoReply());
        return this;
    }

    /** Every request rendered for this step, in order (one per invocation). */
    public List<Map<String, Object>> requests() {
        return List.copyOf(requests);
    }

    /** The last request rendered, or null. */
    public Map<String, Object> lastRequest() {
        return requests.isEmpty() ? null : requests.getLast();
    }

    /** A new invocation: records its request and takes its first answer. */
    Answer invoked(JsonNode request) {
        requests.add(Values.toMap(request));
        return next();
    }

    /** The next answer of the script (the last one repeats); a failure when the stub has none. */
    Answer next() {
        if (answers.isEmpty()) {
            return new Failure("AGENT_STUB", "AGENT step '" + stepId + "' is stubbed but has no answer");
        }
        return answers.get(Math.min(calls.getAndIncrement(), answers.size() - 1));
    }
}
