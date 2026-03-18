package com.polypulse.service;

import com.polypulse.dto.MarketDTO;
import com.polypulse.model.Market;
import com.polypulse.repository.CorrelationRepository;
import com.polypulse.repository.MarketRepository;
import com.polypulse.repository.PriceTickRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@Slf4j
public class MarketCacheService {

    private final MarketRepository marketRepository;
    private final PriceTickRepository priceTickRepository;
    private final CorrelationRepository correlationRepository;

    private volatile List<Market> cachedMarkets = null;
    private volatile Map<Long, List<MarketDTO.SparklinePoint>> cachedSparklines = new HashMap<>();
    private volatile Set<Long> cachedCorrelationMarketIds = null;

    public MarketCacheService(MarketRepository marketRepository,
                               PriceTickRepository priceTickRepository,
                               CorrelationRepository correlationRepository) {
        this.marketRepository = marketRepository;
        this.priceTickRepository = priceTickRepository;
        this.correlationRepository = correlationRepository;
    }

    public List<Market> getActiveMarkets() {
        List<Market> markets = cachedMarkets;
        if (markets == null) {
            try {
                markets = marketRepository.findByActiveTrue();
                cachedMarkets = markets;
                log.info("Loaded {} markets from DB (first request)", markets.size());
            } catch (Exception e) {
                log.error("Failed to load markets from DB: {}", e.getMessage(), e);
                return List.of();
            }
        }
        return markets;
    }

    public Map<Long, List<MarketDTO.SparklinePoint>> getSparklines() {
        return cachedSparklines;
    }

    public Set<Long> getCorrelationMarketIds() {
        Set<Long> marketIds = cachedCorrelationMarketIds;
        if (marketIds == null) {
            try {
                marketIds = correlationRepository.findAllMarketIdsWithCorrelations();
                cachedCorrelationMarketIds = marketIds;
                log.info("Loaded correlation market IDs from DB (first request): {}", marketIds.size());
            } catch (Exception e) {
                log.error("Failed to load correlation market IDs from DB: {}", e.getMessage(), e);
                return Set.of();
            }
        }
        return marketIds;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void refreshCaches() {
        try {
            long start = System.currentTimeMillis();

            List<Market> markets = marketRepository.findByActiveTrue();
            cachedMarkets = markets;

            List<Long> marketIds = markets.stream().map(Market::getId).toList();
            Instant oneDayAgo = Instant.now().minus(Duration.ofDays(1));

            if (!marketIds.isEmpty()) {
                try {
                    cachedSparklines = computeSparklines(marketIds, oneDayAgo);
                } catch (Exception e) {
                    log.warn("Sparkline computation failed: {}", e.getMessage());
                }
                try {
                    // Dashboard badge should reflect any correlation that exists for the market,
                    // not just the latest paginated/recent subset.
                    cachedCorrelationMarketIds = correlationRepository.findAllMarketIdsWithCorrelations();
                } catch (Exception e) {
                    log.warn("Correlation IDs fetch failed: {}", e.getMessage());
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("Cache refresh: {} markets, {} sparklines in {}ms",
                    markets.size(), cachedSparklines.size(), elapsed);
        } catch (Exception e) {
            log.error("Failed to refresh caches: {}", e.getMessage(), e);
        }
    }

    /**
     * Purge price_ticks older than 3 days to prevent unbounded table growth.
     * Runs every 6 hours.
     */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000, initialDelay = 60_000)
    @Transactional
    public void purgeOldPriceTicks() {
        try {
            Instant cutoff = Instant.now().minus(Duration.ofDays(3));
            int deleted = priceTickRepository.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("Purged {} price_ticks older than 3 days", deleted);
            }
        } catch (Exception e) {
            log.error("Failed to purge old price_ticks: {}", e.getMessage(), e);
        }
    }

    private Map<Long, List<MarketDTO.SparklinePoint>> computeSparklines(List<Long> marketIds, Instant oneDayAgo) {
        Map<Long, List<MarketDTO.SparklinePoint>> sparklines = new HashMap<>();
        if (marketIds.isEmpty()) return sparklines;

        // Batch into chunks to avoid enormous IN clauses that crash the DB
        int batchSize = 200;
        for (int i = 0; i < marketIds.size(); i += batchSize) {
            List<Long> batch = marketIds.subList(i, Math.min(i + batchSize, marketIds.size()));
            try {
                List<Object[]> rows = priceTickRepository.findSparklineData(batch, oneDayAgo);
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
            } catch (Exception e) {
                log.warn("Sparkline batch {}-{} failed: {}", i, i + batch.size(), e.getMessage());
            }
        }
        return sparklines;
    }
}
