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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/permissions")
@Tag(name = "Permissions Admin", description = "Endpoints for managing permissions")
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
    @Operation(summary = "List Permissions", description = "Retrieve all permissions with their assigned bit indexes.")
    public ResponseEntity<List<Permission>> listPermissions() {
        return ResponseEntity.ok(permissionRepository.findAll());
    }

    /**
     * POST /api/admin/permissions
     * Create a new permission with auto-assigned bit_index.
     */
    @PostMapping
    @Operation(summary = "Create Permission", description = "Create a new permission and automatically assign it a bit index.")
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
    @Operation(summary = "Sync Permission Index", description = "Force sync all permissions from the database to the Redis perm:index hash.")
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
    @Operation(summary = "Permission Statistics", description = "Get statistics about the permissions, like total count and max bit index.")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(Map.of(
                "totalPermissions", permissionRepository.count(),
                "maxBitIndex", permissionRepository.findMaxBitIndex().orElse(0),
                "redisIndexCount", permissionIndexService.getPermissionCount()
        ));
    }
}
