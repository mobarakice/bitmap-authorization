package com.pss.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

/**
 * Listens to Redis Pub/Sub channel "auth:invalidation" for distributed
 * cache coordination across multiple JVM instances.
 *
 * Messages:
 *   ROLE_UPDATED:{roleId}      → re-build role bitmap + cascade to users
 *   USER_UPDATED:{userId}      → invalidate user bitmap
 *   PERM_INDEX_REFRESHED       → re-sync permission index from DB
 *   FORCE_REAUTH:{userId}      → invalidate user bitmap + sessions
 *   CAPABILITY_UPDATED         → clear in-memory capability cache
 *   MODULE_UPDATED             → clear in-memory module/feature tree cache
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CacheInvalidationListener implements MessageListener {

    private final SessionPermissionService sessionPermissionService;
    private final PermissionIndexService permissionIndexService;

    /**
     * -- SETTER --
     *  Register callback for CAPABILITY_UPDATED events (set by CapabilityService).
     */
    @Setter
    private volatile Runnable onCapabilityUpdated;
    /**
     * -- SETTER --
     *  Register callback for MODULE_UPDATED events (set by CapabilityService).
     */
    @Setter
    private volatile Runnable onModuleUpdated;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        var payload = new String(message.getBody());
        log.info("Received cache invalidation event: {}", payload);

        try {
            if (payload.startsWith("ROLE_UPDATED:")) {
                var roleId = Integer.parseInt(payload.substring("ROLE_UPDATED:".length()));
                sessionPermissionService.invalidateRoleBitmap(roleId);
                log.info("Processed ROLE_UPDATED for roleId={}", roleId);

            } else if (payload.startsWith("USER_UPDATED:")) {
                var userId = Long.parseLong(payload.substring("USER_UPDATED:".length()));
                sessionPermissionService.invalidateUserBitmap(userId);
                log.info("Processed USER_UPDATED for userId={}", userId);

            } else if (payload.equals("PERM_INDEX_REFRESHED")) {
                permissionIndexService.syncPermissionIndex();
                sessionPermissionService.invalidateMaxBitIndexCache();
                log.info("Processed PERM_INDEX_REFRESHED");

            } else if (payload.startsWith("FORCE_REAUTH:")) {
                var userId = Long.parseLong(payload.substring("FORCE_REAUTH:".length()));
                sessionPermissionService.invalidateUserBitmap(userId);
                log.info("Processed FORCE_REAUTH for userId={}", userId);

            } else if (payload.equals("CAPABILITY_UPDATED")) {
                if (onCapabilityUpdated != null) onCapabilityUpdated.run();
                log.info("Processed CAPABILITY_UPDATED");

            } else if (payload.equals("MODULE_UPDATED")) {
                if (onModuleUpdated != null) onModuleUpdated.run();
                log.info("Processed MODULE_UPDATED");

            } else {
                log.warn("Unknown invalidation event: {}", payload);
            }
        } catch (Exception e) {
            log.error("Failed to process invalidation event: {}", payload, e);
        }
    }
}
