package com.polypulse.repository;

import com.polypulse.model.PriceTick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface PriceTickRepository extends JpaRepository<PriceTick, Long> {

    List<PriceTick> findByMarketIdAndTimestampBetweenOrderByTimestampDesc(
            Long marketId, Instant start, Instant end);

    @Query(value = """
            SELECT date_trunc(:interval, timestamp) AS bucket,
                   AVG(price) AS price,
                   SUM(volume) AS volume
            FROM price_ticks
            WHERE market_id = :marketId AND timestamp > :start
            GROUP BY bucket ORDER BY bucket
            """, nativeQuery = true)
    List<Object[]> findBucketed(
            @Param("marketId") Long marketId,
            @Param("start") Instant start,
            @Param("interval") String interval);
}
