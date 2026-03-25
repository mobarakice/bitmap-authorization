package com.pss.auth.controller;

import com.pss.auth.exception.AccountDisabledException;
import com.pss.auth.exception.InvalidCredentialsException;
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

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCredentials(InvalidCredentialsException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AccountDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleAccountDisabled(AccountDisabledException ex) {
        log.warn("Disabled account login attempt: {}", ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        log.error("Unhandled runtime exception: {}", ex.getMessage(), ex);

        var status = switch (ex.getMessage()) {
            case String msg when msg.contains("Authorization") -> HttpStatus.UNAUTHORIZED;
            case null, default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return buildResponse(status, ex.getMessage() != null ? ex.getMessage() : "An error occurred");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            MethodArgumentNotValidException ex) {
        var detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new java.util.StringJoiner(": ").add(e.getField()).add(e.getDefaultMessage()).toString())
                .reduce((a, b) -> new java.util.StringJoiner("; ").add(a).add(b).toString())
                .orElse("Validation failed");

        return buildResponse(HttpStatus.BAD_REQUEST, detail);
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of(
                "type", "about:blank",
                "title", status.getReasonPhrase(),
                "status", status.value(),
                "detail", detail,
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
