package io.github.osir933.anchor.core.instrument;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SparklineAndCsvTest {

    @Test
    void barsRiseFromTheLowestMeanToTheHighest() {
        TimeSeries s = new TimeSeries();
        for (int i = 0; i < 8; i++) {
            s.add(i, 10.0 * i);
        }
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7}, Sparkline.levels(s, 8));
        assertEquals("▁▂▃▄▅▆▇█", Sparkline.text(s, 8, ' '));
        assertArrayEquals(new int[] {0, 2, 5, 7}, Sparkline.levels(s, 4), "pairs of readings: 5, 25, 45, 65");
        assertEquals(8, Sparkline.levels(s, 20).length, "never more bars than buckets");
    }

    @Test
    void aFlatRecordingIsHalfHighAndGapsAreLeftOpen() {
        TimeSeries s = new TimeSeries();
        s.add(0, 5);
        s.add(1, Double.NaN);
        s.add(2, 5);
        assertArrayEquals(new int[] {3, Sparkline.GAP, 3}, Sparkline.levels(s, 10));
        assertEquals("▄·▄", Sparkline.text(s, 10, '·'));
        assertEquals(0, Sparkline.levels(new TimeSeries(), 10).length);
        assertThrows(IllegalArgumentException.class, () -> Sparkline.levels(s, 0));
    }

    @Test
    void csvHasABucketPerRowAndLeavesGapsEmpty() {
        TimeSeries s = new TimeSeries(4);
        s.add(0.0, 273.15);
        s.add(14.4, Double.NaN);
        s.add(28.8, 283.15);
        String csv = TimeSeriesCsv.write(s, "C", k -> k - 273.15);
        assertEquals("time_s,start_s,end_s,samples,readings,min_C,mean_C,max_C\n"
                + "0.000,0.000,0.000,1,1,0.0000,0.0000,0.0000\n"
                + "14.400,14.400,14.400,1,0,,,\n"
                + "28.800,28.800,28.800,1,1,10.0000,10.0000,10.0000\n", csv);
        s.add(43.2, 293.15);
        s.add(57.6, 303.15);
        String merged = TimeSeriesCsv.write(s, "K", k -> k);
        assertEquals("7.200,0.000,14.400,2,1,273.1500,273.1500,273.1500", merged.split("\n")[1]);
        assertEquals("36.000,28.800,43.200,2,2,283.1500,288.1500,293.1500", merged.split("\n")[2]);
        assertThrows(IllegalArgumentException.class, () -> TimeSeriesCsv.write(s, "deg C", k -> k));
    }
}
