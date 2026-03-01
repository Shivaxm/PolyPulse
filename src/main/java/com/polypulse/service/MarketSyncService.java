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
import java.time.Instant;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketSyncService {

    private final MarketRepository marketRepository;
    private final PolymarketConfig config;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile Instant lastSyncAt;

    @Scheduled(fixedDelayString = "${polypulse.sync.market-interval-ms:300000}", initialDelay = 5000)
    public void syncMarkets() {
        try {
            String url = config.getPolymarket().getGammaUrl()
                    + "/events?active=true&closed=false&limit=50";

            RestTemplate restTemplate = new RestTemplate();
            String response = restTemplate.getForObject(url, String.class);
            JsonNode events = objectMapper.readTree(response);

            int synced = 0;
            for (JsonNode event : events) {
                JsonNode markets = event.get("markets");
                if (markets == null || !markets.isArray()) continue;

                String category = event.has("category") ? event.get("category").asText(null) : null;

                for (JsonNode marketNode : markets) {
                    try {
                        synced += processMarket(marketNode, category);
                    } catch (Exception e) {
                        log.warn("Failed to process market: {}", e.getMessage());
                    }
                }
            }

            lastSyncAt = Instant.now();
            log.info("Synced {} markets from Polymarket Gamma API", synced);
            eventPublisher.publishEvent(new MarketsSyncedEvent(this, synced));

        } catch (Exception e) {
            log.error("Failed to sync markets from Gamma API: {}", e.getMessage(), e);
        }
    }

    private int processMarket(JsonNode marketNode, String category) {
        String conditionId = marketNode.get("conditionId").asText();

        // Get the first clobTokenId (field is a JSON string, not an array node)
        String clobTokenId;
        try {
            JsonNode clobTokenIdsNode = marketNode.get("clobTokenIds");
            if (clobTokenIdsNode == null) return 0;
            // Could be a JSON string "["abc","def"]" or an actual array node
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

        // Parse outcome prices
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
            } catch (Exception e) {
                // ignore
            }
        }

        Optional<Market> existing = marketRepository.findByConditionId(conditionId);
        if (existing.isPresent()) {
            Market market = existing.get();
            market.setOutcomeYesPrice(yesPrice);
            market.setOutcomeNoPrice(noPrice);
            market.setVolume24h(volume);
            market.setLastSyncedAt(Instant.now());
            if (category != null) market.setCategory(category);
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

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }
}
