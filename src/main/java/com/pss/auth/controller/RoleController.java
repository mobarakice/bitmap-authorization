package com.pss.auth.controller;

import com.pss.auth.domain.entity.Role;
import com.pss.auth.domain.entity.RolePermission;
import com.pss.auth.dto.RoleRequest;
import com.pss.auth.dto.RoleResponse;
import com.pss.auth.exception.BusinessRuleException;
import com.pss.auth.exception.ResourceNotFoundException;
import com.pss.auth.repository.PermissionRepository;
import com.pss.auth.repository.RolePermissionRepository;
import com.pss.auth.repository.RoleRepository;
import com.pss.auth.service.CacheInvalidationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/roles")
@Tag(name = "Roles Admin", description = "Endpoints for managing roles and role permissions")
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
    @Operation(summary = "List Roles", description = "Retrieve a list of all roles in the system.")
    public ResponseEntity<List<Role>> listRoles() {
        return ResponseEntity.ok(roleRepository.findAll());
    }

    /**
     * GET /api/admin/roles/{id}
     * Get role by ID.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get Role by ID", description = "Find a role by its primary key ID.")
    public ResponseEntity<Role> getRole(@PathVariable Integer id) {
        return roleRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/admin/roles
     * Create a new role.
     */
    @PostMapping
    @Operation(summary = "Create Role", description = "Create a new role.")
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody RoleRequest request) {
        if (roleRepository.findByName(request.name()).isPresent()) {
            throw new BusinessRuleException("Role with name '" + request.name() + "' already exists");
        }

        var role = new Role();
        role.setName(request.name());
        role.setDescription(request.description());
        role.setIsActive(true);
        role.setIsSystem(false);
        var saved = roleRepository.save(role);

        return ResponseEntity.status(HttpStatus.CREATED).body(RoleResponse.from(saved));
    }

    /**
     * PUT /api/admin/roles/{id}
     * Update an existing role.
     */
    @PutMapping("/{id}")
    @Operation(summary = "Update Role", description = "Update a role's name and description.")
    public ResponseEntity<RoleResponse> updateRole(
            @PathVariable Integer id,
            @Valid @RequestBody RoleRequest request) {

        var role = roleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + id));

        // Check name uniqueness if changed
        if (!role.getName().equals(request.name())) {
            roleRepository.findByName(request.name()).ifPresent(existing -> {
                throw new BusinessRuleException("Role with name '" + request.name() + "' already exists");
            });
        }

        role.setName(request.name());
        role.setDescription(request.description());
        var saved = roleRepository.save(role);

        return ResponseEntity.ok(RoleResponse.from(saved));
    }

    /**
     * DELETE /api/admin/roles/{id}
     * Delete a role. System roles cannot be deleted.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete Role", description = "Delete a role. System roles (is_system=true) cannot be deleted.")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteRole(@PathVariable Integer id) {
        var role = roleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + id));

        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new BusinessRuleException("Cannot delete system role: " + role.getName());
        }

        // Invalidate all bitmaps for users with this role before deletion
        cacheInvalidationService.onRolePermissionUpdate(id);

        roleRepository.delete(role);

        return ResponseEntity.ok(Map.of(
                "message", "Role deleted",
                "roleId", id
        ));
    }

    /**
     * GET /api/admin/roles/{id}/permissions
     * Get all permission bit_indexes assigned to a role.
     */
    @GetMapping("/{id}/permissions")
    @Operation(summary = "Get Role Permissions", description = "Get all permission bit_indexes currently granted to a role.")
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
    @Operation(summary = "Update Role Permissions", description = "Replace all permissions for a given role ID. This triggers a permission cache invalidation.")
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

    /**
     * POST /api/admin/roles/{id}/permissions
     * Add individual permissions to a role (in-place SETBIT 1 if bitmap exists).
     */
    @PostMapping("/{id}/permissions")
    @Operation(summary = "Add Role Permissions", description = "Add individual permissions. Updates bitmap in-place if it exists.")
    @Transactional
    public ResponseEntity<Map<String, Object>> addRolePermissions(
            @PathVariable Integer id,
            @RequestBody Map<String, List<Integer>> body) {

        var permissionIds = body.get("permissionIds");
        if (permissionIds == null || permissionIds.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // Filter duplicates against existing
        var existing = rolePermissionRepository.findPermissionIdsByRoleId(id);
        var newOnly = permissionIds.stream()
                .filter(pid -> !existing.contains(pid))
                .toList();

        if (newOnly.isEmpty()) {
            return ResponseEntity.ok(Map.of("roleId", id, "added", 0, "totalPermissions", existing.size()));
        }

        // DB insert new entries
        newOnly.stream()
                .map(permId -> new RolePermission(id, permId))
                .forEach(rolePermissionRepository::save);

        // Resolve to bit_indexes for in-place update
        var bitIndexes = permissionRepository.findBitIndexesByIds(newOnly);

        // In-place SETBIT 1 + cascade
        cacheInvalidationService.onRolePermissionAdd(id, bitIndexes);

        return ResponseEntity.ok(Map.of(
                "roleId", id,
                "added", newOnly.size(),
                "totalPermissions", existing.size() + newOnly.size()
        ));
    }

    /**
     * DELETE /api/admin/roles/{id}/permissions
     * Remove individual permissions from a role (in-place SETBIT 0 if bitmap exists).
     */
    @DeleteMapping("/{id}/permissions")
    @Operation(summary = "Remove Role Permissions", description = "Remove individual permissions. Clears bitmap bits in-place if bitmap exists.")
    @Transactional
    public ResponseEntity<Map<String, Object>> removeRolePermissions(
            @PathVariable Integer id,
            @RequestBody Map<String, List<Integer>> body) {

        var permissionIds = body.get("permissionIds");
        if (permissionIds == null || permissionIds.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // Resolve bit_indexes BEFORE DB delete
        var bitIndexes = permissionRepository.findBitIndexesByIds(permissionIds);

        // DB delete
        rolePermissionRepository.deleteByRoleIdAndPermissionIdIn(id, permissionIds);

        // In-place SETBIT 0 + cascade
        cacheInvalidationService.onRolePermissionRemove(id, bitIndexes);

        var remaining = rolePermissionRepository.findPermissionIdsByRoleId(id);
        return ResponseEntity.ok(Map.of(
                "roleId", id,
                "removed", permissionIds.size(),
                "remainingPermissions", remaining.size()
        ));
    }
}
