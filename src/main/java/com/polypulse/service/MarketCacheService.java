package com.polypulse.service;

import com.polypulse.dto.MarketDTO;
import com.polypulse.model.Market;
import com.polypulse.repository.CorrelationRepository;
import com.polypulse.repository.MarketRepository;
import com.polypulse.repository.PriceTickRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketCacheService {

    private final MarketRepository marketRepository;
    private final PriceTickRepository priceTickRepository;
    private final CorrelationRepository correlationRepository;

    // In-memory caches — warm after first background computation
    private volatile List<Market> cachedMarkets = List.of();
    private volatile Map<Long, List<MarketDTO.SparklinePoint>> cachedSparklines = Map.of();
    private volatile Set<Long> cachedCorrelationMarketIds = Set.of();
    private volatile Instant lastComputedAt = null;

    public List<Market> getActiveMarkets() {
        if (cachedMarkets.isEmpty()) {
            cachedMarkets = marketRepository.findByActiveTrue();
        }
        return cachedMarkets;
    }

    public Map<Long, List<MarketDTO.SparklinePoint>> getSparklines() {
        return cachedSparklines;
    }

    public Set<Long> getCorrelationMarketIds() {
        return cachedCorrelationMarketIds;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void refreshCaches() {
        try {
            long start = System.currentTimeMillis();

            List<Market> markets = marketRepository.findByActiveTrue();
            cachedMarkets = markets;

            List<Long> marketIds = markets.stream().map(Market::getId).toList();
            if (!marketIds.isEmpty()) {
                Instant oneDayAgo = Instant.now().minus(Duration.ofDays(1));
                cachedSparklines = computeSparklines(marketIds, oneDayAgo);
                cachedCorrelationMarketIds = correlationRepository.findMarketIdsWithCorrelationsSince(oneDayAgo);
            } else {
                cachedSparklines = Map.of();
                cachedCorrelationMarketIds = Set.of();
            }

            lastComputedAt = Instant.now();
            long elapsed = System.currentTimeMillis() - start;
            log.info("Cache refresh: {} markets, {} sparklines in {}ms",
                    markets.size(), cachedSparklines.size(), elapsed);
        } catch (Exception e) {
            log.error("Failed to refresh caches: {}", e.getMessage());
            // Keep stale cache data instead of serving empty payloads.
        }
    }

    private Map<Long, List<MarketDTO.SparklinePoint>> computeSparklines(List<Long> marketIds, Instant oneDayAgo) {
        Map<Long, List<MarketDTO.SparklinePoint>> sparklines = new HashMap<>();
        if (marketIds.isEmpty()) return sparklines;

        List<Object[]> rows = priceTickRepository.findSparklineData(marketIds, oneDayAgo);
        for (Object[] row : rows) {
            Long marketId = ((Number) row[0]).longValue();
            Instant ts;
            if (row[1] instanceof java.sql.Timestamp sqlTs) {
                ts = sqlTs.toInstant();
            } else if (row[1] instanceof java.time.OffsetDateTime odt) {
                ts = odt.toInstant();
            } else {
                ts = Instant.parse(row[1].toString());
            }
            BigDecimal price = (BigDecimal) row[2];
            sparklines.computeIfAbsent(marketId, k -> new ArrayList<>())
                    .add(MarketDTO.SparklinePoint.builder().timestamp(ts).price(price).build());
        }
        return sparklines;
    }
}
