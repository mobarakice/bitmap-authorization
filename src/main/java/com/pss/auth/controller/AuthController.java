package com.pss.auth.controller;

import com.pss.auth.dto.LoginRequest;
import com.pss.auth.dto.LoginResponse;
import com.pss.auth.dto.PermissionCheckRequest;
import com.pss.auth.dto.PermissionCheckResponse;
import com.pss.auth.service.AuthService;
import com.pss.auth.service.JwtService;
import com.pss.auth.service.PermissionIndexService;
import com.pss.auth.service.SessionPermissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final PermissionIndexService permissionIndexService;
    private final SessionPermissionService sessionPermissionService;

    /**
     * POST /api/auth/login
     * Authenticates user, ensures permission bitmaps, returns JWT.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        var response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/auth/logout
     * Removes the session from tracking.
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @RequestHeader("Authorization") String authHeader) {
        var token = extractToken(authHeader);
        authService.logout(token);
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    /**
     * POST /api/auth/check
     * Verifies if the authenticated user has a specific permission.
     * Checks the user-level bitmap (no session-specific bitmap needed).
     * Also validates that the session is still active.
     */
    @PostMapping("/check")
    public ResponseEntity<PermissionCheckResponse> checkPermission(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody PermissionCheckRequest request) {

        var token = extractToken(authHeader);
        var claims = jwtService.validateToken(token).getPayload();
        var userId = jwtService.extractUserId(claims);
        var sessionId = jwtService.extractSessionId(claims);

        if (userId == null || sessionId == null) {
            return ResponseEntity.status(401).build();
        }

//        // Verify session is still tracked (not invalidated)
//        if (!sessionPermissionService.isSessionValid(userId, sessionId)) {
//            return ResponseEntity.status(401).body(new PermissionCheckResponse(
//                    false, request.action(), request.resource(), null
//            ));
//        }

        var permIndex = permissionIndexService.getPermissionIndex(
                request.action(), request.resource());

        if (permIndex == null) {
            return ResponseEntity.ok(new PermissionCheckResponse(
                    false, request.action(), request.resource(), null
            ));
        }

        // Check against user-level bitmap (not session-specific)
        var allowed = sessionPermissionService.checkPermission(userId, permIndex);

        return ResponseEntity.ok(new PermissionCheckResponse(
                allowed, request.action(), request.resource(), permIndex
        ));
    }

    /**
     * GET /api/auth/me
     * Returns information about the authenticated user.
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(
            @RequestHeader("Authorization") String authHeader) {
        var token = extractToken(authHeader);
        var claims = jwtService.validateToken(token).getPayload();
        var userId = jwtService.extractUserId(claims);
        var sessionId = jwtService.extractSessionId(claims);

        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "sessionId", sessionId != null ? sessionId : "",
                "sessionValid", sessionPermissionService.isSessionValid(userId, sessionId)
        ));
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        throw new RuntimeException("Missing or invalid Authorization header");
    }
}
