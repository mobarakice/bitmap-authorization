package com.pss.auth.exception;

/**
 * Thrown when login credentials are invalid (wrong username or password).
 * Maps to HTTP 401 Unauthorized.
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid credentials");
    }

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
