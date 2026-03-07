package com.polypulse.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "markets")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Market {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "condition_id", unique = true, nullable = false, length = 128)
    private String conditionId;

    @Column(name = "clob_token_id", nullable = false, length = 128)
    private String clobTokenId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(length = 256)
    private String slug;

    @Column(length = 64)
    private String category;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "outcome_yes_price", precision = 10, scale = 6)
    private BigDecimal outcomeYesPrice;

    @Column(name = "outcome_no_price", precision = 10, scale = 6)
    private BigDecimal outcomeNoPrice;

    @Column(name = "volume_24h", precision = 18, scale = 2)
    private BigDecimal volume24h;

    @Column(name = "created_at_source")
    private Instant createdAtSource;

    @Column(name = "liquidity", precision = 18, scale = 2)
    private BigDecimal liquidity;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (lastSyncedAt == null) lastSyncedAt = Instant.now();
    }
}
