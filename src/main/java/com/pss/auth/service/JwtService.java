package com.pss.auth.service;

import com.pss.auth.config.JwtConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * JWT token service for issuance, validation, and claim extraction.
 * Tokens carry userId and sessionId as claims.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    private final JwtConfig jwtConfig;
    private SecretKey signingKey;

    @PostConstruct
    void init() {
        this.signingKey = Keys.hmacShaKeyFor(
                Base64.getDecoder().decode(jwtConfig.secret())
        );
    }

    /**
     * Generate a JWT token with userId and sessionId claims.
     */
    public String generateToken(Long userId, String sessionId) {
        var now = new Date();
        var expiry = new Date(now.getTime() + jwtConfig.expirySeconds() * 1000);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(Map.of(
                        "sid", sessionId,
                        "uid", userId
                ))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Generate a new unique session ID.
     */
    public String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Validate a JWT token and return claims.
     * Returns null if token is invalid/expired.
     */
    public Jws<Claims> validateToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract user ID from a validated token.
     */
    public Long extractUserId(Claims claims) {
        if (claims == null) return null;
        return claims.get("uid", Long.class);
    }

    /**
     * Extract session ID from a validated token.
     */
    public String extractSessionId(Claims claims) {
        if (claims == null) return null;
        return claims.get("sid", String.class);
    }

    /**
     * Get token expiry in seconds.
     */
    public long getExpirySeconds() {
        return jwtConfig.expirySeconds();
    }
}
