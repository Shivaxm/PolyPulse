package com.polypulse.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
public class CorrelationDetectedEvent extends ApplicationEvent {

    private final Long correlationId;
    private final Long marketId;
    private final String marketQuestion;
    private final Long newsEventId;
    private final String headline;
    private final BigDecimal priceBefore;
    private final BigDecimal priceAfter;
    private final BigDecimal priceDelta;
    private final double confidence;
    private final Instant detectedAt;
    private final String reasoning;

    public CorrelationDetectedEvent(Object source, Long correlationId, Long marketId,
                                     String marketQuestion, Long newsEventId, String headline,
                                     BigDecimal priceBefore, BigDecimal priceAfter,
                                     BigDecimal priceDelta, double confidence, Instant detectedAt,
                                     String reasoning) {
        super(source);
        this.correlationId = correlationId;
        this.marketId = marketId;
        this.marketQuestion = marketQuestion;
        this.newsEventId = newsEventId;
        this.headline = headline;
        this.priceBefore = priceBefore;
        this.priceAfter = priceAfter;
        this.priceDelta = priceDelta;
        this.confidence = confidence;
        this.detectedAt = detectedAt;
        this.reasoning = reasoning;
    }
}
