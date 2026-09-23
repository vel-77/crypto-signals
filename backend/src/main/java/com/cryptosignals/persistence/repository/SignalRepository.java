package com.cryptosignals.persistence.repository;

import com.cryptosignals.persistence.entity.SignalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface SignalRepository
        extends JpaRepository<SignalEntity, UUID>, JpaSpecificationExecutor<SignalEntity> {

    /** Signals persisted but not yet delivered — the retry queue after a notifier outage. */
    List<SignalEntity> findByNotifiedFalseOrderByDetectedAtAsc();

    boolean existsByLevelIdAndTouchCount(UUID levelId, int touchCount);
}
