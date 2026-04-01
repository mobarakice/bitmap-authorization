package com.pss.auth.repository;

import com.pss.auth.domain.entity.Capability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CapabilityRepository extends JpaRepository<Capability, Integer> {
    List<Capability> findByFeatureId(Integer featureId);
    Optional<Capability> findByCode(String code);
    boolean existsByCode(String code);

    @Query("SELECT c FROM Capability c JOIN FETCH c.feature f JOIN FETCH f.module")
    List<Capability> findAllWithFeatureAndModule();
}
