package com.pss.auth.service;

import com.pss.auth.domain.entity.Module;
import com.pss.auth.dto.FeatureNode;
import com.pss.auth.dto.ModuleTreeNode;
import com.pss.auth.dto.ResolvedCapabilities;
import com.pss.auth.repository.CapabilityPermissionRepository;
import com.pss.auth.repository.CapabilityRepository;
import com.pss.auth.repository.ModuleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Resolves capabilities from bitmap state at login time.
 * Maintains in-memory caches for capability→permission mappings and module tree.
 * Caches are invalidated via Pub/Sub events (CAPABILITY_UPDATED, MODULE_UPDATED).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CapabilityService {

    private final CapabilityRepository capabilityRepository;
    private final CapabilityPermissionRepository capabilityPermissionRepository;
    private final ModuleRepository moduleRepository;
    private final SessionPermissionService sessionPermissionService;
    private final CacheInvalidationListener cacheInvalidationListener;

    /**
     * Maps capabilityId → {code, featureId, List<bitIndex>}
     */
    private record CapabilityMapping(Integer id, String code, Integer featureId, List<Integer> bitIndexes) {}

    private final AtomicReference<Map<Integer, CapabilityMapping>> capabilityCache = new AtomicReference<>();
    private final AtomicReference<List<Module>> moduleTreeCache = new AtomicReference<>();

    @PostConstruct
    void init() {
        cacheInvalidationListener.setOnCapabilityUpdated(this::invalidateCapabilityCache);
        cacheInvalidationListener.setOnModuleUpdated(this::invalidateModuleCache);
    }

    public void invalidateCapabilityCache() {
        capabilityCache.set(null);
        log.info("Capability cache invalidated");
    }

    public void invalidateModuleCache() {
        moduleTreeCache.set(null);
        log.info("Module tree cache invalidated");
    }

    /**
     * Resolve capabilities for a user based on their effective bitmap state.
     * A capability is GRANTED only if ALL its mapped permissions pass the bitmap formula.
     */
    public ResolvedCapabilities resolveCapabilities(Long userId, Integer roleId) {
        var capMap = getCapabilityMap();
        if (capMap.isEmpty()) {
            return new ResolvedCapabilities(List.of(), List.of());
        }

        // Collect all unique bit_indexes across all capabilities
        var allBitIndexes = capMap.values().stream()
                .flatMap(cm -> cm.bitIndexes().stream())
                .distinct()
                .toList();

        // Batch check all permissions in 3 pipelines
        var permResults = sessionPermissionService.checkPermissionsBatch(userId, roleId, allBitIndexes);

        // Evaluate each capability: granted only if ALL its permissions pass
        var grantedCapabilities = new ArrayList<String>();
        var grantedByFeature = new HashMap<Integer, List<String>>();

        for (var entry : capMap.entrySet()) {
            var cm = entry.getValue();
            boolean allGranted = cm.bitIndexes().stream()
                    .allMatch(idx -> Boolean.TRUE.equals(permResults.get(idx)));

            if (allGranted && !cm.bitIndexes().isEmpty()) {
                grantedCapabilities.add(cm.code());
                grantedByFeature
                        .computeIfAbsent(cm.featureId(), k -> new ArrayList<>())
                        .add(cm.code());
            }
        }

        // Build module → feature → capability tree
        var moduleTree = buildModuleTree(grantedByFeature);

        log.debug("Resolved {} capabilities for userId={}, roleId={}", grantedCapabilities.size(), userId, roleId);
        return new ResolvedCapabilities(grantedCapabilities, moduleTree);
    }

    private Map<Integer, CapabilityMapping> getCapabilityMap() {
        var cached = capabilityCache.get();
        if (cached != null) return cached;

        var capabilities = capabilityRepository.findAllWithFeatureAndModule();
        var map = new HashMap<Integer, CapabilityMapping>();

        for (var cap : capabilities) {
            var permIds = capabilityPermissionRepository.findPermissionIdsByCapabilityId(cap.getId());
            // We need bit_indexes, not permission IDs. Get them from the permission repo.
            // But capabilityPermissionRepository only stores permissionIds.
            // We need a query that resolves capabilityId → bit_indexes.
            var bitIndexes = capabilityPermissionRepository.findBitIndexesByCapabilityId(cap.getId());
            map.put(cap.getId(), new CapabilityMapping(
                    cap.getId(), cap.getCode(), cap.getFeature().getId(), bitIndexes));
        }

        capabilityCache.set(map);
        log.info("Loaded capability→permission map: {} capabilities", map.size());
        return map;
    }

    private List<ModuleTreeNode> buildModuleTree(Map<Integer, List<String>> grantedByFeature) {
        var roots = getModuleTree();
        return buildTreeRecursive(roots, grantedByFeature);
    }

    private List<ModuleTreeNode> buildTreeRecursive(List<Module> modules, Map<Integer, List<String>> grantedByFeature) {
        var result = new ArrayList<ModuleTreeNode>();

        for (var module : modules) {
            // Build features with granted capabilities
            var featureNodes = new ArrayList<FeatureNode>();
            if (module.getFeatures() != null) {
                for (var feature : module.getFeatures()) {
                    var caps = grantedByFeature.getOrDefault(feature.getId(), List.of());
                    if (!caps.isEmpty() && Boolean.TRUE.equals(feature.getIsActive())) {
                        featureNodes.add(new FeatureNode(feature.getCode(), feature.getName(), caps));
                    }
                }
            }

            // Recurse into children
            var childNodes = List.<ModuleTreeNode>of();
            if (module.getChildren() != null && !module.getChildren().isEmpty()) {
                childNodes = buildTreeRecursive(module.getChildren(), grantedByFeature);
            }

            // Include module only if it has features or children with content
            if (!featureNodes.isEmpty() || !childNodes.isEmpty()) {
                if (Boolean.TRUE.equals(module.getIsActive())) {
                    result.add(new ModuleTreeNode(module.getCode(), module.getName(), featureNodes, childNodes));
                }
            }
        }

        return result;
    }

    private List<Module> getModuleTree() {
        var cached = moduleTreeCache.get();
        if (cached != null) return cached;

        var roots = moduleRepository.findByParentIsNullOrderBySortOrder();
        moduleTreeCache.set(roots);
        log.info("Loaded module tree: {} root modules", roots.size());
        return roots;
    }
}
