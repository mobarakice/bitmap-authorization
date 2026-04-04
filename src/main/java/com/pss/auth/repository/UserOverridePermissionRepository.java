package com.pss.auth.repository;

import com.pss.auth.domain.entity.OverrideType;
import com.pss.auth.domain.entity.UserOverridePermission;
import com.pss.auth.domain.entity.UserOverridePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserOverridePermissionRepository extends JpaRepository<UserOverridePermission, UserOverridePermissionId> {
    List<UserOverridePermission> findByUserId(Long userId);
    List<UserOverridePermission> findByUserIdAndOverrideType(Long userId, OverrideType overrideType);
    void deleteByUserId(Long userId);
    void deleteByUserIdAndOverrideType(Long userId, OverrideType overrideType);
}
