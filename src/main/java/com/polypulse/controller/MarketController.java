package com.polypulse.controller;

import com.polypulse.dto.CorrelationDTO;
import com.polypulse.dto.CorrelationMapper;
import com.polypulse.dto.MarketDTO;
import com.polypulse.dto.PricePointDTO;
import com.polypulse.model.Market;
import com.polypulse.model.PriceTick;
import com.polypulse.repository.CorrelationRepository;
import com.polypulse.repository.MarketRepository;
import com.polypulse.repository.PriceTickRepository;
import com.polypulse.service.MarketCacheService;
import com.polypulse.service.PriceCacheService;
import com.polypulse.service.PriceBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/markets")
@RequiredArgsConstructor
@Slf4j
public class MarketController {

    private final MarketRepository marketRepository;
    private final PriceTickRepository priceTickRepository;
    private final CorrelationRepository correlationRepository;
    private final PriceBackfillService priceBackfillService;
    private final MarketCacheService marketCacheService;
    private final PriceCacheService priceCacheService;

    @GetMapping
    public List<MarketDTO> getMarkets(@RequestParam(required = false) String category) {
        List<Market> allActiveMarkets = marketCacheService.getActiveMarkets();
        List<Market> markets = allActiveMarkets;

        if (category != null && !category.isBlank()) {
            markets = markets.stream()
                    .filter(m -> category.equalsIgnoreCase(m.getCategory()))
                    .toList();
        }

        Set<Long> marketsWithCorrelations = marketCacheService.getCorrelationMarketIds();
        Map<Long, List<MarketDTO.SparklinePoint>> sparklines = marketCacheService.getSparklines();

        // Get live prices from in-memory cache (instant)
        Map<Long, PriceCacheService.CachedPrice> livePrices = priceCacheService.getAllLatestPrices();

        return markets.stream().map(m -> {
            PriceCacheService.CachedPrice livePrice = livePrices.get(m.getId());
            BigDecimal currentPrice = livePrice != null ? livePrice.price() : m.getOutcomeYesPrice();

            return MarketDTO.builder()
                    .id(m.getId())
                    .question(m.getQuestion())
                    .yesPrice(currentPrice)
                    .noPrice(m.getOutcomeNoPrice())
                    .volume24h(m.getVolume24h())
                    .liquidity(m.getLiquidity())
                    .category(m.getCategory())
                    .resolved(Boolean.TRUE.equals(m.getResolved()))
                    .createdAtSource(m.getCreatedAtSource())
                    .hasRecentCorrelation(marketsWithCorrelations.contains(m.getId()))
                    .lastUpdated(livePrice != null ? livePrice.timestamp() : m.getLastSyncedAt())
                    .sparkline(sparklines.getOrDefault(m.getId(), List.of()))
                    .build();
        }).toList();
    }

    @GetMapping("/{id}")
    public MarketDTO getMarket(@PathVariable Long id) {
        Market market = marketRepository.findById(id)
                .orElseThrow(() -> new GlobalExceptionHandler.MarketNotFoundException(id));

        Instant oneDayAgo = Instant.now().minus(Duration.ofDays(1));
        boolean hasCorrelation = correlationRepository.existsByMarketIdAndDetectedAtAfter(market.getId(), oneDayAgo);

        return MarketDTO.builder()
                .id(market.getId())
                .question(market.getQuestion())
                .yesPrice(market.getOutcomeYesPrice())
                .noPrice(market.getOutcomeNoPrice())
                .volume24h(market.getVolume24h())
                .liquidity(market.getLiquidity())
                .category(market.getCategory())
                .resolved(Boolean.TRUE.equals(market.getResolved()))
                .createdAtSource(market.getCreatedAtSource())
                .hasRecentCorrelation(hasCorrelation)
                .lastUpdated(market.getLastSyncedAt())
                .build();
    }

    @GetMapping("/{id}/prices")
    public List<PricePointDTO> getPriceHistory(@PathVariable Long id,
                                                @RequestParam(defaultValue = "24h") String range) {
        Market market = marketRepository.findById(id)
                .orElseThrow(() -> new GlobalExceptionHandler.MarketNotFoundException(id));

        Instant start;
        String interval;

        switch (range) {
            case "1h" -> { start = Instant.now().minus(Duration.ofHours(1)); interval = "1 minute"; }
            case "6h" -> { start = Instant.now().minus(Duration.ofHours(6)); interval = "5 minutes"; }
            case "24h" -> { start = Instant.now().minus(Duration.ofDays(1)); interval = "15 minutes"; }
            case "7d" -> { start = Instant.now().minus(Duration.ofDays(7)); interval = "1 hour"; }
            default -> throw new IllegalArgumentException("Invalid range: " + range + ". Use 1h, 6h, 24h, or 7d");
        };

        // Backfill from Polymarket API if we don't have enough local data
        priceBackfillService.backfillIfNeeded(market, start);

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

        List<Object[]> rows;
        try {
            rows = priceTickRepository.findBucketed(id, start, interval);
        } catch (Exception e) {
            log.warn("date_bin query failed, falling back to date_trunc: {}", e.getMessage());
            String fallbackInterval = switch (range) {
                case "1h", "6h" -> "minute";
                case "24h" -> "hour";
                case "7d" -> "hour";
                default -> "hour";
            };
            rows = priceTickRepository.findBucketedFallback(id, start, fallbackInterval);
        }

        return rows.stream().map(row -> PricePointDTO.builder()
                .timestamp(toInstant(row[0]))
                .price(row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO)
                .volume(row[2] != null ? (BigDecimal) row[2] : BigDecimal.ZERO)
                .build()
        ).toList();
    }

    @GetMapping("/{id}/correlations")
    public Map<String, Object> getMarketCorrelations(@PathVariable Long id,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        marketRepository.findById(id)
                .orElseThrow(() -> new GlobalExceptionHandler.MarketNotFoundException(id));

        List<Object[]> rows = correlationRepository.findCorrelationsWithDetailsByMarketId(id, size, page * size);
        long total = correlationRepository.countCorrelationsByMarketId(id);

        List<CorrelationDTO> content = rows.stream().map(CorrelationMapper::fromRow).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("totalElements", total);
        result.put("totalPages", (int) Math.ceil((double) total / size));
        result.put("number", page);
        result.put("size", size);
        result.put("last", (page + 1) * size >= total);
        return result;
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return Instant.parse(value.toString());
    }
}
