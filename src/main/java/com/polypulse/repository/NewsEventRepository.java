package com.polypulse.repository;

import com.polypulse.model.NewsEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

@Repository
public interface NewsEventRepository extends JpaRepository<NewsEvent, Long> {

    List<NewsEvent> findByPublishedAtBetweenOrderByPublishedAtDesc(Instant start, Instant end);

    boolean existsByUrl(String url);

    @Modifying
    @Query(value = "DELETE FROM news_events WHERE published_at < :cutoff", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
