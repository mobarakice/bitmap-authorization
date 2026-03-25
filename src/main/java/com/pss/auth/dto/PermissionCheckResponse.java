package com.pss.auth.dto;


public record PermissionCheckResponse(
    boolean allowed,
    String action,
    String resource,
    Integer permissionIndex
) {}
