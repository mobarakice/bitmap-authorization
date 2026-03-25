package com.pss.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.bitmap")
public record BitmapConfig(int chunkSize) {
    public BitmapConfig {
        if (chunkSize <= 0) chunkSize = 1024;
    }
}
