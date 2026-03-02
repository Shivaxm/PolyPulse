package com.polypulse.repository;

import com.polypulse.model.Correlation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Repository
public interface CorrelationRepository extends JpaRepository<Correlation, Long> {

    Page<Correlation> findByMarketIdOrderByDetectedAtDesc(Long marketId, Pageable pageable);

    Page<Correlation> findAllByOrderByDetectedAtDesc(Pageable pageable);

    boolean existsByMarketIdAndNewsEventId(Long marketId, Long newsEventId);

    long countByMarketId(Long marketId);

    @Query("SELECT DISTINCT c.marketId FROM Correlation c WHERE c.detectedAt > :since")
    Set<Long> findMarketIdsWithCorrelationsSince(@Param("since") Instant since);

    boolean existsByMarketIdAndDetectedAtAfter(Long marketId, Instant since);

    @Query(value = """
            SELECT c.id, c.market_id, c.news_event_id,
                   c.price_before, c.price_after, c.price_delta,
                   c.time_window_ms, c.confidence, c.detected_at,
                   m.question AS market_question,
                   ne.headline AS news_headline, ne.source AS news_source,
                   ne.url AS news_url, ne.published_at AS news_published_at
            FROM correlations c
            LEFT JOIN markets m ON m.id = c.market_id
            LEFT JOIN news_events ne ON ne.id = c.news_event_id
            WHERE c.market_id = :marketId
            ORDER BY c.detected_at DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Object[]> findCorrelationsWithDetailsByMarketId(
            @Param("marketId") Long marketId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Query(value = "SELECT count(*) FROM correlations WHERE market_id = :marketId", nativeQuery = true)
    long countCorrelationsByMarketId(@Param("marketId") Long marketId);

    @Query(value = """
            SELECT c.id, c.market_id, c.news_event_id,
                   c.price_before, c.price_after, c.price_delta,
                   c.time_window_ms, c.confidence, c.detected_at,
                   m.question AS market_question,
                   ne.headline AS news_headline, ne.source AS news_source,
                   ne.url AS news_url, ne.published_at AS news_published_at
            FROM correlations c
            LEFT JOIN markets m ON m.id = c.market_id
            LEFT JOIN news_events ne ON ne.id = c.news_event_id
            ORDER BY c.detected_at DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Object[]> findRecentCorrelationsWithDetails(
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Query(value = "SELECT count(*) FROM correlations", nativeQuery = true)
    long countAllCorrelations();
}
