package com.pss.auth.repository;

import com.pss.auth.domain.entity.UserAllowPermission;
import com.pss.auth.domain.entity.UserPermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserAllowPermissionRepository extends JpaRepository<UserAllowPermission, UserPermissionId> {
    List<UserAllowPermission> findByUserId(Long userId);
    void deleteByUserId(Long userId);
}
