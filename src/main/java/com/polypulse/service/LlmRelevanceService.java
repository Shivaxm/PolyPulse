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

    public record RelevantMarket(Long marketId, String reasoning, double relevanceScore) {}

    public List<RelevantMarket> checkRelevance(String headline, Map<Long, String> candidates) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("ANTHROPIC_API_KEY not configured — LLM validation disabled, returning no matches");
            return List.of();
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        try {
            String prompt = buildPrompt(headline, candidates);
            String response = callClaude(prompt);
            return parseResponse(response, candidates);
        } catch (Exception e) {
            log.error("LLM relevance check failed: {} — returning no matches", e.getMessage());
            return List.of();
        }
    }

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

Your task: identify which prediction markets are DIRECTLY and SPECIFICALLY affected by this news headline. Be VERY strict — most headlines do NOT directly affect most markets.

A market is DIRECTLY affected ONLY if ALL of these are true:
1. The news is about the SPECIFIC topic, person, entity, or event named in the market question
2. The news provides NEW INFORMATION that would change the probability of the market's outcome
3. A professional trader would IMMEDIATELY adjust their position on this specific market after reading this headline

REJECT these common false positives:
- Same country/location but different topics (e.g. "SpaceX launches from Florida" does NOT affect "Florida Panthers win Stanley Cup")
- Same broad sector (e.g. "Dow Jones falls" does NOT directly affect "Will specific crypto token reach $X valuation" — general market sentiment is too indirect)
- General economic news matched to unrelated markets (e.g. "US wholesale prices rise" does NOT affect "Harvey Weinstein sentenced" or "US-Russia military clash")
- Sharing a keyword like "US", "China", "Trump", or "market" is NOT enough — the news must be ABOUT the specific question the market asks
- News about REACTIONS or CONSEQUENCES of an event is NOT the same as news about the event itself (e.g. "Wall Street drops amid Iran war" is about financial markets reacting — it does NOT affect "Will Iran strike Israel on March 9?" because stock market performance doesn't change the probability of a military strike. Similarly, "Oil prices surge due to Middle East tensions" does NOT affect specific geopolitical outcome markets)
- The headline must provide information about whether the specific OUTCOME in the market question is more or less likely. Ask: "Does this headline contain new facts about WHETHER the thing will happen?" If it only describes consequences of something already happening, it is NOT a direct match

When in doubt, do NOT include the market. It is much better to return an empty array than to include a weak connection.

Respond with ONLY a JSON array. Each element:
- "index": market number from the list
- "score": 0.0-1.0 (only include markets you'd score 0.75 or higher)
- "reasoning": one sentence explaining the SPECIFIC causal link

If NO markets are genuinely and directly related, respond with: []

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
        body.put("system", "You are a strict financial relevance classifier. You ONLY return markets that have a direct, specific, first-order causal connection to the news headline. You are skeptical by default and prefer returning an empty array over including weak or indirect connections. General market conditions, shared geography, shared sector, or downstream consequences of events are NEVER sufficient. A headline must provide new information about WHETHER the market's outcome will happen — not merely reference the same topic in a different context.");
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
            String cleaned = response.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").strip();
            }

            JsonNode array = objectMapper.readTree(cleaned);
            if (!array.isArray()) {
                log.warn("LLM response is not a JSON array: {}", cleaned);
                return List.of();
            }

            List<Long> marketIds = new ArrayList<>(candidates.keySet());

            List<RelevantMarket> results = new ArrayList<>();
            for (JsonNode item : array) {
                int index = item.get("index").asInt() - 1;
                double score = item.has("score") ? item.get("score").asDouble() : 0.5;
                String reasoning = item.has("reasoning") ? item.get("reasoning").asText() : "";

                if (index < 0 || index >= marketIds.size()) {
                    log.debug("LLM returned invalid index {}", index + 1);
                    continue;
                }

                // Raised threshold: only accept clearly relevant markets
                if (score < 0.75) {
                    log.debug("LLM rated market at {} (below 0.75 threshold), skipping: {}",
                            String.format("%.2f", score),
                            candidates.get(marketIds.get(index)));
                    continue;
                }

                results.add(new RelevantMarket(marketIds.get(index), reasoning, score));
            }

            log.info("LLM relevance check: {} of {} candidates passed (threshold 0.75)",
                    results.size(), candidates.size());
            return results;

        } catch (Exception e) {
            log.error("Failed to parse LLM response: {} — response was: {}", e.getMessage(), response);
            return List.of();
        }
    }
}
