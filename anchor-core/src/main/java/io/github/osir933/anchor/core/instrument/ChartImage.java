package io.github.osir933.anchor.core.instrument;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

/**
 * A chart of recordings, drawn as a small square picture the size of a Minecraft map, in a handful of inks that the
 * host turns into its own colours.
 *
 * <p>Time runs left to right and ends at the latest reading, marked {@code NOW}; earlier times are marked by how long
 * ago they were. Each recording is a line through the mean of each of its buckets, over a lighter band from the
 * lowest to the highest reading, so a peak too brief to move the mean still shows. A legend at the top names each
 * recording with its latest reading, and the unit of the readings stands below the value axis.
 */
public final class ChartImage {

    /** The picture's width and height, in pixels. */
    public static final int SIZE = 128;

    /** The most recordings a chart shows. */
    public static final int MAX_TRACES = 4;

    /** The inks a chart is drawn in. */
    public enum Ink {
        /** The background. */
        PAPER,
        /** Grid lines. */
        GRID,
        /** The axes. */
        AXIS,
        /** Writing. */
        TEXT,
        /** The first recording's line. */
        LINE_1,
        /** The second recording's line. */
        LINE_2,
        /** The third recording's line. */
        LINE_3,
        /** The fourth recording's line. */
        LINE_4,
        /** The band of the first recording's lowest and highest readings. */
        BAND_1,
        /** The second recording's band. */
        BAND_2,
        /** The third recording's band. */
        BAND_3,
        /** The fourth recording's band. */
        BAND_4;

        private static final Ink[] VALUES = values();

        /**
         * Returns the ink of a recording's line.
         *
         * @param trace the recording's place on the chart, from 0
         * @return the ink
         */
        public static Ink line(int trace) {
            return VALUES[LINE_1.ordinal() + Objects.checkIndex(trace, MAX_TRACES)];
        }

        /**
         * Returns the ink of a recording's band.
         *
         * @param trace the recording's place on the chart, from 0
         * @return the ink
         */
        public static Ink band(int trace) {
            return VALUES[BAND_1.ordinal() + Objects.checkIndex(trace, MAX_TRACES)];
        }

        /**
         * Returns the ink with an ordinal.
         *
         * @param ordinal the ordinal
         * @return the ink
         */
        public static Ink byOrdinal(int ordinal) {
            return VALUES[ordinal];
        }
    }

    /**
     * One recording on a chart.
     *
     * @param label its name in the legend
     * @param series the recording
     */
    public record Trace(String label, TimeSeries series) {

        /**
         * Checks the parts.
         *
         * @param label the label
         * @param series the recording
         */
        public Trace {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(series, "series");
        }
    }

    /** How long apart time marks may be, in seconds: round numbers of seconds, minutes, hours and days. */
    private static final long[] TIME_STEPS = {1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10_800,
        21_600, 43_200, 86_400, 172_800, 432_000, 864_000, 1_728_000, 4_320_000, 8_640_000};

    private static final int LEGEND_TOP = 2;
    private static final int LEGEND_ROW = 7;
    private static final int SWATCH = 3;

    private final byte[] pixels = new byte[SIZE * SIZE];

    private ChartImage() {
    }

    /**
     * Returns the ink of a pixel.
     *
     * @param x its column, from 0 at the left
     * @param y its row, from 0 at the top
     * @return the ink
     */
    public Ink at(int x, int y) {
        return Ink.byOrdinal(pixels[index(x, y)]);
    }

    /**
     * Returns the inks of all pixels, row by row from the top, each as its {@linkplain Ink#ordinal() ordinal}.
     *
     * @return a copy of the pixels
     */
    public byte[] pixels() {
        return pixels.clone();
    }

    /**
     * Returns the picture as text, a line per row, for tests and logs: {@code ' '} for paper, {@code '.'} for the
     * grid, {@code '+'} for the axes, {@code '#'} for writing, {@code 1} to {@code 4} for lines and {@code a} to
     * {@code d} for bands.
     *
     * @return the text
     */
    public String ascii() {
        String key = " .+#1234abcd";
        StringBuilder out = new StringBuilder(SIZE * (SIZE + 1));
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                out.append(key.charAt(pixels[index(x, y)]));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Draws a chart.
     *
     * @param traces the recordings, as many as {@link #MAX_TRACES}; any more are left out
     * @param toUnit turns a recorded value into the unit the chart shows; it must keep larger values larger
     * @param unit the unit of the values, such as {@code °C}, written below the value axis
     * @return the picture
     */
    public static ChartImage draw(List<Trace> traces, DoubleUnaryOperator toUnit, String unit) {
        ChartImage image = new ChartImage();
        List<Trace> shown = traces.subList(0, Math.min(MAX_TRACES, traces.size()));
        int rows = shown.size() <= 2 ? 1 : 2;
        image.legend(shown, toUnit);
        int plotTop = LEGEND_TOP + rows * LEGEND_ROW + 4;
        int plotBottom = SIZE - 11;
        double t0 = Double.NaN;
        double t1 = Double.NaN;
        double lo = Double.NaN;
        double hi = Double.NaN;
        for (Trace t : shown) {
            TimeSeries s = t.series();
            if (s.isEmpty()) {
                continue;
            }
            t0 = Double.isNaN(t0) ? s.firstTime() : Math.min(t0, s.firstTime());
            t1 = Double.isNaN(t1) ? s.lastTime() : Math.max(t1, s.lastTime());
            double min = s.minimum();
            if (!Double.isNaN(min)) {
                double a = toUnit.applyAsDouble(min);
                double b = toUnit.applyAsDouble(s.maximum());
                lo = Double.isNaN(lo) ? a : Math.min(lo, a);
                hi = Double.isNaN(hi) ? b : Math.max(hi, b);
            }
        }
        if (Double.isNaN(lo)) {
            String message = shown.isEmpty() ? "NO PROBES" : "NO READINGS YET";
            image.text(message, (SIZE - PixelFont.width(message)) / 2, (plotTop + plotBottom - PixelFont.HEIGHT) / 2,
                    Ink.TEXT);
            return image;
        }
        if (!(t1 - t0 >= 1.0)) {
            t0 = t1 - 1.0;
        }
        Axis axis = Axis.of(lo, hi);
        List<String> labels = new ArrayList<>();
        int labelWidth = 0;
        for (int k = 0; k < axis.ticks(); k++) {
            String label = axis.label(k);
            labels.add(label);
            labelWidth = Math.max(labelWidth, PixelFont.width(label));
        }
        Frame f = new Frame(2 + labelWidth + 3, SIZE - 3, plotTop, plotBottom, t0, t1, axis.first(), axis.last());
        for (int k = 0; k < axis.ticks(); k++) {
            int y = f.y(axis.value(k));
            for (int x = f.left(); x <= f.right(); x += 2) {
                image.set(x, y, Ink.GRID);
            }
            String label = labels.get(k);
            image.text(label, f.left() - 3 - PixelFont.width(label), y - PixelFont.HEIGHT / 2, Ink.TEXT);
        }
        int labelY = f.bottom() + 4;
        image.text(unit, 2, labelY, Ink.TEXT);
        image.timeAxis(f, labelY, 2 + PixelFont.width(unit) + 3);
        for (int x = f.left() - 1; x <= f.right(); x++) {
            image.set(x, f.bottom() + 1, Ink.AXIS);
        }
        for (int y = f.top(); y <= f.bottom() + 1; y++) {
            image.set(f.left() - 1, y, Ink.AXIS);
        }
        for (int i = 0; i < shown.size(); i++) {
            image.band(f, shown.get(i).series(), toUnit, Ink.band(i));
        }
        for (int i = 0; i < shown.size(); i++) {
            image.line(f, shown.get(i).series(), toUnit, Ink.line(i));
        }
        return image;
    }

    /** Writes the legend: a swatch, the name and the latest reading of each recording, two to a row. */
    private void legend(List<Trace> shown, DoubleUnaryOperator toUnit) {
        int columns = shown.size() == 1 ? 1 : 2;
        int columnWidth = (SIZE - 4) / columns;
        for (int i = 0; i < shown.size(); i++) {
            int x = 2 + (i % columns) * columnWidth;
            int y = LEGEND_TOP + (i / columns) * LEGEND_ROW;
            for (int dx = 0; dx < SWATCH; dx++) {
                for (int dy = 1; dy <= SWATCH; dy++) {
                    set(x + dx, y + dy, Ink.line(i));
                }
            }
            double last = shown.get(i).series().last();
            String value = Double.isNaN(last) ? "--" : String.format(Locale.ROOT, "%.1f", toUnit.applyAsDouble(last));
            int chars = (columnWidth - SWATCH - 3) / PixelFont.ADVANCE;
            int room = chars - value.length() - 1;
            String name = shown.get(i).label();
            String text = room < 1 ? value : (name.length() > room ? name.substring(0, room) : name) + " " + value;
            text(text, x + SWATCH + 2, y, Ink.TEXT);
        }
    }

    /**
     * Draws the time marks: dotted grid lines at round times before the latest reading, labelled where they fit, all in
     * one unit, and none starting left of {@code minStart}.
     */
    private void timeAxis(Frame f, int labelY, int minStart) {
        double span = f.t1() - f.t0();
        long step = TIME_STEPS[TIME_STEPS.length - 1];
        for (long candidate : TIME_STEPS) {
            if (candidate * 4 >= span) {
                step = candidate;
                break;
            }
        }
        if (step * 4 < span) {
            step *= (long) Math.ceil(span / (4.0 * step));
        }
        int free = SIZE + 1;
        for (long k = 0; f.t1() - k * step >= f.t0(); k++) {
            int x = f.x(f.t1() - k * step);
            for (int y = f.top(); y <= f.bottom(); y += 2) {
                set(x, y, Ink.GRID);
            }
            String label = k == 0 ? "NOW" : "-" + timeLabel(k * step, step);
            int width = PixelFont.width(label);
            int start = k == 0 ? Math.min(x, f.right()) - width + 1 : x - width / 2;
            if (start >= minStart && start + width + 2 <= free) {
                text(label, start, labelY, Ink.TEXT);
                free = start;
            }
        }
    }

    /** Draws a recording's band: each bucket's lowest to highest reading, across the bucket's time. */
    private void band(Frame f, TimeSeries s, DoubleUnaryOperator toUnit, Ink ink) {
        for (int i = 0; i < s.size(); i++) {
            TimeSeries.Bucket b = s.bucket(i);
            if (b.gap()) {
                continue;
            }
            int top = f.y(toUnit.applyAsDouble(b.maximum()));
            int bottom = f.y(toUnit.applyAsDouble(b.minimum()));
            for (int x = f.x(b.startS()); x <= f.x(b.endS()); x++) {
                for (int y = top; y <= bottom; y++) {
                    set(x, y, ink);
                }
            }
        }
    }

    /** Draws a recording's line through the means of its buckets, broken at gaps; a lone reading is a small cross. */
    private void line(Frame f, TimeSeries s, DoubleUnaryOperator toUnit, Ink ink) {
        int px = 0;
        int py = 0;
        int run = 0;
        for (int i = 0; i <= s.size(); i++) {
            TimeSeries.Bucket b = i < s.size() ? s.bucket(i) : null;
            if (b == null || b.gap()) {
                if (run == 1) {
                    set(px - 1, py, ink);
                    set(px + 1, py, ink);
                    set(px, py - 1, ink);
                    set(px, py + 1, ink);
                }
                run = 0;
                continue;
            }
            int x = f.x(b.middleS());
            int y = f.y(toUnit.applyAsDouble(b.mean()));
            if (run == 0) {
                set(x, y, ink);
            } else {
                segment(px, py, x, y, ink);
            }
            px = x;
            py = y;
            run++;
        }
    }

    /** Draws a straight line between two pixels, both included. */
    private void segment(int x0, int y0, int x1, int y1, Ink ink) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        while (true) {
            set(x, y, ink);
            if (x == x1 && y == y1) {
                return;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private void text(String text, int x, int y, Ink ink) {
        PixelFont.write((px, py) -> set(px, py, ink), text, x, y);
    }

    private void set(int x, int y, Ink ink) {
        if (x >= 0 && x < SIZE && y >= 0 && y < SIZE) {
            pixels[index(x, y)] = (byte) ink.ordinal();
        }
    }

    private static int index(int x, int y) {
        return Objects.checkIndex(y, SIZE) * SIZE + Objects.checkIndex(x, SIZE);
    }

    /**
     * Writes a time on an axis marked every {@code step} seconds, in the largest unit that divides the step, so that
     * all marks on the axis share a unit.
     *
     * @param seconds the time, a whole number of steps
     * @param step the time between marks
     * @return for example {@code 45S}, {@code 90M}, {@code 36H} or {@code 2D}
     */
    static String timeLabel(long seconds, long step) {
        if (step % 86_400 == 0) {
            return seconds / 86_400 + "D";
        }
        if (step % 3600 == 0) {
            return seconds / 3600 + "H";
        }
        if (step % 60 == 0) {
            return seconds / 60 + "M";
        }
        return seconds + "S";
    }

    /** Where the plot lies in the picture, and the times and values at its edges. */
    private record Frame(int left, int right, int top, int bottom, double t0, double t1, double lo, double hi) {

        int x(double t) {
            return left + (int) Math.round((t - t0) / (t1 - t0) * (right - left));
        }

        int y(double v) {
            int y = bottom - (int) Math.round((v - lo) / (hi - lo) * (bottom - top));
            return Math.max(top, Math.min(bottom, y));
        }
    }

    /**
     * A value axis with round marks: the marks run from {@code first} to {@code last}, {@code step} apart, and the
     * axis spans exactly that.
     */
    record Axis(double first, double last, double step, int decimals) {

        /**
         * Chooses marks for values from {@code lo} to {@code hi}: four or so steps of 1, 2 or 5 times a power of ten,
         * from the mark at or below {@code lo} to the one at or above {@code hi}. Values all alike get an axis a unit
         * wide around them.
         */
        static Axis of(double lo, double hi) {
            if (!(hi - lo > 1e-9 * Math.max(1.0, Math.abs(hi)))) {
                lo -= 0.5;
                hi += 0.5;
            }
            double rough = (hi - lo) / 4.0;
            double magnitude = StrictMath.pow(10.0, StrictMath.floor(StrictMath.log10(rough)));
            double norm = rough / magnitude;
            double step = (norm <= 1.0 ? 1.0 : norm <= 2.0 ? 2.0 : norm <= 5.0 ? 5.0 : 10.0) * magnitude;
            double first = StrictMath.floor(lo / step) * step;
            double last = StrictMath.ceil(hi / step) * step;
            if (!(last > first)) {
                last = first + step;
            }
            int decimals = (int) Math.max(0, Math.min(4, -StrictMath.floor(StrictMath.log10(step) + 1e-9)));
            return new Axis(first, last, step, decimals);
        }

        int ticks() {
            return (int) Math.round((last - first) / step) + 1;
        }

        double value(int k) {
            return first + k * step;
        }

        String label(int k) {
            double v = value(k);
            if (Math.abs(v) < step * 1e-6) {
                v = 0.0;
            }
            return String.format(Locale.ROOT, "%." + decimals + "f", v);
        }
    }
}
