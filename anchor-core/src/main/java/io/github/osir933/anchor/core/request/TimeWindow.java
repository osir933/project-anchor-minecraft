package io.github.osir933.anchor.core.request;

/**
 * The span of ticks a request covers: the WHEN of a request.
 *
 * @param fromTick the first tick, inclusive
 * @param untilTick the tick at which the window closes, exclusive; {@link Long#MAX_VALUE} for no end
 */
public record TimeWindow(long fromTick, long untilTick) {

    /**
     * Validates the window.
     *
     * @param fromTick the first tick
     * @param untilTick the closing tick
     */
    public TimeWindow {
        if (untilTick < fromTick) {
            throw new IllegalArgumentException("a time window cannot close before it opens");
        }
    }

    /**
     * Returns a window that opens at a tick and never closes.
     *
     * @param tick the first tick
     * @return the window
     */
    public static TimeWindow from(long tick) {
        return new TimeWindow(tick, Long.MAX_VALUE);
    }

    /**
     * Returns a window of a fixed length.
     *
     * @param tick the first tick
     * @param ticks how many ticks it lasts
     * @return the window
     */
    public static TimeWindow during(long tick, long ticks) {
        return new TimeWindow(tick, Math.addExact(tick, ticks));
    }

    /**
     * Tells whether a tick falls inside the window.
     *
     * @param tick the tick
     * @return {@code true} if the window is open at that tick
     */
    public boolean contains(long tick) {
        return tick >= fromTick && tick < untilTick;
    }
}
