package com.cryptosignals.exchange;

/** A live subscription. Closing it stops the stream and its reconnect loop. */
public interface StreamSubscription extends AutoCloseable {

    boolean isConnected();

    /** How many times the underlying transport has reconnected since the subscription opened. */
    long reconnectCount();

    @Override
    void close();
}
