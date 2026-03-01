package com.polypulse.controller;

import com.polypulse.repository.CorrelationRepository;
import com.polypulse.repository.MarketRepository;
import com.polypulse.service.IngestionMetrics;
import com.polypulse.service.MarketSyncService;
import com.polypulse.service.NewsIngestionService;
import com.polypulse.service.PriceIngestionService;
import com.polypulse.service.SseConnectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final MarketRepository marketRepository;
    private final MarketSyncService marketSyncService;
    private final IngestionMetrics ingestionMetrics;
    private final PriceIngestionService priceIngestionService;
    private final NewsIngestionService newsIngestionService;
    private final CorrelationRepository correlationRepository;
    private final SseConnectionManager sseConnectionManager;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "ok");
        status.put("marketsTracked", marketRepository.countByActiveTrue());
        status.put("lastSync", marketSyncService.getLastSyncAt());
        status.put("ticksReceived", ingestionMetrics.getTicksReceived());
        status.put("ticksWritten", ingestionMetrics.getTicksWritten());
        status.put("ticksDropped", ingestionMetrics.getTicksDropped());
        status.put("queueDepth", priceIngestionService.getQueueDepth());
        status.put("wsConnected", ingestionMetrics.isWsConnected());
        status.put("lastTickTimestamp", ingestionMetrics.getLastTickTimestamp());
        status.put("uptime", ingestionMetrics.getUptime().toSeconds() + "s");
        status.put("newsEventsTotal", newsIngestionService.getTotalIngested());
        status.put("lastNewsIngestedAt", newsIngestionService.getLastIngestedAt());
        status.put("correlationsDetected", correlationRepository.count());
        status.put("activeConnections", sseConnectionManager.getActiveConnectionCount());
        status.put("timestamp", Instant.now());
        return status;
    }
}
