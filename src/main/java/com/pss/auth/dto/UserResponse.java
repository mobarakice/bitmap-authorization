package com.pss.auth.dto;

import com.pss.auth.domain.entity.User;

public record UserResponse(
    Long id,
    String username,
    String firstName,
    String lastName,
    String email,
    String mobileNo,
    String signcode,
    String role,
    Boolean isActive,
    Boolean isSystem
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getMobileNo(),
                user.getSigncode(),
                user.getRole() != null ? user.getRole().getName() : null,
                user.getIsActive(),
                user.getIsSystem()
        );
    }
}
