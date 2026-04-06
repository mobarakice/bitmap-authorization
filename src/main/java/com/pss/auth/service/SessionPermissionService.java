package com.pss.auth.service;

import com.pss.auth.repository.PermissionRepository;
import com.pss.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages permission bitmaps in Redis with a two-tier caching strategy:
 *
 * Tier 1 — Role Bitmap (permanent):
 *   Key: role:{roleId}:perm:{chunk}
 *   Built lazily on first login. No TTL. Invalidated only when
 *   role-permission mappings change in the database.
 *
 * Tier 2 — User Bitmap (session-scoped TTL):
 *   Key: user:{uid}:perm:{chunk}
 *   Computed once per user: final = (role OR allow) AND (NOT deny).
 *   TTL is extended to the latest session expiry on each login.
 *   Invalidated when role permissions, allow list, or deny list change.
 *
 * Session Tracking:
 *   Key: user:{uid}:sessions  (Redis SET of session IDs)
 *   Tracks all active sessions per user for bulk invalidation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionPermissionService {

    private static final String ROLE_PERM_PREFIX = "role:%d:perm";
    private static final String USER_ALLOW_PREFIX = "user:%d:allow";
    private static final String USER_DENY_PREFIX = "user:%d:deny";
    private static final String USER_SESSIONS_KEY = "user:%d:sessions";

    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final BitmapService bitmapService;
    private final StringRedisTemplate stringRedisTemplate;

    private final AtomicInteger cachedMaxBitIndex = new AtomicInteger(-1);

    public void invalidateMaxBitIndexCache() {
        cachedMaxBitIndex.set(-1);
        log.info("Invalidated maxBitIndex cache");
    }

    private int getMaxBitIndex() {
        int current = cachedMaxBitIndex.get();
        if (current == -1) {
            current = permissionRepository.findMaxBitIndex().orElse(0);
            cachedMaxBitIndex.set(current);
        }
        return current;
    }

    /**
     * Called during login. Ensures role and user bitmaps exist, extends TTL,
     * and tracks the session.
     */
    public void onLogin(Long userId, String sessionId, Integer roleId, String token, long ttlSeconds) {
        log.info("Processing login permissions for user={}, session={}", userId, sessionId);

        // 1. Ensure the role bitmap exists (lazy build if missing)
        ensureRoleBitmap(roleId);

        // 2. Ensure the user's effective permission bitmap exists
        ensureUserBitmap(userId, roleId, ttlSeconds);

        // 3. Track this session
        trackSession(userId, sessionId, token, ttlSeconds);

        log.info("Login permissions ready for user={} (TTL={}s)", userId, ttlSeconds);
    }

    // =========================================================================
    // TIER 1: ROLE BITMAP (PERMANENT)
    // =========================================================================

    /**
     * Check if the role bitmap exists in Redis; if not, build it from DB.
     * Uses SET NX as a distributed lock to prevent thundering herd on
     * concurrent first-login for the same role.
     */
    public void ensureRoleBitmap(Integer roleId) {
        var prefix = String.format(ROLE_PERM_PREFIX, roleId);
        var checkKey = bitmapService.buildChunkKey(prefix, 0);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(checkKey))) {
            return;
        }

        // Acquire distributed lock — only one instance builds the bitmap
        var lockKey = "lock:role_bitmap:" + roleId;
        var acquired = Boolean.TRUE.equals(
                stringRedisTemplate.opsForValue()
                        .setIfAbsent(lockKey, "1", 30, TimeUnit.SECONDS));
        if (!acquired) {
            log.debug("Role bitmap build already in progress for roleId={}", roleId);
            return;
        }
        try {
            // Double-check after acquiring lock
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(checkKey))) {
                return;
            }
            buildRoleBitmap(prefix, roleId);
        } finally {
            stringRedisTemplate.delete(lockKey);
        }
    }

    /**
     * Build the role bitmap from database and store permanently in Redis.
     */
    private void buildRoleBitmap(String prefix, Integer roleId) {
        log.info("Building role bitmap for roleId={}", roleId);
        var bitIndexes = permissionRepository.findBitIndexesByRoleId(roleId);

        bitmapService.buildBitmap(prefix, bitIndexes);

        log.info("Role bitmap built: roleId={}, bits={}", roleId, bitIndexes.size());
    }

    /**
     * Invalidate a role's bitmap. Called when role-permission mappings change.
     * Also invalidates all users who have this role.
     */
    public void invalidateRoleBitmap(Integer roleId) {
        log.info("Invalidating role bitmap for roleId={}", roleId);
        var prefix = String.format(ROLE_PERM_PREFIX, roleId);

        var maxBit = getMaxBitIndex();
        if (maxBit > 0) {
            var totalChunks = bitmapService.getTotalChunks(maxBit);
            bitmapService.deleteAll(prefix, totalChunks);
        }

        // Invalidate all users with this role
        var userIds = userRepository.findUserIdsByRoleId(roleId);
        userIds.forEach(this::invalidateUserBitmap);

        log.info("Role bitmap invalidated: roleId={}, affected users={}", roleId, userIds.size());
    }

    // =========================================================================
    // TIER 2: USER BITMAP (TTL-BASED)
    // =========================================================================

    /**
     * Ensure the user's allow/deny bitmaps exist in Redis.
     * OPTIMIZATION: Only creates bitmaps if user has overrides.
     */
    private void ensureUserBitmap(Long userId, Integer roleId, long ttlSeconds) {
        var allowPrefix = String.format(USER_ALLOW_PREFIX, userId);
        var denyPrefix = String.format(USER_DENY_PREFIX, userId);
        var allowKey = bitmapService.buildChunkKey(allowPrefix, 0);
        var denyKey = bitmapService.buildChunkKey(denyPrefix, 0);
        
        boolean allowExists = Boolean.TRUE.equals(stringRedisTemplate.hasKey(allowKey));
        boolean denyExists = Boolean.TRUE.equals(stringRedisTemplate.hasKey(denyKey));
        
        if (allowExists || denyExists) {
            // At least one bitmap exists, extend TTL
            extendUserBitmapTtl(userId, ttlSeconds);
            log.debug("Extended user allow/deny TTL for user={}", userId);
            return;
        }
        
        // No bitmaps exist, build from DB (only if user has overrides)
        buildUserBitmaps(userId, ttlSeconds);
    }

    /**
     * Build user's allow/deny bitmaps from database.
     * OPTIMIZATION: Only creates bitmaps if user has overrides.
     */
    private void buildUserBitmaps(Long userId, long ttlSeconds) {
        log.info("Building user allow/deny bitmaps for userId={}", userId);

        List<Integer> allowBitIndexes;
        List<Integer> denyBitIndexes;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var f1 = executor.submit(() -> permissionRepository.findAllowBitIndexesByUserId(userId));
            var f2 = executor.submit(() -> permissionRepository.findDenyBitIndexesByUserId(userId));
            allowBitIndexes = f1.get();
            denyBitIndexes = f2.get();
        } catch (Exception e) {
            log.error("Failed to fetch user permission overrides", e);
            throw new RuntimeException("Failed to fetch user permission overrides", e);
        }

        // OPTIMIZATION: If user has no overrides, don't create bitmaps
        if (allowBitIndexes.isEmpty() && denyBitIndexes.isEmpty()) {
            log.info("User has no permission overrides: userId={}", userId);
            return;
        }

        var allowPrefix = String.format(USER_ALLOW_PREFIX, userId);
        var denyPrefix = String.format(USER_DENY_PREFIX, userId);
        var maxBitIndex = getMaxBitIndex();

        // Build allow bitmap (only if user has allows)
        if (!allowBitIndexes.isEmpty()) {
            bitmapService.buildBitmap(allowPrefix, allowBitIndexes);
            if (maxBitIndex > 0) {
                bitmapService.setTtl(allowPrefix, maxBitIndex, ttlSeconds);
            }
            log.info("Built allow bitmap: userId={}, count={}", userId, allowBitIndexes.size());
        }

        // Build deny bitmap (only if user has denies)
        if (!denyBitIndexes.isEmpty()) {
            bitmapService.buildBitmap(denyPrefix, denyBitIndexes);
            if (maxBitIndex > 0) {
                bitmapService.setTtl(denyPrefix, maxBitIndex, ttlSeconds);
            }
            log.info("Built deny bitmap: userId={}, count={}", userId, denyBitIndexes.size());
        }
    }

    /**
     * Extend TTL on user's allow/deny bitmaps (only if they exist).
     */
    private void extendUserBitmapTtl(Long userId, long ttlSeconds) {
        var allowPrefix = String.format(USER_ALLOW_PREFIX, userId);
        var denyPrefix = String.format(USER_DENY_PREFIX, userId);
        var maxBit = getMaxBitIndex();
        
        if (maxBit > 0) {
            var allowKey = bitmapService.buildChunkKey(allowPrefix, 0);
            var denyKey = bitmapService.buildChunkKey(denyPrefix, 0);
            
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(allowKey))) {
                bitmapService.setTtl(allowPrefix, maxBit, ttlSeconds);
            }
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(denyKey))) {
                bitmapService.setTtl(denyPrefix, maxBit, ttlSeconds);
            }
        }
    }

    /**
     * Invalidate a user's allow/deny bitmaps and all active sessions.
     */
    public void invalidateUserBitmap(Long userId) {
        log.info("Invalidating user allow/deny bitmaps for userId={}", userId);
        var allowPrefix = String.format(USER_ALLOW_PREFIX, userId);
        var denyPrefix = String.format(USER_DENY_PREFIX, userId);

        var maxBit = getMaxBitIndex();
        if (maxBit > 0) {
            var totalChunks = bitmapService.getTotalChunks(maxBit);
            bitmapService.deleteAll(allowPrefix, totalChunks);
            bitmapService.deleteAll(denyPrefix, totalChunks);
        }

        invalidateAllSessions(userId);
    }

    // =========================================================================
    // IN-PLACE ROLE BITMAP OPERATIONS
    // =========================================================================

    /**
     * Add permission bits to an existing role bitmap in-place.
     * If bitmap absent, skip (lazy build on next login will include them).
     */
    public void addRolePermissionBits(Integer roleId, List<Integer> bitIndexes) {
        var prefix = String.format(ROLE_PERM_PREFIX, roleId);
        var checkKey = bitmapService.buildChunkKey(prefix, 0);
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(checkKey))) {
            log.debug("Role bitmap absent for roleId={}, skipping in-place add", roleId);
            return;
        }

        bitmapService.buildBitmap(prefix, bitIndexes);
        log.info("In-place added {} bits to role bitmap: roleId={}", bitIndexes.size(), roleId);
    }

    /**
     * Remove permission bits from an existing role bitmap in-place.
     * If bitmap absent, skip.
     */
    public void removeRolePermissionBits(Integer roleId, List<Integer> bitIndexes) {
        var prefix = String.format(ROLE_PERM_PREFIX, roleId);
        var checkKey = bitmapService.buildChunkKey(prefix, 0);
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(checkKey))) {
            log.debug("Role bitmap absent for roleId={}, skipping in-place remove", roleId);
            return;
        }

        bitmapService.clearBits(prefix, bitIndexes);
        log.info("In-place cleared {} bits from role bitmap: roleId={}", bitIndexes.size(), roleId);
    }

    // =========================================================================
    // CONDITIONAL USER BITMAP REBUILD
    // =========================================================================

    /**
     * Rebuild user allow/deny bitmaps if they exist in Redis.
     * Preserves remaining TTL. If absent, skip (lazy build on next login).
     * Always invalidates sessions.
     */
    public void rebuildUserBitmapsIfExist(Long userId) {
        var allowPrefix = String.format(USER_ALLOW_PREFIX, userId);
        var denyPrefix = String.format(USER_DENY_PREFIX, userId);
        var allowKey = bitmapService.buildChunkKey(allowPrefix, 0);
        var denyKey = bitmapService.buildChunkKey(denyPrefix, 0);

        boolean allowExists = Boolean.TRUE.equals(stringRedisTemplate.hasKey(allowKey));
        boolean denyExists = Boolean.TRUE.equals(stringRedisTemplate.hasKey(denyKey));

        if (!allowExists && !denyExists) {
            log.info("No user bitmaps exist for userId={}, skipping rebuild (lazy on next login)", userId);
            invalidateAllSessions(userId);
            return;
        }

        // Preserve remaining TTL from whichever bitmap exists
        long ttl = -1;
        if (allowExists) {
            ttl = bitmapService.getRemainingTtl(allowPrefix, 0);
        } else {
            ttl = bitmapService.getRemainingTtl(denyPrefix, 0);
        }
        if (ttl <= 0) {
            ttl = 3600; // fallback 1h if TTL unavailable
        }

        // Delete old bitmaps
        var maxBit = getMaxBitIndex();
        if (maxBit > 0) {
            var totalChunks = bitmapService.getTotalChunks(maxBit);
            bitmapService.deleteAll(allowPrefix, totalChunks);
            bitmapService.deleteAll(denyPrefix, totalChunks);
        }

        // Rebuild from DB with virtual threads
        List<Integer> allowBitIndexes;
        List<Integer> denyBitIndexes;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var f1 = executor.submit(() -> permissionRepository.findAllowBitIndexesByUserId(userId));
            var f2 = executor.submit(() -> permissionRepository.findDenyBitIndexesByUserId(userId));
            allowBitIndexes = f1.get();
            denyBitIndexes = f2.get();
        } catch (Exception e) {
            log.error("Failed to rebuild user bitmaps for userId={}", userId, e);
            throw new RuntimeException("Failed to rebuild user bitmaps", e);
        }

        // Write new bitmaps with preserved TTL
        if (!allowBitIndexes.isEmpty()) {
            bitmapService.buildBitmap(allowPrefix, allowBitIndexes);
            if (maxBit > 0) bitmapService.setTtl(allowPrefix, maxBit, ttl);
        }
        if (!denyBitIndexes.isEmpty()) {
            bitmapService.buildBitmap(denyPrefix, denyBitIndexes);
            if (maxBit > 0) bitmapService.setTtl(denyPrefix, maxBit, ttl);
        }

        invalidateAllSessions(userId);
        log.info("Rebuilt user bitmaps: userId={}, allow={}, deny={}, ttl={}s",
                userId, allowBitIndexes.size(), denyBitIndexes.size(), ttl);
    }

    // =========================================================================
    // SESSION TRACKING
    // =========================================================================

    /**
     * Track a new session for a user. TTL on the sessions set extends
     * with each login so it persists as long as any session is active.
     */
    private void trackSession(Long userId, String sessionId, String token, long ttlSeconds) {
        var key = String.format(USER_SESSIONS_KEY, userId);
        stringRedisTemplate.opsForHash().putIfAbsent(key, sessionId, token);
        stringRedisTemplate.opsForHash().expire(key, Duration.ofSeconds(ttlSeconds), List.of(sessionId));
    }

    /**
     * Check if a session is still valid (tracked in Redis).
     */
    public boolean isSessionValid(Long userId, String sessionId) {
        var key = String.format(USER_SESSIONS_KEY, userId);
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(key, sessionId));
    }

    /**
     * Remove a single session (logout).
     */
    public void removeSession(Long userId, String sessionId) {
        var key = String.format(USER_SESSIONS_KEY, userId);
        stringRedisTemplate.opsForSet().remove(key, sessionId);
        log.info("Session removed: user={}, session={}", userId, sessionId);
    }

    /**
     * Invalidate all active sessions for a user.
     */
    public void invalidateAllSessions(Long userId) {
        var key = String.format(USER_SESSIONS_KEY, userId);
        stringRedisTemplate.delete(key);
        log.info("All sessions invalidated for user={}", userId);
    }

    // =========================================================================
    // PERMISSION CHECK
    // =========================================================================

    /**
     * Batch permission check — evaluates multiple bit indexes in pipelined GETBIT calls.
     * Returns a map of bitIndex → allowed (true/false).
     * Reduces round-trips from 3*N to 3 pipelines.
     */
    public Map<Integer, Boolean> checkPermissionsBatch(Long userId, Integer roleId, List<Integer> bitIndexes) {
        if (bitIndexes.isEmpty()) return Map.of();

        var rolePrefix = String.format(ROLE_PERM_PREFIX, roleId);
        var allowPrefix = String.format(USER_ALLOW_PREFIX, userId);
        var denyPrefix = String.format(USER_DENY_PREFIX, userId);

        // Pipeline all GETBIT calls for each prefix
        var denyBits = bitmapService.getBitsBatch(denyPrefix, bitIndexes);
        var roleBits = bitmapService.getBitsBatch(rolePrefix, bitIndexes);
        var allowBits = bitmapService.getBitsBatch(allowPrefix, bitIndexes);

        // Apply formula: (role OR allow) AND (NOT deny)
        var result = new java.util.HashMap<Integer, Boolean>();
        for (int i = 0; i < bitIndexes.size(); i++) {
            var idx = bitIndexes.get(i);
            boolean deny = denyBits.get(i);
            boolean role = roleBits.get(i);
            boolean allow = allowBits.get(i);
            result.put(idx, !deny && (role || allow));
        }
        return result;
    }

    /**
     * Check if a user has a specific permission.
     * Formula: (role OR allow) AND (NOT deny)
     */
    public boolean checkPermission(Long userId, Integer roleId, int bitIndex) {
        var rolePrefix = String.format(ROLE_PERM_PREFIX, roleId);
        var allowPrefix = String.format(USER_ALLOW_PREFIX, userId);
        var denyPrefix = String.format(USER_DENY_PREFIX, userId);

        // Check deny first (fast rejection)
        boolean denyHas = bitmapService.getBit(denyPrefix, bitIndex);
        if (denyHas) {
            return false;  // Explicit deny
        }

        // Check role or allow
        boolean roleHas = bitmapService.getBit(rolePrefix, bitIndex);
        boolean allowHas = bitmapService.getBit(allowPrefix, bitIndex);

        return roleHas || allowHas;
    }
}
