package com.muji.worldcup.composer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muji.worldcup.concurrency.NonRetryableException;
import com.muji.worldcup.concurrency.RetryWithBackoff;
import com.muji.worldcup.model.ComposedEmail;
import com.muji.worldcup.model.ContextBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls the Python FastAPI composer service running on localhost.
 * Serializes a ContextBundle to JSON, POSTs to /compose, and
 * deserializes the {"subject", "body"} response.
 *
 * Wrapped in RetryWithBackoff so Gemini 429s are retried transparently.
 */
public class PythonComposerClient {

    private static final Logger log = LoggerFactory.getLogger(PythonComposerClient.class);

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final RetryWithBackoff retry;

    public PythonComposerClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
        // Gemini free tier can 429 — 4 attempts, 30s initial delay, 1.5x backoff
        this.retry = new RetryWithBackoff(4, 30_000, 1.5);
    }

    public ComposedEmail compose(ContextBundle bundle) throws Exception {
        String json = serializeBundle(bundle);

        return retry.execute("compose:" + bundle.subscriber().email(), () -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/compose"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                // Retryable — Gemini rate limit
                throw new RuntimeException("Gemini rate limit (429) from composer service");
            }
            if (response.statusCode() != 200) {
                // Non-retryable — config error, bad request, server crash
                throw new NonRetryableException("Composer returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            String subject = root.path("subject").asText();
            String body    = root.path("body").asText();
            log.info("Composed email for {} — subject: {}", bundle.subscriber().email(), subject);
            return new ComposedEmail(subject, body);
        });
    }

    public boolean isHealthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String serializeBundle(ContextBundle bundle) throws Exception {
        // Flatten into a plain map so field names match what Python expects
        var subscriber = bundle.subscriber();
        var node = mapper.createObjectNode();

        var subNode = node.putObject("subscriber");
        subNode.put("email",         subscriber.email());
        subNode.put("followedTeam",  subscriber.followedTeam());
        subNode.put("followedPlayer", subscriber.followedPlayer());

        node.put("matchDayRecapText",  bundle.matchDayRecapText());
        node.put("teamUpdate",         bundle.teamUpdate());
        node.put("playerUpdate",       bundle.playerUpdate());
        node.put("nextMatchDayPreview", bundle.nextMatchDayPreview());
        node.put("eliminationStatus",  bundle.eliminationStatus().name());

        return mapper.writeValueAsString(node);
    }
}
