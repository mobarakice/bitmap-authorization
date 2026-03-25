package com.pss.auth.dto;


public record PermissionCheckRequest(
    String action,
    String resource
) {}
