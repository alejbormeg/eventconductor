package io.mateu.workflow.infra.out.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.WorkflowAgentCatalogue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Service-account authenticated catalogue read. A failed fetch never silently accepts a definition. */
@Component
@ConditionalOnProperty(name = "workflow.agents.catalogue-url")
public final class HttpWorkflowAgentCatalogue implements WorkflowAgentCatalogue {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final URI catalogue;
    private final URI tokenUri;
    private final String clientId;
    private final String clientSecret;

    public HttpWorkflowAgentCatalogue(
            @Value("${workflow.agents.catalogue-url:${WORKFLOW_AGENTS_CATALOGUE_URL:}}") String catalogue,
            @Value("${workflow.agents.token-uri:${WORKFLOW_AGENTS_TOKEN_URI:}}") String tokenUri,
            @Value("${workflow.agents.client-id:${WORKFLOW_AGENTS_CLIENT_ID:workflow-agent-executor}}") String clientId,
            @Value("${workflow.agents.client-secret:${WORKFLOW_AGENTS_CLIENT_SECRET:}}") String clientSecret) {
        this.catalogue = URI.create(catalogue);
        this.tokenUri = URI.create(tokenUri);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override public List<Agent> list() {
        try {
            var body = "grant_type=client_credentials&client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret);
            var token = client.send(HttpRequest.newBuilder(tokenUri).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (token.statusCode() != 200) throw new IllegalStateException("Catalogue service authentication failed");
            var accessToken = json.readTree(token.body()).path("access_token").asText();
            if (accessToken.isBlank()) throw new IllegalStateException("Catalogue service returned no access token");
            var response = client.send(HttpRequest.newBuilder(catalogue).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + accessToken).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Workflow agent catalogue is unavailable");
            return json.readValue(response.body(), new TypeReference<List<Agent>>() { });
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workflow agent catalogue read interrupted", error);
        } catch (Exception error) {
            // Response bodies and secrets are deliberately excluded from the outward failure.
            throw new IllegalStateException("Could not read the published workflow agent catalogue", error);
        }
    }

    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
}
