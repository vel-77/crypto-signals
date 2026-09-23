package com.cryptosignals.notifications;

import com.cryptosignals.analysis.model.TradingSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Always-on channel that writes the alert to the application log.
 *
 * <p>Keeps the system useful and observable before any external channel is configured, and means a
 * signal is never produced without leaving a trace somewhere.
 */
@Component
@Order(0)
public class LoggingNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);

    @Override
    public String name() {
        return "log";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean send(TradingSignal signal, String formattedMessage) {
        log.info("Signal {}:\n{}", signal.id(), formattedMessage);
        return true;
    }
}
