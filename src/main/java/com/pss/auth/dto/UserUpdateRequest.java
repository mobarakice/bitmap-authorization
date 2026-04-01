package com.pss.auth.dto;

import jakarta.validation.constraints.Size;

public record UserUpdateRequest(
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    @Size(max = 255) String email,
    @Size(max = 20) String mobileNo,
    Boolean isActive
) {}
