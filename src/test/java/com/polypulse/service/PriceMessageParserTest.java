package com.polypulse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PriceMessageParserTest {

    private final PriceMessageParser parser = new PriceMessageParser(new ObjectMapper());
    private final Map<String, Long> known = Map.of("token123", 1L, "token456", 2L);

    @Test
    void parse_lastTradePrice_normalEvent_parsesTick() {
        String msg = "{\"event_type\":\"last_trade_price\",\"asset_id\":\"token123\",\"price\":\"0.456\",\"timestamp\":\"1750428146322\"}";

        List<PriceMessageParser.ParsedTick> ticks = parser.parse(msg, known);

        assertThat(ticks).hasSize(1);
        assertThat(ticks.get(0).assetId()).isEqualTo("token123");
        assertThat(ticks.get(0).price()).isEqualByComparingTo("0.456");
        assertThat(ticks.get(0).timestamp()).isEqualTo(Instant.ofEpochMilli(1750428146322L));
    }

    @Test
    void parse_lastTradePrice_unknownAsset_returnsEmpty() {
        String msg = "{\"event_type\":\"last_trade_price\",\"asset_id\":\"unknown\",\"price\":\"0.456\",\"timestamp\":\"1750428146322\"}";

        assertThat(parser.parse(msg, known)).isEmpty();
    }

    @Test
    void parse_lastTradePrice_missingPrice_returnsEmpty() {
        String msg = "{\"event_type\":\"last_trade_price\",\"asset_id\":\"token123\",\"timestamp\":\"1750428146322\"}";

        assertThat(parser.parse(msg, known)).isEmpty();
    }

    @Test
    void parse_lastTradePrice_missingAsset_returnsEmpty() {
        String msg = "{\"event_type\":\"last_trade_price\",\"price\":\"0.456\",\"timestamp\":\"1750428146322\"}";

        assertThat(parser.parse(msg, known)).isEmpty();
    }

    @Test
    void parse_priceChangeNewSchema_computesMidpoint() {
        String msg = "{\"event_type\":\"price_change\",\"price_changes\":[{\"asset_id\":\"token123\",\"best_bid\":\"0.48\",\"best_ask\":\"0.52\"}],\"timestamp\":\"1750000000000\"}";

        List<PriceMessageParser.ParsedTick> ticks = parser.parse(msg, known);

        assertThat(ticks).hasSize(1);
        assertThat(ticks.get(0).price()).isEqualByComparingTo("0.500000");
    }

    @Test
    void parse_priceChangeNewSchema_zeroBid_skipsTick() {
        String msg = "{\"event_type\":\"price_change\",\"price_changes\":[{\"asset_id\":\"token123\",\"best_bid\":\"0\",\"best_ask\":\"0.52\"}],\"timestamp\":\"1750000000000\"}";

        assertThat(parser.parse(msg, known)).isEmpty();
    }

    @Test
    void parse_priceChangeNewSchema_zeroAsk_skipsTick() {
        String msg = "{\"event_type\":\"price_change\",\"price_changes\":[{\"asset_id\":\"token123\",\"best_bid\":\"0.48\",\"best_ask\":\"0\"}],\"timestamp\":\"1750000000000\"}";

        assertThat(parser.parse(msg, known)).isEmpty();
    }

    @Test
    void parse_priceChangeNewSchema_multipleElements_parsesEachValid() {
        String msg = "{\"event_type\":\"price_change\",\"price_changes\":["
                + "{\"asset_id\":\"token123\",\"best_bid\":\"0.48\",\"best_ask\":\"0.52\"},"
                + "{\"asset_id\":\"token456\",\"best_bid\":\"0.40\",\"best_ask\":\"0.60\"}],"
                + "\"timestamp\":\"1750000000000\"}";

        List<PriceMessageParser.ParsedTick> ticks = parser.parse(msg, known);

        assertThat(ticks).hasSize(2);
        assertThat(ticks).extracting(PriceMessageParser.ParsedTick::assetId)
                .containsExactlyInAnyOrder("token123", "token456");
        assertThat(ticks).extracting(PriceMessageParser.ParsedTick::price)
                .containsExactlyInAnyOrder(new BigDecimal("0.500000"), new BigDecimal("0.500000"));
    }

    @Test
    void parse_priceChangeNewSchema_missingBidOnOneElement_skipsOnlyMalformedElement() {
        String msg = "{\"event_type\":\"price_change\",\"price_changes\":["
                + "{\"asset_id\":\"token123\",\"best_ask\":\"0.52\"},"
                + "{\"asset_id\":\"token456\",\"best_bid\":\"0.40\",\"best_ask\":\"0.60\"}],"
                + "\"timestamp\":\"1750000000000\"}";

        List<PriceMessageParser.ParsedTick> ticks = parser.parse(msg, known);

        assertThat(ticks).hasSize(1);
        assertThat(ticks.get(0).assetId()).isEqualTo("token456");
    }

    @Test
    void parse_legacyPriceChange_parsesTick() {
        String msg = "{\"event_type\":\"price_change\",\"asset_id\":\"token123\",\"price\":\"0.5\",\"timestamp\":\"1750000000000\"}";

        List<PriceMessageParser.ParsedTick> ticks = parser.parse(msg, known);

        assertThat(ticks).hasSize(1);
        assertThat(ticks.get(0).price()).isEqualByComparingTo("0.5");
    }

    @Test
    void parse_topLevelArray_mixedEvents_ignoresUnsupported() {
        String msg = "["
                + "{\"event_type\":\"last_trade_price\",\"asset_id\":\"token123\",\"price\":\"0.4\",\"timestamp\":\"1750000000000\"},"
                + "{\"event_type\":\"book\",\"asset_id\":\"token123\"}"
                + "]";

        List<PriceMessageParser.ParsedTick> ticks = parser.parse(msg, known);

        assertThat(ticks).hasSize(1);
        assertThat(ticks.get(0).assetId()).isEqualTo("token123");
    }

    @Test
    void parse_invalidJson_returnsEmpty() {
        assertThat(parser.parse("{this is not json", known)).isEmpty();
    }

    @Test
    void parse_emptyArray_returnsEmpty() {
        assertThat(parser.parse("[]", known)).isEmpty();
    }

    @Test
    void parse_unknownEventType_returnsEmpty() {
        String msg = "{\"event_type\":\"unknown\",\"asset_id\":\"token123\"}";

        assertThat(parser.parse(msg, known)).isEmpty();
    }

    @Test
    void parseTimestamp_isoFormat_parsesInstant() throws Exception {
        var node = new ObjectMapper().readTree("{\"timestamp\":\"2025-06-20T12:00:00Z\"}");

        Instant ts = parser.parseTimestamp(node);

        assertThat(ts).isEqualTo(Instant.parse("2025-06-20T12:00:00Z"));
    }

    @Test
    void parseTimestamp_missingTimestamp_returnsNowApprox() throws Exception {
        var node = new ObjectMapper().readTree("{}");
        Instant before = Instant.now().minusSeconds(1);

        Instant ts = parser.parseTimestamp(node);

        Instant after = Instant.now().plusSeconds(1);
        assertThat(ts).isAfter(before);
        assertThat(ts).isBefore(after);
    }
}
