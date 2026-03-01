package com.polypulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CorrelationDTO {
    private Long id;
    private MarketSummary market;
    private NewsSummary news;
    private BigDecimal priceBefore;
    private BigDecimal priceAfter;
    private BigDecimal priceDelta;
    private BigDecimal confidence;
    private Instant detectedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MarketSummary {
        private Long id;
        private String question;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NewsSummary {
        private String headline;
        private String source;
        private String url;
        private Instant publishedAt;
    }
}
