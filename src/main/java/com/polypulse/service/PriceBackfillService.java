package com.polypulse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polypulse.config.PolymarketConfig;
import com.polypulse.model.Market;
import com.polypulse.model.PriceTick;
import com.polypulse.repository.PriceTickRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class PriceBackfillService {

    private final PriceTickRepository priceTickRepository;
    private final PolymarketConfig config;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private final Set<Long> backfilledMarkets = ConcurrentHashMap.newKeySet();

    public boolean backfillIfNeeded(Market market, Instant since) {
        if (backfilledMarkets.contains(market.getId())) {
            return false;
        }

        List<PriceTick> existing = priceTickRepository
                .findByMarketIdAndTimestampBetweenOrderByTimestampDesc(
                        market.getId(), since, Instant.now());

        if (existing.size() >= 5) {
            backfilledMarkets.add(market.getId());
            return false;
        }

        try {
            String url = config.getPolymarket().getClobUrl()
                    + "/prices-history?market=" + market.getConditionId()
                    + "&interval=max&fidelity=hour";

            log.info("Backfilling price history for market {} from CLOB API", market.getId());
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            JsonNode history = root.has("history") ? root.get("history") : root;
            if (!history.isArray()) {
                log.warn("Unexpected price history response format for market {}", market.getId());
                backfilledMarkets.add(market.getId());
                return false;
            }

            List<PriceTick> ticks = new ArrayList<>();
            for (JsonNode point : history) {
                try {
                    Instant timestamp;
                    if (point.has("t")) {
                        JsonNode tNode = point.get("t");
                        if (tNode.isNumber()) {
                            long epochSeconds = tNode.asLong();
                            if (epochSeconds > 1_000_000_000_000L) {
                                timestamp = Instant.ofEpochMilli(epochSeconds);
                            } else {
                                timestamp = Instant.ofEpochSecond(epochSeconds);
                            }
                        } else {
                            timestamp = Instant.parse(tNode.asText());
                        }
                    } else {
                        continue;
                    }

                    if (timestamp.isBefore(since)) continue;

                    BigDecimal price;
                    if (point.has("p")) {
                        price = new BigDecimal(point.get("p").asText());
                    } else {
                        continue;
                    }

                    ticks.add(PriceTick.builder()
                            .marketId(market.getId())
                            .price(price)
                            .timestamp(timestamp)
                            .build());
                } catch (Exception e) {
                    log.debug("Skipping malformed price history point: {}", e.getMessage());
                }
            }

            if (!ticks.isEmpty()) {
                priceTickRepository.saveAll(ticks);
                log.info("Backfilled {} price points for market {}", ticks.size(), market.getId());
            }

            backfilledMarkets.add(market.getId());
            return !ticks.isEmpty();

        } catch (Exception e) {
            log.warn("Failed to backfill price history for market {}: {}", market.getId(), e.getMessage());
            backfilledMarkets.add(market.getId());
            return false;
        }
    }
}
