package com.polypulse.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "news_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String headline;

    @Column(length = 128)
    private String source;

    @Column(unique = true, columnDefinition = "TEXT")
    private String url;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> keywords;

    @Column(length = 64)
    private String category;

    @PrePersist
    protected void onCreate() {
        if (ingestedAt == null) ingestedAt = Instant.now();
    }
}
