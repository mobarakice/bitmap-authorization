package com.pss.auth.controller;

import com.pss.auth.domain.entity.Module;
import com.pss.auth.domain.entity.ModuleFeature;
import com.pss.auth.dto.FeatureRequest;
import com.pss.auth.dto.ModuleRequest;
import com.pss.auth.dto.ModuleResponse;
import com.pss.auth.exception.BusinessRuleException;
import com.pss.auth.exception.ResourceNotFoundException;
import com.pss.auth.repository.ModuleFeatureRepository;
import com.pss.auth.repository.ModuleRepository;
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
@RequestMapping("/api/admin/modules")
@Tag(name = "Modules Admin", description = "Manage modules and features in the presentation layer")
@RequiredArgsConstructor
public class ModuleController {

    private final ModuleRepository moduleRepository;
    private final ModuleFeatureRepository featureRepository;
    private final CacheInvalidationService cacheInvalidationService;

    @GetMapping
    @Operation(summary = "List Modules (Tree)", description = "Get all root modules with children and features.")
    public ResponseEntity<List<ModuleResponse>> listModules() {
        var roots = moduleRepository.findByParentIsNullOrderBySortOrder();
        return ResponseEntity.ok(roots.stream().map(ModuleResponse::from).toList());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get Module", description = "Get a module by ID with children and features.")
    public ResponseEntity<ModuleResponse> getModule(@PathVariable Integer id) {
        var module = findModuleOrThrow(id);
        return ResponseEntity.ok(ModuleResponse.from(module));
    }

    @PostMapping
    @Operation(summary = "Create Module", description = "Create a new module.")
    public ResponseEntity<ModuleResponse> createModule(@Valid @RequestBody ModuleRequest request) {
        if (moduleRepository.existsByCode(request.code())) {
            throw new BusinessRuleException("Module code '" + request.code() + "' already exists");
        }

        var module = new Module();
        module.setCode(request.code());
        module.setName(request.name());
        module.setSortOrder(request.sortOrder());
        module.setIsActive(true);

        if (request.parentId() != null) {
            var parent = findModuleOrThrow(request.parentId());
            module.setParent(parent);
        }

        var saved = moduleRepository.save(module);
        cacheInvalidationService.onModuleUpdate();

        return ResponseEntity.status(HttpStatus.CREATED).body(ModuleResponse.from(saved));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update Module")
    public ResponseEntity<ModuleResponse> updateModule(
            @PathVariable Integer id,
            @Valid @RequestBody ModuleRequest request) {

        var module = findModuleOrThrow(id);

        if (!module.getCode().equals(request.code()) && moduleRepository.existsByCode(request.code())) {
            throw new BusinessRuleException("Module code '" + request.code() + "' already exists");
        }

        module.setCode(request.code());
        module.setName(request.name());
        module.setSortOrder(request.sortOrder());

        if (request.parentId() != null) {
            var parent = findModuleOrThrow(request.parentId());
            module.setParent(parent);
        } else {
            module.setParent(null);
        }

        var saved = moduleRepository.save(module);
        cacheInvalidationService.onModuleUpdate();

        return ResponseEntity.ok(ModuleResponse.from(saved));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete Module", description = "Delete module (cascades to features and capabilities).")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteModule(@PathVariable Integer id) {
        var module = findModuleOrThrow(id);
        moduleRepository.delete(module);
        cacheInvalidationService.onModuleUpdate();
        return ResponseEntity.ok(Map.of("message", "Module deleted", "moduleId", id));
    }

    // ---- Nested feature endpoints under module ----

    @GetMapping("/{moduleId}/features")
    @Operation(summary = "List Features for Module")
    public ResponseEntity<List<ModuleResponse.FeatureResponse>> listFeatures(@PathVariable Integer moduleId) {
        findModuleOrThrow(moduleId);
        var features = featureRepository.findByModuleIdOrderBySortOrder(moduleId);
        return ResponseEntity.ok(features.stream().map(ModuleResponse.FeatureResponse::from).toList());
    }

    @PostMapping("/{moduleId}/features")
    @Operation(summary = "Create Feature", description = "Create a feature under a module.")
    public ResponseEntity<ModuleResponse.FeatureResponse> createFeature(
            @PathVariable Integer moduleId,
            @Valid @RequestBody FeatureRequest request) {

        var module = findModuleOrThrow(moduleId);

        if (featureRepository.existsByCode(request.code())) {
            throw new BusinessRuleException("Feature code '" + request.code() + "' already exists");
        }

        var feature = new ModuleFeature();
        feature.setCode(request.code());
        feature.setName(request.name());
        feature.setModule(module);
        feature.setSortOrder(request.sortOrder());
        feature.setIsActive(true);

        var saved = featureRepository.save(feature);
        cacheInvalidationService.onModuleUpdate();

        return ResponseEntity.status(HttpStatus.CREATED).body(ModuleResponse.FeatureResponse.from(saved));
    }

    private Module findModuleOrThrow(Integer id) {
        return moduleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Module not found: " + id));
    }
}
