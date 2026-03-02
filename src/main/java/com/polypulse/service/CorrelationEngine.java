package com.polypulse.service;

import com.polypulse.config.PolymarketConfig;
import com.polypulse.event.CorrelationDetectedEvent;
import com.polypulse.event.NewsIngestedEvent;
import com.polypulse.model.Correlation;
import com.polypulse.model.Market;
import com.polypulse.model.NewsEvent;
import com.polypulse.model.PriceTick;
import com.polypulse.repository.CorrelationRepository;
import com.polypulse.repository.MarketRepository;
import com.polypulse.repository.NewsEventRepository;
import com.polypulse.repository.PriceTickRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class CorrelationEngine {

    private final MarketRepository marketRepository;
    private final PriceTickRepository priceTickRepository;
    private final NewsEventRepository newsEventRepository;
    private final CorrelationRepository correlationRepository;
    private final PriceBackfillService priceBackfillService;
    private final PolymarketConfig config;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void onNewsIngested(NewsIngestedEvent event) {
        NewsEvent newsEvent = newsEventRepository.findById(event.getNewsEventId()).orElse(null);
        if (newsEvent == null) return;

        log.info("Processing news for correlations: '{}'", newsEvent.getHeadline());
        checkCorrelations(newsEvent);
    }

    @Scheduled(fixedDelay = 900000) // 15 minutes
    public void retroactiveCorrelationCheck() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(4));
        List<NewsEvent> recentNews = newsEventRepository
                .findByPublishedAtBetweenOrderByPublishedAtDesc(cutoff, Instant.now());

        int checked = 0;
        int skipped = 0;
        for (NewsEvent newsEvent : recentNews) {
            if (newsEvent.getKeywords() == null || newsEvent.getKeywords().isEmpty()) {
                skipped++;
                continue;
            }
            checkCorrelations(newsEvent);
            checked++;
        }

        if (checked > 0 || skipped > 0) {
            log.info("Retroactive correlation check: processed {} news events, skipped {} (no keywords)",
                    checked, skipped);
        }
    }

    private void checkCorrelations(NewsEvent newsEvent) {
        List<String> keywords = newsEvent.getKeywords();
        if (keywords == null || keywords.isEmpty()) return;

        List<Market> activeMarkets = marketRepository.findByActiveTrue();
        PolymarketConfig.Correlation corrConfig = config.getCorrelation();

        int candidates = 0;
        int evaluated = 0;
        for (Market market : activeMarkets) {
            if (candidates >= corrConfig.getMaxCandidateMarkets()) break;

            String questionLower = market.getQuestion().toLowerCase();
            long matchingKeywords = keywords.stream()
                    .filter(kw -> questionLower.contains(kw.toLowerCase()))
                    .count();

            if (matchingKeywords == 0) continue;
            if (matchingKeywords == 1) {
                boolean hasSingleLongMatch = keywords.stream()
                        .filter(kw -> kw.length() >= 6)
                        .anyMatch(kw -> questionLower.contains(kw.toLowerCase()));
                if (!hasSingleLongMatch) continue;
            }

            candidates++;

            if (correlationRepository.existsByMarketIdAndNewsEventId(market.getId(), newsEvent.getId())) {
                continue;
            }

            evaluateCorrelation(market, newsEvent, keywords, matchingKeywords);
            evaluated++;
        }

        if (candidates > 0) {
            log.debug("News '{}': {} candidate markets, {} evaluated (keywords: {})",
                    truncate(newsEvent.getHeadline(), 60), candidates, evaluated, keywords);
        }
    }

    private void evaluateCorrelation(Market market, NewsEvent newsEvent,
                                      List<String> keywords, long matchingKeywords) {
        PolymarketConfig.Correlation corrConfig = config.getCorrelation();
        Instant newsTime = newsEvent.getPublishedAt();

        try {
            Instant backfillSince = newsTime.minus(Duration.ofDays(7));
            priceBackfillService.backfillIfNeeded(market, backfillSince);
        } catch (Exception e) {
            log.debug("Backfill failed for market {}, continuing with available data: {}",
                    market.getId(), e.getMessage());
        }

        // Find price BEFORE the news
        Instant beforeStart = newsTime.minus(Duration.ofMinutes(corrConfig.getBeforeWindowMinutes()));
        List<PriceTick> beforeTicks = priceTickRepository
                .findByMarketIdAndTimestampBetweenOrderByTimestampDesc(
                        market.getId(), beforeStart, newsTime);

        BigDecimal priceBefore;
        if (!beforeTicks.isEmpty()) {
            priceBefore = beforeTicks.get(0).getPrice();
        } else if (market.getOutcomeYesPrice() != null) {
            priceBefore = market.getOutcomeYesPrice();
            log.debug("No before-ticks for market {} around news time {}, using stored price {}",
                    market.getId(), newsTime, priceBefore);
        } else {
            log.debug("No price data at all for market {} — skipping", market.getId());
            return;
        }

        // Find price AFTER the news
        Instant afterStart = newsTime.plus(Duration.ofMinutes(5));
        Instant afterEnd = newsTime.plus(Duration.ofMinutes(corrConfig.getAfterWindowMinutes()));
        Instant now = Instant.now();

        if (now.isBefore(newsTime.plus(Duration.ofMinutes(15)))) {
            return;
        }
        if (afterEnd.isAfter(now)) {
            afterEnd = now;
        }

        // ASC order for correct time-tightness calculation
        List<PriceTick> afterTicks = priceTickRepository
                .findByMarketIdAndTimestampBetweenOrderByTimestampAsc(
                        market.getId(), afterStart, afterEnd);

        BigDecimal priceAfter;
        if (!afterTicks.isEmpty()) {
            priceAfter = afterTicks.get(afterTicks.size() - 1).getPrice();
        } else if (market.getOutcomeYesPrice() != null
                && Duration.between(newsTime, now).toMinutes() > 30) {
            priceAfter = market.getOutcomeYesPrice();
            log.debug("No after-ticks for market {} after news time {}, using current price {}",
                    market.getId(), newsTime, priceAfter);
        } else {
            return;
        }

        BigDecimal priceDelta = priceAfter.subtract(priceBefore);

        if (priceDelta.abs().doubleValue() < corrConfig.getMinPriceDelta()) {
            return;
        }

        // Compute confidence score
        double magnitudeScore = Math.min(priceDelta.abs().doubleValue() / 0.10, 1.0);
        double keywordOverlap = (double) matchingKeywords / keywords.size();

        // Time tightness: find EARLIEST significant move (ASC order)
        double timeTightness = 0.3;
        for (PriceTick tick : afterTicks) {
            double tickDelta = tick.getPrice().subtract(priceBefore).abs().doubleValue();
            if (tickDelta > corrConfig.getMinPriceDelta() / 2) {
                long minutesToMove = Duration.between(newsTime, tick.getTimestamp()).toMinutes();
                timeTightness = Math.max(0, 1.0 - ((double) minutesToMove / 60.0));
                break;
            }
        }

        double confidence = (magnitudeScore * 0.4) + (keywordOverlap * 0.35) + (timeTightness * 0.25);

        // Gentle sparse data penalty
        int totalTicks = beforeTicks.size() + afterTicks.size();
        if (totalTicks < 4) {
            confidence *= 0.8;
        }

        if (confidence < corrConfig.getMinConfidence()) {
            log.debug("Correlation below threshold: '{}' -> '{}' | delta={}, confidence={} (min={})",
                    truncate(newsEvent.getHeadline(), 40), truncate(market.getQuestion(), 40),
                    priceDelta, String.format("%.3f", confidence), corrConfig.getMinConfidence());
            return;
        }

        long timeWindowMs;
        if (!beforeTicks.isEmpty() && !afterTicks.isEmpty()) {
            timeWindowMs = Math.abs(Duration.between(
                    beforeTicks.get(0).getTimestamp(),
                    afterTicks.get(afterTicks.size() - 1).getTimestamp()).toMillis());
        } else {
            timeWindowMs = Duration.between(newsTime, now).toMillis();
        }

        Correlation correlation = Correlation.builder()
                .marketId(market.getId())
                .newsEventId(newsEvent.getId())
                .priceBefore(priceBefore)
                .priceAfter(priceAfter)
                .priceDelta(priceDelta)
                .timeWindowMs((int) Math.min(timeWindowMs, Integer.MAX_VALUE))
                .confidence(BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP))
                .detectedAt(Instant.now())
                .build();

        correlation = correlationRepository.save(correlation);

        log.info("Correlation detected: '{}' -> '{}' | delta: {}, confidence: {}, window: {}min",
                truncate(newsEvent.getHeadline(), 50),
                truncate(market.getQuestion(), 50),
                priceDelta.setScale(4, RoundingMode.HALF_UP),
                String.format("%.3f", confidence),
                timeWindowMs / 60000);

        eventPublisher.publishEvent(new CorrelationDetectedEvent(
                this, correlation.getId(), market.getId(), market.getQuestion(),
                newsEvent.getId(), newsEvent.getHeadline(),
                priceBefore, priceAfter, priceDelta, confidence, correlation.getDetectedAt()));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
