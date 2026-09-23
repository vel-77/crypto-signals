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
            description = "Every filter is optional and they combine.")
    public Dtos.PageDto<Dtos.SignalDto> list(
            @Parameter(description = "e.g. BTCUSDT") @RequestParam(required = false) String symbol,
            @Parameter(description = "e.g. 5m") @RequestParam(required = false) String timeframe,
            @Parameter(description = "SUPPORT or RESISTANCE") @RequestParam(required = false) LevelType type,
            @Parameter(description = "ACTIVE, INVALIDATED or EXPIRED") @RequestParam(required = false) SignalStatus status,
            @Parameter(description = "only signals found during historical replay") @RequestParam(required = false) Boolean historical,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 50, sort = "detectedAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<SignalEntity> page = signals.findAll(
                QuerySpecifications.signals(symbol, timeframe, type, status, from, to, historical),
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
