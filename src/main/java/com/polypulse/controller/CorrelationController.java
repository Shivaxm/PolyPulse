package com.polypulse.controller;

import com.polypulse.dto.CorrelationDTO;
import com.polypulse.model.Correlation;
import com.polypulse.model.Market;
import com.polypulse.model.NewsEvent;
import com.polypulse.repository.CorrelationRepository;
import com.polypulse.repository.MarketRepository;
import com.polypulse.repository.NewsEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/correlations")
@RequiredArgsConstructor
public class CorrelationController {

    private final CorrelationRepository correlationRepository;
    private final MarketRepository marketRepository;
    private final NewsEventRepository newsEventRepository;

    @GetMapping("/recent")
    public Page<CorrelationDTO> getRecent(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return correlationRepository.findAllByOrderByDetectedAtDesc(PageRequest.of(page, size))
                .map(this::toDTO);
    }

    private CorrelationDTO toDTO(Correlation c) {
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
