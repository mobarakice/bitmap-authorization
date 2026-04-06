package com.pss.auth.service;

import com.pss.auth.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
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
     * Generate a JWT token with userId, sessionId, and roleId claims.
     */
    public String generateToken(Long userId, String sessionId, Integer roleId) {
        var now = new Date();
        var expiry = new Date(now.getTime() + jwtConfig.expirySeconds() * 1000);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(Map.of(
                        "sid", sessionId,
                        "uid", userId,
                        "roleId", roleId
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
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token);
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
     * Extract role ID from a validated token.
     */
    public Integer extractRoleId(Claims claims) {
        if (claims == null) return null;
        return claims.get("roleId", Integer.class);
    }

    /**
     * Get token expiry in seconds.
     */
    public long getExpirySeconds() {
        return jwtConfig.expirySeconds();
    }
}
