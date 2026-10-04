package io.github.osir933.anchor.core.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.math.DeterministicRandom;
import org.junit.jupiter.api.Test;

class TimeSeriesTest {

    @Test
    void fillsABucketPerReadingUntilTheBucketsRunOut() {
        TimeSeries s = new TimeSeries(8);
        for (int i = 1; i <= 8; i++) {
            s.add(i, 10.0 * i);
        }
        assertEquals(8, s.size());
        assertEquals(1, s.span());
        for (int i = 0; i < 8; i++) {
            TimeSeries.Bucket b = s.bucket(i);
            assertEquals(i + 1.0, b.startS());
            assertEquals(i + 1.0, b.endS());
            assertEquals(1, b.samples());
            assertEquals(10.0 * (i + 1), b.minimum());
            assertEquals(10.0 * (i + 1), b.maximum());
            assertEquals(10.0 * (i + 1), b.mean());
        }
        assertEquals(80.0, s.last());
        assertEquals(1.0, s.firstTime());
        assertEquals(8.0, s.lastTime());
    }

    @Test
    void mergesNeighboursInPairsWhenFullAndKeepsTheirExtremes() {
        TimeSeries s = new TimeSeries(8);
        double[] values = {5, 1, 7, 7, 2, 9, 4, 4, 6};
        for (int i = 0; i < values.length; i++) {
            s.add(i, values[i]);
        }
        assertEquals(5, s.size(), "four merged buckets and a new one");
        assertEquals(2, s.span());
        TimeSeries.Bucket first = s.bucket(0);
        assertEquals(0.0, first.startS());
        assertEquals(1.0, first.endS());
        assertEquals(2, first.samples());
        assertEquals(1.0, first.minimum());
        assertEquals(5.0, first.maximum());
        assertEquals(3.0, first.mean());
        assertEquals(new TimeSeries.Bucket(4.0, 5.0, 2, 2, 2.0, 9.0, 5.5), s.bucket(2));
        TimeSeries.Bucket last = s.bucket(4);
        assertEquals(1, last.samples(), "the newest bucket is still filling");
        assertEquals(6.0, last.mean());
        assertEquals(1.0, s.minimum());
        assertEquals(9.0, s.maximum());
        assertEquals(45.0 / 9.0, s.mean(), 1e-12);
    }

    @Test
    void aLongRecordingKeepsItsWholeSpanAndEveryPeakInFixedMemory() {
        TimeSeries s = new TimeSeries(16);
        double total = 0.0;
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            double v = 20.0 + StrictMath.sin(i / 50.0) + (i == 7_777 ? 500.0 : 0.0);
            total += v;
            s.add(14.4 * i, v);
        }
        assertTrue(s.size() <= 16 && s.size() > 8, "between half full and full: " + s.size());
        assertEquals(0.0, s.firstTime());
        assertEquals(14.4 * (n - 1), s.lastTime(), 1e-9);
        assertEquals(n, s.samples());
        long held = 0;
        for (int i = 0; i < s.size(); i++) {
            TimeSeries.Bucket b = s.bucket(i);
            held += b.samples();
            if (i < s.size() - 1) {
                assertEquals(s.span(), b.samples(), "every bucket but the last is full");
                assertTrue(b.endS() <= s.bucket(i + 1).startS(), "buckets follow each other in time");
            }
        }
        assertEquals(n, held);
        assertEquals(520.0 + StrictMath.sin(7_777 / 50.0), s.maximum(), 1e-12, "the one-step spike survives");
        assertEquals(total / n, s.mean(), 1e-9);
    }

    @Test
    void missingReadingsTakeTimeButNoValue() {
        TimeSeries s = new TimeSeries(4);
        s.add(0, 10);
        s.add(1, Double.NaN);
        s.add(2, Double.NaN);
        s.add(3, 30);
        TimeSeries.Bucket gap = s.bucket(1);
        assertTrue(gap.gap());
        assertEquals(1, gap.samples());
        assertTrue(Double.isNaN(gap.mean()) && Double.isNaN(gap.minimum()) && Double.isNaN(gap.maximum()));
        s.add(4, Double.NaN);
        assertEquals(3, s.size(), "merged: (10, gap), (gap, 30), and the new gap");
        assertEquals(10.0, s.bucket(0).mean());
        assertEquals(1, s.bucket(0).readings());
        assertEquals(30.0, s.bucket(1).maximum());
        assertTrue(s.bucket(2).gap());
        assertTrue(Double.isNaN(s.last()), "the latest reading was missing");
        assertEquals(20.0, s.mean());
        TimeSeries empty = new TimeSeries();
        empty.add(0, Double.NaN);
        assertTrue(Double.isNaN(empty.minimum()) && Double.isNaN(empty.maximum()) && Double.isNaN(empty.mean()));
    }

    @Test
    void theRecentRateIsTheSlopeOfTheLatestReadings() {
        TimeSeries s = new TimeSeries(8);
        assertTrue(Double.isNaN(s.recentRate()));
        s.add(0, 5);
        assertTrue(Double.isNaN(s.recentRate()), "one reading has no slope");
        for (int i = 1; i < 40; i++) {
            s.add(10.0 * i, i % 7 == 3 ? Double.NaN : 5 + 2.0 * 10.0 * i);
        }
        assertEquals(2.0, s.recentRate(), 1e-12, "2 units a second, missing readings skipped");
        TimeSeries turning = new TimeSeries(8);
        for (int i = 0; i < 100; i++) {
            turning.add(i, i < 80 ? i : 80 - 3.0 * (i - 80));
        }
        assertEquals(-3.0, turning.recentRate(), 1e-12, "only the latest readings count");
        TimeSeries still = new TimeSeries(8);
        still.add(0, 1);
        still.add(0, 2);
        assertTrue(Double.isNaN(still.recentRate()), "readings taken at once have no slope");
    }

    @Test
    void comesBackExactlyAsItWasSavedAndRecordsOnAlike() {
        DeterministicRandom random = new DeterministicRandom(42);
        TimeSeries s = new TimeSeries(32);
        double t = 0;
        double v = 300;
        for (int i = 0; i < 1_000; i++) {
            t += 14.4;
            v += random.nextDouble() - 0.5;
            s.add(t, random.nextInt(10) == 0 ? Double.NaN : v);
        }
        TimeSeries.State state = s.state();
        TimeSeries back = TimeSeries.restore(state);
        assertEquals(state, back.state());
        assertEquals(state.hashCode(), back.state().hashCode());
        assertEquals(s.recentRate(), back.recentRate());
        for (int i = 0; i < 500; i++) {
            t += 14.4;
            v += random.nextDouble() - 0.5;
            s.add(t, v);
            back.add(t, v);
        }
        assertEquals(s.state(), back.state());
        assertNotEquals(state, s.state());
        TimeSeries empty = TimeSeries.restore(new TimeSeries().state());
        assertTrue(empty.isEmpty());
        assertEquals(TimeSeries.DEFAULT_CAPACITY, empty.capacity());
    }

    @Test
    void refusesWhatCannotBeARecording() {
        assertThrows(IllegalArgumentException.class, () -> new TimeSeries(7));
        assertThrows(IllegalArgumentException.class, () -> new TimeSeries(2));
        TimeSeries s = new TimeSeries();
        s.add(10, 1);
        assertThrows(IllegalArgumentException.class, () -> s.add(9, 1), "time goes back");
        assertThrows(IllegalArgumentException.class, () -> s.add(Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> s.add(11, Double.POSITIVE_INFINITY));
        s.add(10, 2);
        TimeSeries.State good = s.state();
        assertThrows(IllegalArgumentException.class, () -> TimeSeries.restore(new TimeSeries.State(512, 3,
                good.samples(), good.start(), good.end(), good.minimum(), good.maximum(), good.sum(),
                good.readings(), good.recentTime(), good.recentValue())), "a span of 3");
        assertThrows(IllegalArgumentException.class, () -> TimeSeries.restore(new TimeSeries.State(512, 1, 5,
                good.start(), good.end(), good.minimum(), good.maximum(), good.sum(), good.readings(),
                good.recentTime(), good.recentValue())), "more readings than the buckets hold");
        assertThrows(IllegalArgumentException.class, () -> TimeSeries.restore(new TimeSeries.State(512, 1, 2,
                good.start(), good.end(), good.minimum(), good.maximum(), good.sum(), new long[] {3, 1},
                good.recentTime(), good.recentValue())), "a bucket with more values than readings");
        assertThrows(IllegalArgumentException.class, () -> TimeSeries.restore(new TimeSeries.State(512, 1, 2,
                good.start(), good.end(), new double[] {1, Double.NaN}, good.maximum(), good.sum(),
                good.readings(), good.recentTime(), good.recentValue())), "a value missing from a full bucket");
        assertThrows(IllegalArgumentException.class, () -> TimeSeries.restore(new TimeSeries.State(512, 1, 2,
                good.start(), good.end(), good.minimum(), good.maximum(), good.sum(), good.readings(),
                new double[] {12, 10}, good.recentValue())), "recent readings out of order");
    }

    @Test
    void clearingForgetsEverything() {
        TimeSeries s = new TimeSeries(4);
        for (int i = 0; i < 10; i++) {
            s.add(i, i);
        }
        s.clear();
        assertTrue(s.isEmpty());
        assertEquals(0, s.size());
        assertEquals(1, s.span());
        assertTrue(Double.isNaN(s.last()) && Double.isNaN(s.firstTime()) && Double.isNaN(s.recentRate()));
        s.add(3, 7);
        assertFalse(s.isEmpty());
        assertEquals(7.0, s.bucket(0).mean());
    }
}
