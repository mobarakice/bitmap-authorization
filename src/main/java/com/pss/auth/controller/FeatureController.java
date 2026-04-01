package com.pss.auth.controller;

import com.pss.auth.domain.entity.Capability;
import com.pss.auth.domain.entity.ModuleFeature;
import com.pss.auth.dto.CapabilityRequest;
import com.pss.auth.dto.FeatureRequest;
import com.pss.auth.exception.BusinessRuleException;
import com.pss.auth.exception.ResourceNotFoundException;
import com.pss.auth.repository.CapabilityRepository;
import com.pss.auth.repository.ModuleFeatureRepository;
import com.pss.auth.service.CacheInvalidationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/admin/features")
@Tag(name = "Features Admin", description = "Manage features and their capabilities")
@RequiredArgsConstructor
public class FeatureController {

    private final ModuleFeatureRepository featureRepository;
    private final CapabilityRepository capabilityRepository;
    private final CacheInvalidationService cacheInvalidationService;

    @PutMapping("/{id}")
    @Operation(summary = "Update Feature")
    public ResponseEntity<Map<String, Object>> updateFeature(
            @PathVariable Integer id,
            @Valid @RequestBody FeatureRequest request) {

        var feature = findFeatureOrThrow(id);

        if (!feature.getCode().equals(request.code()) && featureRepository.existsByCode(request.code())) {
            throw new BusinessRuleException("Feature code '" + request.code() + "' already exists");
        }

        feature.setCode(request.code());
        feature.setName(request.name());
        feature.setSortOrder(request.sortOrder());
        featureRepository.save(feature);
        cacheInvalidationService.onModuleUpdate();

        return ResponseEntity.ok(Map.of(
                "id", feature.getId(),
                "code", feature.getCode(),
                "name", feature.getName()
        ));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete Feature", description = "Delete feature (cascades to capabilities).")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteFeature(@PathVariable Integer id) {
        var feature = findFeatureOrThrow(id);
        featureRepository.delete(feature);
        cacheInvalidationService.onModuleUpdate();
        return ResponseEntity.ok(Map.of("message", "Feature deleted", "featureId", id));
    }

    @GetMapping("/{featureId}/capabilities")
    @Operation(summary = "List Capabilities for Feature")
    public ResponseEntity<List<Map<String, Object>>> listCapabilities(@PathVariable Integer featureId) {
        findFeatureOrThrow(featureId);
        var capabilities = capabilityRepository.findByFeatureId(featureId);
        var result = capabilities.stream().map(c -> Map.<String, Object>of(
                "id", c.getId(),
                "code", c.getCode(),
                "name", c.getName(),
                "description", c.getDescription() != null ? c.getDescription() : ""
        )).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{featureId}/capabilities")
    @Operation(summary = "Create Capability", description = "Create a capability under a feature.")
    public ResponseEntity<Map<String, Object>> createCapability(
            @PathVariable Integer featureId,
            @Valid @RequestBody CapabilityRequest request) {

        var feature = findFeatureOrThrow(featureId);

        if (capabilityRepository.existsByCode(request.code())) {
            throw new BusinessRuleException("Capability code '" + request.code() + "' already exists");
        }

        var capability = new Capability();
        capability.setCode(request.code());
        capability.setName(request.name());
        capability.setDescription(request.description());
        capability.setFeature(feature);

        var saved = capabilityRepository.save(capability);
        cacheInvalidationService.onCapabilityUpdate();

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", saved.getId(),
                "code", saved.getCode(),
                "name", saved.getName(),
                "featureId", featureId
        ));
    }

    private ModuleFeature findFeatureOrThrow(Integer id) {
        return featureRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Feature not found: " + id));
    }
}
