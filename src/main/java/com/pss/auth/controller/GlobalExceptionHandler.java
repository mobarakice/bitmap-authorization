package com.pss.auth.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        log.error("Runtime exception: {}", ex.getMessage());

        var status = switch (ex.getMessage()) {
            case String msg when msg.contains("Invalid credentials") -> HttpStatus.UNAUTHORIZED;
            case String msg when msg.contains("disabled") -> HttpStatus.FORBIDDEN;
            case String msg when msg.contains("Authorization") -> HttpStatus.UNAUTHORIZED;
            case null, default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return ResponseEntity.status(status).body(Map.of(
                "type", "about:blank",
                "title", status.getReasonPhrase(),
                "status", status.value(),
                "detail", ex.getMessage() != null ? ex.getMessage() : "An error occurred",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            MethodArgumentNotValidException ex) {
        var detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new java.util.StringJoiner(": ").add(e.getField()).add(e.getDefaultMessage()).toString())
                .reduce((a, b) -> new java.util.StringJoiner("; ").add(a).add(b).toString())
                .orElse("Validation failed");

        return ResponseEntity.badRequest().body(Map.of(
                "type", "about:blank",
                "title", "Bad Request",
                "status", 400,
                "detail", detail,
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
