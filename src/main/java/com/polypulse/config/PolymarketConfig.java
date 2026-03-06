package com.polypulse.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "polypulse")
public class PolymarketConfig {

    private Polymarket polymarket = new Polymarket();
    private Sync sync = new Sync();
    private News news = new News();
    private Correlation correlation = new Correlation();

    @Data
    public static class Polymarket {
        private String gammaUrl = "https://gamma-api.polymarket.com";
        private String clobUrl = "https://clob.polymarket.com";
        private String wsUrl = "wss://ws-subscriptions-clob.polymarket.com/ws/market";
    }

    @Data
    public static class Sync {
        private long marketIntervalMs = 300000;
        private Map<String, Integer> categoryTags = new LinkedHashMap<>(Map.of(
                "politics", 2,
                "crypto", 21,
                "sports", 100639,
                "finance", 120,
                "tech", 1401,
                "culture", 596,
                "geopolitics", 100265
        ));
    }

    @Data
    public static class News {
        private String apiKey;
        private long pollIntervalMs = 60000;
        private String provider = "newsapi";
    }

    @Data
    public static class Correlation {
        private double minPriceDelta = 0.03;
        private int beforeWindowMinutes = 10;
        private int afterWindowMinutes = 30;
        private double minConfidence = 0.5;
        private int maxCandidateMarkets = 20;
        private int recheckDelayMinutes = 30;
        private int cooldownMinutes = 30;
    }
}
