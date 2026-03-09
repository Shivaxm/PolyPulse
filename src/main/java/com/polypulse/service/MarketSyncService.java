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
     * Maps granular Polymarket tag/category strings into a smaller set
     * of display categories for dashboard filtering.
     */
    private static final Map<String, String> CATEGORY_KEYWORDS = new LinkedHashMap<>();
    static {
        // Politics
        CATEGORY_KEYWORDS.put("politic", "politics");
        CATEGORY_KEYWORDS.put("election", "politics");
        CATEGORY_KEYWORDS.put("senate", "politics");
        CATEGORY_KEYWORDS.put("congress", "politics");
        CATEGORY_KEYWORDS.put("governor", "politics");
        CATEGORY_KEYWORDS.put("parliament", "politics");
        CATEGORY_KEYWORDS.put("cabinet", "politics");
        CATEGORY_KEYWORDS.put("supreme-court", "politics");
        CATEGORY_KEYWORDS.put("legislation", "politics");
        CATEGORY_KEYWORDS.put("democrat", "politics");
        CATEGORY_KEYWORDS.put("republican", "politics");
        CATEGORY_KEYWORDS.put("trump", "politics");
        CATEGORY_KEYWORDS.put("biden", "politics");
        CATEGORY_KEYWORDS.put("presidential", "politics");
        CATEGORY_KEYWORDS.put("primary", "politics");
        CATEGORY_KEYWORDS.put("vote", "politics");

        // Geopolitics
        CATEGORY_KEYWORDS.put("geopolitic", "geopolitics");
        CATEGORY_KEYWORDS.put("iran", "geopolitics");
        CATEGORY_KEYWORDS.put("ukraine", "geopolitics");
        CATEGORY_KEYWORDS.put("russia", "geopolitics");
        CATEGORY_KEYWORDS.put("china", "geopolitics");
        CATEGORY_KEYWORDS.put("war", "geopolitics");
        CATEGORY_KEYWORDS.put("nato", "geopolitics");
        CATEGORY_KEYWORDS.put("middle-east", "geopolitics");
        CATEGORY_KEYWORDS.put("conflict", "geopolitics");
        CATEGORY_KEYWORDS.put("sanctions", "geopolitics");
        CATEGORY_KEYWORDS.put("military", "geopolitics");
        CATEGORY_KEYWORDS.put("ceasefire", "geopolitics");

        // Sports
        CATEGORY_KEYWORDS.put("sport", "sports");
        CATEGORY_KEYWORDS.put("nba", "sports");
        CATEGORY_KEYWORDS.put("nfl", "sports");
        CATEGORY_KEYWORDS.put("mlb", "sports");
        CATEGORY_KEYWORDS.put("nhl", "sports");
        CATEGORY_KEYWORDS.put("soccer", "sports");
        CATEGORY_KEYWORDS.put("football", "sports");
        CATEGORY_KEYWORDS.put("basketball", "sports");
        CATEGORY_KEYWORDS.put("baseball", "sports");
        CATEGORY_KEYWORDS.put("tennis", "sports");
        CATEGORY_KEYWORDS.put("march-madness", "sports");
        CATEGORY_KEYWORDS.put("premier-league", "sports");
        CATEGORY_KEYWORDS.put("champions-league", "sports");
        CATEGORY_KEYWORDS.put("world-cup", "sports");
        CATEGORY_KEYWORDS.put("league", "sports");
        CATEGORY_KEYWORDS.put("ncaa", "sports");
        CATEGORY_KEYWORDS.put("ufc", "sports");
        CATEGORY_KEYWORDS.put("boxing", "sports");
        CATEGORY_KEYWORDS.put("f1", "sports");
        CATEGORY_KEYWORDS.put("formula", "sports");

        // Crypto
        CATEGORY_KEYWORDS.put("crypto", "crypto");
        CATEGORY_KEYWORDS.put("bitcoin", "crypto");
        CATEGORY_KEYWORDS.put("ethereum", "crypto");
        CATEGORY_KEYWORDS.put("solana", "crypto");
        CATEGORY_KEYWORDS.put("defi", "crypto");
        CATEGORY_KEYWORDS.put("token", "crypto");
        CATEGORY_KEYWORDS.put("nft", "crypto");
        CATEGORY_KEYWORDS.put("blockchain", "crypto");
        CATEGORY_KEYWORDS.put("stablecoin", "crypto");
        CATEGORY_KEYWORDS.put("altcoin", "crypto");

        // Finance / Economy
        CATEGORY_KEYWORDS.put("financ", "finance");
        CATEGORY_KEYWORDS.put("econom", "finance");
        CATEGORY_KEYWORDS.put("fed", "finance");
        CATEGORY_KEYWORDS.put("rate-cut", "finance");
        CATEGORY_KEYWORDS.put("interest-rate", "finance");
        CATEGORY_KEYWORDS.put("inflation", "finance");
        CATEGORY_KEYWORDS.put("stock", "finance");
        CATEGORY_KEYWORDS.put("market-cap", "finance");
        CATEGORY_KEYWORDS.put("gdp", "finance");
        CATEGORY_KEYWORDS.put("recession", "finance");
        CATEGORY_KEYWORDS.put("tariff", "finance");
        CATEGORY_KEYWORDS.put("trade-war", "finance");
        CATEGORY_KEYWORDS.put("ipo", "finance");
        CATEGORY_KEYWORDS.put("earnings", "finance");

        // Tech / AI / Science
        CATEGORY_KEYWORDS.put("tech", "tech");
        CATEGORY_KEYWORDS.put("ai", "tech");
        CATEGORY_KEYWORDS.put("artificial-intelligence", "tech");
        CATEGORY_KEYWORDS.put("openai", "tech");
        CATEGORY_KEYWORDS.put("google", "tech");
        CATEGORY_KEYWORDS.put("apple", "tech");
        CATEGORY_KEYWORDS.put("spacex", "tech");
        CATEGORY_KEYWORDS.put("science", "tech");
        CATEGORY_KEYWORDS.put("climate", "tech");
        CATEGORY_KEYWORDS.put("energy", "tech");

        // Culture / Entertainment
        CATEGORY_KEYWORDS.put("culture", "culture");
        CATEGORY_KEYWORDS.put("entertainment", "culture");
        CATEGORY_KEYWORDS.put("movie", "culture");
        CATEGORY_KEYWORDS.put("music", "culture");
        CATEGORY_KEYWORDS.put("oscar", "culture");
        CATEGORY_KEYWORDS.put("celebrity", "culture");
        CATEGORY_KEYWORDS.put("viral", "culture");
        CATEGORY_KEYWORDS.put("social-media", "culture");
        CATEGORY_KEYWORDS.put("pop-culture", "culture");
        CATEGORY_KEYWORDS.put("tv", "culture");
        CATEGORY_KEYWORDS.put("gaming", "culture");
    }

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
        if (question.isBlank()) return 0;
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

        boolean isClosed = false;
        if (marketNode.has("closed") && marketNode.get("closed").asBoolean(false)) {
            isClosed = true;
        }
        if (marketNode.has("acceptingOrders") && !marketNode.get("acceptingOrders").asBoolean(true)) {
            isClosed = true;
        }

        Instant endDate = null;
        if (marketNode.has("endDate") && !marketNode.get("endDate").isNull()) {
            try {
                endDate = Instant.parse(marketNode.get("endDate").asText());
            } catch (Exception ignored) {
            }
        }

        boolean isExpired = isClosed || (endDate != null && endDate.isBefore(Instant.now()));
        if (yesPrice != null && (yesPrice.doubleValue() >= 0.97 || yesPrice.doubleValue() <= 0.03)) {
            isExpired = true;
        }

        Optional<Market> existing = marketRepository.findByConditionId(conditionId);
        if (existing.isPresent()) {
            Market market = existing.get();
            market.setOutcomeYesPrice(yesPrice);
            market.setOutcomeNoPrice(noPrice);
            market.setVolume24h(volume);
            market.setLiquidity(liquidity);
            market.setEndDate(endDate);
            market.setLastSyncedAt(Instant.now());
            market.setActive(!isExpired);
            if (createdAtSource != null && market.getCreatedAtSource() == null) {
                market.setCreatedAtSource(createdAtSource);
            }
            // Always update category — tag-based categories are more reliable
            market.setCategory(category);
            marketRepository.save(market);
        } else {
            if (isExpired || yesPrice == null) {
                return 0;
            }

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
                    .endDate(endDate)
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
        // First: use direct event category, if available.
        if (event != null && event.has("category") && !event.get("category").isNull()) {
            String rawCategory = event.get("category").asText("").toLowerCase().trim();
            if (!rawCategory.isBlank()) {
                String normalized = normalizeCategory(rawCategory);
                if (normalized != null) return normalized;
            }
        }

        // Second: derive from tag slug/label.
        if (event != null && event.has("tags") && event.get("tags").isArray()) {
            for (JsonNode tag : event.get("tags")) {
                String slug = tag.has("slug") ? tag.get("slug").asText("").toLowerCase().trim() : "";
                String label = tag.has("label") ? tag.get("label").asText("").toLowerCase().trim() : "";

                String normalized = normalizeCategory(slug);
                if (normalized != null) return normalized;

                normalized = normalizeCategory(label);
                if (normalized != null) return normalized;
            }
        }

        // Third: fallback to title matching.
        if (event != null && event.has("title") && !event.get("title").isNull()) {
            String normalized = normalizeCategory(event.get("title").asText("").toLowerCase());
            if (normalized != null) return normalized;
        }

        return "other";
    }

    private String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (Map.Entry<String, String> entry : CATEGORY_KEYWORDS.entrySet()) {
            if (raw.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }
}
