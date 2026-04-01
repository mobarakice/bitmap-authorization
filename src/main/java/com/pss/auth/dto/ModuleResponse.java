package com.pss.auth.dto;

import com.pss.auth.domain.entity.Module;

import java.util.List;

public record ModuleResponse(
    Integer id,
    String code,
    String name,
    Integer parentId,
    Integer sortOrder,
    Boolean isActive,
    List<ModuleResponse> children,
    List<FeatureResponse> features
) {
    public static ModuleResponse from(Module module) {
        return new ModuleResponse(
                module.getId(),
                module.getCode(),
                module.getName(),
                module.getParent() != null ? module.getParent().getId() : null,
                module.getSortOrder(),
                module.getIsActive(),
                module.getChildren() != null
                        ? module.getChildren().stream().map(ModuleResponse::from).toList()
                        : List.of(),
                module.getFeatures() != null
                        ? module.getFeatures().stream().map(FeatureResponse::from).toList()
                        : List.of()
        );
    }

    public record FeatureResponse(
        Integer id,
        String code,
        String name,
        Integer sortOrder,
        Boolean isActive,
        int capabilityCount
    ) {
        public static FeatureResponse from(com.pss.auth.domain.entity.ModuleFeature feature) {
            return new FeatureResponse(
                    feature.getId(),
                    feature.getCode(),
                    feature.getName(),
                    feature.getSortOrder(),
                    feature.getIsActive(),
                    feature.getCapabilities() != null ? feature.getCapabilities().size() : 0
            );
        }
    }
}
