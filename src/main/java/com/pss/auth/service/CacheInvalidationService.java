package com.pss.auth.service;

import com.pss.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Cache invalidation service for the two-tier bitmap architecture.
 *
 * Handles invalidation events triggered by admin operations:
 *   - Role permission changes  → invalidate role bitmap + all affected users
 *   - User allow/deny changes  → invalidate user bitmap + all sessions
 *   - Permission index changes → refresh the perm:index hash
 *
 * Also publishes Redis Pub/Sub events for distributed cache coordination.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CacheInvalidationService {

    private static final String INVALIDATION_CHANNEL = "auth:invalidation";

    private final StringRedisTemplate stringRedisTemplate;
    private final SessionPermissionService sessionPermissionService;
    private final PermissionIndexService permissionIndexService;
    private final UserRepository userRepository;

    // =========================================================================
    // ROLE PERMISSION CHANGES
    // =========================================================================

    /**
     * Called when a role's permission set changes (add/remove permissions from role).
     * Invalidates the role bitmap and cascades to all users with that role.
     */
    public void onRolePermissionUpdate(Integer roleId) {
        log.info("Role permission update: roleId={}", roleId);

        // 1. Invalidate role bitmap + all affected user bitmaps + sessions
        sessionPermissionService.invalidateRoleBitmap(roleId);

        // 2. Publish event for distributed coordination
        var message = "ROLE_UPDATED:" + roleId;
        stringRedisTemplate.convertAndSend(INVALIDATION_CHANNEL, message);
        log.info("Published cache invalidation: {}", message);
    }

    /**
     * Called when individual permissions are ADDED to a role.
     * In-place SETBIT 1 if bitmap exists + cascade to affected users.
     */
    public void onRolePermissionAdd(Integer roleId, List<Integer> bitIndexes) {
        log.info("Role permission add: roleId={}, bits={}", roleId, bitIndexes.size());

        sessionPermissionService.addRolePermissionBits(roleId, bitIndexes);
        cascadeInvalidateUsersForRole(roleId);

        var message = "ROLE_UPDATED:" + roleId;
        stringRedisTemplate.convertAndSend(INVALIDATION_CHANNEL, message);
        log.info("Published cache invalidation: {}", message);
    }

    /**
     * Called when individual permissions are REMOVED from a role.
     * In-place SETBIT 0 if bitmap exists + cascade to affected users.
     */
    public void onRolePermissionRemove(Integer roleId, List<Integer> bitIndexes) {
        log.info("Role permission remove: roleId={}, bits={}", roleId, bitIndexes.size());

        sessionPermissionService.removeRolePermissionBits(roleId, bitIndexes);
        cascadeInvalidateUsersForRole(roleId);

        var message = "ROLE_UPDATED:" + roleId;
        stringRedisTemplate.convertAndSend(INVALIDATION_CHANNEL, message);
        log.info("Published cache invalidation: {}", message);
    }

    /**
     * Called when a user's allow/deny permissions change.
     * Conditionally rebuilds bitmaps if they exist in Redis (preserving TTL).
     */
    public void onUserPermissionUpdateWithRebuild(Long userId) {
        log.info("User permission update with conditional rebuild: userId={}", userId);

        sessionPermissionService.rebuildUserBitmapsIfExist(userId);

        var message = "USER_UPDATED:" + userId;
        stringRedisTemplate.convertAndSend(INVALIDATION_CHANNEL, message);
        log.info("Published cache invalidation: {}", message);
    }

    private void cascadeInvalidateUsersForRole(Integer roleId) {
        var userIds = userRepository.findUserIdsByRoleId(roleId);
        userIds.forEach(uid -> {
            sessionPermissionService.invalidateUserBitmap(uid);
        });
        log.info("Cascade invalidated {} users for roleId={}", userIds.size(), roleId);
    }

    // =========================================================================
    // USER PERMISSION CHANGES
    // =========================================================================

    /**
     * Called when a user's allow or deny list changes.
     * Invalidates the user's bitmap and all active sessions.
     */
    public void onUserPermissionUpdate(Long userId) {
        log.info("User permission update: userId={}", userId);

        // 1. Invalidate user bitmap + all sessions
        sessionPermissionService.invalidateUserBitmap(userId);

        // 2. Publish event
        var message = "USER_UPDATED:" + userId;
        stringRedisTemplate.convertAndSend(INVALIDATION_CHANNEL, message);
        log.info("Published cache invalidation: {}", message);
    }

    // =========================================================================
    // PERMISSION INDEX CHANGES
    // =========================================================================

    /**
     * Called when a permission is added, modified, or deleted.
     * Refreshes the global perm:index hash in Redis.
     */
    public void onPermissionUpdate() {
        log.info("Permission index update — refreshing perm:index");
        permissionIndexService.syncPermissionIndex();

        var message = "PERM_INDEX_REFRESHED";
        stringRedisTemplate.convertAndSend(INVALIDATION_CHANNEL, message);
        log.info("Published cache invalidation: {}", message);
    }

    // =========================================================================
    // PRESENTATION LAYER CHANGES
    // =========================================================================

    /**
     * Called when capability → permission mappings change.
     * Clears in-memory capability cache on all instances.
     */
    public void onCapabilityUpdate() {
        log.info("Capability mapping update");
        var message = "CAPABILITY_UPDATED";
        stringRedisTemplate.convertAndSend(INVALIDATION_CHANNEL, message);
        log.info("Published cache invalidation: {}", message);
    }

    /**
     * Called when modules or features change.
     * Clears in-memory module/feature tree cache on all instances.
     */
    public void onModuleUpdate() {
        log.info("Module/feature structure update");
        var message = "MODULE_UPDATED";
        stringRedisTemplate.convertAndSend(INVALIDATION_CHANNEL, message);
        log.info("Published cache invalidation: {}", message);
    }

    // =========================================================================
    // FORCE RE-AUTHENTICATION
    // =========================================================================

    /**
     * Force a specific user to re-authenticate by invalidating all sessions.
     */
    public void forceReauth(Long userId) {
        log.info("Force re-auth for userId={}", userId);
        sessionPermissionService.invalidateUserBitmap(userId);

        var message = "FORCE_REAUTH:" + userId;
        stringRedisTemplate.convertAndSend(INVALIDATION_CHANNEL, message);
    }

    /**
     * Force all users of a role to re-authenticate.
     */
    public void forceReauthByRole(Integer roleId) {
        log.info("Force re-auth for all users of roleId={}", roleId);
        var userIds = userRepository.findUserIdsByRoleId(roleId);
        userIds.forEach(this::forceReauth);
    }
}
