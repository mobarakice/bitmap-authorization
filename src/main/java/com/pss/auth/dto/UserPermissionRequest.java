package com.pss.auth.dto;

import java.util.List;

public record UserPermissionRequest(
    List<Integer> allowPermissionIds,
    List<Integer> denyPermissionIds
) {
    public UserPermissionRequest {
        if (allowPermissionIds == null) allowPermissionIds = List.of();
        if (denyPermissionIds == null) denyPermissionIds = List.of();
    }
}
