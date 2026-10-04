package io.github.osir933.anchor.core.instrument;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.instrument.ChartImage.Ink;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

class ChartImageTest {

    private static final DoubleUnaryOperator CELSIUS = k -> k - 273.15;

    @Test
    void aChartWithoutRecordingsSaysSo() {
        ChartImage none = ChartImage.draw(List.of(), CELSIUS, "°C");
        assertTrue(count(none, Ink.TEXT) > 20, "it says there are no probes");
        assertEquals(ChartImage.SIZE * ChartImage.SIZE, count(none, Ink.PAPER) + count(none, Ink.TEXT));
        ChartImage waiting = ChartImage.draw(List.of(new ChartImage.Trace("p1", new TimeSeries())), CELSIUS, "C");
        assertTrue(count(waiting, Ink.LINE_1) > 0, "the legend's swatch");
        assertEquals(0, count(waiting, Ink.AXIS), "no axes before the first reading");
    }

    @Test
    void aRisingRecordingRunsFromTheLowerLeftToTheUpperRight() {
        TimeSeries s = new TimeSeries();
        for (int i = 0; i <= 250; i++) {
            s.add(14.4 * i, 273.15 + 0.4 * i);
        }
        ChartImage chart = ChartImage.draw(List.of(new ChartImage.Trace("ramp", s)), CELSIUS, "°C");
        int[] left = extreme(chart, Ink.LINE_1, true);
        int[] right = extreme(chart, Ink.LINE_1, false);
        assertEquals(ChartImage.SIZE - 3, right[0], "the latest reading is at the right edge of the plot");
        assertTrue(left[0] < 30, "the first reading is at the left edge of the plot: " + left[0]);
        assertTrue(left[1] > right[1] + 60, "and much lower down: " + left[1] + " against " + right[1]);
        assertTrue(count(chart, Ink.AXIS) > 200, "both axes are drawn");
        assertTrue(count(chart, Ink.GRID) > 100, "and the grid");
        assertTrue(count(chart, Ink.TEXT) > 100, "and the labels and the legend");
        assertArrayEquals(chart.pixels(), ChartImage.draw(List.of(new ChartImage.Trace("ramp", s)), CELSIUS,
                "°C").pixels(), "the same recording always draws the same chart");
    }

    @Test
    void theBandShowsAPeakThatTheMeanHides() {
        TimeSeries s = new TimeSeries(16);
        for (int i = 0; i < 4_000; i++) {
            s.add(i, i == 2_500 ? 400.0 : 300.0 + (i % 2));
        }
        ChartImage chart = ChartImage.draw(List.of(new ChartImage.Trace("p", s)), CELSIUS, "C");
        int bandTop = topmost(chart, Ink.BAND_1)[1];
        int lineTop = topmost(chart, Ink.LINE_1)[1];
        assertTrue(bandTop < lineTop - 50, "the band reaches the peak, far above the line: " + bandTop + " against "
                + lineTop);
    }

    @Test
    void aGapBreaksTheLine() {
        TimeSeries s = new TimeSeries();
        for (int i = 0; i < 300; i++) {
            s.add(i, i >= 100 && i < 200 ? Double.NaN : 290.0 + StrictMath.sin(i / 10.0));
        }
        ChartImage chart = ChartImage.draw(List.of(new ChartImage.Trace("p", s)), CELSIUS, "C");
        int left = extreme(chart, Ink.LINE_1, true)[0];
        int right = extreme(chart, Ink.LINE_1, false)[0];
        int middle = (left + right) / 2;
        for (int y = 0; y < ChartImage.SIZE; y++) {
            assertFalse(chart.at(middle, y) == Ink.LINE_1, "nothing is drawn while nothing was read");
        }
        int lone = extreme(ChartImage.draw(List.of(new ChartImage.Trace("p", single())), CELSIUS, "C"), Ink.LINE_1,
                true)[0];
        assertTrue(lone > 100, "a single reading is drawn as a cross at the latest time: " + lone);
    }

    @Test
    void eachRecordingHasItsOwnInkAndFourFitInTheLegend() {
        List<ChartImage.Trace> traces = new ArrayList<>();
        for (int k = 0; k < 5; k++) {
            TimeSeries s = new TimeSeries();
            for (int i = 0; i < 100; i++) {
                s.add(i, 280.0 + 10 * k + i * 0.1);
            }
            traces.add(new ChartImage.Trace("probe" + k, s));
        }
        ChartImage chart = ChartImage.draw(traces, CELSIUS, "°C");
        for (int k = 0; k < ChartImage.MAX_TRACES; k++) {
            assertTrue(count(chart, Ink.line(k)) > 50, "recording " + k + " is drawn");
        }
        int lowestSwatch = 0;
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < ChartImage.SIZE; x++) {
                if (chart.at(x, y) == Ink.LINE_3) {
                    lowestSwatch = y;
                }
            }
        }
        assertEquals(12, lowestSwatch, "the third and fourth are named on a second row");
    }

    @Test
    void valueAxesHaveRoundMarks() {
        ChartImage.Axis a = ChartImage.Axis.of(19.3, 87.2);
        assertEquals(0.0, a.first());
        assertEquals(100.0, a.last());
        assertEquals(20.0, a.step());
        assertEquals(6, a.ticks());
        assertEquals("40", a.label(2));
        ChartImage.Axis flat = ChartImage.Axis.of(20.0, 20.0);
        assertEquals(0.5, flat.step(), 1e-12);
        assertEquals(19.5, flat.first(), 1e-9);
        assertEquals(20.5, flat.last(), 1e-9);
        assertEquals("19.5", flat.label(0));
        assertEquals("20.0", flat.label(1));
        ChartImage.Axis across = ChartImage.Axis.of(-0.31, 0.27);
        assertEquals(0.2, across.step(), 1e-12);
        assertEquals("0.0", across.label(2), "never minus zero");
        assertEquals("-0.4", across.label(0));
        assertEquals(3, ChartImage.Axis.of(1.003, 1.009).decimals());
    }

    @Test
    void timesOnAnAxisShareTheUnitOfItsMarks() {
        assertEquals("45S", ChartImage.timeLabel(45, 15));
        assertEquals("15M", ChartImage.timeLabel(900, 900));
        assertEquals("60M", ChartImage.timeLabel(3_600, 1_800), "not 1H beside 30M");
        assertEquals("90M", ChartImage.timeLabel(5_400, 1_800));
        assertEquals("24H", ChartImage.timeLabel(86_400, 43_200), "not 1D beside 12H");
        assertEquals("36H", ChartImage.timeLabel(129_600, 43_200));
        assertEquals("2D", ChartImage.timeLabel(172_800, 86_400));
    }

    @Test
    void theFontWritesWhatChartsNeed() {
        assertEquals(11, PixelFont.width("NOW"));
        assertEquals(0, PixelFont.width(""));
        int[] lit = new int[1];
        PixelFont.write((x, y) -> lit[0]++, "1", 0, 0);
        assertEquals(8, lit[0]);
        lit[0] = 0;
        PixelFont.write((x, y) -> lit[0]++, "°", 0, 0);
        assertEquals(4, lit[0], "the degree sign");
        lit[0] = 0;
        PixelFont.write((x, y) -> lit[0]++, "€", 0, 0);
        assertEquals(5, lit[0], "a question mark for what the font lacks");
        StringBuilder letters = new StringBuilder();
        PixelFont.write((x, y) -> letters.append(x).append(',').append(y).append(' '), "a", 0, 0);
        StringBuilder capitals = new StringBuilder();
        PixelFont.write((x, y) -> capitals.append(x).append(',').append(y).append(' '), "A", 0, 0);
        assertEquals(capitals.toString(), letters.toString(), "small letters are written as capitals");
    }

    private static TimeSeries single() {
        TimeSeries s = new TimeSeries();
        s.add(0, Double.NaN);
        s.add(100, Double.NaN);
        s.add(200, 300.0);
        return s;
    }

    private static int count(ChartImage chart, Ink ink) {
        int n = 0;
        for (int y = 0; y < ChartImage.SIZE; y++) {
            for (int x = 0; x < ChartImage.SIZE; x++) {
                n += chart.at(x, y) == ink ? 1 : 0;
            }
        }
        return n;
    }

    /** Returns the leftmost or rightmost pixel of an ink below the legend, as x and y. */
    private static int[] extreme(ChartImage chart, Ink ink, boolean leftmost) {
        for (int k = 0; k < ChartImage.SIZE; k++) {
            int x = leftmost ? k : ChartImage.SIZE - 1 - k;
            for (int y = 8; y < ChartImage.SIZE; y++) {
                if (chart.at(x, y) == ink) {
                    return new int[] {x, y};
                }
            }
        }
        throw new AssertionError("no pixel of " + ink);
    }

    /** Returns the topmost pixel of an ink below the legend, as x and y. */
    private static int[] topmost(ChartImage chart, Ink ink) {
        for (int y = 8; y < ChartImage.SIZE; y++) {
            for (int x = 0; x < ChartImage.SIZE; x++) {
                if (chart.at(x, y) == ink) {
                    return new int[] {x, y};
                }
            }
        }
        throw new AssertionError("no pixel of " + ink);
    }
}
