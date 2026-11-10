package com.cryptosignals.notifications;

import com.cryptosignals.analysis.model.Touch;
import com.cryptosignals.analysis.model.TradingSignal;
import com.cryptosignals.domain.PriceFormatter;
import org.springframework.stereotype.Component;

/**
 * Renders a signal as the alert text from specification section 15.
 *
 * <p>Deliberately plain text rather than Markdown or HTML: Telegram, Discord, email and a log line
 * all accept it unescaped, so no channel needs its own template, and an asset whose symbol contains
 * an underscore cannot accidentally turn half a message into italics.
 */
@Component
public class SignalFormatter {

    private final java.time.ZoneId zone;

    /** UTC, as used by tests and anything constructed outside Spring. */
    public SignalFormatter() {
        this(java.time.ZoneOffset.UTC);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SignalFormatter(com.cryptosignals.config.NotificationProperties properties) {
        this(java.time.ZoneId.of(properties.getTimezone()));
    }

    public SignalFormatter(java.time.ZoneId zone) {
        this.zone = zone;
    }

    public String format(TradingSignal signal) {
        StringBuilder out = new StringBuilder(512);

        out.append("🔥 NEW ").append(signal.touchCount()).append("-TOUCH SIGNAL\n\n");
        out.append("Symbol: ").append(signal.market().symbol()).append('\n');
        out.append("Timeframe: ").append(signal.market().timeframe().code()).append("\n\n");
        out.append("Type: ").append(signal.type()).append("\n\n");
        out.append("Level: ").append(PriceFormatter.price(signal.price())).append('\n');
        out.append("Zone: ").append(PriceFormatter.price(signal.zoneLower()))
                .append(" - ").append(PriceFormatter.price(signal.zoneUpper())).append("\n\n");

        out.append("Touches:\n");
        for (Touch touch : signal.touches()) {
            out.append(touch.touchNumber()).append(". ")
                    .append(PriceFormatter.timestamp(touch.extremeTime(), zone))
                    .append(" @ ").append(PriceFormatter.price(touch.extremePrice()))
                    .append('\n');
        }

        out.append("\nBreakout: NO\n");
        out.append("Strength: ").append(String.format(java.util.Locale.ROOT, "%.2f", signal.strength()))
                .append("\n\n");
        // The signal fires when the candle that completed it closes; its open time would read as
        // a full candle earlier (an hour, on 1h) than the alert actually went out.
        long generatedAt = signal.detectedAtTime() + signal.market().timeframe().durationMillis();
        out.append("Signal generated:\n")
                .append(PriceFormatter.timestamp(generatedAt, zone)).append(' ')
                .append(zoneLabel());

        if (signal.historical()) {
            out.append("\n\n(historical — found while replaying past candles)");
        }
        return out.toString();
    }

    private String zoneLabel() {
        return zone.normalized().equals(java.time.ZoneOffset.UTC) ? "UTC" : zone.getId();
    }
}
