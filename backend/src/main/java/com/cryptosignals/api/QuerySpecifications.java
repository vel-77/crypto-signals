package com.cryptosignals.api;

import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.SignalStatus;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.persistence.entity.PriceLevelEntity;
import com.cryptosignals.persistence.entity.SignalEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Composable filters for the query endpoints.
 *
 * <p>Every criterion is optional and every list criterion accepts several values. Values within one
 * filter are OR-ed ("BTCUSDT or SOLUSDT"); different filters are AND-ed ("… and on 15m"). Absent or
 * empty filters contribute no predicate, so one specification serves "everything" and
 * "superseded or invalidated 4- and 5-touch BTCUSDT and SOLUSDT resistance on 15m last week" alike.
 */
public final class QuerySpecifications {

    private QuerySpecifications() {
    }

    /**
     * Touch-count criterion shared by signals and levels.
     *
     * @param exact   exact counts to include, e.g. [3, 4]
     * @param atLeast also include anything with at least this many touches (the "8+" bucket)
     */
    public record TouchFilter(List<Integer> exact, Integer atLeast) {

        public static TouchFilter none() {
            return new TouchFilter(List.of(), null);
        }

        boolean isEmpty() {
            return (exact == null || exact.isEmpty()) && atLeast == null;
        }
    }

    public static Specification<SignalEntity> signals(List<String> symbols, List<String> timeframes,
                                                      List<LevelType> types, List<SignalStatus> statuses,
                                                      TouchFilter touches, Instant from, Instant to,
                                                      Boolean historical) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            in(predicates, root.get("symbol"), symbols(symbols));
            in(predicates, root.get("timeframe"), timeframes(timeframes));
            in(predicates, root.get("type"), types);
            in(predicates, root.get("status"), statuses);
            touches(predicates, builder, root, touches);
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

    public static Specification<PriceLevelEntity> levels(List<String> symbols, List<String> timeframes,
                                                         List<LevelType> types, List<LevelStatus> statuses,
                                                         TouchFilter touches, Instant from, Instant to,
                                                         Integer minTouches, Double minStrength) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            in(predicates, root.get("symbol"), symbols(symbols));
            in(predicates, root.get("timeframe"), timeframes(timeframes));
            in(predicates, root.get("type"), types);
            in(predicates, root.get("status"), statuses);
            touches(predicates, builder, root, touches);
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

    private static <T> void in(List<Predicate> predicates, Path<Object> field, Collection<T> values) {
        if (values != null && !values.isEmpty()) {
            predicates.add(field.in(values));
        }
    }

    private static void touches(List<Predicate> predicates, CriteriaBuilder builder, Root<?> root,
                                TouchFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return;
        }
        List<Predicate> any = new ArrayList<>();
        if (filter.exact() != null && !filter.exact().isEmpty()) {
            any.add(root.get("touchCount").in(filter.exact()));
        }
        if (filter.atLeast() != null) {
            any.add(builder.greaterThanOrEqualTo(root.get("touchCount"), filter.atLeast()));
        }
        predicates.add(builder.or(any.toArray(Predicate[]::new)));
    }

    private static List<String> symbols(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase()).distinct().toList();
    }

    /** Accepts "15m" or "M15" alike; an unknown code is a client error, reported as such. */
    private static List<String> timeframes(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().filter(s -> s != null && !s.isBlank())
                .map(s -> Timeframe.fromCode(s).code()).distinct().toList();
    }
}
