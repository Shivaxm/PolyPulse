package com.polypulse.controller;

import com.polypulse.dto.MarketDTO;
import com.polypulse.model.Market;
import com.polypulse.repository.CorrelationRepository;
import com.polypulse.repository.MarketRepository;
import com.polypulse.repository.PriceTickRepository;
import com.polypulse.service.MarketCacheService;
import com.polypulse.service.PriceBackfillService;
import com.polypulse.service.PriceCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MarketController.class)
class MarketControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean MarketRepository marketRepository;
    @MockBean PriceTickRepository priceTickRepository;
    @MockBean CorrelationRepository correlationRepository;
    @MockBean PriceBackfillService priceBackfillService;
    @MockBean MarketCacheService marketCacheService;
    @MockBean PriceCacheService priceCacheService;

    @Test
    void getMarkets_returns200WithArray() throws Exception {
        Market m1 = market(1L, "Will Trump win?", "politics", new BigDecimal("1000"));
        when(marketCacheService.getActiveMarkets()).thenReturn(List.of(m1));
        when(marketCacheService.getCorrelationMarketIds()).thenReturn(Set.of(1L));
        when(marketCacheService.getSparklines()).thenReturn(Map.of(
                1L, List.of(MarketDTO.SparklinePoint.builder()
                        .timestamp(Instant.parse("2026-03-01T10:00:00Z"))
                        .price(new BigDecimal("0.50"))
                        .build())
        ));
        when(priceCacheService.getAllLatestPrices()).thenReturn(Map.of());

        mockMvc.perform(get("/api/markets"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].question").value("Will Trump win?"))
                .andExpect(jsonPath("$[0].category").value("politics"))
                .andExpect(jsonPath("$[0].hasRecentCorrelation").value(true));
    }

    @Test
    void getMarkets_categoryFilter_filtersCorrectly() throws Exception {
        Market p = market(1L, "Will A?", "politics", new BigDecimal("10"));
        Market c = market(2L, "Will B?", "crypto", new BigDecimal("20"));
        when(marketCacheService.getActiveMarkets()).thenReturn(List.of(p, c));
        when(marketCacheService.getCorrelationMarketIds()).thenReturn(Set.of());
        when(marketCacheService.getSparklines()).thenReturn(Map.of());
        when(priceCacheService.getAllLatestPrices()).thenReturn(Map.of());

        mockMvc.perform(get("/api/markets").param("category", "politics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].category").value("politics"));
    }

    @Test
    void getMarkets_emptyList_returnsEmptyArray() throws Exception {
        when(marketCacheService.getActiveMarkets()).thenReturn(List.of());
        when(marketCacheService.getCorrelationMarketIds()).thenReturn(Set.of());
        when(marketCacheService.getSparklines()).thenReturn(Map.of());
        when(priceCacheService.getAllLatestPrices()).thenReturn(Map.of());

        mockMvc.perform(get("/api/markets"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void getMarkets_nullVolume_serializesAsNull() throws Exception {
        Market m = market(1L, "Will Trump win?", "politics", null);
        when(marketCacheService.getActiveMarkets()).thenReturn(List.of(m));
        when(marketCacheService.getCorrelationMarketIds()).thenReturn(Set.of());
        when(marketCacheService.getSparklines()).thenReturn(Map.of());
        when(priceCacheService.getAllLatestPrices()).thenReturn(Map.of());

        mockMvc.perform(get("/api/markets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].volume24h").doesNotExist());
    }

    @Test
    void getMarket_knownId_returns200() throws Exception {
        when(marketRepository.findById(1L)).thenReturn(Optional.of(market(1L, "Will Trump win?", "politics", new BigDecimal("100"))));
        when(correlationRepository.existsByMarketIdAndDetectedAtAfter(eq(1L), any())).thenReturn(true);

        mockMvc.perform(get("/api/markets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.hasRecentCorrelation").value(true));
    }

    @Test
    void getMarket_unknownId_returns404() throws Exception {
        when(marketRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/markets/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Market with id 999 not found"));
    }

    @Test
    void getPrices_invalidRange_returns400() throws Exception {
        when(marketRepository.findById(1L)).thenReturn(Optional.of(market(1L, "Will Trump win?", "politics", new BigDecimal("100"))));

        mockMvc.perform(get("/api/markets/1/prices").param("range", "3d"))
                .andExpect(status().isBadRequest());
    }

    private Market market(Long id, String q, String category, BigDecimal vol24h) {
        return Market.builder()
                .id(id)
                .conditionId("cond-" + id)
                .clobTokenId("token-" + id)
                .question(q)
                .category(category)
                .active(true)
                .outcomeYesPrice(new BigDecimal("0.50"))
                .outcomeNoPrice(new BigDecimal("0.50"))
                .volume24h(vol24h)
                .lastSyncedAt(Instant.parse("2026-03-01T10:00:00Z"))
                .build();
    }
}
