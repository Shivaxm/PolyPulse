package com.polypulse.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordExtractorTest {

    private final KeywordExtractor extractor = new KeywordExtractor();

    @Test
    void extract_normalHeadline_returnsKeywordsWithoutStopwords() {
        List<String> out = extractor.extract("Trump announces new tariffs on Chinese goods");

        assertThat(out).contains("trump", "announces", "tariffs", "chinese", "goods");
        assertThat(out).doesNotContain("on", "new");
    }

    @Test
    void extract_stopwordRemoved_excludesThe() {
        List<String> out = extractor.extract("The quick brown fox");

        assertThat(out).contains("quick", "brown");
        assertThat(out).doesNotContain("the", "fox");
    }

    @Test
    void extract_shortTokens_filteredByMinLength() {
        List<String> out = extractor.extract("US war tax");

        assertThat(out).isEmpty();
    }

    @Test
    void extract_nullOrBlank_returnsEmptyList() {
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract("   ")).isEmpty();
    }

    @Test
    void extract_specialCharacters_handlesPunctuation() {
        List<String> out = extractor.extract("Biden's $2B plan — what's next?");

        assertThat(out).contains("biden", "plan", "next");
        assertThat(out).doesNotContain("2b");
    }

    @Test
    void extract_allStopwords_returnsEmpty() {
        List<String> out = extractor.extract("The is are was");

        assertThat(out).isEmpty();
    }

    @Test
    void extract_duplicateTokens_keepsDuplicatesInCurrentImplementation() {
        List<String> out = extractor.extract("Trump Trump tariffs tariffs");

        assertThat(out).containsExactly("trump", "trump", "tariffs", "tariffs");
    }

    @Test
    void extract_mixedCase_lowercasesOutput() {
        List<String> out = extractor.extract("BREAKING: NATO Responds");

        assertThat(out).contains("breaking", "nato", "responds");
        assertThat(out).allMatch(s -> s.equals(s.toLowerCase()));
    }
}
