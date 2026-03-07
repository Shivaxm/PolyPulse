package com.polypulse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polypulse.config.PolymarketConfig;
import com.polypulse.event.MarketsSyncedEvent;
import com.polypulse.model.Market;
import com.polypulse.repository.MarketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketSyncService {

    private final MarketRepository marketRepository;
    private final PolymarketConfig config;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private volatile Instant lastSyncAt;

    @Scheduled(fixedDelayString = "${polypulse.sync.market-interval-ms:300000}", initialDelay = 5000)
    public void syncMarkets() {
        try {
            String baseUrl = config.getPolymarket().getGammaUrl();
            Set<String> seenConditionIds = new HashSet<>();
            int totalSynced = fetchEventsByVolume(baseUrl, seenConditionIds);

            // Deactivate stale markets not seen in this sync cycle
            int deactivated = deactivateStaleMarkets(seenConditionIds);

            lastSyncAt = Instant.now();
            log.info("Market sync complete: {} active, {} deactivated", totalSynced, deactivated);
            eventPublisher.publishEvent(new MarketsSyncedEvent(this, totalSynced));

        } catch (Exception e) {
            log.error("Failed to sync markets: {}", e.getMessage(), e);
        }
    }

    /**
     * Fetches active events ordered by 24h volume (most active first).
     * One pass gets the top markets across all categories.
     */
    private int fetchEventsByVolume(String baseUrl, Set<String> seenConditionIds) {
        int totalSynced = 0;
        int offset = 0;
        int limit = 50;
        int maxPages = 12; // 600 events max

        for (int page = 0; page < maxPages; page++) {
            try {
                String url = baseUrl + "/events?active=true&closed=false"
                        + "&order=volume24hr&ascending=false"
                        + "&limit=" + limit
                        + "&offset=" + offset;

                String response = restTemplate.getForObject(url, String.class);
                JsonNode events = objectMapper.readTree(response);

                if (!events.isArray() || events.isEmpty()) {
                    break; // No more events
                }

                int pageSynced = 0;
                for (JsonNode event : events) {
                    Instant createdAtSource = parseEventCreationDate(event);
                    String category = parseEventCategory(event);

                    JsonNode markets = event.get("markets");
                    if (markets == null || !markets.isArray()) continue;

                    for (JsonNode marketNode : markets) {
                        try {
                            String conditionId = marketNode.get("conditionId").asText();
                            seenConditionIds.add(conditionId);
                            pageSynced += processMarket(marketNode, category, createdAtSource);
                        } catch (Exception e) {
                            log.debug("Failed to process market: {}", e.getMessage());
                        }
                    }
                }
                totalSynced += pageSynced;

                if (events.size() < limit) {
                    break;
                }
                offset += limit;

            } catch (Exception e) {
                log.warn("Failed to fetch events at offset {}: {}", offset, e.getMessage());
                break;
            }
        }
        return totalSynced;
    }

    /**
     * Deactivate markets not seen in this sync cycle.
     * Only deactivates markets that haven't been synced in over 1 hour
     * to avoid issues with partial sync failures.
     */
    private int deactivateStaleMarkets(Set<String> seenConditionIds) {
        if (seenConditionIds.size() < 20) {
            // Safety check: if we fetched very few markets, something is wrong.
            // Don't deactivate anything.
            log.warn("Only {} markets seen in sync — skipping deactivation", seenConditionIds.size());
            return 0;
        }

        Instant staleThreshold = Instant.now().minus(Duration.ofHours(2));
        List<Market> activeMarkets = marketRepository.findByActiveTrue();
        int deactivated = 0;

        for (Market market : activeMarkets) {
            if (!seenConditionIds.contains(market.getConditionId())
                    && market.getLastSyncedAt().isBefore(staleThreshold)) {
                market.setActive(false);
                marketRepository.save(market);
                deactivated++;
            }
        }

        return deactivated;
    }

    private int processMarket(JsonNode marketNode, String category, Instant createdAtSource) {
        String conditionId = marketNode.get("conditionId").asText();

        String clobTokenId;
        try {
            JsonNode clobTokenIdsNode = marketNode.get("clobTokenIds");
            if (clobTokenIdsNode == null) return 0;
            JsonNode tokenArray;
            if (clobTokenIdsNode.isArray()) {
                tokenArray = clobTokenIdsNode;
            } else {
                tokenArray = objectMapper.readTree(clobTokenIdsNode.asText());
            }
            if (!tokenArray.isArray() || tokenArray.isEmpty()) return 0;
            clobTokenId = tokenArray.get(0).asText();
        } catch (Exception e) {
            log.debug("Failed to parse clobTokenIds: {}", e.getMessage());
            return 0;
        }

        String question = marketNode.has("question") ? marketNode.get("question").asText() : "";
        String slug = marketNode.has("slug") ? marketNode.get("slug").asText(null) : null;

        BigDecimal yesPrice = null;
        BigDecimal noPrice = null;
        if (marketNode.has("outcomePrices")) {
            try {
                String pricesStr = marketNode.get("outcomePrices").asText();
                JsonNode prices = objectMapper.readTree(pricesStr);
                if (prices.isArray() && prices.size() >= 2) {
                    yesPrice = new BigDecimal(prices.get(0).asText());
                    noPrice = new BigDecimal(prices.get(1).asText());
                }
            } catch (Exception e) {
                log.debug("Failed to parse outcomePrices: {}", e.getMessage());
            }
        }

        BigDecimal volume = null;
        if (marketNode.has("volume24hr")) {
            try {
                volume = new BigDecimal(marketNode.get("volume24hr").asText("0"));
            } catch (Exception ignored) {}
        }

        BigDecimal liquidity = null;
        if (marketNode.has("liquidity")) {
            try {
                liquidity = new BigDecimal(marketNode.get("liquidity").asText("0"));
            } catch (Exception ignored) {}
        }

        Optional<Market> existing = marketRepository.findByConditionId(conditionId);
        if (existing.isPresent()) {
            Market market = existing.get();
            market.setOutcomeYesPrice(yesPrice);
            market.setOutcomeNoPrice(noPrice);
            market.setVolume24h(volume);
            market.setLiquidity(liquidity);
            market.setLastSyncedAt(Instant.now());
            market.setActive(true); // Re-activate if it was deactivated
            if (createdAtSource != null && market.getCreatedAtSource() == null) {
                market.setCreatedAtSource(createdAtSource);
            }
            // Always update category — tag-based categories are more reliable
            market.setCategory(category);
            marketRepository.save(market);
        } else {
            Market market = Market.builder()
                    .conditionId(conditionId)
                    .clobTokenId(clobTokenId)
                    .question(question)
                    .slug(slug)
                    .category(category)
                    .active(true)
                    .outcomeYesPrice(yesPrice)
                    .outcomeNoPrice(noPrice)
                    .volume24h(volume)
                    .liquidity(liquidity)
                    .createdAtSource(createdAtSource)
                    .lastSyncedAt(Instant.now())
                    .build();
            marketRepository.save(market);
        }
        return 1;
    }

    private Instant parseEventCreationDate(JsonNode event) {
        if (event == null || !event.has("creationDate") || event.get("creationDate").isNull()) {
            return null;
        }
        try {
            return Instant.parse(event.get("creationDate").asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String parseEventCategory(JsonNode event) {
        if (event != null && event.has("category") && !event.get("category").isNull()) {
            String rawCategory = event.get("category").asText("").toLowerCase().trim();
            if (!rawCategory.isBlank()) {
                return rawCategory;
            }
        }

        if (event != null && event.has("tags") && event.get("tags").isArray()) {
            JsonNode tags = event.get("tags");
            for (JsonNode tag : tags) {
                if (tag.has("slug") && !tag.get("slug").isNull()) {
                    String slug = tag.get("slug").asText("").toLowerCase().trim();
                    if (!slug.isBlank()) {
                        return slug;
                    }
                }
                if (tag.has("label") && !tag.get("label").isNull()) {
                    String label = tag.get("label").asText("").toLowerCase().trim();
                    if (!label.isBlank()) {
                        return label;
                    }
                }
            }
        }

        return "general";
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }
}
