package com.polypulse.repository;

import com.polypulse.model.NewsEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface NewsEventRepository extends JpaRepository<NewsEvent, Long> {

    List<NewsEvent> findByPublishedAtBetweenOrderByPublishedAtDesc(Instant start, Instant end);

    boolean existsByUrl(String url);
}
