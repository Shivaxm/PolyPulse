package com.polypulse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polypulse.dto.CorrelationDTO;
import com.polypulse.dto.PriceUpdateDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "polypulse.redis.enabled", havingValue = "true")
public class RedisEventSubscriber {

    private final SseConnectionManager sseConnectionManager;
    private final ObjectMapper objectMapper;

    public void onPriceMessage(String message) {
        try {
            PriceUpdateDTO update = objectMapper.readValue(message, PriceUpdateDTO.class);
            sseConnectionManager.broadcastPriceUpdate(update);
        } catch (Exception e) {
            log.error("Failed to process Redis price message", e);
        }
    }

    public void onCorrelationMessage(String message) {
        try {
            CorrelationDTO correlation = objectMapper.readValue(message, CorrelationDTO.class);
            sseConnectionManager.broadcastCorrelation(correlation);
        } catch (Exception e) {
            log.error("Failed to process Redis correlation message", e);
        }
    }
}
