package com.cryptosignals.persistence.repository;

import com.cryptosignals.persistence.entity.MarketEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MarketRepository extends JpaRepository<MarketEntity, Long> {

    Optional<MarketEntity> findByExchangeAndSymbolAndTimeframe(String exchange, String symbol, String timeframe);

    List<MarketEntity> findByEnabledTrue();
}
