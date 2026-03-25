package com.pss.auth.service;

import com.pss.auth.repository.PermissionRepository;
import com.pss.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

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
    private static final String USER_PERM_PREFIX = "user:%d:perm";
    private static final String USER_SESSIONS_KEY = "user:%d:sessions";
    private static final String TEMP_PREFIX = "tmp:perm:%s";

    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final BitmapService bitmapService;
    private final StringRedisTemplate stringRedisTemplate;

    private DefaultRedisScript<Long> computeScript;

    @PostConstruct
    void init() {
        computeScript = new DefaultRedisScript<>();
        computeScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("scripts/compute_user_bitmap.lua"))
        );
        computeScript.setResultType(Long.class);
        log.info("Loaded compute_user_bitmap.lua script");
    }

    // =========================================================================
    // LOGIN FLOW
    // =========================================================================

    /**
     * Called during login. Ensures role and user bitmaps exist, extends TTL,
     * and tracks the session.
     */
    public void onLogin(Long userId, String sessionId, Integer roleId, long ttlSeconds) {
        log.info("Processing login permissions for user={}, session={}", userId, sessionId);

        // 1. Ensure the role bitmap exists (lazy build if missing)
        ensureRoleBitmap(roleId);

        // 2. Ensure the user's effective permission bitmap exists
        ensureUserBitmap(userId, roleId, ttlSeconds);

        // 3. Track this session
        trackSession(userId, sessionId, ttlSeconds);

        log.info("Login permissions ready for user={} (TTL={}s)", userId, ttlSeconds);
    }

    // =========================================================================
    // TIER 1: ROLE BITMAP (PERMANENT)
    // =========================================================================

    /**
     * Check if the role bitmap exists in Redis; if not, build it from DB.
     * The role bitmap has no TTL — it lives until explicitly invalidated.
     */
    public void ensureRoleBitmap(Integer roleId) {
        var prefix = String.format(ROLE_PERM_PREFIX, roleId);
        var checkKey = bitmapService.buildChunkKey(prefix, 0);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(checkKey))) {
            return;
        }
        buildRoleBitmap(prefix, roleId);
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

        permissionRepository.findMaxBitIndex().ifPresent(maxBit -> {
            var totalChunks = bitmapService.getTotalChunks(maxBit);
            bitmapService.deleteAll(prefix, totalChunks);
        });

        // Invalidate all users with this role
        var userIds = userRepository.findUserIdsByRoleId(roleId);
        userIds.forEach(this::invalidateUserBitmap);

        log.info("Role bitmap invalidated: roleId={}, affected users={}", roleId, userIds.size());
    }

    // =========================================================================
    // TIER 2: USER BITMAP (TTL-BASED)
    // =========================================================================

    /**
     * Ensure the user's effective permission bitmap exists.
     * If it exists, extend its TTL to the latest session expiry.
     * If missing, compute it: final = (role OR allow) AND (NOT deny).
     */
    private void ensureUserBitmap(Long userId, Integer roleId, long ttlSeconds) {
        var prefix = String.format(USER_PERM_PREFIX, userId);
        var checkKey = bitmapService.buildChunkKey(prefix, 0);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(checkKey))) {
            // Bitmap exists — just extend TTL
            extendUserBitmapTtl(userId, ttlSeconds);
            log.debug("Extended user bitmap TTL for user={}", userId);
            return;
        }
        // Bitmap missing — compute from scratch
        computeUserBitmap(userId, roleId, ttlSeconds);
    }

    /**
     * Compute the user's effective permission bitmap using the Lua script.
     * Formula: final = (role OR allow) AND (NOT deny)
     */
    private void computeUserBitmap(Long userId, Integer roleId, long ttlSeconds) {
        log.info("Computing user bitmap for userId={}", userId);

        // Fetch allow/deny from database (parallel via Virtual Threads)
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

        var maxBitIndexOpt = permissionRepository.findMaxBitIndex();
        if (maxBitIndexOpt.isEmpty()) {
            log.warn("No permissions defined in system");
            return;
        }

        var maxBitIndex = maxBitIndexOpt.get();
        var totalChunks = bitmapService.getTotalChunks(maxBitIndex);

        // Build temporary allow/deny bitmaps
        var uniqueId = UUID.randomUUID().toString().substring(0, 8);
        var tempAllow = String.format(TEMP_PREFIX, "allow:" + uniqueId);
        var tempDeny = String.format(TEMP_PREFIX, "deny:" + uniqueId);
        var rolePrefix = String.format(ROLE_PERM_PREFIX, roleId);
        var userPrefix = String.format(USER_PERM_PREFIX, userId);

        bitmapService.buildBitmap(tempAllow, allowBitIndexes);
        bitmapService.buildBitmap(tempDeny, denyBitIndexes);

        // Build Lua script arguments
        // KEYS: [roleChunk, allowChunk, denyChunk, resultChunk] × totalChunks
        // ARGV: [ttl, totalChunks, tmpOrKey0, tmpNotKey0, ...]
        var keys = new ArrayList<String>();
        var args = new ArrayList<String>();

        args.add(String.valueOf(ttlSeconds));
        args.add(String.valueOf(totalChunks));

        IntStream.range(0, totalChunks).forEach(c -> {
            keys.add(bitmapService.buildChunkKey(rolePrefix, c));
            keys.add(bitmapService.buildChunkKey(tempAllow, c));
            keys.add(bitmapService.buildChunkKey(tempDeny, c));
            keys.add(bitmapService.buildChunkKey(userPrefix, c));

            args.add("tmp:or:" + uniqueId + ":" + c);
            args.add("tmp:not:" + uniqueId + ":" + c);
        });

        stringRedisTemplate.execute(computeScript, keys, args.toArray());



        log.info("User bitmap computed: userId={}, chunks={}, TTL={}s", userId, totalChunks, ttlSeconds);
    }

    /**
     * Extend the TTL on all user bitmap chunk keys and meta key.
     */
    private void extendUserBitmapTtl(Long userId, long ttlSeconds) {
        var prefix = String.format(USER_PERM_PREFIX, userId);

        permissionRepository.findMaxBitIndex().ifPresent(maxBit -> {
            var totalChunks = bitmapService.getTotalChunks(maxBit);
            IntStream.range(0, totalChunks)
                    .mapToObj(c -> bitmapService.buildChunkKey(prefix, c))
                    .forEach(key -> stringRedisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS));
        });
    }

    /**
     * Invalidate a user's permission bitmap and all active sessions.
     * Called when allow/deny lists change, or when the user's role changes.
     */
    public void invalidateUserBitmap(Long userId) {
        log.info("Invalidating user bitmap for userId={}", userId);
        var prefix = String.format(USER_PERM_PREFIX, userId);

        permissionRepository.findMaxBitIndex().ifPresent(maxBit -> {
            var totalChunks = bitmapService.getTotalChunks(maxBit);
            bitmapService.deleteAll(prefix, totalChunks);
        });

        // Invalidate all active sessions for this user
        invalidateAllSessions(userId);
    }

    // =========================================================================
    // SESSION TRACKING
    // =========================================================================

    /**
     * Track a new session for a user. TTL on the sessions set extends
     * with each login so it persists as long as any session is active.
     */
    private void trackSession(Long userId, String sessionId, long ttlSeconds) {
        var key = String.format(USER_SESSIONS_KEY, userId);
        stringRedisTemplate.opsForSet().add(key, sessionId);
        stringRedisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
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
     * Check if a user has a specific permission.
     * Reads from the user's effective bitmap (no session ID needed).
     */
    public boolean checkPermission(Long userId, int bitIndex) {
        var prefix = String.format(USER_PERM_PREFIX, userId);
        return bitmapService.getBit(prefix, bitIndex);
    }

    /**
     * Get the user permission key prefix for external use (e.g., APISIX).
     */
    public String getUserPermKeyPrefix(Long userId) {
        return String.format(USER_PERM_PREFIX, userId);
    }
}
