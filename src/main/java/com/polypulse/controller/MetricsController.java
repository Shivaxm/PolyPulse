package com.polypulse.controller;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.search.Search;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MetricsController {

    private final MeterRegistry meterRegistry;

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Per-endpoint server-side latency
        List<Map<String, Object>> endpoints = new ArrayList<>();
        Collection<Timer> timers = Search.in(meterRegistry)
                .name("http.server.requests")
                .timers();

        for (Timer timer : timers) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("uri", timer.getId().getTag("uri"));
            entry.put("method", timer.getId().getTag("method"));
            entry.put("status", timer.getId().getTag("status"));
            entry.put("count", timer.count());
            entry.put("totalTimeMs", Math.round(timer.totalTime(TimeUnit.MILLISECONDS)));
            entry.put("meanMs", round(timer.mean(TimeUnit.MILLISECONDS)));
            entry.put("maxMs", round(timer.max(TimeUnit.MILLISECONDS)));

            HistogramSnapshot snapshot = timer.takeSnapshot();
            for (ValueAtPercentile vp : snapshot.percentileValues()) {
                double ms = vp.value(TimeUnit.MILLISECONDS);
                if (vp.percentile() == 0.5) entry.put("p50Ms", round(ms));
                else if (vp.percentile() == 0.95) entry.put("p95Ms", round(ms));
                else if (vp.percentile() == 0.99) entry.put("p99Ms", round(ms));
            }

            endpoints.add(entry);
        }

        endpoints.sort((a, b) -> Long.compare((long) b.get("count"), (long) a.get("count")));
        result.put("httpRequests", endpoints);

        // HikariCP pool stats
        Map<String, Object> hikari = new LinkedHashMap<>();
        hikari.put("active", gaugeValue("hikaricp.connections.active"));
        hikari.put("idle", gaugeValue("hikaricp.connections.idle"));
        hikari.put("max", gaugeValue("hikaricp.connections.max"));
        hikari.put("pending", gaugeValue("hikaricp.connections.pending"));
        result.put("connectionPool", hikari);

        return result;
    }

    private int gaugeValue(String name) {
        Gauge gauge = meterRegistry.find(name).gauge();
        return gauge != null ? (int) gauge.value() : -1;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
