package com.polypulse.service;

import com.polypulse.dto.CorrelationDTO;
import com.polypulse.dto.PriceUpdateDTO;
import com.polypulse.event.CorrelationDetectedEvent;
import com.polypulse.event.PriceTickEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class SseEventBridge {

    private final SseConnectionManager sseConnectionManager;

    // Throttle: max 1 price update per market per second
    private final ConcurrentHashMap<Long, Instant> lastSentTime = new ConcurrentHashMap<>();

    @Async
    @EventListener
    public void onPriceTick(PriceTickEvent event) {
        Long marketId = event.getMarketId();

        // Throttle check
        Instant now = Instant.now();
        Instant lastSent = lastSentTime.get(marketId);
        if (lastSent != null && now.minusSeconds(1).isBefore(lastSent)) {
            return; // throttled
        }
        lastSentTime.put(marketId, now);

        PriceUpdateDTO update = PriceUpdateDTO.builder()
                .marketId(marketId)
                .price(event.getPrice())
                .timestamp(event.getOccurredAt())
                .build();

        sseConnectionManager.broadcastPriceUpdate(update);
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

        sseConnectionManager.broadcastCorrelation(dto);
    }
}
