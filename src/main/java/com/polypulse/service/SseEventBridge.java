package com.polypulse.service;

import com.polypulse.dto.CorrelationDTO;
import com.polypulse.dto.PriceUpdateDTO;
import com.polypulse.event.CorrelationDetectedEvent;
import com.polypulse.event.PriceTickEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SseEventBridge {

    private final SseConnectionManager sseConnectionManager;
    private final Optional<RedisEventPublisher> redisEventPublisher;

    // Throttle: max 1 price update per market per second
    private final ConcurrentHashMap<Long, Instant> lastSentTime = new ConcurrentHashMap<>();

    public SseEventBridge(SseConnectionManager sseConnectionManager,
                          Optional<RedisEventPublisher> redisEventPublisher) {
        this.sseConnectionManager = sseConnectionManager;
        this.redisEventPublisher = redisEventPublisher;
        log.info("SseEventBridge initialized with Redis pub/sub: {}",
                redisEventPublisher.isPresent() ? "ENABLED" : "DISABLED (local broadcast)");
    }

    @Async
    @EventListener
    public void onPriceTick(PriceTickEvent event) {
        Long marketId = event.getMarketId();

        // Throttle check
        Instant now = Instant.now();
        Instant lastSent = lastSentTime.get(marketId);
        if (lastSent != null && now.minusSeconds(1).isBefore(lastSent)) {
            return;
        }
        lastSentTime.put(marketId, now);

        PriceUpdateDTO update = PriceUpdateDTO.builder()
                .marketId(marketId)
                .price(event.getPrice())
                .timestamp(event.getOccurredAt())
                .build();

        if (redisEventPublisher.isPresent()) {
            // Publish to Redis — all instances (including this one) will receive
            // via RedisEventSubscriber and broadcast to their local SSE clients
            redisEventPublisher.get().publishPriceUpdate(update);
        } else {
            // No Redis — broadcast directly to local SSE clients
            sseConnectionManager.broadcastPriceUpdate(update);
        }
    }

    @EventListener
    public void onCorrelationDetected(CorrelationDetectedEvent event) {
        CorrelationDTO dto = CorrelationDTO.builder()
                .id(event.getCorrelationId())
                .market(CorrelationDTO.MarketSummary.builder()
                        .id(event.getMarketId())
                        .question(event.getMarketQuestion())
                        .build())
                .news(CorrelationDTO.NewsSummary.builder()
                        .headline(event.getHeadline())
                        .build())
                .priceBefore(event.getPriceBefore())
                .priceAfter(event.getPriceAfter())
                .priceDelta(event.getPriceDelta())
                .confidence(java.math.BigDecimal.valueOf(event.getConfidence()))
                .detectedAt(event.getDetectedAt())
                .reasoning(event.getReasoning())
                .build();

        if (redisEventPublisher.isPresent()) {
            redisEventPublisher.get().publishCorrelation(dto);
        } else {
            sseConnectionManager.broadcastCorrelation(dto);
        }
    }
}
