package com.cryptosignals.api;

import com.cryptosignals.api.dto.Dtos.VolumeRuleDto;
import com.cryptosignals.api.dto.Dtos.WatchListDto;
import com.cryptosignals.api.dto.Dtos.WatchedSymbolDto;
import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.marketdata.WatchList;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@RestController
@RequestMapping("/api/watchlist")
@Tag(name = "Watch list", description = "Which coins are analysed, and why")
public class WatchListController {

    private final WatchList watchList;
    private final MarketProperties markets;

    public WatchListController(WatchList watchList, MarketProperties markets) {
        this.watchList = watchList;
        this.markets = markets;
    }

    @GetMapping
    @Operation(summary = "Watched symbols",
            description = "The fixed symbols from the configuration and, when volume selection is on, "
                    + "the most traded other coins with their average daily volume. Each symbol is "
                    + "analysed on every configured timeframe.")
    public WatchListDto current() {
        MarketProperties.VolumeSelection rules = markets.getVolumeSelection();
        List<WatchedSymbolDto> symbols = watchList.entries().stream()
                .map(e -> new WatchedSymbolDto(e.symbol(), e.fixed() ? "FIXED" : "VOLUME",
                        e.dailyVolume() == null ? null
                                : BigDecimal.valueOf(e.dailyVolume()).setScale(0, RoundingMode.HALF_UP),
                        e.selectedAt()))
                .toList();
        VolumeRuleDto rule = rules.isEnabled()
                ? new VolumeRuleDto(rules.getQuoteAsset(), BigDecimal.valueOf(rules.getMinDailyVolume()),
                        rules.getWindow().toString(), rules.getMaxSymbols(), rules.getKeepPercent(),
                        rules.getMinPriceRangePercent(), List.copyOf(rules.getExcludeTags()),
                        List.copyOf(rules.getExcludeAssets()), rules.getRefreshInterval().toString())
                : null;
        return new WatchListDto(rules.isEnabled() ? "VOLUME" : "FIXED", watchList.source().name(),
                symbols.size(), symbols, rule, watchList.refreshedAt(), watchList.nextAttemptAt(),
                watchList.lastError());
    }
}
