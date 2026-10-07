package com.cryptosignals.api;

import com.cryptosignals.api.dto.Dtos;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.SignalStatus;
import com.cryptosignals.persistence.entity.SignalEntity;
import com.cryptosignals.persistence.repository.LevelTouchRepository;
import com.cryptosignals.persistence.repository.SignalRepository;
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
@RequestMapping("/api/signals")
@Tag(name = "Signals", description = "Generated 3-touch alerts")
public class SignalController {

    private final SignalRepository signals;
    private final LevelTouchRepository touches;

    public SignalController(SignalRepository signals, LevelTouchRepository touches) {
        this.signals = signals;
        this.touches = touches;
    }

    @GetMapping
    @Operation(summary = "List signals, newest first",
            description = "Every filter is optional. List filters take several comma-separated values, "
                    + "which are OR-ed; different filters are AND-ed.")
    public Dtos.PageDto<Dtos.SignalDto> list(
            @Parameter(description = "one or more, e.g. BTCUSDT,SOLUSDT") @RequestParam(required = false) List<String> symbol,
            @Parameter(description = "one or more, e.g. 15m,1h") @RequestParam(required = false) List<String> timeframe,
            @Parameter(description = "SUPPORT and/or RESISTANCE") @RequestParam(required = false) List<LevelType> type,
            @Parameter(description = "one or more of ACTIVE, SUPERSEDED, INVALIDATED, EXPIRED") @RequestParam(required = false) List<SignalStatus> status,
            @Parameter(description = "exact touch counts, e.g. 3,4") @RequestParam(required = false) List<Integer> touches,
            @Parameter(description = "also include signals with at least this many touches") @RequestParam(required = false) Integer touchesAtLeast,
            @Parameter(description = "only signals found during historical replay") @RequestParam(required = false) Boolean historical,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 50, sort = "detectedAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<SignalEntity> page = signals.findAll(
                QuerySpecifications.signals(symbol, timeframe, type, status,
                        new QuerySpecifications.TouchFilter(touches, touchesAtLeast), from, to, historical),
                pageable);
        // Touches are omitted from list responses: a page of 50 signals would otherwise issue 50
        // extra queries to render detail nobody reads in a list.
        return Dtos.PageDto.of(page.map(entity -> Dtos.SignalDto.from(entity, null)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One signal, including every touch that produced it")
    public ResponseEntity<Dtos.SignalDto> byId(@PathVariable UUID id) {
        return signals.findById(id)
                .map(entity -> {
                    List<Dtos.TouchDto> touchDtos =
                            touches.findByLevelIdOrderByTouchNumberAsc(entity.getLevelId()).stream()
                                    .filter(t -> t.getTouchNumber() <= entity.getTouchCount())
                                    .map(Dtos.TouchDto::from)
                                    .toList();
                    return ResponseEntity.ok(Dtos.SignalDto.from(entity, touchDtos));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
