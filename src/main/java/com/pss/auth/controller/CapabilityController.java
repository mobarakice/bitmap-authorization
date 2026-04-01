package com.pss.auth.controller;

import com.pss.auth.domain.entity.Capability;
import com.pss.auth.domain.entity.CapabilityPermission;
import com.pss.auth.dto.CapabilityRequest;
import com.pss.auth.exception.BusinessRuleException;
import com.pss.auth.exception.ResourceNotFoundException;
import com.pss.auth.repository.CapabilityPermissionRepository;
import com.pss.auth.repository.CapabilityRepository;
import com.pss.auth.service.CacheInvalidationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/capabilities")
@Tag(name = "Capabilities Admin", description = "Manage capabilities and their permission mappings")
@RequiredArgsConstructor
public class CapabilityController {

    private final CapabilityRepository capabilityRepository;
    private final CapabilityPermissionRepository capabilityPermissionRepository;
    private final CacheInvalidationService cacheInvalidationService;

    @GetMapping
    @Operation(summary = "List Capabilities", description = "List all capabilities with feature and permission count.")
    public ResponseEntity<List<Map<String, Object>>> listCapabilities() {
        var capabilities = capabilityRepository.findAllWithFeatureAndModule();
        var result = capabilities.stream().map(c -> Map.<String, Object>of(
                "id", c.getId(),
                "code", c.getCode(),
                "name", c.getName(),
                "featureCode", c.getFeature().getCode(),
                "moduleCode", c.getFeature().getModule().getCode(),
                "description", c.getDescription() != null ? c.getDescription() : ""
        )).toList();
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update Capability")
    public ResponseEntity<Map<String, Object>> updateCapability(
            @PathVariable Integer id,
            @Valid @RequestBody CapabilityRequest request) {

        var capability = findCapabilityOrThrow(id);

        if (!capability.getCode().equals(request.code()) && capabilityRepository.existsByCode(request.code())) {
            throw new BusinessRuleException("Capability code '" + request.code() + "' already exists");
        }

        capability.setCode(request.code());
        capability.setName(request.name());
        capability.setDescription(request.description());
        capabilityRepository.save(capability);
        cacheInvalidationService.onCapabilityUpdate();

        return ResponseEntity.ok(Map.of(
                "id", capability.getId(),
                "code", capability.getCode(),
                "name", capability.getName()
        ));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete Capability")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteCapability(@PathVariable Integer id) {
        var capability = findCapabilityOrThrow(id);
        capabilityRepository.delete(capability);
        cacheInvalidationService.onCapabilityUpdate();
        return ResponseEntity.ok(Map.of("message", "Capability deleted", "capabilityId", id));
    }

    @GetMapping("/{id}/permissions")
    @Operation(summary = "Get Capability Permission Mappings")
    public ResponseEntity<Map<String, Object>> getPermissions(@PathVariable Integer id) {
        findCapabilityOrThrow(id);
        var permIds = capabilityPermissionRepository.findPermissionIdsByCapabilityId(id);
        return ResponseEntity.ok(Map.of(
                "capabilityId", id,
                "permissionIds", permIds,
                "permissionCount", permIds.size()
        ));
    }

    @PutMapping("/{id}/permissions")
    @Operation(summary = "Set Capability Permissions", description = "Atomic replace of capability → permission mappings.")
    @Transactional
    public ResponseEntity<Map<String, Object>> setPermissions(
            @PathVariable Integer id,
            @RequestBody Map<String, List<Integer>> body) {

        var capability = findCapabilityOrThrow(id);
        var permissionIds = body.get("permissionIds");
        if (permissionIds == null) {
            return ResponseEntity.badRequest().build();
        }

        // Atomic replace
        capabilityPermissionRepository.deleteByCapabilityId(id);
        permissionIds.forEach(permId ->
                capabilityPermissionRepository.save(new CapabilityPermission(id, permId)));

        cacheInvalidationService.onCapabilityUpdate();

        return ResponseEntity.ok(Map.of(
                "capabilityId", id,
                "code", capability.getCode(),
                "permissionCount", permissionIds.size()
        ));
    }

    private Capability findCapabilityOrThrow(Integer id) {
        return capabilityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Capability not found: " + id));
    }
}
