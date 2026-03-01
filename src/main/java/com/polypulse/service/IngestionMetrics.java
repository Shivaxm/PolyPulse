package com.polypulse.service;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class IngestionMetrics {

    private final AtomicLong ticksReceived = new AtomicLong(0);
    private final AtomicLong ticksWritten = new AtomicLong(0);
    private final AtomicLong ticksDropped = new AtomicLong(0);

    @Getter
    private volatile boolean wsConnected = false;
    @Getter
    private volatile Instant lastTickTimestamp;
    @Getter
    private volatile Instant connectedSince;

    public long getTicksReceived() {
        return ticksReceived.get();
    }

    public long getTicksWritten() {
        return ticksWritten.get();
    }

    public long getTicksDropped() {
        return ticksDropped.get();
    }

    public void incrementTicksReceived() {
        ticksReceived.incrementAndGet();
    }

    public void addTicksWritten(long count) {
        ticksWritten.addAndGet(count);
    }

    public long incrementTicksDropped() {
        return ticksDropped.incrementAndGet();
    }

    public void setWsConnected(boolean connected) {
        this.wsConnected = connected;
        if (connected && connectedSince == null) {
            connectedSince = Instant.now();
        }
    }

    public void setLastTickTimestamp(Instant timestamp) {
        this.lastTickTimestamp = timestamp;
    }

    public Duration getUptime() {
        if (connectedSince == null) return Duration.ZERO;
        return Duration.between(connectedSince, Instant.now());
    }
}
