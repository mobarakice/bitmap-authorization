package com.pss.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserCreateRequest(
    @NotBlank @Size(max = 100) String username,
    @NotBlank @Size(min = 6, max = 128) String password,
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    @Size(max = 255) String email,
    @Size(max = 20) String mobileNo
) {}
