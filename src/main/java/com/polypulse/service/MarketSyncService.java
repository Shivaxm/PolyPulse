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
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * Polymarket's official tag IDs for category filtering.
     * These are the same IDs used by polymarket.com to populate its category tabs.
     */
    private static final Map<String, Integer> DEFAULT_CATEGORY_TAGS = new LinkedHashMap<>();
    static {
        DEFAULT_CATEGORY_TAGS.put("politics", 2);
        DEFAULT_CATEGORY_TAGS.put("crypto", 21);
        DEFAULT_CATEGORY_TAGS.put("sports", 100639);
        DEFAULT_CATEGORY_TAGS.put("finance", 120);
        DEFAULT_CATEGORY_TAGS.put("tech", 1401);
        DEFAULT_CATEGORY_TAGS.put("culture", 596);
        DEFAULT_CATEGORY_TAGS.put("geopolitics", 100265);
    }

    @Scheduled(fixedDelayString = "${polypulse.sync.market-interval-ms:300000}", initialDelay = 5000)
    public void syncMarkets() {
        try {
            String baseUrl = config.getPolymarket().getGammaUrl();
            Set<String> seenConditionIds = new HashSet<>();
            int totalSynced = 0;
            Map<String, Integer> categoryTags = config.getSync().getCategoryTags();
            if (categoryTags == null || categoryTags.isEmpty()) {
                categoryTags = DEFAULT_CATEGORY_TAGS;
            }

            // Phase 1: Fetch events by category tag
            for (Map.Entry<String, Integer> entry : categoryTags.entrySet()) {
                String category = entry.getKey();
                int tagId = entry.getValue();
                int synced = fetchEventsByTag(baseUrl, tagId, category, seenConditionIds);
                totalSynced += synced;
                log.debug("Synced {} markets for category '{}' (tag_id={})", synced, category, tagId);
            }

            // Phase 2: Fetch general events (no tag filter) to catch uncategorized markets
            int generalSynced = fetchGeneralEvents(baseUrl, seenConditionIds);
            totalSynced += generalSynced;

            // Phase 3: Deactivate stale markets not seen in this sync cycle
            int deactivated = deactivateStaleMarkets(seenConditionIds);

            lastSyncAt = Instant.now();
            log.info("Market sync complete: {} active, {} deactivated, categories: {}",
                    totalSynced, deactivated, summarizeCategories(seenConditionIds));
            eventPublisher.publishEvent(new MarketsSyncedEvent(this, totalSynced));

        } catch (Exception e) {
            log.error("Failed to sync markets: {}", e.getMessage(), e);
        }
    }

    /**
     * Fetches events for a specific tag_id with pagination.
     * Polymarket returns up to 50 events per request.
     */
    private int fetchEventsByTag(String baseUrl, int tagId, String category, Set<String> seenConditionIds) {
        int totalSynced = 0;
        int offset = 0;
        int limit = 50;
        int maxPages = 4; // Cap at 200 events per category

        for (int page = 0; page < maxPages; page++) {
            try {
                String url = baseUrl + "/events?active=true&closed=false"
                        + "&tag_id=" + tagId
                        + "&limit=" + limit
                        + "&offset=" + offset;

                String response = restTemplate.getForObject(url, String.class);
                JsonNode events = objectMapper.readTree(response);

                if (!events.isArray() || events.isEmpty()) {
                    break; // No more events
                }

                int pageSynced = 0;
                for (JsonNode event : events) {
                    JsonNode markets = event.get("markets");
                    if (markets == null || !markets.isArray()) continue;

                    for (JsonNode marketNode : markets) {
                        try {
                            String conditionId = marketNode.get("conditionId").asText();
                            seenConditionIds.add(conditionId);
                            pageSynced += processMarket(marketNode, category);
                        } catch (Exception e) {
                            log.debug("Failed to process market: {}", e.getMessage());
                        }
                    }
                }
                totalSynced += pageSynced;

                if (events.size() < limit) {
                    break; // Last page
                }
                offset += limit;

            } catch (Exception e) {
                log.warn("Failed to fetch events for tag {}: {}", tagId, e.getMessage());
                break;
            }
        }
        return totalSynced;
    }

    /**
     * Fetches general events (no tag filter) to pick up markets that
     * don't have a recognized tag. These get "general" category.
     */
    private int fetchGeneralEvents(String baseUrl, Set<String> seenConditionIds) {
        int totalSynced = 0;
        int offset = 0;
        int limit = 50;
        int maxPages = 3; // 150 general events max

        for (int page = 0; page < maxPages; page++) {
            try {
                String url = baseUrl + "/events?active=true&closed=false"
                        + "&limit=" + limit
                        + "&offset=" + offset;

                String response = restTemplate.getForObject(url, String.class);
                JsonNode events = objectMapper.readTree(response);

                if (!events.isArray() || events.isEmpty()) break;

                for (JsonNode event : events) {
                    JsonNode markets = event.get("markets");
                    if (markets == null || !markets.isArray()) continue;

                    // Use Gamma event-level category if available, otherwise "general"
                    String eventCategory = event.has("category") && !event.get("category").isNull()
                            ? event.get("category").asText("").toLowerCase()
                            : null;

                    for (JsonNode marketNode : markets) {
                        try {
                            String conditionId = marketNode.get("conditionId").asText();
                            if (seenConditionIds.contains(conditionId)) {
                                continue; // Already categorized by tag fetch
                            }
                            seenConditionIds.add(conditionId);

                            // Use event category or fall back to "general"
                            String category = (eventCategory != null && !eventCategory.isBlank())
                                    ? eventCategory : "general";
                            totalSynced += processMarket(marketNode, category);
                        } catch (Exception e) {
                            log.debug("Failed to process market: {}", e.getMessage());
                        }
                    }
                }

                if (events.size() < limit) break;
                offset += limit;

            } catch (Exception e) {
                log.warn("Failed to fetch general events: {}", e.getMessage());
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

    private int processMarket(JsonNode marketNode, String category) {
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

        Optional<Market> existing = marketRepository.findByConditionId(conditionId);
        if (existing.isPresent()) {
            Market market = existing.get();
            market.setOutcomeYesPrice(yesPrice);
            market.setOutcomeNoPrice(noPrice);
            market.setVolume24h(volume);
            market.setLastSyncedAt(Instant.now());
            market.setActive(true); // Re-activate if it was deactivated
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
                    .lastSyncedAt(Instant.now())
                    .build();
            marketRepository.save(market);
        }
        return 1;
    }

    private String summarizeCategories(Set<String> seen) {
        // Count how many active markets per category
        List<Market> active = marketRepository.findByActiveTrue();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Market m : active) {
            String cat = m.getCategory() != null ? m.getCategory() : "unknown";
            counts.merge(cat, 1, Integer::sum);
        }
        return counts.toString();
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }
}
