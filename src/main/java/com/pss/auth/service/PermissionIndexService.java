package com.pss.auth.service;

import com.pss.auth.domain.entity.Permission;
import com.pss.auth.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;

/**
 * Manages the global permission → bit_index mapping in Redis.
 * 
 * Redis key: "perm:index" (HASH)
 *   field: "GET:/users" → value: "0"
 *   field: "POST:/users" → value: "1"
 *   ...
 * 
 * This hash is loaded on application startup and can be refreshed
 * when permissions are added/modified.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionIndexService {

    private static final String PERM_INDEX_KEY = "perm:index";

    private final PermissionRepository permissionRepository;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * On startup, sync all permissions from PostgreSQL → Redis hash.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncPermissionIndex() {
        log.info("Syncing permission index to Redis...");
        var permissions = permissionRepository.findAll();

        if (permissions.isEmpty()) {
            log.warn("No permissions found in database.");
            return;
        }

        var indexMap = permissions.stream()
                .collect(java.util.stream.Collectors.toMap(
                        Permission::toPermissionKey,
                        p -> String.valueOf(p.getBitIndex()),
                        (existing, replacement) -> existing,
                        HashMap::new
                ));

        // Atomic replace: delete old, set new
        var tmpKey = PERM_INDEX_KEY + ":tmp";
        stringRedisTemplate.delete(tmpKey);
        stringRedisTemplate.opsForHash().putAll(tmpKey, indexMap);
        stringRedisTemplate.rename(tmpKey, PERM_INDEX_KEY);

        log.info("Synced {} permissions to Redis perm:index", indexMap.size());
    }

    /**
     * Lookup the bit_index for a given permission key (e.g., "GET:/users").
     * Returns null if the permission is not indexed.
     */
    public Integer getPermissionIndex(String action, String resource) {
        var key = new java.util.StringJoiner(":").add(action).add(resource).toString();
        var value = stringRedisTemplate.opsForHash().get(PERM_INDEX_KEY, key);
        if (value == null) {
            log.debug("Permission index not found for: {}", key);
            return null;
        }
        return Integer.parseInt(value.toString());
    }

    /**
     * Add or update a single permission in the index.
     */
    public void updatePermissionIndex(String action, String resource, int bitIndex) {
        var key = new java.util.StringJoiner(":").add(action).add(resource).toString();
        stringRedisTemplate.opsForHash().put(PERM_INDEX_KEY, key, String.valueOf(bitIndex));
        log.info("Updated perm:index {} → {}", key, bitIndex);
    }

    /**
     * Remove a permission from the index.
     */
    public void removePermissionIndex(String action, String resource) {
        var key = new java.util.StringJoiner(":").add(action).add(resource).toString();
        stringRedisTemplate.opsForHash().delete(PERM_INDEX_KEY, key);
        log.info("Removed perm:index {}", key);
    }

    /**
     * Get total number of indexed permissions.
     */
    public long getPermissionCount() {
        var size = stringRedisTemplate.opsForHash().size(PERM_INDEX_KEY);
        return size != null ? size : 0L;
    }
}
