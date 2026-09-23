package com.cryptosignals.persistence.repository;

import com.cryptosignals.persistence.entity.LevelTouchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LevelTouchRepository extends JpaRepository<LevelTouchEntity, Long> {

    List<LevelTouchEntity> findByLevelIdOrderByTouchNumberAsc(UUID levelId);

    void deleteByLevelId(UUID levelId);
}
