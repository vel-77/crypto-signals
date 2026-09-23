package com.cryptosignals.persistence.repository;

import com.cryptosignals.persistence.entity.BreakoutEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BreakoutEventRepository extends JpaRepository<BreakoutEventEntity, Long> {

    Optional<BreakoutEventEntity> findByLevelId(UUID levelId);

    List<BreakoutEventEntity> findByLevelIdIn(List<UUID> levelIds);
}
