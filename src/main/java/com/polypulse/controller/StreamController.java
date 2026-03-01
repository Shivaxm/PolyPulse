package com.polypulse.controller;

import com.polypulse.service.SseConnectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/stream")
@RequiredArgsConstructor
public class StreamController {

    private final SseConnectionManager sseConnectionManager;

    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLive() {
        return sseConnectionManager.registerClient().emitter();
    }

    @GetMapping(value = "/markets/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMarket(@PathVariable Long id) {
        return sseConnectionManager.registerMarketClient(id).emitter();
    }
}
