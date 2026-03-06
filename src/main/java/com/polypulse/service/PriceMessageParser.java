package com.polypulse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class PriceMessageParser {

    private final ObjectMapper objectMapper;

    public record ParsedTick(String assetId, BigDecimal price, Instant timestamp) {}

    /**
     * Parses a raw WebSocket message string into a list of price ticks.
     * Handles both single events and arrays of events.
     */
    public List<ParsedTick> parse(String message, Map<String, Long> knownTokens) {
        List<ParsedTick> results = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(message);
            if (node.isArray()) {
                for (JsonNode item : node) {
                    results.addAll(processEvent(item, knownTokens));
                }
            } else {
                results.addAll(processEvent(node, knownTokens));
            }
        } catch (Exception e) {
            log.debug("Failed to parse WS message: {}", e.getMessage());
        }
        return results;
    }

    List<ParsedTick> processEvent(JsonNode node, Map<String, Long> knownTokens) {
        String eventType = node.has("event_type") ? node.get("event_type").asText() : "";
        return switch (eventType) {
            case "last_trade_price" -> processLastTradePrice(node, knownTokens);
            case "price_change" -> processPriceChange(node, knownTokens);
            default -> List.of();
        };
    }

    List<ParsedTick> processLastTradePrice(JsonNode node, Map<String, Long> knownTokens) {
        String assetId = node.has("asset_id") ? node.get("asset_id").asText() : null;
        if (assetId == null || !knownTokens.containsKey(assetId)) return List.of();

        try {
            BigDecimal price = new BigDecimal(node.get("price").asText());
            Instant timestamp = parseTimestamp(node);
            return List.of(new ParsedTick(assetId, price, timestamp));
        } catch (Exception e) {
            return List.of();
        }
    }

    List<ParsedTick> processPriceChange(JsonNode node, Map<String, Long> knownTokens) {
        JsonNode priceChanges = node.get("price_changes");
        if (priceChanges == null || !priceChanges.isArray()) {
            return processLegacyPriceChange(node, knownTokens);
        }

        Instant timestamp = parseTimestamp(node);
        List<ParsedTick> results = new ArrayList<>();

        for (JsonNode change : priceChanges) {
            String assetId = change.has("asset_id") ? change.get("asset_id").asText() : null;
            if (assetId == null || !knownTokens.containsKey(assetId)) continue;

            try {
                String bestBidStr = change.has("best_bid") ? change.get("best_bid").asText() : null;
                String bestAskStr = change.has("best_ask") ? change.get("best_ask").asText() : null;
                if (bestBidStr == null || bestAskStr == null) continue;

                BigDecimal bestBid = new BigDecimal(bestBidStr);
                BigDecimal bestAsk = new BigDecimal(bestAskStr);
                if (bestBid.compareTo(BigDecimal.ZERO) == 0 || bestAsk.compareTo(BigDecimal.ZERO) == 0) continue;

                BigDecimal price = bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
                results.add(new ParsedTick(assetId, price, timestamp));
            } catch (Exception e) {
                // skip malformed element
            }
        }
        return results;
    }

    List<ParsedTick> processLegacyPriceChange(JsonNode node, Map<String, Long> knownTokens) {
        String assetId = node.has("asset_id") ? node.get("asset_id").asText() : null;
        if (assetId == null || !knownTokens.containsKey(assetId)) return List.of();

        JsonNode priceNode = node.get("price");
        if (priceNode == null) return List.of();

        try {
            BigDecimal price = new BigDecimal(priceNode.asText());
            Instant timestamp = parseTimestamp(node);
            return List.of(new ParsedTick(assetId, price, timestamp));
        } catch (Exception e) {
            return List.of();
        }
    }

    Instant parseTimestamp(JsonNode node) {
        if (node.has("timestamp")) {
            try {
                String tsStr = node.get("timestamp").asText();
                long tsMillis = Long.parseLong(tsStr);
                return Instant.ofEpochMilli(tsMillis);
            } catch (Exception e) {
                try {
                    return Instant.parse(node.get("timestamp").asText());
                } catch (Exception ignored) {
                    // fallback below
                }
            }
        }
        return Instant.now();
    }
}
