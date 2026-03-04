package com.polypulse.repository;

import com.polypulse.model.PriceTick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface PriceTickRepository extends JpaRepository<PriceTick, Long> {

    long countByMarketId(Long marketId);

    List<PriceTick> findByMarketIdAndTimestampBetweenOrderByTimestampDesc(
            Long marketId, Instant start, Instant end);

    @Query(value = """
            SELECT date_bin(CAST(:interval AS interval), timestamp, CAST(:start AS timestamptz)) AS bucket,
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

    @Query(value = """
            SELECT market_id,
                   date_bin('72 minutes', timestamp, :start) AS bucket,
                   AVG(price) AS price
            FROM price_ticks
            WHERE market_id IN :marketIds AND timestamp > :start
            GROUP BY market_id, bucket
            ORDER BY market_id, bucket
            """, nativeQuery = true)
    List<Object[]> findSparklineData(
            @Param("marketIds") List<Long> marketIds,
            @Param("start") Instant start);

    @Query(value = """
            SELECT date_trunc(:interval, timestamp) AS bucket,
                   AVG(price) AS price,
                   SUM(volume) AS volume
            FROM price_ticks
            WHERE market_id = :marketId AND timestamp > :start
            GROUP BY bucket ORDER BY bucket
            """, nativeQuery = true)
    List<Object[]> findBucketedFallback(
            @Param("marketId") Long marketId,
            @Param("start") Instant start,
            @Param("interval") String interval);

    List<PriceTick> findByMarketIdAndTimestampBetweenOrderByTimestampAsc(
            Long marketId, Instant start, Instant end);

    @Modifying
    @Query(value = "DELETE FROM price_ticks WHERE timestamp < :cutoff", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
