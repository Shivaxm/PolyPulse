package com.polypulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketDTO {
    private Long id;
    private String question;
    private BigDecimal yesPrice;
    private BigDecimal noPrice;
    private BigDecimal volume24h;
    private BigDecimal liquidity;
    private String category;
    private Instant createdAtSource;
    private Instant endDate;
    private boolean hasRecentCorrelation;
    private Instant lastUpdated;
    private List<SparklinePoint> sparkline;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SparklinePoint {
        private Instant timestamp;
        private BigDecimal price;
    }
}
