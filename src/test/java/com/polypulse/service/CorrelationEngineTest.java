package com.polypulse.service;

import com.polypulse.config.PolymarketConfig;
import com.polypulse.event.CorrelationDetectedEvent;
import com.polypulse.model.Correlation;
import com.polypulse.model.Market;
import com.polypulse.model.NewsEvent;
import com.polypulse.model.PriceTick;
import com.polypulse.repository.CorrelationRepository;
import com.polypulse.repository.MarketRepository;
import com.polypulse.repository.NewsEventRepository;
import com.polypulse.repository.PriceTickRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CorrelationEngineTest {

    @Mock MarketRepository marketRepository;
    @Mock PriceTickRepository priceTickRepository;
    @Mock NewsEventRepository newsEventRepository;
    @Mock CorrelationRepository correlationRepository;
    @Mock PriceBackfillService priceBackfillService;
    @Mock LlmRelevanceService llmRelevanceService;
    @Mock ApplicationEventPublisher eventPublisher;

    private CorrelationEngine engine;
    private PolymarketConfig config;

    @BeforeEach
    void setUp() {
        config = new PolymarketConfig();
        config.getCorrelation().setMinPriceDelta(0.01);
        config.getCorrelation().setBeforeWindowMinutes(15);
        config.getCorrelation().setAfterWindowMinutes(45);
        config.getCorrelation().setMinConfidence(0.3);
        config.getCorrelation().setMaxCandidateMarkets(30);

        engine = new CorrelationEngine(
                marketRepository,
                priceTickRepository,
                newsEventRepository,
                correlationRepository,
                priceBackfillService,
                llmRelevanceService,
                config,
                eventPublisher
        );
    }

    @Test
    void checkCorrelations_noKeywordMatch_llmNeverCalled() {
        NewsEvent news = makeNews(1L, "Fed signals pause", List.of("fed", "signals"));
        when(marketRepository.findByActiveTrue()).thenReturn(List.of(
                makeMarket(10L, "Will Trump win 2028?", "politics"),
                makeMarket(11L, "Will Bitcoin hit 200k?", "crypto")
        ));

        int found = engine.checkCorrelations(news);

        assertThat(found).isEqualTo(0);
        verifyNoInteractions(llmRelevanceService);
    }

    @Test
    void checkCorrelations_maxCandidateMarkets_capsCandidateCount() {
        config.getCorrelation().setMaxCandidateMarkets(2);
        NewsEvent news = makeNews(1L, "Trump tariffs update", List.of("trump", "tariffs"));
        when(marketRepository.findByActiveTrue()).thenReturn(List.of(
                makeMarket(1L, "Will Trump win?", "politics"),
                makeMarket(2L, "Will Trump debate Biden?", "politics"),
                makeMarket(3L, "Will Trump visit China?", "politics")
        ));
        when(llmRelevanceService.checkRelevance(anyString(), anyMap())).thenReturn(List.of());

        engine.checkCorrelations(news);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, String>> cap = ArgumentCaptor.forClass(Map.class);
        verify(llmRelevanceService).checkRelevance(eq(news.getHeadline()), cap.capture());
        assertThat(cap.getValue()).hasSize(2);
    }

    @Test
    void checkCorrelations_llmReturnsEmpty_noCorrelationSaved() {
        NewsEvent news = makeNews(1L, "Trump tariffs", List.of("trump", "tariffs"));
        when(marketRepository.findByActiveTrue()).thenReturn(List.of(
                makeMarket(1L, "Will Trump impose tariffs?", "politics")
        ));
        when(llmRelevanceService.checkRelevance(anyString(), anyMap())).thenReturn(List.of());

        int found = engine.checkCorrelations(news);

        assertThat(found).isEqualTo(0);
        verify(correlationRepository, never()).save(any());
    }

    @Test
    void checkCorrelations_llmThrows_returnsZeroAndDoesNotCrash() {
        NewsEvent news = makeNews(1L, "Trump tariffs", List.of("trump", "tariffs"));
        when(marketRepository.findByActiveTrue()).thenReturn(List.of(
                makeMarket(1L, "Will Trump impose tariffs?", "politics")
        ));
        when(llmRelevanceService.checkRelevance(anyString(), anyMap())).thenThrow(new RuntimeException("boom"));

        int found = engine.checkCorrelations(news);

        assertThat(found).isEqualTo(0);
        verify(correlationRepository, never()).save(any());
    }

    @Test
    void checkCorrelations_duplicatePair_skipsSave() {
        Market market = makeMarket(1L, "Will Trump impose tariffs?", "politics");
        market.setOutcomeYesPrice(null);
        NewsEvent news = makeNews(9L, "Trump tariffs", List.of("trump", "tariffs"));

        when(marketRepository.findByActiveTrue()).thenReturn(List.of(market));
        when(llmRelevanceService.checkRelevance(anyString(), anyMap()))
                .thenReturn(List.of(new LlmRelevanceService.RelevantMarket(1L, "Direct", 0.9)));
        when(correlationRepository.existsByMarketIdAndNewsEventId(1L, 9L)).thenReturn(true);

        int found = engine.checkCorrelations(news);

        assertThat(found).isEqualTo(0);
        verify(correlationRepository, never()).save(any());
    }

    @Test
    void checkCorrelations_marketInCooldown_skipsSave() {
        Market market = makeMarket(1L, "Will Trump impose tariffs?", "politics");
        market.setOutcomeYesPrice(null);
        NewsEvent news = makeNews(9L, "Trump tariffs", List.of("trump", "tariffs"));

        when(marketRepository.findByActiveTrue()).thenReturn(List.of(market));
        when(llmRelevanceService.checkRelevance(anyString(), anyMap()))
                .thenReturn(List.of(new LlmRelevanceService.RelevantMarket(1L, "Direct", 0.9)));
        when(correlationRepository.existsByMarketIdAndNewsEventId(1L, 9L)).thenReturn(false);
        when(correlationRepository.existsByMarketIdAndDetectedAtAfter(eq(1L), any())).thenReturn(true);

        int found = engine.checkCorrelations(news);

        assertThat(found).isEqualTo(0);
        verify(correlationRepository, never()).save(any());
        verifyNoInteractions(priceTickRepository);
    }

    @Test
    void checkCorrelations_cooldownUsesNewsPublishedAtReference() {
        Market market = makeMarket(1L, "Will Trump impose tariffs?", "politics");
        market.setOutcomeYesPrice(null);
        NewsEvent news = makeNews(9L, "Trump tariffs", List.of("trump", "tariffs"));
        Instant newsTime = news.getPublishedAt();

        when(marketRepository.findByActiveTrue()).thenReturn(List.of(market));
        when(llmRelevanceService.checkRelevance(anyString(), anyMap()))
                .thenReturn(List.of(new LlmRelevanceService.RelevantMarket(1L, "Direct impact", 0.85)));
        when(correlationRepository.existsByMarketIdAndNewsEventId(1L, 9L)).thenReturn(false);
        when(correlationRepository.existsByMarketIdAndDetectedAtAfter(eq(1L), any())).thenReturn(false);
        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampDesc(eq(1L), any(), eq(newsTime)))
                .thenReturn(List.of(makeTick(1L, new BigDecimal("0.45"), newsTime.minus(Duration.ofMinutes(1)))));
        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampAsc(eq(1L), eq(newsTime), any()))
                .thenReturn(List.of(makeTick(1L, new BigDecimal("0.52"), newsTime.plus(Duration.ofMinutes(5)))));
        when(correlationRepository.save(any(Correlation.class))).thenAnswer(inv -> inv.getArgument(0));

        int found = engine.checkCorrelations(news);

        assertThat(found).isEqualTo(1);
        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(correlationRepository).existsByMarketIdAndDetectedAtAfter(eq(1L), cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue())
                .isEqualTo(newsTime.minus(Duration.ofMinutes(config.getCorrelation().getCooldownMinutes())));
    }

    @Test
    void checkCorrelations_validRelevantMarket_savesAndPublishesEvent() {
        Market market = makeMarket(1L, "Will Trump impose tariffs?", "politics");
        market.setOutcomeYesPrice(null); // force after-window ticks path
        NewsEvent news = makeNews(9L, "Trump tariffs", List.of("trump", "tariffs"));

        when(marketRepository.findByActiveTrue()).thenReturn(List.of(market));
        when(llmRelevanceService.checkRelevance(anyString(), anyMap()))
                .thenReturn(List.of(new LlmRelevanceService.RelevantMarket(1L, "Direct impact", 0.85)));
        when(correlationRepository.existsByMarketIdAndNewsEventId(1L, 9L)).thenReturn(false);
        when(correlationRepository.existsByMarketIdAndDetectedAtAfter(eq(1L), any())).thenReturn(false);

        Instant newsTime = news.getPublishedAt();
        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampDesc(eq(1L), any(), eq(newsTime)))
                .thenReturn(List.of(makeTick(1L, new BigDecimal("0.45"), newsTime.minus(Duration.ofMinutes(1)))));
        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampAsc(eq(1L), eq(newsTime), any()))
                .thenReturn(List.of(makeTick(1L, new BigDecimal("0.52"), newsTime.plus(Duration.ofMinutes(5)))));
        when(correlationRepository.save(any(Correlation.class))).thenAnswer(inv -> {
            Correlation c = inv.getArgument(0);
            c.setId(555L);
            return c;
        });

        int found = engine.checkCorrelations(news);

        assertThat(found).isEqualTo(1);
        ArgumentCaptor<Correlation> corrCap = ArgumentCaptor.forClass(Correlation.class);
        verify(correlationRepository).save(corrCap.capture());
        assertThat(corrCap.getValue().getPriceBefore()).isEqualByComparingTo("0.45");
        assertThat(corrCap.getValue().getPriceAfter()).isEqualByComparingTo("0.52");
        assertThat(corrCap.getValue().getPriceDelta()).isEqualByComparingTo("0.07");
        assertThat(corrCap.getValue().getReasoning()).contains("Direct");

        verify(eventPublisher).publishEvent(any(CorrelationDetectedEvent.class));
    }

    @Test
    void evaluateAndSave_priceDeltaBelowThreshold_returnsFalse() {
        Market market = makeMarket(1L, "Will X?", "politics");
        market.setOutcomeYesPrice(null);
        NewsEvent news = makeNews(1L, "Headline", List.of("headline"));
        Instant newsTime = news.getPublishedAt();

        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampDesc(eq(1L), any(), eq(newsTime)))
                .thenReturn(List.of(makeTick(1L, new BigDecimal("0.5000"), newsTime.minusSeconds(60))));
        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampAsc(eq(1L), eq(newsTime), any()))
                .thenReturn(List.of(makeTick(1L, new BigDecimal("0.5050"), newsTime.plusSeconds(60))));

        boolean saved = engine.evaluateAndSave(market, news, "reason", 0.9);

        assertThat(saved).isFalse();
        verify(correlationRepository, never()).save(any());
    }

    @Test
    void evaluateAndSave_noTicksBeforeOrAfter_returnsFalse() {
        Market market = makeMarket(1L, "Will X?", "politics");
        market.setOutcomeYesPrice(null);
        NewsEvent news = makeNews(1L, "Headline", List.of("headline"));
        Instant newsTime = news.getPublishedAt();

        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampDesc(eq(1L), any(), eq(newsTime)))
                .thenReturn(List.of());
        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampDesc(eq(1L), eq(newsTime.minus(Duration.ofHours(24))), eq(newsTime)))
                .thenReturn(List.of());

        boolean saved = engine.evaluateAndSave(market, news, "reason", 0.9);

        assertThat(saved).isFalse();
    }

    @Test
    void evaluateAndSave_noBeforeTicks_usesWiderWindow() {
        Market market = makeMarket(1L, "Will X?", "politics");
        market.setOutcomeYesPrice(null);
        NewsEvent news = makeNews(1L, "Headline", List.of("headline"));
        Instant newsTime = news.getPublishedAt();

        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampDesc(eq(1L), any(), eq(newsTime)))
                .thenReturn(List.of());
        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampDesc(eq(1L), eq(newsTime.minus(Duration.ofHours(24))), eq(newsTime)))
                .thenReturn(List.of(makeTick(1L, new BigDecimal("0.40"), newsTime.minus(Duration.ofHours(3)))));
        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampAsc(eq(1L), eq(newsTime), any()))
                .thenReturn(List.of(makeTick(1L, new BigDecimal("0.50"), newsTime.plus(Duration.ofMinutes(3)))));
        when(correlationRepository.save(any(Correlation.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean saved = engine.evaluateAndSave(market, news, "reason", 0.9);

        assertThat(saved).isTrue();
        verify(priceTickRepository).findByMarketIdAndTimestampBetweenOrderByTimestampDesc(eq(1L), eq(newsTime.minus(Duration.ofHours(24))), eq(newsTime));
    }

    @Test
    void evaluateAndSave_confidenceCalculation_matchesExpected() {
        config.getCorrelation().setMinConfidence(0.0);
        Market market = makeMarket(1L, "Will X?", "politics");
        market.setOutcomeYesPrice(null);
        NewsEvent news = makeNews(1L, "Headline", List.of("headline"));
        Instant newsTime = news.getPublishedAt();

        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampDesc(eq(1L), any(), eq(newsTime)))
                .thenReturn(List.of(makeTick(1L, new BigDecimal("0.45"), newsTime.minus(Duration.ofMinutes(1)))));
        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampAsc(eq(1L), eq(newsTime), any()))
                .thenReturn(List.of(makeTick(1L, new BigDecimal("0.50"), newsTime.plus(Duration.ofMinutes(1)))));
        when(correlationRepository.save(any(Correlation.class))).thenAnswer(inv -> inv.getArgument(0));

        engine.evaluateAndSave(market, news, "reason", 0.85);

        ArgumentCaptor<Correlation> cap = ArgumentCaptor.forClass(Correlation.class);
        verify(correlationRepository).save(cap.capture());
        // delta 0.05 => magnitude 0.5 => confidence (0.85*0.6)+(0.5*0.4)=0.71
        assertThat(cap.getValue().getConfidence()).isEqualByComparingTo("0.710");
    }

    @Test
    void evaluateAndSave_noBeforeTicks_appliesPointNinePenalty() {
        config.getCorrelation().setMinConfidence(0.0);
        Market market = makeMarket(1L, "Will X?", "politics");
        market.setOutcomeYesPrice(null);
        NewsEvent news = makeNews(1L, "Headline", List.of("headline"));
        Instant newsTime = news.getPublishedAt();

        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampDesc(eq(1L), any(), eq(newsTime)))
                .thenReturn(List.of());
        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampDesc(eq(1L), eq(newsTime.minus(Duration.ofHours(24))), eq(newsTime)))
                .thenReturn(List.of(makeTick(1L, new BigDecimal("0.45"), newsTime.minus(Duration.ofHours(2)))));
        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampAsc(eq(1L), eq(newsTime), any()))
                .thenReturn(List.of(makeTick(1L, new BigDecimal("0.50"), newsTime.plus(Duration.ofMinutes(2)))));
        when(correlationRepository.save(any(Correlation.class))).thenAnswer(inv -> inv.getArgument(0));

        engine.evaluateAndSave(market, news, "reason", 0.85);

        ArgumentCaptor<Correlation> cap = ArgumentCaptor.forClass(Correlation.class);
        verify(correlationRepository).save(cap.capture());
        // base 0.71 * 0.9 = 0.639
        assertThat(cap.getValue().getConfidence()).isEqualByComparingTo("0.639");
    }

    @Test
    void evaluateAndSave_constraintViolation_returnsFalseAndDoesNotPublishEvent() {
        config.getCorrelation().setMinConfidence(0.0);
        Market market = makeMarket(1L, "Will X?", "politics");
        market.setOutcomeYesPrice(null);
        NewsEvent news = makeNews(1L, "Headline", List.of("headline"));
        Instant newsTime = news.getPublishedAt();

        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampDesc(eq(1L), any(), eq(newsTime)))
                .thenReturn(List.of(makeTick(1L, new BigDecimal("0.45"), newsTime.minus(Duration.ofMinutes(1)))));
        when(priceTickRepository.findByMarketIdAndTimestampBetweenOrderByTimestampAsc(eq(1L), eq(newsTime), any()))
                .thenReturn(List.of(makeTick(1L, new BigDecimal("0.50"), newsTime.plus(Duration.ofMinutes(1)))));
        when(correlationRepository.save(any(Correlation.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        boolean saved = engine.evaluateAndSave(market, news, "reason", 0.85);

        assertThat(saved).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
    }

    private Market makeMarket(Long id, String question, String category) {
        return Market.builder()
                .id(id)
                .conditionId("cond-" + id)
                .clobTokenId("token-" + id)
                .question(question)
                .category(category)
                .active(true)
                .outcomeYesPrice(new BigDecimal("0.50"))
                .outcomeNoPrice(new BigDecimal("0.50"))
                .lastSyncedAt(Instant.now())
                .build();
    }

    private NewsEvent makeNews(Long id, String headline, List<String> keywords) {
        return NewsEvent.builder()
                .id(id)
                .headline(headline)
                .keywords(new ArrayList<>(keywords))
                .publishedAt(Instant.now().minus(Duration.ofHours(1)))
                .ingestedAt(Instant.now())
                .build();
    }

    private PriceTick makeTick(Long marketId, BigDecimal price, Instant timestamp) {
        return PriceTick.builder()
                .marketId(marketId)
                .price(price)
                .timestamp(timestamp)
                .build();
    }
}
