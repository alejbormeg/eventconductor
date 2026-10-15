package io.mateu.workflow.llmworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The model egress worker: serves the built-in llm-call task of LLM_CALL steps from the llm-calls topic. */
@SpringBootApplication
public class LlmWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmWorkerApplication.class, args);
    }
}
