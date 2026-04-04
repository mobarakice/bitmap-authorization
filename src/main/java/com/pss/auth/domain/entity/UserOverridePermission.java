package com.pss.auth.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.IdClass;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.FetchType;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "user_override_permissions")
@IdClass(UserOverridePermissionId.class)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
public class UserOverridePermission {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "permission_id")
    private Integer permissionId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "override_type", length = 10)
    private OverrideType overrideType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "permission_id", insertable = false, updatable = false)
    private Permission permission;
}
