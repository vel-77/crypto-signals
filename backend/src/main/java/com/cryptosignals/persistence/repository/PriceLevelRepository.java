package com.cryptosignals.persistence.repository;

import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.persistence.entity.PriceLevelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PriceLevelRepository
        extends JpaRepository<PriceLevelEntity, UUID>, JpaSpecificationExecutor<PriceLevelEntity> {

    List<PriceLevelEntity> findByMarketIdAndStatus(Long marketId, LevelStatus status);

    long countByMarketId(Long marketId);

    @Query("select l.id from PriceLevelEntity l where l.marketId = :marketId and l.status = :status")
    List<UUID> findIdsByMarketIdAndStatus(@Param("marketId") Long marketId,
                                          @Param("status") LevelStatus status);

    /**
     * Moves levels from one status to another, but only rows still in {@code from} when the update
     * runs — a status written concurrently by the live path is never overwritten.
     */
    @Modifying
    @Query("update PriceLevelEntity l set l.status = :to, l.updatedAt = :now "
            + "where l.id in :ids and l.status = :from")
    int changeStatusIfStill(@Param("ids") Collection<UUID> ids, @Param("from") LevelStatus from,
                            @Param("to") LevelStatus to, @Param("now") Instant now);
}
