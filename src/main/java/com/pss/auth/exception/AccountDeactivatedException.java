package com.pss.auth.exception;

/**
 * Thrown when a deactivated account attempts to log in.
 * Maps to HTTP 403 Forbidden.
 */
public class AccountDeactivatedException extends RuntimeException {
    public AccountDeactivatedException() {
        super("Account is deactivated");
    }

    public AccountDeactivatedException(String message) {
        super(message);
    }
}
