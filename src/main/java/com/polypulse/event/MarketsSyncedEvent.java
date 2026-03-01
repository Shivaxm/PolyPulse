package com.polypulse.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class MarketsSyncedEvent extends ApplicationEvent {

    private final int marketCount;

    public MarketsSyncedEvent(Object source, int marketCount) {
        super(source);
        this.marketCount = marketCount;
    }
}
