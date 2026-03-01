package com.polypulse.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
public class PriceTickEvent extends ApplicationEvent {

    private final Long marketId;
    private final String conditionId;
    private final BigDecimal price;
    private final Instant occurredAt;

    public PriceTickEvent(Object source, Long marketId, String conditionId, BigDecimal price, Instant occurredAt) {
        super(source);
        this.marketId = marketId;
        this.conditionId = conditionId;
        this.price = price;
        this.occurredAt = occurredAt;
    }
}
