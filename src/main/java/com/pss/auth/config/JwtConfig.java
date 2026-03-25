package com.pss.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtConfig(String secret, long expirySeconds) {
    public JwtConfig {
        if (expirySeconds <= 0) expirySeconds = 3600;
    }
}
