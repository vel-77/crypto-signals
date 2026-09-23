package com.cryptosignals.api;

import com.cryptosignals.api.dto.Dtos;
import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.persistence.entity.PriceLevelEntity;
import com.cryptosignals.persistence.repository.BreakoutEventRepository;
import com.cryptosignals.persistence.repository.LevelTouchRepository;
import com.cryptosignals.persistence.repository.PriceLevelRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/levels")
@Tag(name = "Levels", description = "Automatically discovered support and resistance zones")
public class LevelController {

    private final PriceLevelRepository levels;
    private final LevelTouchRepository touches;
    private final BreakoutEventRepository breakouts;

    public LevelController(PriceLevelRepository levels, LevelTouchRepository touches,
                           BreakoutEventRepository breakouts) {
        this.levels = levels;
        this.touches = touches;
        this.breakouts = breakouts;
    }

    @GetMapping
    @Operation(summary = "List discovered levels")
    public Dtos.PageDto<Dtos.LevelDto> list(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String timeframe,
            @RequestParam(required = false) LevelType type,
            @Parameter(description = "ACTIVE, BROKEN or EXPIRED") @RequestParam(required = false) LevelStatus status,
            @Parameter(description = "only levels with at least this many touches") @RequestParam(required = false) Integer minTouches,
            @Parameter(description = "0..1 quality floor") @RequestParam(required = false) Double minStrength,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 50, sort = "firstSeenAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<PriceLevelEntity> page = levels.findAll(QuerySpecifications.levels(
                symbol, timeframe, type, status, from, to, minTouches, minStrength), pageable);
        return Dtos.PageDto.of(page.map(Dtos.LevelDto::from));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One level with its touches and breakout, if any")
    public ResponseEntity<Dtos.LevelDto> byId(@PathVariable UUID id) {
        return levels.findById(id)
                .map(entity -> ResponseEntity.ok(Dtos.LevelDto.from(entity,
                        touchesOf(id),
                        breakouts.findByLevelId(id).map(Dtos.BreakoutDto::from).orElse(null))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/touches")
    @Operation(summary = "Every recorded touch of a level, in order")
    public ResponseEntity<List<Dtos.TouchDto>> touchesFor(@PathVariable UUID id) {
        if (!levels.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(touchesOf(id));
    }

    @GetMapping("/{id}/breakout")
    @Operation(summary = "The breakout that invalidated a level, if it was broken")
    public ResponseEntity<Dtos.BreakoutDto> breakoutFor(@PathVariable UUID id) {
        return breakouts.findByLevelId(id)
                .map(entity -> ResponseEntity.ok(Dtos.BreakoutDto.from(entity)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private List<Dtos.TouchDto> touchesOf(UUID levelId) {
        return touches.findByLevelIdOrderByTouchNumberAsc(levelId).stream()
                .map(Dtos.TouchDto::from)
                .toList();
    }
}
