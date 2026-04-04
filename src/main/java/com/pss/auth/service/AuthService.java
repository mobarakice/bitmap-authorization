package com.pss.auth.service;

import com.pss.auth.dto.LoginRequest;
import com.pss.auth.dto.LoginResponse;
import com.pss.auth.exception.AccountDeactivatedException;
import com.pss.auth.exception.InvalidCredentialsException;
import com.pss.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Authentication service orchestrating login and logout flows.
 * <p>
 * Login flow:
 * 1. Validate credentials
 * 2. Generate session ID and JWT token
 * 3. Ensure role bitmap exists (lazy), ensure user bitmap exists (lazy/extend TTL)
 * 4. Track session in Redis
 * 5. Return JWT to client
 * <p>
 * Logout flow:
 * 1. Extract userId/sessionId from token
 * 2. Remove session from tracking set
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final SessionPermissionService sessionPermissionService;
    private final PasswordEncoder passwordEncoder;
    private final CapabilityService capabilityService;

    /**
     * Authenticate user and ensure permission bitmaps are ready.
     */
    public LoginResponse login(LoginRequest request) {
        // 1. Find user
        var user = userRepository.findByUsername(request.username())
                .orElseThrow(InvalidCredentialsException::new);

        if (!user.getIsActive()) {
            throw new AccountDeactivatedException();
        }

        // 1b. Check role assigned
        if (user.getRole() == null) {
            throw new AccountDeactivatedException("No role assigned. Contact administrator.");
        }

        // 2. Validate password
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        // 3. Generate session
        var sessionId = jwtService.generateSessionId();
        var token = jwtService.generateToken(user.getId(), sessionId, user.getRole().getId());
        var ttl = jwtService.getExpirySeconds();

        // 4. Ensure bitmaps + track session (the core operation)
        sessionPermissionService.onLogin(user.getId(), sessionId, user.getRole().getId(), token, ttl);

        // 5. Resolve capabilities (presentation layer)
        var resolved = capabilityService.resolveCapabilities(user.getId(), user.getRole().getId());

        log.info("User '{}' logged in successfully (userId={}, sessionId={}, capabilities={})",
                user.getUsername(), user.getId(), sessionId, resolved.capabilities().size());

        return new LoginResponse(
                token,
                "Bearer",
                ttl,
                user.getId(),
                user.getUsername(),
                user.getRole().getName(),
                resolved.capabilities(),
                resolved.modules()
        );
    }

    /**
     * Logout: remove session from tracking.
     */
    public void logout(String token) {
        var claims = jwtService.validateToken(token).getPayload();
        var userId = jwtService.extractUserId(claims);
        var sessionId = jwtService.extractSessionId(claims);

        if (userId != null && sessionId != null) {
            sessionPermissionService.removeSession(userId, sessionId);
            log.info("User {} logged out (session={})", userId, sessionId);
        }
    }
}
