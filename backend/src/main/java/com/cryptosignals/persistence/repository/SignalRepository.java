package com.cryptosignals.persistence.repository;

import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.SignalStatus;
import com.cryptosignals.persistence.entity.SignalEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SignalRepository
        extends JpaRepository<SignalEntity, UUID>, JpaSpecificationExecutor<SignalEntity> {

    /** Signals persisted but not yet delivered — the retry queue after a notifier outage. */
    List<SignalEntity> findByNotifiedFalseOrderByDetectedAtAsc();

    boolean existsByLevelIdAndTouchCount(UUID levelId, int touchCount);

    /** Gives every still-ACTIVE signal of a market's levels in {@code levelStatus} a new status. */
    @Modifying
    @Query("update SignalEntity s set s.status = :to, s.updatedAt = :now "
            + "where s.marketId = :marketId "
            + "and s.status = com.cryptosignals.domain.SignalStatus.ACTIVE "
            + "and s.levelId in (select l.id from PriceLevelEntity l "
            + "                  where l.marketId = :marketId and l.status = :levelStatus)")
    int retireActiveSignalsOfLevels(@Param("marketId") Long marketId,
                                    @Param("levelStatus") LevelStatus levelStatus,
                                    @Param("to") SignalStatus to, @Param("now") Instant now);

    /**
     * Signals of a market whose next potential low / high has not been worked out under the given
     * rule: never worked out, or worked out under different settings. Oldest first.
     */
    @Query("select s from SignalEntity s where s.marketId = :marketId "
            + "and (s.nextPotentialRule is null or s.nextPotentialRule <> :rule) "
            + "order by s.detectedAt asc, s.id asc")
    List<SignalEntity> findNextPotentialPending(@Param("marketId") Long marketId,
                                                @Param("rule") String rule, Pageable page);

    /**
     * Stores a signal's next potential low / high. Touches only those columns, so it can never
     * undo a status change made concurrently by the engine or by reconciliation.
     */
    @Modifying
    @Transactional
    @Query("update SignalEntity s set s.nextPotentialPrice = :price, s.nextPotentialTime = :time, "
            + "s.nextPotentialRule = :rule where s.id = :id")
    int storeNextPotential(@Param("id") UUID id, @Param("price") BigDecimal price,
                           @Param("time") Instant time, @Param("rule") String rule);
}
