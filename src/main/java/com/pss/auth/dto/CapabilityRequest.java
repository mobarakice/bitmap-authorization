package com.pss.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CapabilityRequest(
    @NotBlank @Size(max = 100) String code,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 500) String description
) {}
