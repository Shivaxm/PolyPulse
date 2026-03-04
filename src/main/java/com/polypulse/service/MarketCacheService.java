package com.polypulse.service;

import com.polypulse.dto.MarketDTO;
import com.polypulse.model.Market;
import com.polypulse.repository.MarketRepository;
import com.polypulse.repository.PriceTickRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MarketCacheService {

    private final MarketRepository marketRepository;
    private final PriceTickRepository priceTickRepository;

    @Cacheable(value = "markets:active", unless = "#result.isEmpty()")
    public List<Market> getActiveMarkets() {
        return marketRepository.findByActiveTrue();
    }

    /**
     * Cache sparklines for 30 seconds.
     * Key uses epoch/30 so all requests within a 30s window share the same cache entry.
     */
    @Cacheable(value = "sparklines", key = "'all:' + #oneDayAgo.epochSecond / 30")
    public Map<Long, List<MarketDTO.SparklinePoint>> getSparklines(List<Long> marketIds, Instant oneDayAgo) {
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
