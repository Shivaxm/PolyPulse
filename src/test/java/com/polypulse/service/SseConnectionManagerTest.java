package com.polypulse.service;

import com.polypulse.dto.CorrelationDTO;
import com.polypulse.dto.PriceUpdateDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SseConnectionManagerTest {

    private SseConnectionManager manager;

    @BeforeEach
    void setUp() {
        manager = new SseConnectionManager();
    }

    @Test
    void registerClient_increasesConnectionCount() {
        int before = manager.getActiveConnectionCount();

        SseConnectionManager.ClientRegistration reg = manager.registerClient();

        assertThat(reg.clientId()).isNotBlank();
        assertThat(reg.emitter()).isNotNull();
        assertThat(manager.getActiveConnectionCount()).isEqualTo(before + 1);
    }

    @Test
    void broadcastPriceUpdate_sendsToEmitter() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        String clientId = putClient(emitter);

        manager.broadcastPriceUpdate(PriceUpdateDTO.builder()
                .marketId(42L)
                .price(new BigDecimal("0.5"))
                .timestamp(Instant.now())
                .build());

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(manager.getActiveConnectionCount()).isEqualTo(1);
        assertThat(getClients()).containsKey(clientId);
    }

    @Test
    void broadcastPriceUpdate_emitterThrowsIOException_clientRemoved() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("closed")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        putClient(emitter);

        manager.broadcastPriceUpdate(PriceUpdateDTO.builder()
                .marketId(42L)
                .price(new BigDecimal("0.5"))
                .timestamp(Instant.now())
                .build());

        assertThat(manager.getActiveConnectionCount()).isZero();
    }

    @Test
    void broadcastPriceUpdate_emitterThrowsIllegalState_clientRemoved() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("already completed")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        putClient(emitter);

        manager.broadcastPriceUpdate(PriceUpdateDTO.builder()
                .marketId(42L)
                .price(new BigDecimal("0.5"))
                .timestamp(Instant.now())
                .build());

        assertThat(manager.getActiveConnectionCount()).isZero();
    }

    @Test
    void removeClient_callsComplete() {
        SseEmitter emitter = mock(SseEmitter.class);
        String clientId = putClient(emitter);

        manager.removeClient(clientId);

        verify(emitter).complete();
        assertThat(manager.getActiveConnectionCount()).isZero();
    }

    @Test
    void removeClient_alreadyCompleted_noException() {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("done")).when(emitter).complete();
        String clientId = putClient(emitter);

        manager.removeClient(clientId);

        assertThat(manager.getActiveConnectionCount()).isZero();
    }

    @Test
    void broadcastPriceUpdate_marketSubscriptionFiltersUpdates() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        String clientId = putClient(emitter);
        getSubscriptions().put(clientId, 42L);

        manager.broadcastPriceUpdate(PriceUpdateDTO.builder()
                .marketId(99L)
                .price(new BigDecimal("0.5"))
                .timestamp(Instant.now())
                .build());

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void broadcastCorrelation_marketSubscriptionFiltersUpdates() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        String clientId = putClient(emitter);
        getSubscriptions().put(clientId, 42L);

        CorrelationDTO correlation = CorrelationDTO.builder()
                .id(1L)
                .market(CorrelationDTO.MarketSummary.builder().id(99L).question("Q").build())
                .build();

        manager.broadcastCorrelation(correlation);

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, SseEmitter> getClients() {
        return (ConcurrentHashMap<String, SseEmitter>) ReflectionTestUtils.getField(manager, "clients");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Long> getSubscriptions() {
        return (ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(manager, "marketSubscriptions");
    }

    private String putClient(SseEmitter emitter) {
        String clientId = "client-" + System.nanoTime();
        getClients().put(clientId, emitter);
        return clientId;
    }
}
