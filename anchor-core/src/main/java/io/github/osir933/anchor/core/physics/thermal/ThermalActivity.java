package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.RefinedBlock;
import io.github.osir933.anchor.core.world.Section;
import java.util.Arrays;
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
 * <p>The rate of a block is the net heat it gained or lost over the step divided by its heat capacity, so a
 * block of ice that absorbs latent heat at a constant 0 °C counts as changing, while a block that a flame
 * heats exactly as fast as its surroundings cool it counts as calm, and can sleep in that steady state. The
 * capacity uses the material's lowest specific heat, which errs towards staying awake. A model write that
 * changes a cell's material or mass always counts as fast. A refined block's rate is the net changes of its
 * cells, added up regardless of sign, divided by the whole block's capacity: the average change of its matter,
 * so heat moving inside the block counts but refining a block does not change when its section sleeps.
 *
 * <p>Heat a model brings in from outside the world and {@linkplain #forced declares as forced}, such as the
 * sunlight on the ground, does not count: the sun warms the ground every day, and a section that only the sky
 * changes can sleep while it does. Only heat moving within the world keeps a section awake.
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
    /** What the models wrote in each section since the last step ended. */
    private final TreeMap<Long, Written> written = new TreeMap<>();
    /** Models write section by section, so the last section written is kept at hand. */
    private long lastKey;
    private Written last;

    /** A written leaf, its enthalpy before its first write in the step, and the forced heat it took since. */
    private static final class LeafStart {
        final CellId leaf;
        final double enthalpy;
        double forced;

        LeafStart(CellId leaf, double enthalpy) {
            this.leaf = leaf;
            this.enthalpy = enthalpy;
        }
    }

    /** The writes to one section during a step. */
    private static final class Written {
        /** Each written block's enthalpy before its first write in the step. */
        final double[] start = new double[SectionPos.BLOCKS];
        /** The heat from outside the world each written block took since its first write in the step. */
        final double[] forced = new double[SectionPos.BLOCKS];
        /** Which blocks were written, one bit each. */
        final long[] touched = new long[SectionPos.BLOCKS / 64];
        /**
         * Each written leaf of a refined block, with its enthalpy before its first write in the step, by a key
         * that orders leaves block by block in cell order and compares quickly.
         */
        final TreeMap<Long, LeafStart> leafStart = new TreeMap<>();
        boolean any;
        /** Set when a write changed some cell's material or mass. */
        boolean structural;

        void reset() {
            Arrays.fill(touched, 0L);
            leafStart.clear();
            any = false;
            structural = false;
        }
    }

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
        awake.remove(sectionKey);
        written.remove(sectionKey);
        last = null;
    }

    /** Puts every section to sleep. */
    public void clear() {
        awake.clear();
        written.clear();
        last = null;
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
    public void leafWritten(long sectionKey, int block, CellId leaf, CellState before, CellState after) {
        Written w = last;
        if (w == null || lastKey != sectionKey) {
            w = written.computeIfAbsent(sectionKey, k -> new Written());
            last = w;
            lastKey = sectionKey;
        }
        w.any = true;
        if (before.material() != after.material()
                || Double.doubleToRawLongBits(before.mass()) != Double.doubleToRawLongBits(after.mass())) {
            w.structural = true;
            return;
        }
        if (after.material() == MaterialRegistry.VACUUM || after.mass() == 0) {
            return;
        }
        if (leaf != null) {
            long key = leafKey(block, leaf);
            if (!w.leafStart.containsKey(key)) {
                w.leafStart.put(key, new LeafStart(leaf, before.enthalpy()));
            }
            return;
        }
        int word = block >>> 6;
        long bit = 1L << (block & 63);
        if ((w.touched[word] & bit) == 0) {
            w.touched[word] |= bit;
            w.start[block] = before.enthalpy();
            w.forced[block] = 0;
        }
    }

    /**
     * Declares that some of the heat a model just wrote into a block that is not refined came from outside the
     * world, such as sunlight, so that it does not count as change. Call it after the write.
     *
     * @param sectionKey the packed section position
     * @param block the block's index in the section
     * @param joules the heat from outside, negative for heat the block lost to the outside
     */
    public void forced(long sectionKey, int block, double joules) {
        Written w = written.get(sectionKey);
        if (w != null && (w.touched[block >>> 6] & (1L << (block & 63))) != 0) {
            w.forced[block] += joules;
        }
    }

    /**
     * Declares that some of the heat a model just wrote into a leaf of a refined block came from outside the
     * world, so that it does not count as change. Call it after the write.
     *
     * @param sectionKey the packed section position
     * @param block the block's index in the section
     * @param leaf the leaf
     * @param joules the heat from outside, negative for heat the leaf lost to the outside
     */
    public void forced(long sectionKey, int block, CellId leaf, double joules) {
        Written w = written.get(sectionKey);
        LeafStart start = w == null ? null : w.leafStart.get(leafKey(block, leaf));
        if (start != null) {
            start.forced += joules;
        }
    }

    /** Returns a key that orders leaves block by block in cell order and compares quickly. */
    private static long leafKey(int block, CellId leaf) {
        return ((long) block << 34) | ((long) leaf.mortonCode() << 4) | leaf.level();
    }

    /**
     * Returns the largest temperature-equivalent net change of any block a step wrote in a section, counting
     * a refined block's cells together.
     */
    private double change(Section section, Written w) {
        if (w.structural) {
            return Double.POSITIVE_INFINITY;
        }
        double largest = 0;
        for (int word = 0; word < w.touched.length; word++) {
            long bits = w.touched[word];
            while (bits != 0) {
                int b = (word << 6) | Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                if (section.isRefined(b)) {
                    // Refined since the write, so its matter moved into leaves: count it as changing.
                    return Double.POSITIVE_INFINITY;
                }
                int material = section.material(b);
                double mass = section.mass(b);
                if (material == MaterialRegistry.VACUUM || mass == 0) {
                    continue;
                }
                double capacity = mass * materials.get(material).minSpecificHeat();
                largest = Math.max(largest, Math.abs(section.enthalpy(b) - w.start[b] - w.forced[b]) / capacity);
            }
        }
        // Leaves come block by block, in cell order.
        int block = -1;
        RefinedBlock refined = null;
        double moved = 0;
        for (LeafStart start : w.leafStart.values()) {
            int b = start.leaf.block().indexInSection();
            if (b != block) {
                largest = Math.max(largest, blockChange(section, block, moved));
                block = b;
                refined = section.refinedBlock(b);
                moved = 0;
            }
            double now = refined == null ? Double.NaN : refined.leafEnthalpy(start.leaf);
            if (Double.isNaN(now)) {
                // Merged or replaced since the write.
                return Double.POSITIVE_INFINITY;
            }
            moved += Math.abs(now - start.enthalpy - start.forced);
        }
        return Math.max(largest, blockChange(section, block, moved));
    }

    /** Returns the change of a refined block whose cells' net changes add up to some joules. */
    private double blockChange(Section section, int block, double joules) {
        if (block < 0 || joules == 0) {
            return 0;
        }
        int material = section.material(block);
        double mass = section.mass(block);
        if (material == MaterialRegistry.VACUUM || mass == 0) {
            return 0;
        }
        return joules / (mass * materials.get(material).minSpecificHeat());
    }

    /**
     * Ends a step: sections whose matter changed faster than the calm rate wake or stay awake, and awake
     * sections that have been calm for long enough fall asleep. Call it only after a step in which the
     * thermal models ran.
     *
     * @param world the world the models wrote to
     * @param dtSeconds the simulated time the step covered, in seconds
     */
    public void endStep(PhysicalWorld world, double dtSeconds) {
        if (!(dtSeconds > 0) || !Double.isFinite(dtSeconds)) {
            throw new IllegalArgumentException("time step must be positive: " + dtSeconds);
        }
        double limit = calmRate * dtSeconds;
        TreeMap<Long, Double> change = new TreeMap<>();
        for (Iterator<Map.Entry<Long, Written>> it = written.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Written> e = it.next();
            Written w = e.getValue();
            Section section = world.section(e.getKey());
            if (!w.any || section == null) {
                it.remove();
                continue;
            }
            change.put(e.getKey(), change(section, w));
            w.reset();
        }
        last = null;
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
    }
}
