package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Decides where heat is simulated, so that its cost follows what is happening in the world rather than how
 * much of the world is loaded.
 *
 * <p>A section is awake from the moment something wakes it, such as an edit or a new heat source, until its
 * matter has heated or cooled slower than the calm rate for a number of steps in a row. Each step simulates
 * the awake sections and the sections next to them, so heat can flow out of an awake region; a neighbour
 * that starts changing faster than the calm rate wakes in turn. Sleeping sections are paused, not cooled:
 * the small differences they still hold wait until something wakes them.
 *
 * <p>The rate of a cell is the heat it gained or lost divided by its heat capacity, so a block of ice that
 * absorbs latent heat at a constant 0 °C counts as changing. The capacity uses the material's lowest
 * specific heat, which errs towards staying awake. A model write that changes a cell's material or mass
 * always counts as fast.
 *
 * <p>Register the tracker with {@link PhysicalWorld#addWriteListener} so it sees what the models write, take
 * the scope for each step from {@link #scope}, and call {@link #endStep} after the models have run.
 */
public final class ThermalActivity implements PhysicalWorld.WriteListener {

    /** The default calm rate, one kelvin per hour, in kelvin per second. */
    public static final double DEFAULT_CALM_RATE = 1.0 / 3600.0;

    /** The default number of calm steps in a row after which a section falls asleep. */
    public static final int DEFAULT_CALM_STEPS = 20;

    private static final Direction[] DIRECTIONS = Direction.values();

    private final MaterialRegistry materials;
    private final double calmRate;
    private final int calmSteps;
    /** Awake sections, with how many calm steps in a row each has had. */
    private final TreeMap<Long, Integer> awake = new TreeMap<>();
    /** The largest temperature-equivalent change of any cell in each section since the last step ended. */
    private final TreeMap<Long, Double> change = new TreeMap<>();
    /** Models write section by section, so the current section's change is gathered here first. */
    private long pendingKey;
    private double pendingChange = -1;

    /**
     * Creates a tracker with no section awake.
     *
     * @param materials the materials of the world it follows
     * @param calmRate the rate below which a section counts as calm, in kelvin per second
     * @param calmSteps how many calm steps in a row put a section to sleep
     */
    public ThermalActivity(MaterialRegistry materials, double calmRate, int calmSteps) {
        this.materials = Objects.requireNonNull(materials, "materials");
        if (!(calmRate >= 0) || !Double.isFinite(calmRate)) {
            throw new IllegalArgumentException("calm rate must be finite and non-negative: " + calmRate);
        }
        if (calmSteps < 1) {
            throw new IllegalArgumentException("calm steps must be at least 1: " + calmSteps);
        }
        this.calmRate = calmRate;
        this.calmSteps = calmSteps;
    }

    /**
     * Wakes a section, or keeps it awake for another full calm period.
     *
     * @param sectionKey the packed section position
     */
    public void wake(long sectionKey) {
        awake.put(sectionKey, 0);
    }

    /**
     * Forgets a section, for example because it left the world.
     *
     * @param sectionKey the packed section position
     */
    public void forget(long sectionKey) {
        flush();
        awake.remove(sectionKey);
        change.remove(sectionKey);
    }

    /** Puts every section to sleep. */
    public void clear() {
        awake.clear();
        change.clear();
        pendingChange = -1;
    }

    /**
     * Tells whether a section is awake.
     *
     * @param sectionKey the packed section position
     * @return {@code true} if the section is awake
     */
    public boolean isAwake(long sectionKey) {
        return awake.containsKey(sectionKey);
    }

    /**
     * Returns the awake sections.
     *
     * @return an unmodifiable view, in ascending key order
     */
    public SortedSet<Long> awakeSections() {
        return Collections.unmodifiableSortedSet(awake.navigableKeySet());
    }

    /**
     * Returns where heat should be simulated this step: every awake section and its six neighbours, as far as
     * they exist in the world.
     *
     * @param world the world
     * @return section keys in ascending order
     */
    public SortedSet<Long> scope(PhysicalWorld world) {
        TreeSet<Long> keys = new TreeSet<>();
        for (long key : awake.keySet()) {
            if (world.section(key) == null) {
                continue;
            }
            keys.add(key);
            for (Direction d : DIRECTIONS) {
                long neighbour = SectionPos.offset(key, d.dx(), d.dy(), d.dz());
                if (world.section(neighbour) != null) {
                    keys.add(neighbour);
                }
            }
        }
        return Collections.unmodifiableSortedSet(keys);
    }

    @Override
    public void leafWritten(long sectionKey, int block, CellState before, CellState after) {
        double kelvin;
        if (before.material() != after.material()
                || Double.doubleToRawLongBits(before.mass()) != Double.doubleToRawLongBits(after.mass())) {
            kelvin = Double.POSITIVE_INFINITY;
        } else if (after.material() == MaterialRegistry.VACUUM || after.mass() == 0) {
            return;
        } else {
            double capacity = after.mass() * materials.get(after.material()).minSpecificHeat();
            kelvin = Math.abs(after.enthalpy() - before.enthalpy()) / capacity;
        }
        if (pendingChange >= 0 && pendingKey == sectionKey) {
            pendingChange = Math.max(pendingChange, kelvin);
        } else {
            flush();
            pendingKey = sectionKey;
            pendingChange = kelvin;
        }
    }

    private void flush() {
        if (pendingChange >= 0) {
            change.merge(pendingKey, pendingChange, Math::max);
            pendingChange = -1;
        }
    }

    /**
     * Ends a step: sections whose matter changed faster than the calm rate wake or stay awake, and awake
     * sections that have been calm for long enough fall asleep. Call it only after a step in which the
     * thermal models ran.
     *
     * @param dtSeconds the simulated time the step covered, in seconds
     */
    public void endStep(double dtSeconds) {
        if (!(dtSeconds > 0) || !Double.isFinite(dtSeconds)) {
            throw new IllegalArgumentException("time step must be positive: " + dtSeconds);
        }
        flush();
        double limit = calmRate * dtSeconds;
        for (Iterator<Map.Entry<Long, Integer>> it = awake.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Integer> e = it.next();
            Double changed = change.get(e.getKey());
            if (changed != null && changed > limit) {
                e.setValue(0);
            } else if (e.getValue() + 1 >= calmSteps) {
                it.remove();
            } else {
                e.setValue(e.getValue() + 1);
            }
        }
        for (Map.Entry<Long, Double> e : change.entrySet()) {
            if (e.getValue() > limit) {
                awake.putIfAbsent(e.getKey(), 0);
            }
        }
        change.clear();
    }
}
