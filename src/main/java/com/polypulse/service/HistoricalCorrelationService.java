package com.polypulse.service;

import com.polypulse.model.Market;
import com.polypulse.model.NewsEvent;
import com.polypulse.repository.MarketRepository;
import com.polypulse.repository.NewsEventRepository;
import com.polypulse.repository.PriceTickRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class HistoricalCorrelationService {

    private static final long BACKFILL_THRESHOLD_TICKS = 100;

    private final MarketRepository marketRepository;
    private final PriceTickRepository priceTickRepository;
    private final NewsEventRepository newsEventRepository;
    private final CorrelationEngine correlationEngine;
    private final PriceBackfillService priceBackfillService;

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void seedHistoricalData() {
        log.info("Starting historical correlation seeding...");

        List<Market> markets = marketRepository.findByActiveTrue();
        Instant since = Instant.now().minus(Duration.ofDays(30));

        for (Market market : markets) {
            try {
                long tickCount = priceTickRepository.countByMarketId(market.getId());
                if (tickCount < BACKFILL_THRESHOLD_TICKS) {
                    log.info("Backfilling prices for market: {}", market.getQuestion());
                    priceBackfillService.backfillIfNeeded(market, since);
                }
            } catch (Exception e) {
                log.warn("Failed to backfill market {}: {}", market.getId(), e.getMessage());
            }
        }

        List<NewsEvent> historicalNews = newsEventRepository
                .findByPublishedAtBetweenOrderByPublishedAtDesc(since, Instant.now());

        int correlated = 0;
        for (NewsEvent newsEvent : historicalNews) {
            if (newsEvent.getKeywords() == null || newsEvent.getKeywords().isEmpty()) {
                continue;
            }
            try {
                correlationEngine.checkCorrelations(newsEvent);
                correlated++;
            } catch (Exception e) {
                log.debug("Failed historical correlation for news {}: {}", newsEvent.getId(), e.getMessage());
            }
        }

        log.info("Historical seeding complete: processed {} news events against {} markets",
                correlated, markets.size());
    }
}
