package com.pss.auth.repository;

import com.pss.auth.domain.entity.UserDenyPermission;
import com.pss.auth.domain.entity.UserPermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserDenyPermissionRepository extends JpaRepository<UserDenyPermission, UserPermissionId> {
    List<UserDenyPermission> findByUserId(Long userId);
    void deleteByUserId(Long userId);
}
