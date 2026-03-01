package com.polypulse.controller;

import com.polypulse.dto.CorrelationDTO;
import com.polypulse.dto.MarketDTO;
import com.polypulse.dto.PricePointDTO;
import com.polypulse.model.Correlation;
import com.polypulse.model.Market;
import com.polypulse.model.NewsEvent;
import com.polypulse.model.PriceTick;
import com.polypulse.repository.CorrelationRepository;
import com.polypulse.repository.MarketRepository;
import com.polypulse.repository.NewsEventRepository;
import com.polypulse.repository.PriceTickRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/markets")
@RequiredArgsConstructor
public class MarketController {

    private final MarketRepository marketRepository;
    private final PriceTickRepository priceTickRepository;
    private final CorrelationRepository correlationRepository;
    private final NewsEventRepository newsEventRepository;

    @GetMapping
    // TODO: Re-enable @Cacheable when Redis serialization for Instant is configured
    public List<MarketDTO> getMarkets(@RequestParam(required = false) String category) {
        List<Market> markets = marketRepository.findByActiveTrue();

        if (category != null && !category.isBlank()) {
            markets = markets.stream()
                    .filter(m -> category.equalsIgnoreCase(m.getCategory()))
                    .toList();
        }

        Instant oneDayAgo = Instant.now().minus(Duration.ofDays(1));

        return markets.stream().map(m -> MarketDTO.builder()
                .id(m.getId())
                .question(m.getQuestion())
                .yesPrice(m.getOutcomeYesPrice())
                .noPrice(m.getOutcomeNoPrice())
                .volume24h(m.getVolume24h())
                .category(m.getCategory())
                .hasRecentCorrelation(hasRecentCorrelation(m.getId(), oneDayAgo))
                .lastUpdated(m.getLastSyncedAt())
                .build()
        ).toList();
    }

    @GetMapping("/{id}")
    public MarketDTO getMarket(@PathVariable Long id) {
        Market market = marketRepository.findById(id)
                .orElseThrow(() -> new GlobalExceptionHandler.MarketNotFoundException(id));

        Instant oneDayAgo = Instant.now().minus(Duration.ofDays(1));

        return MarketDTO.builder()
                .id(market.getId())
                .question(market.getQuestion())
                .yesPrice(market.getOutcomeYesPrice())
                .noPrice(market.getOutcomeNoPrice())
                .volume24h(market.getVolume24h())
                .category(market.getCategory())
                .hasRecentCorrelation(hasRecentCorrelation(market.getId(), oneDayAgo))
                .lastUpdated(market.getLastSyncedAt())
                .build();
    }

    @GetMapping("/{id}/prices")
    public List<PricePointDTO> getPriceHistory(@PathVariable Long id,
                                                @RequestParam(defaultValue = "24h") String range) {
        // Verify market exists
        marketRepository.findById(id)
                .orElseThrow(() -> new GlobalExceptionHandler.MarketNotFoundException(id));

        Instant start;
        String interval;

        switch (range) {
            case "1h" -> { start = Instant.now().minus(Duration.ofHours(1)); interval = "minute"; }
            case "6h" -> { start = Instant.now().minus(Duration.ofHours(6)); interval = "minute"; }
            case "24h" -> { start = Instant.now().minus(Duration.ofDays(1)); interval = "15 minutes"; }
            case "7d" -> { start = Instant.now().minus(Duration.ofDays(7)); interval = "hour"; }
            default -> throw new IllegalArgumentException("Invalid range: " + range + ". Use 1h, 6h, 24h, or 7d");
        };

        // For short ranges, return individual ticks if few enough
        if ("1h".equals(range) || "6h".equals(range)) {
            List<PriceTick> ticks = priceTickRepository
                    .findByMarketIdAndTimestampBetweenOrderByTimestampDesc(id, start, Instant.now());
            if (ticks.size() <= 500) {
                return ticks.stream().map(t -> PricePointDTO.builder()
                        .timestamp(t.getTimestamp())
                        .price(t.getPrice())
                        .volume(t.getVolume())
                        .build()
                ).toList();
            }
        }

        // Use bucketed query
        List<Object[]> rows = priceTickRepository.findBucketed(id, start, interval);
        return rows.stream().map(row -> PricePointDTO.builder()
                .timestamp(((java.sql.Timestamp) row[0]).toInstant())
                .price(row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO)
                .volume(row[2] != null ? (BigDecimal) row[2] : BigDecimal.ZERO)
                .build()
        ).toList();
    }

    @GetMapping("/{id}/correlations")
    public Page<CorrelationDTO> getMarketCorrelations(@PathVariable Long id,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        marketRepository.findById(id)
                .orElseThrow(() -> new GlobalExceptionHandler.MarketNotFoundException(id));

        return correlationRepository.findByMarketIdOrderByDetectedAtDesc(id, PageRequest.of(page, size))
                .map(this::toCorrelationDTO);
    }

    private boolean hasRecentCorrelation(Long marketId, Instant since) {
        Page<Correlation> recent = correlationRepository
                .findByMarketIdOrderByDetectedAtDesc(marketId, PageRequest.of(0, 1));
        return recent.hasContent() && recent.getContent().getFirst().getDetectedAt().isAfter(since);
    }

    private CorrelationDTO toCorrelationDTO(Correlation c) {
        Market market = marketRepository.findById(c.getMarketId()).orElse(null);
        NewsEvent news = newsEventRepository.findById(c.getNewsEventId()).orElse(null);

        return CorrelationDTO.builder()
                .id(c.getId())
                .market(market != null ? CorrelationDTO.MarketSummary.builder()
                        .id(market.getId()).question(market.getQuestion()).build() : null)
                .news(news != null ? CorrelationDTO.NewsSummary.builder()
                        .headline(news.getHeadline()).source(news.getSource())
                        .url(news.getUrl()).publishedAt(news.getPublishedAt()).build() : null)
                .priceBefore(c.getPriceBefore())
                .priceAfter(c.getPriceAfter())
                .priceDelta(c.getPriceDelta())
                .confidence(c.getConfidence())
                .detectedAt(c.getDetectedAt())
                .build();
    }
}
