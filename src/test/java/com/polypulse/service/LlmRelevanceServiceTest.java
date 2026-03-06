package com.polypulse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmRelevanceServiceTest {

    @Mock
    RestTemplate restTemplate;

    private LlmRelevanceService service;

    @BeforeEach
    void setUp() {
        service = new LlmRelevanceService(restTemplate, new ObjectMapper());
    }

    @Test
    void checkRelevance_apiKeyBlank_returnsEmptyList() {
        ReflectionTestUtils.setField(service, "apiKey", "");
        Map<Long, String> candidates = Map.of(1L, "Will Trump win?");

        List<LlmRelevanceService.RelevantMarket> out = service.checkRelevance("headline", candidates);

        assertThat(out).isEmpty();
        verifyNoInteractions(restTemplate);
    }

    @Test
    void checkRelevance_apiKeyPresent_makesSingleBatchCallAndBuildsPrompt() {
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        Map<Long, String> candidates = new LinkedHashMap<>();
        candidates.put(10L, "Will Trump win?");
        candidates.put(20L, "Will Fed cut rates?");
        candidates.put(30L, "Will Bitcoin hit 100k?");

        String body = "{\"content\":[{\"text\":\"[{\\\"index\\\":2,\\\"score\\\":0.85,\\\"reasoning\\\":\\\"Direct impact\\\"}]\"}]}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));

        List<LlmRelevanceService.RelevantMarket> out = service.checkRelevance("Fed signals no cut", candidates);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).marketId()).isEqualTo(20L);
        assertThat(out.get(0).relevanceScore()).isEqualTo(0.85);
        assertThat(out.get(0).reasoning()).isEqualTo("Direct impact");

        ArgumentCaptor<HttpEntity<String>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(1)).exchange(eq("https://api.anthropic.com/v1/messages"),
                eq(HttpMethod.POST), entityCaptor.capture(), eq(String.class));

        String requestJson = entityCaptor.getValue().getBody();
        assertThat(requestJson).contains("claude-haiku-4-5-20251001");
        assertThat(requestJson).contains("\"max_tokens\":1024");
        assertThat(requestJson).contains("strict financial relevance classifier");
        assertThat(requestJson).contains("1. Will Trump win?");
        assertThat(requestJson).contains("2. Will Fed cut rates?");
        assertThat(requestJson).contains("3. Will Bitcoin hit 100k?");
        assertThat(requestJson).contains("SpaceX launches from Florida");
    }

    @Test
    void checkRelevance_scoreBelowThreshold_filteredOut() {
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        Map<Long, String> candidates = Map.of(1L, "Will X happen?");

        String body = "{\"content\":[{\"text\":\"[{\\\"index\\\":1,\\\"score\\\":0.60,\\\"reasoning\\\":\\\"Weak link\\\"}]\"}]}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));

        assertThat(service.checkRelevance("headline", candidates)).isEmpty();
    }

    @Test
    void checkRelevance_invalidIndex_skipped() {
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        Map<Long, String> candidates = Map.of(1L, "Will X happen?");

        String body = "{\"content\":[{\"text\":\"[{\\\"index\\\":99,\\\"score\\\":0.90,\\\"reasoning\\\":\\\"bad\\\"}]\"}]}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));

        assertThat(service.checkRelevance("headline", candidates)).isEmpty();
    }

    @Test
    void checkRelevance_emptyArrayResponse_returnsEmpty() {
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"content\":[{\"text\":\"[]\"}]}"));

        assertThat(service.checkRelevance("headline", Map.of(1L, "Q"))).isEmpty();
    }

    @Test
    void checkRelevance_markdownFences_parsesSuccessfully() {
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        Map<Long, String> candidates = new LinkedHashMap<>();
        candidates.put(11L, "Q1");

        String fenced = "{\"content\":[{\"text\":\"```json\\n[{\\\"index\\\":1,\\\"score\\\":0.88,\\\"reasoning\\\":\\\"Direct\\\"}]\\n```\"}]}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(fenced));

        List<LlmRelevanceService.RelevantMarket> out = service.checkRelevance("headline", candidates);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).marketId()).isEqualTo(11L);
    }

    @Test
    void checkRelevance_nonArrayResponse_returnsEmpty() {
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"content\":[{\"text\":\"I cannot determine\"}]}"));

        assertThat(service.checkRelevance("headline", Map.of(1L, "Q"))).isEmpty();
    }

    @Test
    void checkRelevance_non200Response_returnsEmpty() {
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.BAD_REQUEST).body("bad"));

        assertThat(service.checkRelevance("headline", Map.of(1L, "Q"))).isEmpty();
    }

    @Test
    void checkRelevance_restTemplateThrows_returnsEmpty() {
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("boom"));

        assertThat(service.checkRelevance("headline", Map.of(1L, "Q"))).isEmpty();
    }
}
