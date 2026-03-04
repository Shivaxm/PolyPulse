package com.polypulse.service;

import com.polypulse.config.PolymarketConfig;
import com.polypulse.event.CorrelationDetectedEvent;
import com.polypulse.event.MarketsSyncedEvent;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@RequiredArgsConstructor
public class CorrelationEngine {

    private final MarketRepository marketRepository;
    private final PriceTickRepository priceTickRepository;
    private final NewsEventRepository newsEventRepository;
    private final CorrelationRepository correlationRepository;
    private final PriceBackfillService priceBackfillService;
    private final LlmRelevanceService llmRelevanceService;
    private final PolymarketConfig config;
    private final ApplicationEventPublisher eventPublisher;

    private final AtomicBoolean historicalSeedDone = new AtomicBoolean(false);

    @EventListener
    public void onNewsIngested(NewsIngestedEvent event) {
        NewsEvent newsEvent = newsEventRepository.findById(event.getNewsEventId()).orElse(null);
        if (newsEvent == null) {
            return;
        }

        log.info("Processing news for correlations: '{}'", newsEvent.getHeadline());
        try {
            checkCorrelations(newsEvent);
        } catch (Exception e) {
            log.error("Failed to process correlations for news {}: {}", event.getNewsEventId(), e.getMessage());
        }
    }

    /**
     * After the first market sync, wait for news ingestion then seed historical correlations.
     */
    @EventListener
    public void onMarketsSynced(MarketsSyncedEvent event) {
        if (!historicalSeedDone.compareAndSet(false, true)) {
            return;
        }

        Thread seedThread = new Thread(() -> {
            try {
                // Wait for initial news fetch to complete
                Thread.sleep(25_000);
                seedHistoricalCorrelations();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "correlation-seed");
        seedThread.setDaemon(true);
        seedThread.start();
    }

    /**
     * Retroactive check every 15 minutes — re-evaluates recent news that may now
     * have more price data available, or that was missed during initial processing.
     */
    @Scheduled(fixedDelay = 900000) // 15 minutes
    public void retroactiveCorrelationCheck() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(6));
        List<NewsEvent> recentNews = newsEventRepository
                .findByPublishedAtBetweenOrderByPublishedAtDesc(cutoff, Instant.now());

        int checked = 0;
        int found = 0;
        for (NewsEvent newsEvent : recentNews) {
            if (newsEvent.getKeywords() == null || newsEvent.getKeywords().isEmpty()) {
                continue;
            }
            try {
                found += checkCorrelations(newsEvent);
            } catch (Exception e) {
                log.warn("Retroactive check failed for news {}: {}", newsEvent.getId(), e.getMessage());
            }
            checked++;
        }

        if (checked > 0) {
            log.info("Retroactive check: {} news events, {} new correlations", checked, found);
        }
    }

    private void seedHistoricalCorrelations() {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        List<NewsEvent> recentNews = newsEventRepository
                .findByPublishedAtBetweenOrderByPublishedAtDesc(since, Instant.now());

        if (recentNews.isEmpty()) {
            log.info("Historical seed: no news events in last 24h");
            return;
        }

        log.info("Historical seed: checking {} news events", recentNews.size());
        int total = 0;
        for (NewsEvent newsEvent : recentNews) {
            if (newsEvent.getKeywords() == null || newsEvent.getKeywords().isEmpty()) {
                continue;
            }
            try {
                total += checkCorrelations(newsEvent);
            } catch (Exception e) {
                log.warn("Seed failed for news {}: {}", newsEvent.getId(), e.getMessage());
            }
        }
        log.info("Historical seed complete: {} correlations from {} articles", total, recentNews.size());
    }

    /**
     * Two-stage correlation detection:
     *
     * Stage 1: Keyword pre-filter
     *   Fast O(n) scan of all active markets. Finds ~15 candidates that share
     *   at least one keyword with the headline. This is cheap and instant.
     *
     * Stage 2: LLM validation (single batch call)
     *   Sends all candidates to Claude Haiku in one API call. The LLM returns
     *   which markets are GENUINELY related to the news, with a reasoning
     *   explanation. This eliminates false positives like "Florida" matching both
     *   SpaceX and Florida Panthers.
     *
     * Stage 3: Price delta check
     *   For each LLM-validated market, check if the price actually moved.
     *   Only save the correlation if there's a measurable price change.
     *
     * @return number of new correlations saved
     */
    private int checkCorrelations(NewsEvent newsEvent) {
        List<String> keywords = newsEvent.getKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return 0;
        }

        List<Market> activeMarkets = marketRepository.findByActiveTrue();
        PolymarketConfig.Correlation corrConfig = config.getCorrelation();

        // Stage 1: Keyword pre-filter
        Map<Long, Market> candidateMap = new LinkedHashMap<>();
        for (Market market : activeMarkets) {
            if (candidateMap.size() >= corrConfig.getMaxCandidateMarkets()) {
                break;
            }

            String questionLower = market.getQuestion().toLowerCase();
            boolean hasMatch = keywords.stream()
                    .anyMatch(kw -> questionLower.contains(kw.toLowerCase()));

            if (hasMatch) {
                candidateMap.put(market.getId(), market);
            }
        }

        if (candidateMap.isEmpty()) {
            log.debug("No keyword candidates for: '{}'", truncate(newsEvent.getHeadline(), 60));
            return 0;
        }

        log.debug("Stage 1: {} keyword candidates for '{}'", candidateMap.size(),
                truncate(newsEvent.getHeadline(), 60));

        // Stage 2: LLM validation
        Map<Long, String> candidateQuestions = new LinkedHashMap<>();
        for (Map.Entry<Long, Market> entry : candidateMap.entrySet()) {
            candidateQuestions.put(entry.getKey(), entry.getValue().getQuestion());
        }

        List<LlmRelevanceService.RelevantMarket> relevantMarkets =
                llmRelevanceService.checkRelevance(newsEvent.getHeadline(), candidateQuestions);

        if (relevantMarkets.isEmpty()) {
            log.debug("Stage 2: LLM found no relevant markets for '{}'",
                    truncate(newsEvent.getHeadline(), 60));
            return 0;
        }

        log.info("Stage 2: LLM validated {} of {} candidates for '{}'",
                relevantMarkets.size(), candidateMap.size(),
                truncate(newsEvent.getHeadline(), 60));

        // Stage 3: Price delta check
        int correlationsFound = 0;
        for (LlmRelevanceService.RelevantMarket rm : relevantMarkets) {
            Market market = candidateMap.get(rm.marketId());
            if (market == null) {
                continue;
            }

            // Skip if already correlated
            if (correlationRepository.existsByMarketIdAndNewsEventId(market.getId(), newsEvent.getId())) {
                continue;
            }

            try {
                boolean saved = evaluateAndSave(market, newsEvent, rm.reasoning(), rm.relevanceScore());
                if (saved) {
                    correlationsFound++;
                }
            } catch (Exception e) {
                log.warn("Stage 3 failed for market {} x news {}: {}",
                        market.getId(), newsEvent.getId(), e.getMessage());
            }
        }

        return correlationsFound;
    }

    /**
     * Checks price movement and saves the correlation if delta is significant.
     * The LLM has already confirmed semantic relevance — this just validates
     * that the market actually moved.
     */
    private boolean evaluateAndSave(Market market, NewsEvent newsEvent,
                                     String reasoning, double llmScore) {
        PolymarketConfig.Correlation corrConfig = config.getCorrelation();
        Instant newsTime = newsEvent.getPublishedAt();
        Instant now = Instant.now();

        // Must have at least 15 minutes of post-news time
        if (now.isBefore(newsTime.plus(Duration.ofMinutes(15)))) {
            return false;
        }

        // Backfill historical price data
        try {
            priceBackfillService.backfillIfNeeded(market, newsTime.minus(Duration.ofDays(1)));
        } catch (Exception e) {
            log.debug("Backfill failed for market {}: {}", market.getId(), e.getMessage());
        }

        // Price BEFORE the news
        Instant beforeStart = newsTime.minus(Duration.ofMinutes(corrConfig.getBeforeWindowMinutes()));
        List<PriceTick> beforeTicks = priceTickRepository
                .findByMarketIdAndTimestampBetweenOrderByTimestampDesc(
                        market.getId(), beforeStart, newsTime);

        BigDecimal priceBefore;
        if (!beforeTicks.isEmpty()) {
            priceBefore = beforeTicks.get(0).getPrice();
        } else {
            // Try wider 24h window
            List<PriceTick> widerTicks = priceTickRepository
                    .findByMarketIdAndTimestampBetweenOrderByTimestampDesc(
                            market.getId(), newsTime.minus(Duration.ofHours(24)), newsTime);
            if (!widerTicks.isEmpty()) {
                priceBefore = widerTicks.get(0).getPrice();
            } else {
                // Cannot determine pre-news price
                return false;
            }
        }

        // Price AFTER the news
        // Use current synced price as the best representation of post-news state.
        // Fall back to tick data if synced price is unavailable.
        BigDecimal priceAfter;
        if (market.getOutcomeYesPrice() != null
                && Duration.between(newsTime, now).toMinutes() > 30) {
            priceAfter = market.getOutcomeYesPrice();
        } else {
            Instant afterEnd = newsTime.plus(Duration.ofMinutes(corrConfig.getAfterWindowMinutes()));
            if (afterEnd.isAfter(now)) {
                afterEnd = now;
            }

            List<PriceTick> afterTicks = priceTickRepository
                    .findByMarketIdAndTimestampBetweenOrderByTimestampAsc(
                            market.getId(), newsTime, afterEnd);
            if (!afterTicks.isEmpty()) {
                priceAfter = afterTicks.get(afterTicks.size() - 1).getPrice();
            } else {
                return false;
            }
        }

        BigDecimal priceDelta = priceAfter.subtract(priceBefore);

        if (priceDelta.abs().doubleValue() < corrConfig.getMinPriceDelta()) {
            log.debug("Price delta too small for market '{}': {}",
                    truncate(market.getQuestion(), 40), priceDelta);
            return false;
        }

        // Confidence score
        // Combines LLM relevance score with price magnitude
        double magnitudeScore = Math.min(priceDelta.abs().doubleValue() / 0.10, 1.0);
        double confidence = (llmScore * 0.6) + (magnitudeScore * 0.4);

        // Light penalty if no tick data — relying on synced price
        if (beforeTicks.isEmpty()) {
            confidence *= 0.9;
        }

        if (confidence < corrConfig.getMinConfidence()) {
            return false;
        }

        long timeWindowMs = Duration.between(newsTime, now).toMillis();

        Correlation correlation = Correlation.builder()
                .marketId(market.getId())
                .newsEventId(newsEvent.getId())
                .priceBefore(priceBefore)
                .priceAfter(priceAfter)
                .priceDelta(priceDelta)
                .timeWindowMs((int) Math.min(timeWindowMs, Integer.MAX_VALUE))
                .confidence(BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP))
                .detectedAt(Instant.now())
                .reasoning(reasoning)
                .build();

        correlation = correlationRepository.save(correlation);

        log.info("✓ Correlation: '{}' → '{}' | Δ={} ({} → {}), conf={} | {}",
                truncate(newsEvent.getHeadline(), 40),
                truncate(market.getQuestion(), 40),
                priceDelta.setScale(4, RoundingMode.HALF_UP),
                priceBefore.setScale(3, RoundingMode.HALF_UP),
                priceAfter.setScale(3, RoundingMode.HALF_UP),
                String.format("%.3f", confidence),
                truncate(reasoning, 80));

        eventPublisher.publishEvent(new CorrelationDetectedEvent(
                this, correlation.getId(), market.getId(), market.getQuestion(),
                newsEvent.getId(), newsEvent.getHeadline(),
                priceBefore, priceAfter, priceDelta, confidence, correlation.getDetectedAt(),
                reasoning));

        return true;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
