package com.polypulse.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "price_ticks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceTick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market_id", nullable = false)
    private Long marketId;

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal price;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(precision = 18, scale = 2)
    private BigDecimal volume;
}
