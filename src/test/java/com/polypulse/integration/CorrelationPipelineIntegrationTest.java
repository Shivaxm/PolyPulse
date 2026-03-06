package com.polypulse.integration;

import com.polypulse.model.Correlation;
import com.polypulse.model.Market;
import com.polypulse.model.NewsEvent;
import com.polypulse.model.PriceTick;
import com.polypulse.repository.CorrelationRepository;
import com.polypulse.repository.MarketRepository;
import com.polypulse.repository.NewsEventRepository;
import com.polypulse.repository.PriceTickRepository;
import com.polypulse.service.CorrelationEngine;
import com.polypulse.service.LlmRelevanceService;
import com.polypulse.service.MarketSyncService;
import com.polypulse.service.NewsIngestionService;
import com.polypulse.service.PriceIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class CorrelationPipelineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "63790");
        registry.add("polypulse.news.api-key", () -> "");
        registry.add("anthropic.api-key", () -> "test-key");
    }

    @Autowired MarketRepository marketRepository;
    @Autowired PriceTickRepository priceTickRepository;
    @Autowired NewsEventRepository newsEventRepository;
    @Autowired CorrelationRepository correlationRepository;
    @Autowired CorrelationEngine correlationEngine;

    @MockBean LlmRelevanceService llmRelevanceService;
    @MockBean PriceIngestionService priceIngestionService;
    @MockBean MarketSyncService marketSyncService;
    @MockBean NewsIngestionService newsIngestionService;

    @BeforeEach
    void clean() {
        correlationRepository.deleteAll();
        priceTickRepository.deleteAll();
        newsEventRepository.deleteAll();
        marketRepository.deleteAll();
    }

    @Test
    void fullPipeline_savesCorrelation_andPreventsDuplicate() {
        Instant now = Instant.now();
        Instant newsTime = now.minus(Duration.ofHours(1));

        Market market = marketRepository.save(Market.builder()
                .conditionId("cond-1")
                .clobTokenId("token-1")
                .question("Will US impose 50% tariffs on China?")
                .category("politics")
                .active(true)
                .outcomeYesPrice(null)
                .outcomeNoPrice(null)
                .lastSyncedAt(now)
                .build());

        priceTickRepository.save(PriceTick.builder()
                .marketId(market.getId())
                .price(new BigDecimal("0.45"))
                .timestamp(newsTime.minus(Duration.ofMinutes(5)))
                .build());
        priceTickRepository.save(PriceTick.builder()
                .marketId(market.getId())
                .price(new BigDecimal("0.52"))
                .timestamp(newsTime.plus(Duration.ofMinutes(5)))
                .build());

        NewsEvent newsEvent = newsEventRepository.save(NewsEvent.builder()
                .headline("Trump announces 50% tariffs on all Chinese imports")
                .source("AP")
                .url("https://example.com/news/1")
                .publishedAt(newsTime)
                .ingestedAt(now)
                .keywords(List.of("trump", "announces", "tariffs", "chinese", "imports"))
                .category("general")
                .build());

        when(llmRelevanceService.checkRelevance(eq(newsEvent.getHeadline()), anyMap()))
                .thenReturn(List.of(new LlmRelevanceService.RelevantMarket(
                        market.getId(),
                        "Tariff announcement directly impacts the tariff prediction market",
                        0.85
                )));

        int found = (int) ReflectionTestUtils.invokeMethod(correlationEngine, "checkCorrelations", newsEvent);

        assertThat(found).isEqualTo(1);
        assertThat(correlationRepository.count()).isEqualTo(1);

        Correlation saved = correlationRepository.findAll().get(0);
        assertThat(saved.getMarketId()).isEqualTo(market.getId());
        assertThat(saved.getNewsEventId()).isEqualTo(newsEvent.getId());
        assertThat(saved.getPriceBefore()).isEqualByComparingTo("0.45");
        assertThat(saved.getPriceAfter()).isEqualByComparingTo("0.52");
        assertThat(saved.getPriceDelta()).isEqualByComparingTo("0.07");
        assertThat(saved.getReasoning().toLowerCase()).contains("tariff");

        int foundAgain = (int) ReflectionTestUtils.invokeMethod(correlationEngine, "checkCorrelations", newsEvent);
        assertThat(foundAgain).isEqualTo(0);
        assertThat(correlationRepository.count()).isEqualTo(1);
    }
}
