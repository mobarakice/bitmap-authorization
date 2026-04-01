package com.pss.auth.dto;

import jakarta.validation.constraints.NotNull;

public record UserRoleRequest(
    @NotNull Integer roleId
) {}
