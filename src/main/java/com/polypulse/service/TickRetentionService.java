package com.polypulse.service;

import com.polypulse.repository.PriceTickRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class TickRetentionService {

    private final PriceTickRepository priceTickRepository;

    /**
     * Delete ticks older than 14 days. Runs every hour.
     * Uses batch deletion to avoid long-running transactions.
     */
    @Scheduled(fixedDelay = 3600_000, initialDelay = 60_000) // every hour, start 1 min after boot
    @Transactional
    public void cleanupOldTicks() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(14));
        int deleted = priceTickRepository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Tick retention: deleted {} ticks older than 14 days", deleted);
        }
    }
}
