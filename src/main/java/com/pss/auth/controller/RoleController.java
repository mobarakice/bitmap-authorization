package com.pss.auth.controller;

import com.pss.auth.domain.entity.Role;
import com.pss.auth.domain.entity.RolePermission;
import com.pss.auth.repository.PermissionRepository;
import com.pss.auth.repository.RolePermissionRepository;
import com.pss.auth.repository.RoleRepository;
import com.pss.auth.service.CacheInvalidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
@Slf4j
public class RoleController {

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;
    private final CacheInvalidationService cacheInvalidationService;

    /**
     * GET /api/admin/roles
     * List all roles.
     */
    @GetMapping
    public ResponseEntity<List<Role>> listRoles() {
        return ResponseEntity.ok(roleRepository.findAll());
    }

    /**
     * GET /api/admin/roles/{id}
     * Get role by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Role> getRole(@PathVariable Integer id) {
        return roleRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/admin/roles/{id}/permissions
     * Get all permission bit_indexes assigned to a role.
     */
    @GetMapping("/{id}/permissions")
    public ResponseEntity<Map<String, Object>> getRolePermissions(@PathVariable Integer id) {
        var bitIndexes = permissionRepository.findBitIndexesByRoleId(id);
        return ResponseEntity.ok(Map.of(
                "roleId", id,
                "permissionCount", bitIndexes.size(),
                "bitIndexes", bitIndexes
        ));
    }

    /**
     * PUT /api/admin/roles/{id}/permissions
     * Replace all permissions for a role.
     * Body: { "permissionIds": [1, 2, 3, ...] }
     */
    @PutMapping("/{id}/permissions")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateRolePermissions(
            @PathVariable Integer id,
            @RequestBody Map<String, List<Integer>> body) {

        var permissionIds = body.get("permissionIds");
        if (permissionIds == null) {
            return ResponseEntity.badRequest().build();
        }

        // Delete existing
        rolePermissionRepository.deleteByRoleId(id);

        // Insert new
        permissionIds.stream()
                .map(permId -> new RolePermission(id, permId))
                .forEach(rolePermissionRepository::save);

        // Invalidate role bitmap + all affected users + sessions
        cacheInvalidationService.onRolePermissionUpdate(id);

        return ResponseEntity.ok(Map.of(
                "roleId", id,
                "permissionsAssigned", permissionIds.size(),
                "message", "Active sessions for this role need re-authentication"
        ));
    }
}
