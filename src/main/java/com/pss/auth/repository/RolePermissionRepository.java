package com.pss.auth.repository;

import com.pss.auth.domain.entity.RolePermission;
import com.pss.auth.domain.entity.RolePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;

import java.util.List;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {
    List<RolePermission> findByRoleId(Integer roleId);
    void deleteByRoleId(Integer roleId);
    void deleteByRoleIdAndPermissionIdIn(Integer roleId, List<Integer> permissionIds);

    @Query("SELECT rp.permissionId FROM RolePermission rp WHERE rp.roleId = :roleId")
    List<Integer> findPermissionIdsByRoleId(Integer roleId);
}
