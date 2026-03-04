package com.polypulse.repository;

import com.polypulse.model.PriceTick;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class PriceTickBatchWriter {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Batch insert using a single multi-row INSERT statement.
     * Bypasses Hibernate's IDENTITY limitation entirely.
     * 500 ticks = 1 SQL statement instead of 500.
     */
    public int batchInsert(List<PriceTick> ticks) {
        if (ticks.isEmpty()) return 0;

        // Build multi-row VALUES clause
        StringBuilder sql = new StringBuilder(
                "INSERT INTO price_ticks (market_id, price, timestamp, volume) VALUES ");

        Object[] params = new Object[ticks.size() * 4];
        for (int i = 0; i < ticks.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("(?,?,?,?)");
            PriceTick t = ticks.get(i);
            int base = i * 4;
            params[base] = t.getMarketId();
            params[base + 1] = t.getPrice();
            params[base + 2] = Timestamp.from(t.getTimestamp());
            params[base + 3] = t.getVolume();
        }

        jdbcTemplate.update(sql.toString(), params);
        return ticks.size();
    }
}
