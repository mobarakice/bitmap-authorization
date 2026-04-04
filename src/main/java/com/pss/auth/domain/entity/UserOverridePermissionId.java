package com.pss.auth.domain.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import java.io.Serializable;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode
public class UserOverridePermissionId implements Serializable {
    private Long userId;
    private Integer permissionId;
    private OverrideType overrideType;
}
