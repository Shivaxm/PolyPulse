package com.polypulse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polypulse.config.PolymarketConfig;
import com.polypulse.event.MarketsSyncedEvent;
import com.polypulse.event.PriceTickEvent;
import com.polypulse.model.Market;
import com.polypulse.model.PriceTick;
import com.polypulse.repository.MarketRepository;
import com.polypulse.repository.PriceTickBatchWriter;
import jakarta.websocket.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class PriceIngestionService implements SmartLifecycle {

    private final MarketRepository marketRepository;
    private final PriceTickBatchWriter priceTickBatchWriter;
    private final PolymarketConfig config;
    private final ApplicationEventPublisher eventPublisher;
    private final IngestionMetrics metrics;
    private final PriceBackfillService priceBackfillService;
    private final PriceCacheService priceCacheService;
    private final PriceMessageParser priceMessageParser;
    private final ObjectMapper objectMapper;

    private final BlockingQueue<PriceTick> buffer = new LinkedBlockingQueue<>(50_000);
    private final ConcurrentHashMap<String, Long> tokenToMarketId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tokenToConditionId = new ConcurrentHashMap<>();
    // Only write to DB if last write for this market was >15s ago
    private final ConcurrentHashMap<Long, Instant> lastDbWriteTime = new ConcurrentHashMap<>();
    private static final Duration DB_WRITE_INTERVAL = Duration.ofSeconds(15);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Session wsSession;
    private final ExecutorService wsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ws-reconnect");
        t.setDaemon(true);
        return t;
    });

    public PriceIngestionService(MarketRepository marketRepository,
                                  PriceTickBatchWriter priceTickBatchWriter,
                                  PolymarketConfig config,
                                  ApplicationEventPublisher eventPublisher,
                                  IngestionMetrics metrics,
                                  PriceBackfillService priceBackfillService,
                                  PriceCacheService priceCacheService,
                                  PriceMessageParser priceMessageParser,
                                  ObjectMapper objectMapper) {
        this.marketRepository = marketRepository;
        this.priceTickBatchWriter = priceTickBatchWriter;
        this.config = config;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.priceBackfillService = priceBackfillService;
        this.priceCacheService = priceCacheService;
        this.priceMessageParser = priceMessageParser;
        this.objectMapper = objectMapper;
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
                        scheduleReconnect();
                    }

                    @Override
                    public void onError(Session session, Throwable thr) {
                        log.error("WebSocket error: {}", thr.getMessage());
                        metrics.setWsConnected(false);
                        scheduleReconnect();
                    }
                }, ClientEndpointConfig.Builder.create().build(), URI.create(wsUrl));

                log.info("Connected to Polymarket WebSocket, tracking {} markets", tokenToMarketId.size());

                Instant lastTick = metrics.getLastTickTimestamp();
                if (lastTick != null) {
                    Duration gap = Duration.between(lastTick, Instant.now());
                    if (gap.toMinutes() > 2 && gap.toHours() < 1) {
                        log.info("Detected {}min gap, triggering backfill from {}", gap.toMinutes(), lastTick);
                        CompletableFuture.runAsync(() -> {
                            List<Market> markets = marketRepository.findByActiveTrue();
                            for (Market market : markets) {
                                try {
                                    priceBackfillService.backfillIfNeeded(market, lastTick);
                                } catch (Exception e) {
                                    log.debug("Gap recovery failed for market {}: {}", market.getId(), e.getMessage());
                                }
                            }
                            log.info("Gap recovery complete");
                        });
                    }
                }

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

    private void scheduleReconnect() {
        if (running.get() && !wsExecutor.isShutdown()) {
            try {
                wsExecutor.submit(() -> {
                    try {
                        Thread.sleep(2000);
                        if (running.get() && !wsExecutor.isShutdown()) {
                            connectWebSocket();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (RejectedExecutionException e) {
                log.debug("Reconnect task rejected: {}", e.getMessage());
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
        List<PriceMessageParser.ParsedTick> ticks = priceMessageParser.parse(message, tokenToMarketId);
        for (PriceMessageParser.ParsedTick tick : ticks) {
            Long marketId = tokenToMarketId.get(tick.assetId());
            if (marketId != null) {
                saveTick(marketId, tick.assetId(), tick.price(), tick.timestamp());
            }
        }
    }

    private void saveTick(Long marketId, String assetId, BigDecimal price, Instant timestamp) {
        // 1. Always update in-memory cache (instant, no DB)
        String conditionId = tokenToConditionId.getOrDefault(assetId, "");
        priceCacheService.updatePrice(marketId, price, timestamp, conditionId);

        // 2. Publish SSE event asynchronously
        metrics.incrementTicksReceived();
        metrics.setLastTickTimestamp(timestamp);
        eventPublisher.publishEvent(new PriceTickEvent(this, marketId, conditionId, price, timestamp));

        // 3. Only buffer for DB write if enough time has passed (sampling)
        Instant lastWrite = lastDbWriteTime.get(marketId);
        if (lastWrite != null && timestamp.isBefore(lastWrite.plus(DB_WRITE_INTERVAL))) {
            return; // Skip DB write, cache is already updated
        }
        lastDbWriteTime.put(marketId, timestamp);

        PriceTick tick = PriceTick.builder()
                .marketId(marketId)
                .price(price)
                .timestamp(timestamp)
                .build();

        if (!buffer.offer(tick)) {
            long dropped = metrics.incrementTicksDropped();
            if (dropped % 1000 == 0) {
                log.warn("Buffer full, dropped {} ticks total", dropped);
            }
        }
    }

    /**
     * Batch writer: drains buffer every second and batch-inserts to DB.
     */
    @Scheduled(fixedDelay = 1000)
    public void flushBuffer() {
        List<PriceTick> batch = new ArrayList<>();
        buffer.drainTo(batch, 2000);
        if (batch.isEmpty()) return;

        try {
            int written = priceTickBatchWriter.batchInsert(batch);
            metrics.addTicksWritten(written);
            log.debug("Wrote {} ticks to DB (native batch), queue depth: {}", written, buffer.size());
        } catch (Exception e) {
            log.error("Failed to write tick batch: {}", e.getMessage());
            // Don't re-queue — ticks are already in the price cache for real-time display.
            // Historical data will be backfilled from CLOB API if needed.
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
