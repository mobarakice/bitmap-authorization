package com.pss.auth.repository;

import com.pss.auth.domain.entity.Module;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModuleRepository extends JpaRepository<Module, Integer> {
    List<Module> findByParentIsNullOrderBySortOrder();
    List<Module> findByParentIdOrderBySortOrder(Integer parentId);
    Optional<Module> findByCode(String code);
    boolean existsByCode(String code);
}
