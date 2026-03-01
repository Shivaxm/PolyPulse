package com.polypulse.controller;

import com.polypulse.repository.MarketRepository;
import com.polypulse.service.MarketSyncService;
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

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "ok");
        status.put("marketsTracked", marketRepository.countByActiveTrue());
        status.put("lastSync", marketSyncService.getLastSyncAt());
        status.put("timestamp", Instant.now());
        return status;
    }
}
