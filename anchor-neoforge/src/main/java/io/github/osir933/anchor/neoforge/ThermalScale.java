package io.github.osir933.anchor.neoforge;

/**
 * The scale of a thermal image: the temperatures it spans and the false colours that show them, from dark violet
 * for the coldest through red and orange to near white for the hottest. The colours get brighter all the way up,
 * so the picture reads in grey too.
 *
 * <p>Like a real thermal camera's automatic range, the span follows what is in view: it widens at once to take in
 * a new extreme and narrows a little each image, so the picture does not flicker. Locked, it keeps its span, so
 * that images taken over time can be compared.
 */
final class ThermalScale {

    /** The narrowest span, in kelvin, so that a view at one temperature does not turn noise into colour. */
    static final double MIN_SPAN_K = 2.0;

    /** How much of the way to a narrower span each image goes. */
    private static final double NARROWING = 0.25;

    /** Where the palette's colours sit, from 0 for the coldest to 1 for the hottest. */
    private static final double[] STOPS = {0.0, 0.15, 0.35, 0.55, 0.75, 0.9, 1.0};

    /** The palette's colours as 0xRRGGBB: violet, purple, magenta, red, orange, yellow, near white. */
    private static final int[] COLORS = {0x14003C, 0x3C008C, 0xA0008C, 0xE6321E, 0xFF9600, 0xFFE63C, 0xFFFFE6};

    private double low = Double.NaN;
    private double high = Double.NaN;
    private boolean locked;

    /**
     * Returns the colour at a place on any scale.
     *
     * @param position from 0 for the coldest to 1 for the hottest; anything outside is taken as the nearest end
     * @return the colour as 0xRRGGBB
     */
    static int color(double position) {
        double t = position > 0.0 ? Math.min(position, 1.0) : 0.0;
        int i = 1;
        while (i < STOPS.length - 1 && t > STOPS[i]) {
            i++;
        }
        double f = (t - STOPS[i - 1]) / (STOPS[i] - STOPS[i - 1]);
        return channel(COLORS[i - 1] >> 16, COLORS[i] >> 16, f) << 16
                | channel(COLORS[i - 1] >> 8, COLORS[i] >> 8, f) << 8
                | channel(COLORS[i - 1], COLORS[i], f);
    }

    private static int channel(int from, int to, double f) {
        int a = from & 0xFF;
        int b = to & 0xFF;
        return (int) Math.round(a + (b - a) * f);
    }

    /**
     * Fits the scale to the temperatures in a new image, unless it is locked.
     *
     * @param coldestK the coldest temperature in the image, in kelvin
     * @param hottestK the hottest temperature in the image, in kelvin; images with nothing in them, given as
     *     {@link Double#NaN}, leave the scale as it is
     */
    void follow(double coldestK, double hottestK) {
        if (locked || !(coldestK <= hottestK)) {
            return;
        }
        double middle = (coldestK + hottestK) / 2.0;
        double half = Math.max(hottestK - coldestK, MIN_SPAN_K) / 2.0;
        double targetLow = middle - half;
        double targetHigh = middle + half;
        if (!ready()) {
            low = targetLow;
            high = targetHigh;
            return;
        }
        low = targetLow < low ? targetLow : low + (targetLow - low) * NARROWING;
        high = targetHigh > high ? targetHigh : high + (targetHigh - high) * NARROWING;
    }

    /** Forgets the span, so that the next image sets it afresh; a locked scale keeps it. */
    void reset() {
        if (!locked) {
            low = Double.NaN;
            high = Double.NaN;
        }
    }

    /**
     * Returns whether the scale has a span yet.
     *
     * @return {@code true} once an image with something in it has been followed
     */
    boolean ready() {
        return !Double.isNaN(low);
    }

    /**
     * Returns the coldest temperature on the scale.
     *
     * @return the temperature in kelvin, or {@link Double#NaN} before the scale is ready
     */
    double low() {
        return low;
    }

    /**
     * Returns the hottest temperature on the scale.
     *
     * @return the temperature in kelvin, or {@link Double#NaN} before the scale is ready
     */
    double high() {
        return high;
    }

    /**
     * Returns the colour that shows a temperature.
     *
     * @param kelvin the temperature in kelvin
     * @return the colour as 0xRRGGBB; temperatures off the scale get the colour of its nearest end
     */
    int colorOf(double kelvin) {
        return color((kelvin - low) / (high - low));
    }

    /**
     * Returns whether the scale is locked.
     *
     * @return {@code true} if it keeps its span whatever is in view
     */
    boolean locked() {
        return locked;
    }

    /**
     * Locks or frees the scale. A scale that is not ready yet stays free, as there is no span to keep.
     *
     * @param lock {@code true} to keep the span as it is, {@code false} to follow the view again
     */
    void lock(boolean lock) {
        locked = lock && ready();
    }
}
