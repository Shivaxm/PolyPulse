package com.polypulse.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "correlations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Correlation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market_id", nullable = false)
    private Long marketId;

    @Column(name = "news_event_id", nullable = false)
    private Long newsEventId;

    @Column(name = "price_before", nullable = false, precision = 10, scale = 6)
    private BigDecimal priceBefore;

    @Column(name = "price_after", nullable = false, precision = 10, scale = 6)
    private BigDecimal priceAfter;

    @Column(name = "price_delta", nullable = false, precision = 10, scale = 6)
    private BigDecimal priceDelta;

    @Column(name = "time_window_ms", nullable = false)
    private Integer timeWindowMs;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(columnDefinition = "TEXT")
    private String reasoning;

    @PrePersist
    protected void onCreate() {
        if (detectedAt == null) detectedAt = Instant.now();
    }
}
