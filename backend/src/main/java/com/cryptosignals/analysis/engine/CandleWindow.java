package com.cryptosignals.analysis.engine;

import com.cryptosignals.domain.Candle;

/**
 * Fixed-capacity ring buffer over the most recent candles, addressable by <em>absolute</em> index
 * within the analysed series.
 *
 * <p>The engine never needs more history than the largest lookback of any rule (ATR period, swing
 * bars, breakout confirmation), so retaining the full series is unnecessary. This keeps memory flat
 * regardless of whether we replay 1 000 or 5 000 000 candles, which is what section 13 of the
 * specification asks for.
 */
public final class CandleWindow {

    private final Candle[] buffer;
    private int count;
    private int nextIndex;

    public CandleWindow(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.buffer = new Candle[capacity];
        this.count = 0;
        this.nextIndex = 0;
    }

    /** Appends the next candle of the series; returns its absolute index. */
    public int append(Candle candle) {
        int index = nextIndex;
        buffer[Math.floorMod(index, buffer.length)] = candle;
        nextIndex = index + 1;
        if (count < buffer.length) {
            count++;
        }
        return index;
    }

    /** Absolute index of the most recently appended candle, or -1 if empty. */
    public int lastIndex() {
        return nextIndex - 1;
    }

    /** Absolute index of the oldest candle still retained, or -1 if empty. */
    public int firstRetainedIndex() {
        return count == 0 ? -1 : nextIndex - count;
    }

    public boolean has(int absoluteIndex) {
        return absoluteIndex >= firstRetainedIndex() && absoluteIndex <= lastIndex() && count > 0;
    }

    /**
     * @throws IndexOutOfBoundsException if the index was never appended or has already been evicted
     */
    public Candle at(int absoluteIndex) {
        if (!has(absoluteIndex)) {
            throw new IndexOutOfBoundsException("candle " + absoluteIndex
                    + " is outside the retained window [" + firstRetainedIndex() + ", " + lastIndex() + "]");
        }
        return buffer[Math.floorMod(absoluteIndex, buffer.length)];
    }

    public int capacity() {
        return buffer.length;
    }

    public int size() {
        return count;
    }
}
