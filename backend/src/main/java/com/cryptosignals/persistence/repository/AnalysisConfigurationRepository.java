package com.cryptosignals.persistence.repository;

import com.cryptosignals.persistence.entity.AnalysisConfigurationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisConfigurationRepository extends JpaRepository<AnalysisConfigurationEntity, Long> {

    Optional<AnalysisConfigurationEntity> findByName(String name);

    Optional<AnalysisConfigurationEntity> findFirstByActiveTrueOrderByIdDesc();
}
