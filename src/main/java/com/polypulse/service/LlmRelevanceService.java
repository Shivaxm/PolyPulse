package com.polypulse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class LlmRelevanceService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${anthropic.api-key:}")
    private String apiKey;

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";

    /**
     * Represents a market that the LLM validated as relevant to a news headline.
     */
    public record RelevantMarket(Long marketId, String reasoning, double relevanceScore) {}

    /**
     * Given a news headline, checks which candidate markets are genuinely related.
     * Makes a single batch LLM call with all candidates — returns only the relevant ones.
     *
     * @param headline     The news article headline
     * @param candidates   Map of marketId → market question text
     * @return List of markets the LLM confirmed as relevant, with explanations
     */
    public List<RelevantMarket> checkRelevance(String headline, Map<Long, String> candidates) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("ANTHROPIC_API_KEY not configured — falling back to keyword matching (no LLM validation)");
            // Return all candidates as relevant with generic reasoning
            return candidates.entrySet().stream()
                    .map(e -> new RelevantMarket(e.getKey(), "Keyword match (LLM unavailable)", 0.5))
                    .toList();
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        try {
            String prompt = buildPrompt(headline, candidates);
            String response = callClaude(prompt);
            return parseResponse(response, candidates);
        } catch (Exception e) {
            log.error("LLM relevance check failed: {} — falling back to no matches", e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns true if the LLM service is available (API key is configured).
     */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    private String buildPrompt(String headline, Map<Long, String> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("News headline: \"").append(headline).append("\"\n\n");
        sb.append("Prediction markets:\n");

        int idx = 1;
        for (Map.Entry<Long, String> entry : candidates.entrySet()) {
            sb.append(idx).append(". ").append(entry.getValue()).append("\n");
            idx++;
        }

        sb.append("""

Which of these prediction markets could be DIRECTLY affected by this news? A market is directly affected if:
- The news is specifically about the same topic, person, entity, or event as the market
- A reasonable trader would update their position on the market after reading this news
- The connection is not coincidental (e.g. both mentioning "Florida" is NOT enough)

Respond with ONLY a JSON array. Each element should have:
- "index": the market number from the list above
- "score": relevance from 0.0-1.0 (0.7+ means clearly related)
- "reasoning": one sentence explaining the causal connection

If NO markets are genuinely related, respond with an empty array: []

Example response:
[{"index": 3, "score": 0.85, "reasoning": "The Fed rate decision directly impacts inflation expectations, which this market tracks."}]

Respond with ONLY the JSON array, no other text.""");

        return sb.toString();
    }

    private String callClaude(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "claude-haiku-4-5-20251001");
        body.put("max_tokens", 1024);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        try {
            String requestBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    ANTHROPIC_API_URL, HttpMethod.POST, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Claude API returned {}: {}", response.getStatusCode(), response.getBody());
                return "[]";
            }

            // Extract text from Claude's response
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode content = root.get("content");
            if (content != null && content.isArray() && !content.isEmpty()) {
                return content.get(0).get("text").asText("[]");
            }
            return "[]";

        } catch (Exception e) {
            log.error("Claude API call failed: {}", e.getMessage());
            return "[]";
        }
    }

    private List<RelevantMarket> parseResponse(String response, Map<Long, String> candidates) {
        try {
            // Strip markdown fences if present
            String cleaned = response.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").strip();
            }

            JsonNode array = objectMapper.readTree(cleaned);
            if (!array.isArray()) {
                log.warn("LLM response is not a JSON array: {}", cleaned);
                return List.of();
            }

            // Build index-to-marketId mapping
            List<Long> marketIds = new ArrayList<>(candidates.keySet());

            List<RelevantMarket> results = new ArrayList<>();
            for (JsonNode item : array) {
                int index = item.get("index").asInt() - 1; // 1-indexed in prompt → 0-indexed
                double score = item.has("score") ? item.get("score").asDouble() : 0.5;
                String reasoning = item.has("reasoning") ? item.get("reasoning").asText() : "";

                if (index < 0 || index >= marketIds.size()) {
                    log.debug("LLM returned invalid index {}", index + 1);
                    continue;
                }

                // Only accept markets with score >= 0.6
                if (score < 0.6) {
                    log.debug("LLM rated market at {} (below 0.6 threshold), skipping", String.format("%.2f", score));
                    continue;
                }

                results.add(new RelevantMarket(marketIds.get(index), reasoning, score));
            }

            log.info("LLM relevance check: {} of {} candidates validated", results.size(), candidates.size());
            return results;

        } catch (Exception e) {
            log.error("Failed to parse LLM response: {} — response was: {}", e.getMessage(), response);
            return List.of();
        }
    }
}
