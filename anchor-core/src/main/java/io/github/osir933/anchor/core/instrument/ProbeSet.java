package io.github.osir933.anchor.core.instrument;

import io.github.osir933.anchor.core.space.GridPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;

/**
 * Named probes that each record a reading, such as a temperature, at one point of a world, all on one clock.
 *
 * <p>The clock counts simulated seconds: each {@link #record} moves it on by one step and gives every probe a reading
 * at the new time, so probes added at different times still share one time line. Each probe has a number that never
 * changes and a name that people may change; a probe added without a name is called {@code p} and its number.
 */
public final class ProbeSet {

    /** The most probes a set holds unless it asks for another number. */
    public static final int DEFAULT_LIMIT = 32;

    /** The longest name a probe may have. */
    public static final int MAX_NAME_LENGTH = 24;

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1," + MAX_NAME_LENGTH + "}");

    /** One probe: where it measures, and what it recorded. */
    public static final class Probe {
        private final long number;
        private String name;
        private final GridPos block;
        private final double x;
        private final double y;
        private final double z;
        private final TimeSeries series;

        private Probe(long number, String name, GridPos block, double x, double y, double z, TimeSeries series) {
            this.number = number;
            this.name = name;
            this.block = block;
            this.x = x;
            this.y = y;
            this.z = z;
            this.series = series;
        }

        /**
         * Returns the probe's number, which never changes.
         *
         * @return the number, from 1
         */
        public long number() {
            return number;
        }

        /**
         * Returns the probe's name.
         *
         * @return the name
         */
        public String name() {
            return name;
        }

        /**
         * Returns the block the probe measures.
         *
         * @return the block
         */
        public GridPos block() {
            return block;
        }

        /**
         * Returns where in the world the probe measures, along x.
         *
         * @return the x coordinate, in blocks, within {@link #block()}
         */
        public double x() {
            return x;
        }

        /**
         * Returns where in the world the probe measures, along y.
         *
         * @return the y coordinate, in blocks, within {@link #block()}
         */
        public double y() {
            return y;
        }

        /**
         * Returns where in the world the probe measures, along z.
         *
         * @return the z coordinate, in blocks, within {@link #block()}
         */
        public double z() {
            return z;
        }

        /**
         * Returns what the probe recorded.
         *
         * @return the recording, owned by the probe
         */
        public TimeSeries series() {
            return series;
        }

        @Override
        public String toString() {
            return "Probe[" + number + " " + name + " at " + block + ", " + series + "]";
        }
    }

    /**
     * One probe as it is saved.
     *
     * @param number its number
     * @param name its name
     * @param block the block it measures
     * @param x where it measures, along x
     * @param y where it measures, along y
     * @param z where it measures, along z
     * @param series what it recorded
     */
    public record SavedProbe(long number, String name, GridPos block, double x, double y, double z,
            TimeSeries.State series) {
    }

    /**
     * Everything a set holds, for saving it.
     *
     * @param clockS the clock, in simulated seconds
     * @param nextNumber the number the next probe gets
     * @param probes the probes in the order they were added
     */
    public record State(double clockS, long nextNumber, List<SavedProbe> probes) {

        /**
         * Takes an unmodifiable copy of the probes.
         *
         * @param clockS the clock
         * @param nextNumber the next number
         * @param probes the probes
         */
        public State {
            probes = List.copyOf(probes);
        }
    }

    private final int limit;
    private final List<Probe> probes = new ArrayList<>();
    private double clockS;
    private long nextNumber = 1;

    /** Creates an empty set that holds as many as {@link #DEFAULT_LIMIT} probes. */
    public ProbeSet() {
        this(DEFAULT_LIMIT);
    }

    /**
     * Creates an empty set.
     *
     * @param limit the most probes it may hold, at least 1
     */
    public ProbeSet(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, not " + limit);
        }
        this.limit = limit;
    }

    /**
     * Brings a set back as it was saved.
     *
     * @param state what {@link #state()} returned
     * @param limit the most probes the set may hold from now on; probes saved beyond it are kept, but no more can be
     *     added until enough are removed
     * @return the set
     * @throws IllegalArgumentException if the state does not describe a set
     */
    public static ProbeSet restore(State state, int limit) {
        ProbeSet set = new ProbeSet(limit);
        if (!Double.isFinite(state.clockS()) || state.clockS() < 0) {
            throw new IllegalArgumentException("clock " + state.clockS() + " is not a time");
        }
        set.clockS = state.clockS();
        long highest = 0;
        for (SavedProbe p : state.probes()) {
            String name = normalize(p.name());
            if (!validName(name) || set.get(name).isPresent() || p.number() < 1
                    || set.byNumber(p.number()).isPresent()) {
                throw new IllegalArgumentException("probe " + p.number() + " '" + p.name()
                        + "' repeats or is misnamed");
            }
            if (!inside(p.x(), p.block().x()) || !inside(p.y(), p.block().y()) || !inside(p.z(), p.block().z())) {
                throw new IllegalArgumentException("probe '" + p.name() + "' measures outside its block");
            }
            TimeSeries series = TimeSeries.restore(p.series());
            if (!series.isEmpty() && series.lastTime() > set.clockS) {
                throw new IllegalArgumentException("probe '" + p.name() + "' recorded after the clock");
            }
            set.probes.add(new Probe(p.number(), name, p.block(), p.x(), p.y(), p.z(), series));
            highest = Math.max(highest, p.number());
        }
        set.nextNumber = Math.max(state.nextNumber(), highest + 1);
        return set;
    }

    /**
     * Turns a name as someone typed it into a probe's name: probe names are in lower case.
     *
     * @param name the typed name
     * @return the name in lower case
     */
    public static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Tells whether a name, once {@linkplain #normalize normalized}, can name a probe: letters, digits, {@code _} and
     * {@code -}, as many as {@link #MAX_NAME_LENGTH}.
     *
     * @param name the name
     * @return {@code true} if it can
     */
    public static boolean validName(String name) {
        return NAME.matcher(normalize(name)).matches();
    }

    /**
     * Returns the most probes the set may hold.
     *
     * @return the limit
     */
    public int limit() {
        return limit;
    }

    /**
     * Returns how many probes the set holds.
     *
     * @return the count
     */
    public int size() {
        return probes.size();
    }

    /**
     * Tells whether another probe can be added.
     *
     * @return {@code true} while the set holds fewer probes than its limit
     */
    public boolean canAdd() {
        return probes.size() < limit;
    }

    /**
     * Returns the clock.
     *
     * @return the simulated seconds recorded so far
     */
    public double clock() {
        return clockS;
    }

    /**
     * Returns the probes.
     *
     * @return the probes in the order they were added, unmodifiable
     */
    public List<Probe> probes() {
        return Collections.unmodifiableList(new ArrayList<>(probes));
    }

    /**
     * Finds a probe by name.
     *
     * @param name the name, in any case
     * @return the probe, or empty if no probe has that name
     */
    public Optional<Probe> get(String name) {
        String wanted = normalize(name);
        for (Probe p : probes) {
            if (p.name.equals(wanted)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /**
     * Finds a probe by number.
     *
     * @param number the number
     * @return the probe, or empty if no probe has that number
     */
    public Optional<Probe> byNumber(long number) {
        for (Probe p : probes) {
            if (p.number == number) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /**
     * Finds the first probe that measures a block.
     *
     * @param block the block
     * @return the probe added first of those in the block, or empty if none is
     */
    public Optional<Probe> in(GridPos block) {
        for (Probe p : probes) {
            if (p.block.equals(block)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the name a probe added now without a name would get.
     *
     * @return {@code p} and the next free number
     */
    public String nextName() {
        long n = nextNumber;
        while (get("p" + n).isPresent()) {
            n++;
        }
        return "p" + n;
    }

    /**
     * Adds a probe that measures a point in a block from the next reading on.
     *
     * @param name the probe's name, in any case, or {@code null} for {@link #nextName()}
     * @param block the block
     * @param x where it measures, along x, in blocks; a point outside the block, such as one on its face, is moved to
     *     the block's nearest side
     * @param y likewise along y
     * @param z likewise along z
     * @return the probe
     * @throws IllegalArgumentException if the set is full, or the name is not {@linkplain #validName valid} or is
     *     taken, or the point is not finite
     */
    public Probe add(String name, GridPos block, double x, double y, double z) {
        Objects.requireNonNull(block, "block");
        String n = name == null ? nextName() : normalize(name);
        if (!canAdd()) {
            throw new IllegalArgumentException("the set already holds " + probes.size() + " probes");
        }
        if (!validName(n)) {
            throw new IllegalArgumentException("'" + name + "' cannot name a probe");
        }
        if (get(n).isPresent()) {
            throw new IllegalArgumentException("a probe is already called '" + n + "'");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("the point " + x + ", " + y + ", " + z + " is not finite");
        }
        long number = nextNumber;
        String probeName = n;
        if (name == null) {
            number = Long.parseLong(n.substring(1));
        }
        nextNumber = Math.max(nextNumber, number) + 1;
        Probe p = new Probe(number, probeName, block, within(x, block.x()), within(y, block.y()), within(z, block.z()),
                new TimeSeries());
        probes.add(p);
        return p;
    }

    /**
     * Renames a probe.
     *
     * @param name its name, in any case
     * @param newName its new name, in any case
     * @return {@code true} if it was renamed; {@code false} if no probe has the name, the new name is not
     *     {@linkplain #validName valid}, or another probe has it
     */
    public boolean rename(String name, String newName) {
        Optional<Probe> p = get(name);
        String n = normalize(newName);
        if (p.isEmpty() || !validName(n) || get(n).filter(other -> other != p.get()).isPresent()) {
            return false;
        }
        p.get().name = n;
        return true;
    }

    /**
     * Removes a probe and what it recorded.
     *
     * @param name its name, in any case
     * @return the probe, or empty if no probe has the name
     */
    public Optional<Probe> remove(String name) {
        Optional<Probe> p = get(name);
        p.ifPresent(probes::remove);
        return p;
    }

    /** Removes every probe. The clock and the numbering carry on. */
    public void clear() {
        probes.clear();
    }

    /**
     * Moves the clock on by one step and records a reading for every probe at the new time.
     *
     * @param stepSeconds the simulated seconds the step took, more than 0
     * @param reading reads a probe's value, or {@link Double#NaN} if there is none, as for a block that is not
     *     simulated
     */
    public void record(double stepSeconds, ToDoubleFunction<Probe> reading) {
        if (!(stepSeconds > 0.0) || !Double.isFinite(stepSeconds)) {
            throw new IllegalArgumentException("a step must take a finite time above 0, not " + stepSeconds);
        }
        clockS += stepSeconds;
        for (Probe p : probes) {
            double value = reading.applyAsDouble(p);
            p.series.add(clockS, Double.isInfinite(value) ? Double.NaN : value);
        }
    }

    /**
     * Returns everything the set holds, to save it.
     *
     * @return the state; {@link #restore} brings back an equal set
     */
    public State state() {
        List<SavedProbe> saved = new ArrayList<>();
        for (Probe p : probes) {
            saved.add(new SavedProbe(p.number, p.name, p.block, p.x, p.y, p.z, p.series.state()));
        }
        return new State(clockS, nextNumber, saved);
    }

    /** Moves a coordinate into the block that starts at {@code start} along its axis. */
    private static double within(double coordinate, int start) {
        return Math.max(start, Math.min(Math.nextDown(start + 1.0), coordinate));
    }

    private static boolean inside(double coordinate, int start) {
        return coordinate >= start && coordinate < start + 1.0;
    }

    @Override
    public String toString() {
        return "ProbeSet[" + probes.size() + " probes at " + clockS + " s]";
    }
}
