package com.polypulse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polypulse.config.PolymarketConfig;
import com.polypulse.event.MarketsSyncedEvent;
import com.polypulse.event.PriceTickEvent;
import com.polypulse.model.Market;
import com.polypulse.model.PriceTick;
import com.polypulse.repository.MarketRepository;
import com.polypulse.repository.PriceTickRepository;
import jakarta.websocket.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class PriceIngestionService implements SmartLifecycle {

    private final MarketRepository marketRepository;
    private final PriceTickRepository priceTickRepository;
    private final PolymarketConfig config;
    private final ApplicationEventPublisher eventPublisher;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final BlockingQueue<PriceTick> buffer = new LinkedBlockingQueue<>(10_000);
    private final ConcurrentHashMap<String, Long> tokenToMarketId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tokenToConditionId = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Session wsSession;
    private final ExecutorService wsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ws-reconnect");
        t.setDaemon(true);
        return t;
    });

    public PriceIngestionService(MarketRepository marketRepository,
                                  PriceTickRepository priceTickRepository,
                                  PolymarketConfig config,
                                  ApplicationEventPublisher eventPublisher,
                                  IngestionMetrics metrics) {
        this.marketRepository = marketRepository;
        this.priceTickRepository = priceTickRepository;
        this.config = config;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
    }

    @Override
    public void start() {
        running.set(true);
        // Delay startup to let MarketSyncService do its first run
        wsExecutor.submit(() -> {
            try {
                log.info("Waiting 10 seconds for initial market sync...");
                Thread.sleep(10_000);
                loadTokenMappings();
                connectWebSocket();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public void stop() {
        running.set(false);
        closeSession();
        wsExecutor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE; // start last
    }

    private void loadTokenMappings() {
        List<Market> markets = marketRepository.findByActiveTrue();
        tokenToMarketId.clear();
        tokenToConditionId.clear();
        for (Market market : markets) {
            tokenToMarketId.put(market.getClobTokenId(), market.getId());
            tokenToConditionId.put(market.getClobTokenId(), market.getConditionId());
        }
        log.info("Loaded {} token-to-market mappings", tokenToMarketId.size());
    }

    private void connectWebSocket() {
        if (!running.get() || tokenToMarketId.isEmpty()) {
            log.warn("Cannot connect WebSocket: running={}, tokens={}", running.get(), tokenToMarketId.size());
            return;
        }

        int attempt = 0;
        long[] backoff = {1000, 2000, 4000, 8000, 16000, 30000};

        while (running.get()) {
            try {
                WebSocketContainer container = ContainerProvider.getWebSocketContainer();
                container.setDefaultMaxTextMessageBufferSize(1024 * 1024);
                String wsUrl = config.getPolymarket().getWsUrl();

                wsSession = container.connectToServer(new Endpoint() {
                    @Override
                    public void onOpen(Session session, EndpointConfig endpointConfig) {
                        log.info("Connected to Polymarket WebSocket");
                        metrics.setWsConnected(true);
                        session.addMessageHandler(String.class, message -> handleMessage(message));
                        sendSubscription(session);
                    }

                    @Override
                    public void onClose(Session session, CloseReason closeReason) {
                        log.warn("WebSocket closed: {}", closeReason);
                        metrics.setWsConnected(false);
                    }

                    @Override
                    public void onError(Session session, Throwable thr) {
                        log.error("WebSocket error: {}", thr.getMessage());
                        metrics.setWsConnected(false);
                    }
                }, ClientEndpointConfig.Builder.create().build(), URI.create(wsUrl));

                log.info("Connected to Polymarket WebSocket, tracking {} markets", tokenToMarketId.size());
                return; // Connected successfully

            } catch (Exception e) {
                long delay = backoff[Math.min(attempt, backoff.length - 1)];
                log.warn("WebSocket connection failed (attempt {}), retrying in {}ms: {}",
                        attempt + 1, delay, e.getMessage());
                metrics.setWsConnected(false);
                attempt++;
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void sendSubscription(Session session) {
        try {
            List<String> tokenIds = new ArrayList<>(tokenToMarketId.keySet());
            Map<String, Object> sub = new LinkedHashMap<>();
            sub.put("type", "market");
            sub.put("assets_ids", tokenIds);
            String msg = objectMapper.writeValueAsString(sub);
            session.getAsyncRemote().sendText(msg);
            log.info("Sent subscription for {} tokens", tokenIds.size());
        } catch (Exception e) {
            log.error("Failed to send subscription: {}", e.getMessage());
        }
    }

    private void handleMessage(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);

            // Handle array of events
            if (node.isArray()) {
                for (JsonNode item : node) {
                    processEvent(item);
                }
            } else {
                processEvent(node);
            }
        } catch (Exception e) {
            log.debug("Failed to parse WS message: {}", e.getMessage());
        }
    }

    private void processEvent(JsonNode node) {
        String eventType = node.has("event_type") ? node.get("event_type").asText() : "";
        if (!"price_change".equals(eventType)) return;

        String assetId = node.has("asset_id") ? node.get("asset_id").asText() : null;
        if (assetId == null) return;

        Long marketId = tokenToMarketId.get(assetId);
        if (marketId == null) return;

        try {
            BigDecimal price = new BigDecimal(node.get("price").asText());
            Instant timestamp = Instant.now();
            if (node.has("timestamp")) {
                try {
                    timestamp = Instant.parse(node.get("timestamp").asText());
                } catch (Exception ignored) {
                    // use current time as fallback
                }
            }

            PriceTick tick = PriceTick.builder()
                    .marketId(marketId)
                    .price(price)
                    .timestamp(timestamp)
                    .build();

            metrics.incrementTicksReceived();
            metrics.setLastTickTimestamp(timestamp);

            if (!buffer.offer(tick)) {
                long dropped = metrics.incrementTicksDropped();
                if (dropped % 100 == 0) {
                    log.warn("Buffer full, dropped {} ticks total", dropped);
                }
                return;
            }

            // Publish event for SSE fan-out (real-time path)
            String conditionId = tokenToConditionId.getOrDefault(assetId, "");
            eventPublisher.publishEvent(new PriceTickEvent(this, marketId, conditionId, price, timestamp));

        } catch (Exception e) {
            log.debug("Failed to process price event: {}", e.getMessage());
        }
    }

    /**
     * Batch writer: drains buffer every 2 seconds and batch-inserts to DB.
     */
    @Scheduled(fixedDelay = 2000)
    public void flushBuffer() {
        List<PriceTick> batch = new ArrayList<>();
        buffer.drainTo(batch, 500);
        if (batch.isEmpty()) return;

        try {
            priceTickRepository.saveAll(batch);
            metrics.addTicksWritten(batch.size());
            log.debug("Wrote {} ticks to DB, queue depth: {}, total dropped: {}",
                    batch.size(), buffer.size(), metrics.getTicksDropped());
        } catch (Exception e) {
            log.error("Failed to write tick batch: {}", e.getMessage());
            // Re-queue what we can
            for (PriceTick tick : batch) {
                buffer.offer(tick);
            }
        }
    }

    @EventListener
    public void onMarketsSynced(MarketsSyncedEvent event) {
        loadTokenMappings();
        // Re-subscribe on existing session if open
        Session session = wsSession;
        if (session != null && session.isOpen()) {
            sendSubscription(session);
        }
    }

    private void closeSession() {
        try {
            Session session = wsSession;
            if (session != null && session.isOpen()) {
                session.close();
            }
        } catch (Exception e) {
            log.debug("Error closing WS session: {}", e.getMessage());
        }
    }

    public int getQueueDepth() {
        return buffer.size();
    }
}
