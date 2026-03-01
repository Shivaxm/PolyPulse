package com.polypulse.service;

import com.polypulse.dto.CorrelationDTO;
import com.polypulse.dto.PriceUpdateDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SseConnectionManager {

    private final ConcurrentHashMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> marketSubscriptions = new ConcurrentHashMap<>();

    public record ClientRegistration(String clientId, SseEmitter emitter) {}

    public ClientRegistration registerClient() {
        String clientId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(300_000L); // 5 minute timeout

        emitter.onCompletion(() -> removeClient(clientId));
        emitter.onTimeout(() -> removeClient(clientId));
        emitter.onError(e -> removeClient(clientId));

        clients.put(clientId, emitter);
        log.debug("SSE client connected: {} (total: {})", clientId, clients.size());

        // Send connected event
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("clientId", clientId, "connections", clients.size())));
        } catch (IOException e) {
            removeClient(clientId);
        }

        return new ClientRegistration(clientId, emitter);
    }

    public ClientRegistration registerMarketClient(Long marketId) {
        ClientRegistration reg = registerClient();
        marketSubscriptions.put(reg.clientId(), marketId);
        return reg;
    }

    public void removeClient(String clientId) {
        clients.remove(clientId);
        marketSubscriptions.remove(clientId);
        log.debug("SSE client disconnected: {} (total: {})", clientId, clients.size());
    }

    public void broadcastPriceUpdate(PriceUpdateDTO update) {
        clients.forEach((clientId, emitter) -> {
            // If client has a market subscription, only send relevant updates
            Long subscribedMarket = marketSubscriptions.get(clientId);
            if (subscribedMarket != null && !subscribedMarket.equals(update.getMarketId())) {
                return;
            }

            try {
                emitter.send(SseEmitter.event()
                        .name("price_update")
                        .data(update));
            } catch (IOException e) {
                removeClient(clientId);
            }
        });
    }

    public void broadcastCorrelation(CorrelationDTO correlation) {
        clients.forEach((clientId, emitter) -> {
            // If client has a market subscription, only send relevant correlations
            Long subscribedMarket = marketSubscriptions.get(clientId);
            if (subscribedMarket != null && correlation.getMarket() != null
                    && !subscribedMarket.equals(correlation.getMarket().getId())) {
                return;
            }

            try {
                emitter.send(SseEmitter.event()
                        .name("correlation")
                        .data(correlation));
            } catch (IOException e) {
                removeClient(clientId);
            }
        });
    }

    @Scheduled(fixedDelay = 30000)
    public void sendHeartbeats() {
        if (clients.isEmpty()) return;

        clients.forEach((clientId, emitter) -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("heartbeat")
                        .data(Map.of("timestamp", Instant.now().toString(),
                                "connections", clients.size())));
            } catch (IOException e) {
                removeClient(clientId);
            }
        });
    }

    public int getActiveConnectionCount() {
        return clients.size();
    }
}
