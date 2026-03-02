package com.polypulse.dto;

import java.math.BigDecimal;
import java.time.Instant;

public class CorrelationMapper {

    /**
     * Maps a native query row from the correlations JOIN query to a CorrelationDTO.
     * Expected column order:
     * 0: c.id, 1: c.market_id, 2: c.news_event_id,
     * 3: c.price_before, 4: c.price_after, 5: c.price_delta,
     * 6: c.time_window_ms, 7: c.confidence, 8: c.detected_at,
     * 9: m.question, 10: ne.headline, 11: ne.source, 12: ne.url, 13: ne.published_at
     */
    public static CorrelationDTO fromRow(Object[] row) {
        return CorrelationDTO.builder()
                .id(((Number) row[0]).longValue())
                .market(CorrelationDTO.MarketSummary.builder()
                        .id(((Number) row[1]).longValue())
                        .question((String) row[9])
                        .build())
                .news(CorrelationDTO.NewsSummary.builder()
                        .headline((String) row[10])
                        .source((String) row[11])
                        .url((String) row[12])
                        .publishedAt(toInstant(row[13]))
                        .build())
                .priceBefore((BigDecimal) row[3])
                .priceAfter((BigDecimal) row[4])
                .priceDelta((BigDecimal) row[5])
                .confidence((BigDecimal) row[7])
                .detectedAt(toInstant(row[8]))
                .build();
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return Instant.parse(value.toString());
    }
}
