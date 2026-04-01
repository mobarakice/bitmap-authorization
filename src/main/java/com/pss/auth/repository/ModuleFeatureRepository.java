package com.pss.auth.repository;

import com.pss.auth.domain.entity.ModuleFeature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModuleFeatureRepository extends JpaRepository<ModuleFeature, Integer> {
    List<ModuleFeature> findByModuleIdOrderBySortOrder(Integer moduleId);
    Optional<ModuleFeature> findByCode(String code);
    boolean existsByCode(String code);
}
