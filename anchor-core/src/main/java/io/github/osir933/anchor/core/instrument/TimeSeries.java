package io.github.osir933.anchor.core.instrument;

import java.util.Arrays;
import java.util.Objects;

/**
 * A recording of one reading over time that keeps all of it in a fixed amount of memory.
 *
 * <p>Readings fill buckets one each until the buckets run out; then neighbouring buckets merge in pairs, so that each
 * holds twice as many readings, and recording goes on. A long recording therefore keeps its whole span at a coarser
 * grain, and every bucket keeps the lowest, highest and mean of its readings, so a brief peak is never averaged away.
 * All buckets but the last hold the same number of readings.
 *
 * <p>A missing reading, written as {@link Double#NaN}, takes up time but has no value, as when the block a probe
 * measures is not simulated; a bucket of nothing but missing readings is a gap.
 *
 * <p>The last few readings are also kept one by one, to tell how fast the reading is changing now.
 */
public final class TimeSeries {

    /** The buckets a series has unless it asks for another number. */
    public static final int DEFAULT_CAPACITY = 512;

    /** How many of the latest readings are kept one by one. */
    public static final int RECENT = 16;

    /** The most readings a bucket may hold: far more than a world records in its lifetime. */
    private static final long MAX_SPAN = 1L << 40;

    /**
     * One bucket of readings.
     *
     * @param startS the time of its first reading, in seconds
     * @param endS the time of its last reading, in seconds
     * @param samples how many readings it holds, missing ones included
     * @param readings how many of those have a value
     * @param minimum the lowest value, or {@link Double#NaN} if no reading has a value
     * @param maximum the highest value, likewise
     * @param mean the mean value, likewise
     */
    public record Bucket(double startS, double endS, long samples, long readings, double minimum, double maximum,
            double mean) {

        /**
         * Tells whether no reading in the bucket has a value.
         *
         * @return {@code true} for a gap
         */
        public boolean gap() {
            return readings == 0;
        }

        /**
         * Returns the middle of the bucket's time.
         *
         * @return the time in seconds
         */
        public double middleS() {
            return 0.5 * (startS + endS);
        }
    }

    /**
     * Everything a series holds, for saving it and bringing it back exactly. The arrays are the series' own copies.
     *
     * @param capacity the number of buckets the series may fill
     * @param span the readings each full bucket holds; a power of two
     * @param samples the readings taken so far
     * @param start the time of each bucket's first reading
     * @param end the time of each bucket's last reading
     * @param minimum each bucket's lowest value, {@link Double#NaN} for a gap
     * @param maximum each bucket's highest value, likewise
     * @param sum the sum of each bucket's values
     * @param readings how many of each bucket's readings have a value
     * @param recentTime the times of the latest readings, oldest first, at most {@link #RECENT}
     * @param recentValue their values
     */
    public record State(int capacity, long span, long samples, double[] start, double[] end, double[] minimum,
            double[] maximum, double[] sum, long[] readings, double[] recentTime, double[] recentValue) {

        /**
         * Takes copies of the arrays.
         *
         * @param capacity the capacity
         * @param span the span
         * @param samples the samples
         * @param start the start times
         * @param end the end times
         * @param minimum the minima
         * @param maximum the maxima
         * @param sum the sums
         * @param readings the reading counts
         * @param recentTime the recent times
         * @param recentValue the recent values
         */
        public State {
            start = start.clone();
            end = end.clone();
            minimum = minimum.clone();
            maximum = maximum.clone();
            sum = sum.clone();
            readings = readings.clone();
            recentTime = recentTime.clone();
            recentValue = recentValue.clone();
        }

        @Override
        public double[] start() {
            return start.clone();
        }

        @Override
        public double[] end() {
            return end.clone();
        }

        @Override
        public double[] minimum() {
            return minimum.clone();
        }

        @Override
        public double[] maximum() {
            return maximum.clone();
        }

        @Override
        public double[] sum() {
            return sum.clone();
        }

        @Override
        public long[] readings() {
            return readings.clone();
        }

        @Override
        public double[] recentTime() {
            return recentTime.clone();
        }

        @Override
        public double[] recentValue() {
            return recentValue.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof State s && capacity == s.capacity && span == s.span && samples == s.samples
                    && Arrays.equals(start, s.start) && Arrays.equals(end, s.end)
                    && Arrays.equals(minimum, s.minimum) && Arrays.equals(maximum, s.maximum)
                    && Arrays.equals(sum, s.sum) && Arrays.equals(readings, s.readings)
                    && Arrays.equals(recentTime, s.recentTime) && Arrays.equals(recentValue, s.recentValue);
        }

        @Override
        public int hashCode() {
            int h = Objects.hash(capacity, span, samples);
            h = 31 * h + Arrays.hashCode(start);
            h = 31 * h + Arrays.hashCode(end);
            h = 31 * h + Arrays.hashCode(minimum);
            h = 31 * h + Arrays.hashCode(maximum);
            h = 31 * h + Arrays.hashCode(sum);
            h = 31 * h + Arrays.hashCode(readings);
            h = 31 * h + Arrays.hashCode(recentTime);
            return 31 * h + Arrays.hashCode(recentValue);
        }

        @Override
        public String toString() {
            return "TimeSeries.State[capacity=" + capacity + ", span=" + span + ", samples=" + samples + ", buckets="
                    + start.length + "]";
        }
    }

    private final int capacity;
    private final double[] start;
    private final double[] end;
    private final double[] minimum;
    private final double[] maximum;
    private final double[] sum;
    private final long[] readings;
    private final double[] recentTime = new double[RECENT];
    private final double[] recentValue = new double[RECENT];
    private int size;
    private long span = 1;
    /** Readings in the last bucket. */
    private long filling;
    private long samples;
    /** How many recent readings are kept, and where the oldest of them is. */
    private int recentCount;
    private int recentFirst;

    /** Creates an empty series with {@link #DEFAULT_CAPACITY} buckets. */
    public TimeSeries() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates an empty series.
     *
     * @param capacity how many buckets it may fill before merging them; even and at least 4
     */
    public TimeSeries(int capacity) {
        if (capacity < 4 || capacity % 2 != 0) {
            throw new IllegalArgumentException("capacity must be even and at least 4, not " + capacity);
        }
        this.capacity = capacity;
        this.start = new double[capacity];
        this.end = new double[capacity];
        this.minimum = new double[capacity];
        this.maximum = new double[capacity];
        this.sum = new double[capacity];
        this.readings = new long[capacity];
    }

    /**
     * Brings a series back as it was saved.
     *
     * @param state what {@link #state()} returned
     * @return the series
     * @throws IllegalArgumentException if the state does not describe a series
     */
    public static TimeSeries restore(State state) {
        TimeSeries s = new TimeSeries(state.capacity());
        double[] start = state.start();
        int n = start.length;
        double[] end = state.end();
        double[] min = state.minimum();
        double[] max = state.maximum();
        double[] sum = state.sum();
        long[] readings = state.readings();
        double[] recentTime = state.recentTime();
        double[] recentValue = state.recentValue();
        long span = state.span();
        if (n > s.capacity || end.length != n || min.length != n || max.length != n || sum.length != n
                || readings.length != n) {
            throw new IllegalArgumentException("the bucket arrays differ in length or exceed the capacity");
        }
        if (span < 1 || span > MAX_SPAN || Long.bitCount(span) != 1) {
            throw new IllegalArgumentException("span " + span + " is not a power of two");
        }
        long samples = state.samples();
        if (n == 0 ? samples != 0 : samples <= (n - 1) * span || samples > n * span) {
            throw new IllegalArgumentException(samples + " readings cannot fill " + n + " buckets of " + span);
        }
        if (recentTime.length != recentValue.length || recentTime.length > RECENT
                || recentTime.length > samples || (samples > 0 && recentTime.length == 0)) {
            throw new IllegalArgumentException("the recent readings do not fit the series");
        }
        for (int k = 0; k < recentTime.length; k++) {
            if (!Double.isFinite(recentTime[k]) || (k > 0 && recentTime[k] < recentTime[k - 1])
                    || Double.isInfinite(recentValue[k]) || recentTime[k] > end[n - 1]) {
                throw new IllegalArgumentException("recent reading " + k + " is not consistent");
            }
        }
        for (int b = 0; b < n; b++) {
            boolean empty = readings[b] == 0;
            long held = b < n - 1 ? span : samples - (n - 1) * span;
            if (!(start[b] <= end[b]) || (b > 0 && !(end[b - 1] <= start[b])) || readings[b] < 0
                    || readings[b] > held || empty != Double.isNaN(min[b]) || empty != Double.isNaN(max[b])
                    || (!empty && !(min[b] <= max[b]))) {
                throw new IllegalArgumentException("bucket " + b + " is not consistent");
            }
        }
        System.arraycopy(start, 0, s.start, 0, n);
        System.arraycopy(end, 0, s.end, 0, n);
        System.arraycopy(min, 0, s.minimum, 0, n);
        System.arraycopy(max, 0, s.maximum, 0, n);
        System.arraycopy(sum, 0, s.sum, 0, n);
        System.arraycopy(readings, 0, s.readings, 0, n);
        System.arraycopy(recentTime, 0, s.recentTime, 0, recentTime.length);
        System.arraycopy(recentValue, 0, s.recentValue, 0, recentValue.length);
        s.size = n;
        s.span = span;
        s.samples = samples;
        s.filling = n == 0 ? 0 : samples - (n - 1) * span;
        s.recentCount = recentTime.length;
        s.recentFirst = 0;
        return s;
    }

    /**
     * Records a reading.
     *
     * @param timeS when it was taken, in seconds; never before the last reading
     * @param value the value, or {@link Double#NaN} for a missing reading
     * @throws IllegalArgumentException if the time is not finite or goes back, or the value is infinite
     */
    public void add(double timeS, double value) {
        if (!Double.isFinite(timeS)) {
            throw new IllegalArgumentException("time " + timeS + " is not finite");
        }
        if (Double.isInfinite(value)) {
            throw new IllegalArgumentException("value " + value + " is infinite");
        }
        if (samples > 0 && timeS < lastTime()) {
            throw new IllegalArgumentException("time " + timeS + " comes before the last reading, at " + lastTime());
        }
        if (size == 0 || filling == span) {
            if (size == capacity) {
                merge();
            }
            int b = size++;
            start[b] = timeS;
            minimum[b] = Double.NaN;
            maximum[b] = Double.NaN;
            sum[b] = 0.0;
            readings[b] = 0;
            filling = 0;
        }
        int b = size - 1;
        end[b] = timeS;
        if (!Double.isNaN(value)) {
            if (readings[b] == 0) {
                minimum[b] = value;
                maximum[b] = value;
            } else {
                minimum[b] = Math.min(minimum[b], value);
                maximum[b] = Math.max(maximum[b], value);
            }
            sum[b] += value;
            readings[b]++;
        }
        filling++;
        samples++;
        int slot = (recentFirst + recentCount) % RECENT;
        if (recentCount == RECENT) {
            recentFirst = (recentFirst + 1) % RECENT;
        } else {
            recentCount++;
        }
        recentTime[slot] = timeS;
        recentValue[slot] = value;
    }

    /** Merges the buckets, all of them full, in pairs. */
    private void merge() {
        int half = capacity / 2;
        for (int k = 0; k < half; k++) {
            int a = 2 * k;
            int b = a + 1;
            double min;
            double max;
            if (readings[a] == 0) {
                min = minimum[b];
                max = maximum[b];
            } else if (readings[b] == 0) {
                min = minimum[a];
                max = maximum[a];
            } else {
                min = Math.min(minimum[a], minimum[b]);
                max = Math.max(maximum[a], maximum[b]);
            }
            start[k] = start[a];
            end[k] = end[b];
            minimum[k] = min;
            maximum[k] = max;
            sum[k] = sum[a] + sum[b];
            readings[k] = readings[a] + readings[b];
        }
        size = half;
        span *= 2;
        filling = span;
    }

    /**
     * Returns how many buckets hold readings.
     *
     * @return the number of buckets
     */
    public int size() {
        return size;
    }

    /**
     * Returns how many buckets the series may fill before merging them.
     *
     * @return the capacity
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Returns how many readings each full bucket holds now.
     *
     * @return a power of two
     */
    public long span() {
        return span;
    }

    /**
     * Returns how many readings were taken, missing ones included.
     *
     * @return the count
     */
    public long samples() {
        return samples;
    }

    /**
     * Tells whether nothing was recorded.
     *
     * @return {@code true} for an empty series
     */
    public boolean isEmpty() {
        return samples == 0;
    }

    /**
     * Returns a bucket.
     *
     * @param index from 0, the oldest, to {@code size() - 1}, the latest
     * @return the bucket
     */
    public Bucket bucket(int index) {
        Objects.checkIndex(index, size);
        long held = index < size - 1 ? span : filling;
        double mean = readings[index] == 0 ? Double.NaN : sum[index] / readings[index];
        return new Bucket(start[index], end[index], held, readings[index], minimum[index], maximum[index], mean);
    }

    /**
     * Returns the time of the first reading.
     *
     * @return the time in seconds, or {@link Double#NaN} for an empty series
     */
    public double firstTime() {
        return size == 0 ? Double.NaN : start[0];
    }

    /**
     * Returns the time of the latest reading.
     *
     * @return the time in seconds, or {@link Double#NaN} for an empty series
     */
    public double lastTime() {
        return size == 0 ? Double.NaN : end[size - 1];
    }

    /**
     * Returns the latest reading.
     *
     * @return its value, {@link Double#NaN} if it was missing or nothing was recorded
     */
    public double last() {
        return recentCount == 0 ? Double.NaN : recentValue[(recentFirst + recentCount - 1) % RECENT];
    }

    /**
     * Returns the lowest value recorded.
     *
     * @return the value, or {@link Double#NaN} if no reading had one
     */
    public double minimum() {
        double min = Double.NaN;
        for (int b = 0; b < size; b++) {
            if (readings[b] > 0) {
                min = Double.isNaN(min) ? minimum[b] : Math.min(min, minimum[b]);
            }
        }
        return min;
    }

    /**
     * Returns the highest value recorded.
     *
     * @return the value, or {@link Double#NaN} if no reading had one
     */
    public double maximum() {
        double max = Double.NaN;
        for (int b = 0; b < size; b++) {
            if (readings[b] > 0) {
                max = Double.isNaN(max) ? maximum[b] : Math.max(max, maximum[b]);
            }
        }
        return max;
    }

    /**
     * Returns the mean of the values recorded.
     *
     * @return the mean, or {@link Double#NaN} if no reading had a value
     */
    public double mean() {
        double total = 0.0;
        long count = 0;
        for (int b = 0; b < size; b++) {
            total += sum[b];
            count += readings[b];
        }
        return count == 0 ? Double.NaN : total / count;
    }

    /**
     * Returns how fast the reading is changing now: the slope of a straight line fitted by least squares through the
     * latest readings that have values, as many as {@link #RECENT}.
     *
     * @return the rate in units per second, or {@link Double#NaN} if fewer than two of those readings have values or
     *     they were all taken at once
     */
    public double recentRate() {
        int n = 0;
        double t0 = Double.NaN;
        double st = 0.0;
        double sv = 0.0;
        for (int k = 0; k < recentCount; k++) {
            int i = (recentFirst + k) % RECENT;
            if (!Double.isNaN(recentValue[i])) {
                if (n == 0) {
                    t0 = recentTime[i];
                }
                st += recentTime[i] - t0;
                sv += recentValue[i];
                n++;
            }
        }
        if (n < 2) {
            return Double.NaN;
        }
        double mt = st / n;
        double mv = sv / n;
        double stt = 0.0;
        double stv = 0.0;
        for (int k = 0; k < recentCount; k++) {
            int i = (recentFirst + k) % RECENT;
            if (!Double.isNaN(recentValue[i])) {
                double dt = recentTime[i] - t0 - mt;
                stt += dt * dt;
                stv += dt * (recentValue[i] - mv);
            }
        }
        return stt > 0.0 ? stv / stt : Double.NaN;
    }

    /** Forgets everything recorded. */
    public void clear() {
        size = 0;
        span = 1;
        filling = 0;
        samples = 0;
        recentCount = 0;
        recentFirst = 0;
    }

    /**
     * Returns everything the series holds, to save it.
     *
     * @return the state; {@link #restore} brings back an equal series
     */
    public State state() {
        double[] rt = new double[recentCount];
        double[] rv = new double[recentCount];
        for (int k = 0; k < recentCount; k++) {
            int i = (recentFirst + k) % RECENT;
            rt[k] = recentTime[i];
            rv[k] = recentValue[i];
        }
        return new State(capacity, span, samples, Arrays.copyOf(start, size), Arrays.copyOf(end, size),
                Arrays.copyOf(minimum, size), Arrays.copyOf(maximum, size), Arrays.copyOf(sum, size),
                Arrays.copyOf(readings, size), rt, rv);
    }

    @Override
    public String toString() {
        return "TimeSeries[" + samples + " readings in " + size + " buckets of " + span + "]";
    }
}
