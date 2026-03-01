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
    private final PolymarketConfig config;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void onNewsIngested(NewsIngestedEvent event) {
        NewsEvent newsEvent = newsEventRepository.findById(event.getNewsEventId()).orElse(null);
        if (newsEvent == null) return;

        checkCorrelations(newsEvent);
    }

    /**
     * Retroactive correlation check: re-check uncorrelated news from the last 60 minutes.
     */
    @Scheduled(fixedDelay = 900000) // 15 minutes
    public void retroactiveCheck() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(60));
        List<NewsEvent> uncorrelated = newsEventRepository
                .findByPublishedAtBetweenOrderByPublishedAtDesc(cutoff, Instant.now());

        int checked = 0;
        for (NewsEvent newsEvent : uncorrelated) {
            // Skip if already has correlations
            if (newsEvent.getKeywords() == null || newsEvent.getKeywords().isEmpty()) continue;
            checkCorrelations(newsEvent);
            checked++;
        }

        if (checked > 0) {
            log.debug("Retroactive correlation check processed {} news events", checked);
        }
    }

    private void checkCorrelations(NewsEvent newsEvent) {
        List<String> keywords = newsEvent.getKeywords();
        if (keywords == null || keywords.isEmpty()) return;

        // Find candidate markets by keyword matching in Java (simpler than native query)
        List<Market> activeMarkets = marketRepository.findByActiveTrue();
        PolymarketConfig.Correlation corrConfig = config.getCorrelation();

        int candidates = 0;
        for (Market market : activeMarkets) {
            if (candidates >= corrConfig.getMaxCandidateMarkets()) break;

            String questionLower = market.getQuestion().toLowerCase();
            long matchingKeywords = keywords.stream()
                    .filter(kw -> questionLower.contains(kw.toLowerCase()))
                    .count();

            if (matchingKeywords == 0) continue;
            candidates++;

            // Skip if already correlated
            if (correlationRepository.existsByMarketIdAndNewsEventId(market.getId(), newsEvent.getId())) {
                continue;
            }

            evaluateCorrelation(market, newsEvent, keywords, matchingKeywords);
        }
    }

    private void evaluateCorrelation(Market market, NewsEvent newsEvent,
                                      List<String> keywords, long matchingKeywords) {
        PolymarketConfig.Correlation corrConfig = config.getCorrelation();
        Instant newsTime = newsEvent.getPublishedAt();

        // Before window
        Instant beforeStart = newsTime.minus(Duration.ofMinutes(corrConfig.getBeforeWindowMinutes()));
        List<PriceTick> beforeTicks = priceTickRepository
                .findByMarketIdAndTimestampBetweenOrderByTimestampDesc(
                        market.getId(), beforeStart, newsTime);

        if (beforeTicks.size() < 1) return; // No baseline data

        BigDecimal priceBefore = averagePrice(beforeTicks);

        // After window
        Instant afterEnd = newsTime.plus(Duration.ofMinutes(corrConfig.getAfterWindowMinutes()));
        Instant now = Instant.now();
        if (afterEnd.isAfter(now)) {
            // Not enough time has passed yet — will be caught by retroactive check
            if (Duration.between(newsTime, now).toMinutes() < 5) {
                return;
            }
            afterEnd = now;
        }

        List<PriceTick> afterTicks = priceTickRepository
                .findByMarketIdAndTimestampBetweenOrderByTimestampDesc(
                        market.getId(), newsTime, afterEnd);

        if (afterTicks.size() < 1) return; // No post-news data

        BigDecimal priceAfter = averagePrice(afterTicks);
        BigDecimal priceDelta = priceAfter.subtract(priceBefore);

        if (priceDelta.abs().doubleValue() < corrConfig.getMinPriceDelta()) return;

        // Compute confidence
        double magnitudeScore = Math.min(priceDelta.abs().doubleValue() / 0.10, 1.0);
        double keywordOverlap = (double) matchingKeywords / keywords.size();

        // Time tightness: how quickly the first significant move happened
        double timeTightness = 0.5; // default
        if (!afterTicks.isEmpty()) {
            // Find first tick that shows significant deviation from before price
            for (PriceTick tick : afterTicks) {
                double tickDelta = tick.getPrice().subtract(priceBefore).abs().doubleValue();
                if (tickDelta > corrConfig.getMinPriceDelta() / 2) {
                    long minutesToMove = Duration.between(newsTime, tick.getTimestamp()).toMinutes();
                    timeTightness = Math.max(0, 1.0 - ((double) minutesToMove / 30.0));
                    break;
                }
            }
        }

        double confidence = (magnitudeScore * 0.5) + (keywordOverlap * 0.3) + (timeTightness * 0.2);

        // Reduce confidence if data is sparse
        if (beforeTicks.size() < 3 || afterTicks.size() < 3) {
            confidence *= 0.5;
        }

        if (confidence < corrConfig.getMinConfidence()) return;

        // Compute time window in ms
        int timeWindowMs = (int) Duration.between(
                beforeTicks.getLast().getTimestamp(),
                afterTicks.getFirst().getTimestamp()).toMillis();

        Correlation correlation = Correlation.builder()
                .marketId(market.getId())
                .newsEventId(newsEvent.getId())
                .priceBefore(priceBefore)
                .priceAfter(priceAfter)
                .priceDelta(priceDelta)
                .timeWindowMs(Math.abs(timeWindowMs))
                .confidence(BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP))
                .detectedAt(Instant.now())
                .build();

        correlation = correlationRepository.save(correlation);

        log.info("Correlation detected: '{}' -> '{}' | delta: {}, confidence: {}",
                newsEvent.getHeadline(), market.getQuestion(),
                priceDelta, String.format("%.3f", confidence));

        eventPublisher.publishEvent(new CorrelationDetectedEvent(
                this, correlation.getId(), market.getId(), market.getQuestion(),
                newsEvent.getId(), newsEvent.getHeadline(),
                priceBefore, priceAfter, priceDelta, confidence, correlation.getDetectedAt()));
    }

    private BigDecimal averagePrice(List<PriceTick> ticks) {
        return ticks.stream()
                .map(PriceTick::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(ticks.size()), 6, RoundingMode.HALF_UP);
    }
}
