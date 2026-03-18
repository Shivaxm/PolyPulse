package com.polypulse.service;

import com.polypulse.repository.CorrelationRepository;
import com.polypulse.repository.NewsEventRepository;
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
    private final NewsEventRepository newsEventRepository;
    private final CorrelationRepository correlationRepository;

    /**
     * Delete ticks older than 3 days. Runs every hour.
     */
    @Scheduled(fixedDelay = 3600_000, initialDelay = 60_000)
    @Transactional
    public void cleanupOldTicks() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(3));
        int deleted = priceTickRepository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Tick retention: deleted {} ticks older than 3 days", deleted);
        }
    }

    /**
     * Delete news events older than 14 days. Runs every 6 hours.
     * First removes correlations that reference the old news (FK constraint).
     */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000, initialDelay = 120_000)
    @Transactional
    public void cleanupOldNews() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(14));
        int correlationsDeleted = correlationRepository.deleteByNewsOlderThan(cutoff);
        int newsDeleted = newsEventRepository.deleteOlderThan(cutoff);
        if (correlationsDeleted > 0 || newsDeleted > 0) {
            log.info("News retention: deleted {} correlations and {} news events older than 14 days",
                    correlationsDeleted, newsDeleted);
        }
    }

    /**
     * Delete correlations older than 30 days. Runs every 6 hours.
     */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000, initialDelay = 180_000)
    @Transactional
    public void cleanupOldCorrelations() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));
        int deleted = correlationRepository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Correlation retention: deleted {} correlations older than 30 days", deleted);
        }
    }
}
