package com.pss.auth.domain.entity;

import java.io.Serializable;
import java.util.Objects;

public class CapabilityPermissionId implements Serializable {

    private Integer capabilityId;
    private Integer permissionId;

    public CapabilityPermissionId() {}

    public CapabilityPermissionId(Integer capabilityId, Integer permissionId) {
        this.capabilityId = capabilityId;
        this.permissionId = permissionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CapabilityPermissionId that)) return false;
        return Objects.equals(capabilityId, that.capabilityId)
                && Objects.equals(permissionId, that.permissionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(capabilityId, permissionId);
    }
}
