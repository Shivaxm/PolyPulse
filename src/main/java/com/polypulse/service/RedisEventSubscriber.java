package com.polypulse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polypulse.config.RedisConfig;
import com.polypulse.dto.CorrelationDTO;
import com.polypulse.dto.PriceUpdateDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "polypulse.redis.enabled", havingValue = "true")
public class RedisEventSubscriber implements MessageListener {

    private final SseConnectionManager sseConnectionManager;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        try {
            if (RedisConfig.PRICE_CHANNEL.equals(channel)) {
                PriceUpdateDTO update = objectMapper.readValue(body, PriceUpdateDTO.class);
                sseConnectionManager.broadcastPriceUpdate(update);
            } else if (RedisConfig.CORRELATION_CHANNEL.equals(channel)) {
                CorrelationDTO correlation = objectMapper.readValue(body, CorrelationDTO.class);
                sseConnectionManager.broadcastCorrelation(correlation);
            }
        } catch (Exception e) {
            log.error("Failed to process Redis message on channel {}: {}", channel, e.getMessage());
        }
    }
}
