package com.cryptosignals.api;

import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.SignalStatus;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.persistence.entity.PriceLevelEntity;
import com.cryptosignals.persistence.entity.SignalEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Composable filters for the query endpoints.
 *
 * <p>Every criterion is optional and absent ones contribute no predicate, so the same specification
 * serves "all signals" and "invalidated BTCUSDT 5m resistance signals from last week" without any
 * branching in the controllers.
 */
public final class QuerySpecifications {

    private QuerySpecifications() {
    }

    public static Specification<SignalEntity> signals(String symbol, String timeframe,
                                                      LevelType type, SignalStatus status,
                                                      Instant from, Instant to, Boolean historical) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (symbol != null && !symbol.isBlank()) {
                predicates.add(builder.equal(root.get("symbol"), symbol.trim().toUpperCase()));
            }
            if (timeframe != null && !timeframe.isBlank()) {
                predicates.add(builder.equal(root.get("timeframe"),
                        Timeframe.fromCode(timeframe).code()));
            }
            if (type != null) {
                predicates.add(builder.equal(root.get("type"), type));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (historical != null) {
                predicates.add(builder.equal(root.get("historical"), historical));
            }
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("detectedAt"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("detectedAt"), to));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<PriceLevelEntity> levels(String symbol, String timeframe,
                                                         LevelType type, LevelStatus status,
                                                         Instant from, Instant to,
                                                         Integer minTouches, Double minStrength) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (symbol != null && !symbol.isBlank()) {
                predicates.add(builder.equal(root.get("symbol"), symbol.trim().toUpperCase()));
            }
            if (timeframe != null && !timeframe.isBlank()) {
                predicates.add(builder.equal(root.get("timeframe"),
                        Timeframe.fromCode(timeframe).code()));
            }
            if (type != null) {
                predicates.add(builder.equal(root.get("type"), type));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (minTouches != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("touchCount"), minTouches));
            }
            if (minStrength != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("strength"), minStrength));
            }
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("firstSeenAt"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("firstSeenAt"), to));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
