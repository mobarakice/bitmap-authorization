package com.pss.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
    String accessToken,
    String tokenType,
    long expiresIn,
    Long userId,
    String username,
    String role,
    List<String> capabilities,
    List<ModuleTreeNode> modules
) {
    /**
     * Backward-compatible constructor without capabilities/modules.
     */
    public LoginResponse(String accessToken, String tokenType, long expiresIn,
                         Long userId, String username, String role) {
        this(accessToken, tokenType, expiresIn, userId, username, role, null, null);
    }
}
