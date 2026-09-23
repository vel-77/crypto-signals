package com.cryptosignals.notifications;

import com.cryptosignals.analysis.model.TradingSignal;

/**
 * A delivery channel for signals — the abstraction required by specification section 15.
 *
 * <p>Telegram is the first implementation. Discord, email, a generic webhook or mobile push are
 * each a new class implementing this interface plus a configuration block; nothing else changes.
 *
 * <p>Implementations receive both the structured signal and a pre-rendered text body, so a chat
 * channel can post the text while a webhook serialises the signal itself.
 */
public interface Notifier {

    String name();

    /** False when the channel is switched off or not fully configured. */
    boolean isEnabled();

    /**
     * Attempts delivery.
     *
     * @return true only if the channel confirmed the message was accepted. Implementations must
     *         return false (or throw) rather than silently swallow a failure: the caller uses this
     *         to decide whether the signal stays queued for retry.
     */
    boolean send(TradingSignal signal, String formattedMessage);
}
