package com.polypulse.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MarketNotFoundException.class)
    public ResponseEntity<?> handleNotFound(MarketNotFoundException ex, HttpServletRequest request) {
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneric(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private ResponseEntity<?> buildError(HttpStatus status, String message, HttpServletRequest request) {
        // SSE endpoints already have text/event-stream response type; avoid writing JSON bodies there.
        if (isSseRequest(request)) {
            return ResponseEntity.status(status).build();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("status", status.value());
        return ResponseEntity.status(status).body(body);
    }

    private boolean isSseRequest(HttpServletRequest request) {
        if (request == null) return false;
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/stream")) return true;

        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    public static class MarketNotFoundException extends RuntimeException {
        public MarketNotFoundException(Long id) {
            super("Market with id " + id + " not found");
        }
    }
}
