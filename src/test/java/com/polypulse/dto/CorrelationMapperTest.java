package com.polypulse.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationMapperTest {

    @Test
    void fromRow_allColumns_mapsCorrectly() {
        Instant detected = Instant.parse("2026-03-01T10:00:00Z");
        Instant published = Instant.parse("2026-03-01T09:00:00Z");
        Object[] row = new Object[] {
                7L, 42L, 99L,
                new BigDecimal("0.450000"), new BigDecimal("0.520000"), new BigDecimal("0.070000"),
                600000, new BigDecimal("0.710"), Timestamp.from(detected),
                "Will tariffs increase?",
                "Trump announces tariffs", "AP", "https://example.com", Timestamp.from(published),
                "Direct policy announcement impacts this market"
        };

        CorrelationDTO dto = CorrelationMapper.fromRow(row);

        assertThat(dto.getId()).isEqualTo(7L);
        assertThat(dto.getMarket().getId()).isEqualTo(42L);
        assertThat(dto.getMarket().getQuestion()).isEqualTo("Will tariffs increase?");
        assertThat(dto.getNews().getHeadline()).isEqualTo("Trump announces tariffs");
        assertThat(dto.getNews().getSource()).isEqualTo("AP");
        assertThat(dto.getNews().getUrl()).isEqualTo("https://example.com");
        assertThat(dto.getNews().getPublishedAt()).isEqualTo(published);
        assertThat(dto.getPriceBefore()).isEqualByComparingTo("0.450000");
        assertThat(dto.getPriceAfter()).isEqualByComparingTo("0.520000");
        assertThat(dto.getPriceDelta()).isEqualByComparingTo("0.070000");
        assertThat(dto.getConfidence()).isEqualByComparingTo("0.710");
        assertThat(dto.getDetectedAt()).isEqualTo(detected);
        assertThat(dto.getReasoning()).contains("Direct policy");
    }

    @Test
    void fromRow_nullSource_keepsNull() {
        Object[] row = baseRow();
        row[11] = null;

        CorrelationDTO dto = CorrelationMapper.fromRow(row);

        assertThat(dto.getNews().getSource()).isNull();
    }

    @Test
    void fromRow_nullReasoning_keepsNull() {
        Object[] row = baseRow();
        row[14] = null;

        CorrelationDTO dto = CorrelationMapper.fromRow(row);

        assertThat(dto.getReasoning()).isNull();
    }

    @Test
    void fromRow_legacyLengthWithoutReasoning_handlesGracefully() {
        Object[] row = new Object[14];
        Object[] full = baseRow();
        System.arraycopy(full, 0, row, 0, 14);

        CorrelationDTO dto = CorrelationMapper.fromRow(row);

        assertThat(dto.getReasoning()).isNull();
        assertThat(dto.getId()).isEqualTo(1L);
    }

    @Test
    void fromRow_offsetDateTimeConvertsToInstant() {
        Object[] row = baseRow();
        OffsetDateTime odt = OffsetDateTime.of(2026, 3, 2, 11, 30, 0, 0, ZoneOffset.ofHours(-5));
        row[8] = odt;
        row[13] = odt.plusHours(1);

        CorrelationDTO dto = CorrelationMapper.fromRow(row);

        assertThat(dto.getDetectedAt()).isEqualTo(odt.toInstant());
        assertThat(dto.getNews().getPublishedAt()).isEqualTo(odt.plusHours(1).toInstant());
    }

    private Object[] baseRow() {
        return new Object[] {
                1L, 2L, 3L,
                new BigDecimal("0.40"), new BigDecimal("0.45"), new BigDecimal("0.05"),
                1000, new BigDecimal("0.8"), Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                "Q", "H", "S", "U", Timestamp.from(Instant.parse("2026-01-01T01:00:00Z")), "R"
        };
    }
}
