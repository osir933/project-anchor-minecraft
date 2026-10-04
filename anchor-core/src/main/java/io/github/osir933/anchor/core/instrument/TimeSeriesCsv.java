package io.github.osir933.anchor.core.instrument;

import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * Writes a recording as comma-separated values, one row per bucket, for spreadsheets and plotting tools.
 *
 * <p>The columns are the middle, start and end of each bucket's time in seconds, how many readings it holds and how
 * many of them have a value, and the lowest, mean and highest value in the unit asked for. A gap leaves its value
 * columns empty.
 */
public final class TimeSeriesCsv {

    private TimeSeriesCsv() {
    }

    /**
     * Writes a recording.
     *
     * @param series the recording
     * @param unit the values' unit as the column names show it, such as {@code C}; letters, digits and {@code _}
     * @param convert turns a recorded value into that unit
     * @return the table, a header row first, each row ending in a line feed
     */
    public static String write(TimeSeries series, String unit, DoubleUnaryOperator convert) {
        if (!unit.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("unit '" + unit + "' would not make a plain column name");
        }
        StringBuilder out = new StringBuilder();
        out.append("time_s,start_s,end_s,samples,readings,min_").append(unit).append(",mean_").append(unit)
                .append(",max_").append(unit).append('\n');
        for (int i = 0; i < series.size(); i++) {
            TimeSeries.Bucket b = series.bucket(i);
            out.append(String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%d,%d", b.middleS(), b.startS(), b.endS(),
                    b.samples(), b.readings()));
            if (b.gap()) {
                out.append(",,,");
            } else {
                out.append(String.format(Locale.ROOT, ",%.4f,%.4f,%.4f", convert.applyAsDouble(b.minimum()),
                        convert.applyAsDouble(b.mean()), convert.applyAsDouble(b.maximum())));
            }
            out.append('\n');
        }
        return out.toString();
    }
}
