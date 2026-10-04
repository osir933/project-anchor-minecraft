package io.github.osir933.anchor.core.instrument;

/**
 * A recording drawn as one line of text: a row of bars, each as high as the mean of its part of the recording, from
 * the lowest mean in the row to the highest.
 */
public final class Sparkline {

    /** The bars, from lowest to highest: the block elements from one eighth to full height. */
    public static final String BARS = "▁▂▃▄▅▆▇█";

    /** The level of a bar for a part of the recording without readings. */
    public static final int GAP = -1;

    private Sparkline() {
    }

    /**
     * Returns the heights of the bars.
     *
     * @param series the recording
     * @param width the most bars; a recording of fewer buckets gets one bar per bucket
     * @return for each bar, oldest first, a level from 0 to 7, or {@link #GAP}
     */
    public static int[] levels(TimeSeries series, int width) {
        if (width < 1) {
            throw new IllegalArgumentException("width must be at least 1, not " + width);
        }
        int columns = Math.min(width, series.size());
        double[] means = new double[columns];
        double lo = Double.NaN;
        double hi = Double.NaN;
        for (int c = 0; c < columns; c++) {
            int from = (int) ((long) c * series.size() / columns);
            int to = (int) ((long) (c + 1) * series.size() / columns);
            double sum = 0.0;
            long count = 0;
            for (int i = from; i < to; i++) {
                TimeSeries.Bucket b = series.bucket(i);
                if (!b.gap()) {
                    sum += b.mean() * b.readings();
                    count += b.readings();
                }
            }
            means[c] = count == 0 ? Double.NaN : sum / count;
            if (count > 0) {
                lo = Double.isNaN(lo) ? means[c] : Math.min(lo, means[c]);
                hi = Double.isNaN(hi) ? means[c] : Math.max(hi, means[c]);
            }
        }
        int[] levels = new int[columns];
        for (int c = 0; c < columns; c++) {
            if (Double.isNaN(means[c])) {
                levels[c] = GAP;
            } else if (!(hi > lo)) {
                levels[c] = 3;
            } else {
                levels[c] = (int) Math.round((means[c] - lo) / (hi - lo) * (BARS.length() - 1));
            }
        }
        return levels;
    }

    /**
     * Draws a recording as text.
     *
     * @param series the recording
     * @param width the most bars
     * @param gap the character for a part without readings
     * @return the bars
     */
    public static String text(TimeSeries series, int width, char gap) {
        StringBuilder out = new StringBuilder();
        for (int level : levels(series, width)) {
            out.append(level == GAP ? gap : BARS.charAt(level));
        }
        return out.toString();
    }
}
