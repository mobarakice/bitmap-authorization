package com.pss.auth.repository;

import com.pss.auth.domain.entity.CapabilityPermission;
import com.pss.auth.domain.entity.CapabilityPermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CapabilityPermissionRepository extends JpaRepository<CapabilityPermission, CapabilityPermissionId> {
    List<CapabilityPermission> findByCapabilityId(Integer capabilityId);
    void deleteByCapabilityId(Integer capabilityId);

    @Query("SELECT cp.permissionId FROM CapabilityPermission cp WHERE cp.capabilityId = :capabilityId")
    List<Integer> findPermissionIdsByCapabilityId(Integer capabilityId);

    @Query("SELECT p.bitIndex FROM CapabilityPermission cp " +
           "JOIN Permission p ON p.id = cp.permissionId " +
           "WHERE cp.capabilityId = :capabilityId")
    List<Integer> findBitIndexesByCapabilityId(Integer capabilityId);
}
