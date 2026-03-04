package com.polypulse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polypulse.config.PolymarketConfig;
import com.polypulse.event.MarketsSyncedEvent;
import com.polypulse.event.NewsIngestedEvent;
import com.polypulse.model.NewsEvent;
import com.polypulse.repository.NewsEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
@RequiredArgsConstructor
public class NewsIngestionService {

    private final NewsEventRepository newsEventRepository;
    private final PolymarketConfig config;
    private final KeywordExtractor keywordExtractor;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private final AtomicLong totalIngested = new AtomicLong(0);
    private final AtomicLong pollCycle = new AtomicLong(0);
    private final AtomicBoolean initialSeedDone = new AtomicBoolean(false);
    private volatile Instant lastIngestedAt;

    /**
     * Fetch news only after markets have been synced — fixes the race condition where
     * news was ingested before any markets existed, causing zero correlations.
     */
    @EventListener
    public void onMarketsSynced(MarketsSyncedEvent event) {
        if (initialSeedDone.compareAndSet(false, true)) {
            log.info("Markets synced. Starting initial news fetch...");
            fetchAndStoreNews();
        }
    }

    @Scheduled(fixedDelayString = "${polypulse.news.poll-interval-ms:900000}", initialDelay = 30000)
    public void pollNews() {
        fetchAndStoreNews();
    }

    private void fetchAndStoreNews() {
        String apiKey = config.getNews().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("NEWS_API_KEY not configured, skipping news ingestion");
            return;
        }

        try {
            String provider = config.getNews().getProvider();
            String url;

            if ("gnews".equals(provider)) {
                url = "https://gnews.io/api/v4/top-headlines?category=general&lang=en&max=10&apikey=" + apiKey;
            } else {
                String[] categories = {"general", "business", "technology", "science"};
                long cycle = pollCycle.getAndIncrement();
                String category = categories[(int) (cycle % categories.length)];
                url = "https://newsapi.org/v2/top-headlines?country=us&category=" + category
                        + "&pageSize=20&apiKey=" + apiKey;
                log.info("Fetching news category: {}", category);
            }

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (root.has("status") && "error".equals(root.get("status").asText())) {
                String msg = root.has("message") ? root.get("message").asText() : "unknown";
                log.error("NewsAPI error: {}", msg);
                return;
            }

            JsonNode articles = root.get("articles");
            if (articles == null || !articles.isArray()) {
                log.warn("No articles in API response");
                return;
            }

            int newCount = 0;
            for (JsonNode article : articles) {
                try {
                    String articleUrl = article.has("url") ? article.get("url").asText(null) : null;
                    if (articleUrl == null || articleUrl.isBlank()) {
                        continue;
                    }
                    if (newsEventRepository.existsByUrl(articleUrl)) {
                        continue;
                    }

                    String headline = article.has("title") ? article.get("title").asText("") : "";
                    if (headline.isBlank() || headline.equals("[Removed]")) {
                        continue;
                    }

                    String source = null;
                    if (article.has("source") && article.get("source").has("name")) {
                        source = article.get("source").get("name").asText(null);
                    }

                    Instant publishedAt = parsePublishedAt(article);
                    List<String> keywords = keywordExtractor.extract(headline);
                    if (keywords.isEmpty()) {
                        continue;
                    }

                    NewsEvent newsEvent = NewsEvent.builder()
                            .headline(headline)
                            .source(source)
                            .url(articleUrl)
                            .publishedAt(publishedAt)
                            .ingestedAt(Instant.now())
                            .keywords(keywords)
                            .category("general")
                            .build();

                    newsEvent = newsEventRepository.save(newsEvent);
                    newCount++;
                    totalIngested.incrementAndGet();
                    lastIngestedAt = Instant.now();

                    eventPublisher.publishEvent(new NewsIngestedEvent(
                            this, newsEvent.getId(), headline, keywords, publishedAt));

                } catch (Exception e) {
                    log.debug("Failed to process article: {}", e.getMessage());
                }
            }

            if (newCount > 0) {
                log.info("Ingested {} new articles (total: {})", newCount, totalIngested.get());
            }

        } catch (Exception e) {
            log.error("Failed to fetch news: {}", e.getMessage());
        }
    }

    private Instant parsePublishedAt(JsonNode article) {
        try {
            String dateStr = article.get("publishedAt").asText();
            return ZonedDateTime.parse(dateStr, DateTimeFormatter.ISO_DATE_TIME).toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }

    public long getTotalIngested() {
        return totalIngested.get();
    }

    public Instant getLastIngestedAt() {
        return lastIngestedAt;
    }
}
