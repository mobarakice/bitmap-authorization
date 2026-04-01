package com.pss.auth.dto;

import com.pss.auth.domain.entity.Role;

public record RoleResponse(
    Integer id,
    String name,
    String description,
    Boolean isActive,
    Boolean isSystem
) {
    public static RoleResponse from(Role role) {
        return new RoleResponse(
                role.getId(),
                role.getName(),
                role.getDescription(),
                role.getIsActive(),
                role.getIsSystem()
        );
    }
}
