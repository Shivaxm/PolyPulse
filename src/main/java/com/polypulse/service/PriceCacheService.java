package com.polypulse.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class PriceCacheService {

    public record CachedPrice(BigDecimal price, Instant timestamp, String conditionId) {}

    private final ConcurrentHashMap<Long, CachedPrice> latestPrices = new ConcurrentHashMap<>();

    public void updatePrice(Long marketId, BigDecimal price, Instant timestamp, String conditionId) {
        latestPrices.compute(marketId, (id, existing) -> {
            // Only update if newer
            if (existing != null && existing.timestamp().isAfter(timestamp)) {
                return existing;
            }
            return new CachedPrice(price, timestamp, conditionId);
        });
    }

    public CachedPrice getLatestPrice(Long marketId) {
        return latestPrices.get(marketId);
    }

    public Map<Long, CachedPrice> getAllLatestPrices() {
        return Map.copyOf(latestPrices);
    }

    public int size() {
        return latestPrices.size();
    }
}
