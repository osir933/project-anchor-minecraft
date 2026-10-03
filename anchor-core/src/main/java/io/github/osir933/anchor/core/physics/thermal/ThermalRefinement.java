package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.matter.ThermalState;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.CoarseningRule;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.RefinedBlock;
import io.github.osir933.anchor.core.world.Section;
import io.github.osir933.anchor.core.world.TransitionReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Refines blocks where temperatures change too steeply across them for whole blocks to follow, and merges
 * their cells back once they have evened out.
 *
 * <p>The thermal models give each cell one temperature, and heat leaves a cell through a face as if from its
 * centre, half a cell away. Where much heat flows through a face, the matter just inside it is much hotter or
 * colder than the centre, which a whole block cannot show: heat taken in at one face warms the whole block at
 * once, so the face heats too slowly and the far side too soon. The models report the temperature drop each
 * cell needs between its centre and each of its faces, the heat flowing through the face times the cell's
 * resistance to it, {@code e/(2k)} per unit area for a cell of edge {@code e} and conductivity {@code k}. A
 * cell whose drop exceeds {@link Settings#refineDropK} is split into eight, one level a step, until the drops
 * are small or the cells reach {@link Settings#maxLevel}. Gas is never split, because convection rather than
 * its cell size sets how it passes heat on, and neither are blocks whose temperature is held from outside,
 * such as heat sources. Liquid reports no drop where it moves, for the same reason as gas.
 *
 * <p>The cells of a refined block merge back, eight at a time, once every drop reported in the block is below
 * a quarter of the split drop and the eight agree to within {@link Settings#mergeSpreadK} in temperature, in
 * the same phase and at the same stage of any phase change, so a melting front keeps its cells. A merged
 * cell's drops are about twice those of the cells it came from, which keeps it well below the split drop, so
 * cells do not split and merge in turn.
 *
 * <p>Each step splits the reported cells with the largest drops first, up to a number of splits and a budget
 * of leaves, and looks at a few refined blocks in the scope for merging, taking turns between them by the
 * world's tick. Decisions depend only on the world and on what the models reported, so a replay makes the same
 * ones.
 */
public final class ThermalRefinement {

    /** Reported drops of refined blocks must be below the split drop divided by this before cells merge. */
    static final double MERGE_DROP_DIVISOR = 4.0;

    /**
     * How refinement behaves.
     *
     * @param refineDropK the temperature drop inside a cell, in kelvin, above which the cell is split
     * @param mergeSpreadK how far apart in temperature, in kelvin, eight cells may be and still merge
     * @param maxLevel the finest level cells are split to; 0 turns refinement off
     * @param maxLeaves the most cells refined blocks may hold across the world before splitting stops
     * @param maxSplitsPerStep the most cells split in one step
     * @param maxBlocksCheckedPerStep the most refined blocks looked at for merging in one step
     */
    public record Settings(double refineDropK, double mergeSpreadK, int maxLevel, int maxLeaves,
            int maxSplitsPerStep, int maxBlocksCheckedPerStep) {

        /**
         * Split at 50 K, merge within 5 K, down to 25 cm cells, at most 16 384 cells. An ice block beside lava
         * then melts in 6 game minutes, against 16 with whole blocks; 12.5 cm cells take it to 5.5 for three
         * times the cost.
         */
        public static final Settings DEFAULT = new Settings(50.0, 5.0, 2, 1 << 14, 256, 64);

        /** No refinement at all. */
        public static final Settings OFF = new Settings(50.0, 5.0, 0, 0, 0, 0);

        /**
         * Validates the settings.
         *
         * @param refineDropK the split drop
         * @param mergeSpreadK the merge spread
         * @param maxLevel the finest level
         * @param maxLeaves the leaf budget
         * @param maxSplitsPerStep the split limit
         * @param maxBlocksCheckedPerStep the merge check limit
         */
        public Settings {
            if (!(refineDropK > 0) || !Double.isFinite(refineDropK)) {
                throw new IllegalArgumentException("split drop must be positive: " + refineDropK);
            }
            if (!(mergeSpreadK >= 0) || !(mergeSpreadK < refineDropK)) {
                throw new IllegalArgumentException("merge spread must be at least 0 and below the split drop: "
                        + mergeSpreadK);
            }
            if (maxLevel < 0 || maxLevel > CellId.MAX_LEVEL) {
                throw new IllegalArgumentException("finest level must be in [0, " + CellId.MAX_LEVEL + "]: "
                        + maxLevel);
            }
            if (maxLeaves < 0 || maxSplitsPerStep < 0 || maxBlocksCheckedPerStep < 0) {
                throw new IllegalArgumentException("limits must not be negative");
            }
        }

        /**
         * Returns these settings with a different finest level.
         *
         * @param level the finest level; 0 turns refinement off
         * @return the new settings
         */
        public Settings withMaxLevel(int level) {
            return new Settings(refineDropK, mergeSpreadK, level, maxLeaves, maxSplitsPerStep,
                    maxBlocksCheckedPerStep);
        }

        /**
         * Returns these settings with a different leaf budget.
         *
         * @param leaves the most cells refined blocks may hold
         * @return the new settings
         */
        public Settings withMaxLeaves(int leaves) {
            return new Settings(refineDropK, mergeSpreadK, maxLevel, leaves, maxSplitsPerStep,
                    maxBlocksCheckedPerStep);
        }

        /**
         * Tells whether cells are ever split.
         *
         * @return {@code true} if refinement is on
         */
        public boolean enabled() {
            return maxLevel > 0 && maxLeaves > 0 && maxSplitsPerStep > 0;
        }
    }

    /**
     * What one update did.
     *
     * @param split cells split into eight
     * @param merged groups of eight cells merged into one
     * @param budgetReached whether splitting stopped because the leaf budget was reached
     */
    public record Report(int split, int merged, boolean budgetReached) {

        /** An update that did nothing. */
        public static final Report NOTHING = new Report(0, 0, false);
    }

    private final Settings settings;
    private final CoarseningRule rule;
    /** Cells to split, with the largest drop reported for each since the last update. */
    private final TreeMap<CellId, Double> splits = new TreeMap<>();
    /** Refined blocks, with the largest drop reported for any of their cells since the last update. */
    private final TreeMap<GridPos, Double> blockDrops = new TreeMap<>();
    /** Whether the reports since the last update cover a whole step of the models. */
    private boolean complete;
    private Report last = Report.NOTHING;

    /**
     * Creates the refinement.
     *
     * @param settings how it behaves
     */
    public ThermalRefinement(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.rule = new CoarseningRule(settings.mergeSpreadK(), CoarseningRule.DEFAULT.maxTransitionFractionSpread(),
                CoarseningRule.DEFAULT.maxRelativeDensitySpread());
    }

    /**
     * Returns the settings.
     *
     * @return the settings
     */
    public Settings settings() {
        return settings;
    }

    /**
     * Returns the drop above which a cell should be split, so models need not report smaller ones for whole
     * blocks.
     *
     * @return the split drop in kelvin, or positive infinity if refinement is off
     */
    public double threshold() {
        return settings.enabled() ? settings.refineDropK() : Double.POSITIVE_INFINITY;
    }

    /**
     * Reports the largest temperature drop a cell needs between its centre and one of its faces. Models call
     * this during their step for cells whose drop exceeds {@link #threshold()}; cells already at the finest
     * level are ignored.
     *
     * @param cell the cell: a whole block, or a leaf of a refined block
     * @param dropK the drop in kelvin
     */
    public void suggestSplit(CellId cell, double dropK) {
        if (dropK > threshold() && cell.level() < settings.maxLevel()) {
            splits.merge(cell, dropK, Math::max);
        }
    }

    /**
     * Reports the largest temperature drop any cell of a refined block needs between its centre and one of its
     * faces, however small. Models call this during their step for every refined block they simulate; it
     * decides whether the block's cells may merge.
     *
     * @param block the refined block
     * @param dropK the drop in kelvin
     */
    public void reportBlockDrop(GridPos block, double dropK) {
        blockDrops.merge(block, dropK, Math::max);
    }

    /**
     * Tells the refinement whether the models all ran in the step that just ended, so their reports cover it.
     * Cells only merge after such a step, so that a model that was put off cannot hide a steep drop.
     *
     * @param modelsRan whether every thermal model ran
     */
    public void endStep(boolean modelsRan) {
        complete = modelsRan;
    }

    /**
     * Returns what the last update did.
     *
     * @return the report
     */
    public Report lastReport() {
        return last;
    }

    /**
     * Splits the cells reported since the last update and merges cells in the scope that have evened out.
     * Call it before a step, with that step's scope.
     *
     * @param world the world
     * @param scope the sections heat runs in this step
     * @param held blocks whose temperature is held from outside, which are never split
     * @return what was done
     */
    public Report update(PhysicalWorld world, SortedSet<Long> scope, Predicate<GridPos> held) {
        if (!settings.enabled()) {
            splits.clear();
            blockDrops.clear();
            last = Report.NOTHING;
            return last;
        }
        int merged = complete ? merge(world, scope) : 0;
        boolean[] budgetReached = new boolean[1];
        int split = split(world, held, budgetReached);
        splits.clear();
        blockDrops.clear();
        complete = false;
        last = new Report(split, merged, budgetReached[0]);
        return last;
    }

    // ---- splitting ----

    private int split(PhysicalWorld world, Predicate<GridPos> held, boolean[] budgetReached) {
        if (splits.isEmpty()) {
            return 0;
        }
        List<Map.Entry<CellId, Double>> order = new ArrayList<>(splits.entrySet());
        order.sort((a, b) -> {
            int c = Double.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        MaterialRegistry registry = world.materials();
        int done = 0;
        for (Map.Entry<CellId, Double> e : order) {
            if (done >= settings.maxSplitsPerStep()) {
                break;
            }
            CellId cell = e.getKey();
            GridPos pos = cell.block();
            if (world.section(pos.sectionKey()) == null || held.test(pos) || !cell.equals(world.leafCovering(cell))
                    || !splittable(registry, world.readLeaf(cell))) {
                continue;
            }
            if ((long) world.leafCount() + (cell.level() == 0 ? 8 : 7) > settings.maxLeaves()) {
                budgetReached[0] = true;
                break;
            }
            TransitionReport report = world.refine(cell.child(0));
            if (report.applied()) {
                done++;
            } else if (report.outcome() == TransitionReport.Outcome.REFUSED) {
                budgetReached[0] = true;
                break;
            }
        }
        return done;
    }

    /** Tells whether a cell's matter may be split: it has mass and is not mostly gas. */
    private static boolean splittable(MaterialRegistry registry, CellState state) {
        if (state.material() == MaterialRegistry.VACUUM || state.mass() == 0) {
            return false;
        }
        Material m = registry.get(state.material());
        return m.dominantPhase(m.stateFor(state.specificEnthalpy())) != Phase.GAS;
    }

    // ---- merging ----

    private int merge(PhysicalWorld world, SortedSet<Long> scope) {
        List<GridPos> refined = new ArrayList<>();
        for (long key : scope) {
            Section s = world.section(key);
            if (s != null) {
                for (RefinedBlock block : s.refinedBlocks()) {
                    refined.add(block.pos());
                }
            }
        }
        int n = refined.size();
        if (n == 0) {
            return 0;
        }
        int checks = Math.min(n, settings.maxBlocksCheckedPerStep());
        int start = (int) Long.remainderUnsigned(world.tick() * (long) checks, n);
        double calm = settings.refineDropK() / MERGE_DROP_DIVISOR;
        int merged = 0;
        for (int i = 0; i < checks; i++) {
            GridPos pos = refined.get((start + i) % n);
            Double drop = blockDrops.get(pos);
            if (drop == null || drop < calm) {
                merged += mergeBlock(world, pos);
            }
        }
        return merged;
    }

    /** Merges every group of eight sibling leaves of a block that is even enough, and returns how many. */
    private int mergeBlock(PhysicalWorld world, GridPos pos) {
        RefinedBlock block = world.refinedBlock(pos);
        if (block == null) {
            return 0;
        }
        List<CellId> leaves = new ArrayList<>(block.leafCount());
        List<CellState> states = new ArrayList<>(block.leafCount());
        block.forEachLeaf((cell, state) -> {
            leaves.add(cell);
            states.add(state.copy());
        });
        MaterialRegistry registry = world.materials();
        int merged = 0;
        int i = 0;
        while (i + 8 <= leaves.size()) {
            CellId first = leaves.get(i);
            if (first.level() == 0 || first.octant() != 0 || !siblings(leaves, i)) {
                i++;
                continue;
            }
            if (even(registry, states, i) && world.coarsen(first.parent(), rule).applied()) {
                merged++;
            }
            i += 8;
        }
        return merged;
    }

    /** Tells whether the eight leaves from an index are the eight children of one cell. */
    private static boolean siblings(List<CellId> leaves, int from) {
        CellId first = leaves.get(from);
        CellId last = leaves.get(from + 7);
        return last.level() == first.level() && last.octant() == 7 && last.parent().equals(first.parent());
    }

    /** Tells whether eight leaves are even enough to merge, as the world's coarsening check would find. */
    private boolean even(MaterialRegistry registry, List<CellState> states, int from) {
        CellState first = states.get(from);
        for (int k = from + 1; k < from + 8; k++) {
            CellState s = states.get(k);
            if (s.material() != first.material() || s.owner() != first.owner()) {
                return false;
            }
        }
        if (first.material() == MaterialRegistry.VACUUM) {
            return true;
        }
        Material m = registry.get(first.material());
        ThermalState reference = null;
        double minT = Double.POSITIVE_INFINITY;
        double maxT = Double.NEGATIVE_INFINITY;
        double minF = Double.POSITIVE_INFINITY;
        double maxF = Double.NEGATIVE_INFINITY;
        for (int k = from; k < from + 8; k++) {
            CellState s = states.get(k);
            if (s.mass() == 0) {
                return false;
            }
            ThermalState t = m.stateFor(s.specificEnthalpy());
            if (reference == null) {
                reference = t;
            } else if (t.region() != reference.region()) {
                return false;
            }
            minT = Math.min(minT, t.temperatureK());
            maxT = Math.max(maxT, t.temperatureK());
            minF = Math.min(minF, t.transitionFraction());
            maxF = Math.max(maxF, t.transitionFraction());
        }
        return maxT - minT <= rule.maxTemperatureSpreadK() && maxF - minF <= rule.maxTransitionFractionSpread();
    }
}
