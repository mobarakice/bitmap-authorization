package com.pss.auth.controller;

import com.pss.auth.domain.entity.Permission;
import com.pss.auth.repository.PermissionRepository;
import com.pss.auth.service.CacheInvalidationService;
import com.pss.auth.service.PermissionIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/permissions")
@RequiredArgsConstructor
@Slf4j
public class PermissionController {

    private final PermissionRepository permissionRepository;
    private final PermissionIndexService permissionIndexService;
    private final CacheInvalidationService cacheInvalidationService;

    /**
     * GET /api/admin/permissions
     * List all permissions with their bit_index assignments.
     */
    @GetMapping
    public ResponseEntity<List<Permission>> listPermissions() {
        return ResponseEntity.ok(permissionRepository.findAll());
    }

    /**
     * POST /api/admin/permissions
     * Create a new permission with auto-assigned bit_index.
     */
    @PostMapping
    public ResponseEntity<Permission> createPermission(@RequestBody Permission permission) {
        // Auto-assign next bit_index
        var nextIndex = permissionRepository.findMaxBitIndex().orElse(-1) + 1;
        permission.setBitIndex(nextIndex);

        var saved = permissionRepository.save(permission);

        // Update Redis index
        permissionIndexService.updatePermissionIndex(
                saved.getAction(), saved.getResource(), saved.getBitIndex());

        cacheInvalidationService.onPermissionUpdate();

        return ResponseEntity.ok(saved);
    }

    /**
     * POST /api/admin/permissions/sync
     * Force sync all permissions from DB to Redis perm:index.
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncPermissions() {
        permissionIndexService.syncPermissionIndex();
        return ResponseEntity.ok(Map.of(
                "message", "Permission index synced",
                "count", permissionIndexService.getPermissionCount()
        ));
    }

    /**
     * GET /api/admin/permissions/stats
     * Permission statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(Map.of(
                "totalPermissions", permissionRepository.count(),
                "maxBitIndex", permissionRepository.findMaxBitIndex().orElse(0),
                "redisIndexCount", permissionIndexService.getPermissionCount()
        ));
    }
}
