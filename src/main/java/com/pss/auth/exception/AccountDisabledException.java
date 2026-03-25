package com.pss.auth.exception;

/**
 * Thrown when a disabled account attempts to log in.
 * Maps to HTTP 403 Forbidden.
 */
public class AccountDisabledException extends RuntimeException {
    public AccountDisabledException() {
        super("Account is disabled");
    }

    public AccountDisabledException(String message) {
        super(message);
    }
}
