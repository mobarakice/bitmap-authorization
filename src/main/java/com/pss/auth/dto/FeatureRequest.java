package com.pss.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FeatureRequest(
    @NotBlank @Size(max = 50) String code,
    @NotBlank @Size(max = 200) String name,
    Integer sortOrder
) {
    public FeatureRequest {
        if (sortOrder == null) sortOrder = 0;
    }
}
