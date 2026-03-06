package com.polypulse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PriceCacheServiceTest {

    private final PriceCacheService cache = new PriceCacheService();

    @Test
    void updatePrice_storeAndGetLatestPrice() {
        Instant ts = Instant.parse("2026-03-01T10:00:00Z");

        cache.updatePrice(1L, new BigDecimal("0.45"), ts, "cond-1");

        PriceCacheService.CachedPrice out = cache.getLatestPrice(1L);
        assertThat(out).isNotNull();
        assertThat(out.price()).isEqualByComparingTo("0.45");
        assertThat(out.timestamp()).isEqualTo(ts);
        assertThat(out.conditionId()).isEqualTo("cond-1");
    }

    @Test
    void updatePrice_olderTimestamp_doesNotOverwrite() {
        Instant newer = Instant.parse("2026-03-01T10:05:00Z");
        Instant older = Instant.parse("2026-03-01T10:00:00Z");

        cache.updatePrice(1L, new BigDecimal("0.50"), newer, "cond-1");
        cache.updatePrice(1L, new BigDecimal("0.40"), older, "cond-1");

        assertThat(cache.getLatestPrice(1L).price()).isEqualByComparingTo("0.50");
        assertThat(cache.getLatestPrice(1L).timestamp()).isEqualTo(newer);
    }

    @Test
    void updatePrice_newerTimestamp_overwrites() {
        Instant older = Instant.parse("2026-03-01T10:00:00Z");
        Instant newer = Instant.parse("2026-03-01T10:05:00Z");

        cache.updatePrice(1L, new BigDecimal("0.40"), older, "cond-1");
        cache.updatePrice(1L, new BigDecimal("0.55"), newer, "cond-1");

        assertThat(cache.getLatestPrice(1L).price()).isEqualByComparingTo("0.55");
        assertThat(cache.getLatestPrice(1L).timestamp()).isEqualTo(newer);
    }

    @Test
    void getAllLatestPrices_returnsCopy() {
        cache.updatePrice(1L, new BigDecimal("0.40"), Instant.parse("2026-03-01T10:00:00Z"), "cond-1");

        Map<Long, PriceCacheService.CachedPrice> copy = cache.getAllLatestPrices();

        assertThat(copy).hasSize(1);
        assertThat(copy).containsKey(1L);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> copy.put(2L, null));
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void updatePrice_multipleMarkets_storedIndependently() {
        cache.updatePrice(1L, new BigDecimal("0.20"), Instant.parse("2026-03-01T10:00:00Z"), "c1");
        cache.updatePrice(2L, new BigDecimal("0.80"), Instant.parse("2026-03-01T10:00:00Z"), "c2");

        assertThat(cache.getLatestPrice(1L).price()).isEqualByComparingTo("0.20");
        assertThat(cache.getLatestPrice(2L).price()).isEqualByComparingTo("0.80");
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void getLatestPrice_unknownMarket_returnsNull() {
        assertThat(cache.getLatestPrice(999L)).isNull();
    }
}
