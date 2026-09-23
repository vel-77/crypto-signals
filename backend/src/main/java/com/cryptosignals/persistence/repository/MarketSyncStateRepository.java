package com.cryptosignals.persistence.repository;

import com.cryptosignals.persistence.entity.MarketSyncStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketSyncStateRepository extends JpaRepository<MarketSyncStateEntity, Long> {
}
