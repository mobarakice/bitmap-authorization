package com.pss.auth.repository;

import com.pss.auth.domain.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Integer> {

    Optional<Permission> findByResourceAndAction(String resource, String action);

    @Query("SELECT MAX(p.bitIndex) FROM Permission p")
    Optional<Integer> findMaxBitIndex();

    @Query("SELECT p.bitIndex FROM Permission p " +
           "JOIN RolePermission rp ON rp.permissionId = p.id " +
           "WHERE rp.roleId = :roleId")
    List<Integer> findBitIndexesByRoleId(Integer roleId);

    @Query("SELECT p.bitIndex FROM Permission p " +
           "JOIN UserAllowPermission uap ON uap.permissionId = p.id " +
           "WHERE uap.userId = :userId")
    List<Integer> findAllowBitIndexesByUserId(Long userId);

    @Query("SELECT p.bitIndex FROM Permission p " +
           "JOIN UserDenyPermission udp ON udp.permissionId = p.id " +
           "WHERE udp.userId = :userId")
    List<Integer> findDenyBitIndexesByUserId(Long userId);

    @Query("SELECT COUNT(p) FROM Permission p WHERE p.id IN :ids")
    long countByIdIn(List<Integer> ids);

    @Query("SELECT p.bitIndex FROM Permission p WHERE p.id IN :ids")
    List<Integer> findBitIndexesByIds(List<Integer> ids);
}
