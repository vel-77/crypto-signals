package com.cryptosignals.persistence.repository;

import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.persistence.entity.PriceLevelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface PriceLevelRepository
        extends JpaRepository<PriceLevelEntity, UUID>, JpaSpecificationExecutor<PriceLevelEntity> {

    List<PriceLevelEntity> findByMarketIdAndStatus(Long marketId, LevelStatus status);

    long countByMarketId(Long marketId);
}
