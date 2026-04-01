package com.pss.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ModuleRequest(
    @NotBlank @Size(max = 50) String code,
    @NotBlank @Size(max = 200) String name,
    Integer parentId,
    Integer sortOrder
) {
    public ModuleRequest {
        if (sortOrder == null) sortOrder = 0;
    }
}
