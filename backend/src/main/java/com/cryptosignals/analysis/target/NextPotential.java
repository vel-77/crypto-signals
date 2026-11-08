package com.cryptosignals.analysis.target;

/**
 * Where price turned last time it was beyond a signal's level: the nearest earlier swing low below
 * a support signal, or swing high above a resistance signal, that is at least a minimum distance
 * away. If the level gives way, that is the next price area that stopped a move before.
 *
 * @param price     the swing's extreme: the low of a swing low, the high of a swing high
 * @param swingTime open time of the candle that printed it, epoch millis
 * @param percent   signed distance from the signal price, in percent: negative for a low below a
 *                  support signal, positive for a high above a resistance signal
 */
public record NextPotential(double price, long swingTime, double percent) {
}
