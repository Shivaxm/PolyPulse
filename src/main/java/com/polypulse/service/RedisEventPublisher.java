package com.polypulse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polypulse.config.RedisConfig;
import com.polypulse.dto.CorrelationDTO;
import com.polypulse.dto.PriceUpdateDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "polypulse.redis.enabled", havingValue = "true")
public class RedisEventPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publishPriceUpdate(PriceUpdateDTO update) {
        try {
            String json = objectMapper.writeValueAsString(update);
            redisTemplate.convertAndSend(RedisConfig.PRICE_CHANNEL, json);
        } catch (Exception e) {
            log.error("Failed to publish price update to Redis", e);
        }
    }

    public void publishCorrelation(CorrelationDTO correlation) {
        try {
            String json = objectMapper.writeValueAsString(correlation);
            redisTemplate.convertAndSend(RedisConfig.CORRELATION_CHANNEL, json);
        } catch (Exception e) {
            log.error("Failed to publish correlation to Redis", e);
        }
    }
}
