package com.polypulse.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class KeywordExtractor {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "will", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "would", "could", "should",
            "may", "might", "can", "shall", "to", "of", "in", "for", "on", "at",
            "by", "with", "from", "as", "into", "about", "between", "after", "before",
            "during", "through", "above", "below", "up", "down", "out", "off", "over",
            "under", "and", "but", "or", "nor", "not", "so", "yet", "both", "either",
            "neither", "each", "every", "all", "any", "few", "more", "most", "no",
            "some", "such", "than", "too", "very", "just", "also", "now", "then",
            "here", "there", "when", "where", "why", "how", "what", "which", "who",
            "whom", "whose", "that", "this", "these", "those", "it", "its", "he",
            "she", "they", "we", "me", "my", "his", "her", "their", "our",
            "said", "says", "new", "news", "report", "reports", "according", "per",
            "via", "get", "got", "one", "two", "first", "last", "back", "still",
            "even", "many", "much", "well", "way", "only", "own", "other", "like"
    );

    public List<String> extract(String text) {
        if (text == null || text.isBlank()) return List.of();

        String[] tokens = text.toLowerCase().split("[^a-zA-Z0-9]+");
        List<String> keywords = new ArrayList<>();

        for (String token : tokens) {
            if (token.length() >= 3 && !STOPWORDS.contains(token)) {
                keywords.add(token);
            }
        }
        return keywords;
    }
}
