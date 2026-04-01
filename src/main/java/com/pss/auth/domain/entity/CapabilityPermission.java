package com.pss.auth.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.IdClass;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "capability_permissions")
@IdClass(CapabilityPermissionId.class)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
public class CapabilityPermission {

    @Id
    @Column(name = "capability_id")
    private Integer capabilityId;

    @Id
    @Column(name = "permission_id")
    private Integer permissionId;
}
