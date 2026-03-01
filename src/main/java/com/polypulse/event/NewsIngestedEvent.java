package com.polypulse.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.List;

@Getter
public class NewsIngestedEvent extends ApplicationEvent {

    private final Long newsEventId;
    private final String headline;
    private final List<String> keywords;
    private final Instant publishedAt;

    public NewsIngestedEvent(Object source, Long newsEventId, String headline,
                              List<String> keywords, Instant publishedAt) {
        super(source);
        this.newsEventId = newsEventId;
        this.headline = headline;
        this.keywords = keywords;
        this.publishedAt = publishedAt;
    }
}
