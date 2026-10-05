package io.github.osir933.anchor.core.host;

import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.Mechanics;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.matter.ThermalState;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.model.Scheduler;
import io.github.osir933.anchor.core.model.TickReport;
import io.github.osir933.anchor.core.physics.structure.Contact;
import io.github.osir933.anchor.core.physics.structure.Frame;
import io.github.osir933.anchor.core.physics.structure.Shape;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis;
import io.github.osir933.anchor.core.physics.thermal.AtmosphereModel;
import io.github.osir933.anchor.core.physics.thermal.ConductionModel;
import io.github.osir933.anchor.core.physics.thermal.HeatSourceModel;
import io.github.osir933.anchor.core.physics.thermal.Incandescence;
import io.github.osir933.anchor.core.physics.thermal.RadiationModel;
import io.github.osir933.anchor.core.physics.thermal.Sky;
import io.github.osir933.anchor.core.physics.thermal.SkyModel;
import io.github.osir933.anchor.core.physics.thermal.SkyPhysics;
import io.github.osir933.anchor.core.physics.thermal.ThermalActivity;
import io.github.osir933.anchor.core.physics.thermal.ThermalRefinement;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.BlockCopy;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.RefinedBlock;
import io.github.osir933.anchor.core.world.Section;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;

/**
 * A physical world that mirrors the parts of a host game's world that matter right now, and runs heat in it.
 *
 * <p>The host refers to its blocks by integer ids of its own, such as Minecraft's block state ids, and says
 * once per id what such a block is made of, through the function given to the constructor. It then:
 * <ul>
 *   <li>{@linkplain #importSection imports} sections it wants simulated, with the temperature of their
 *   surroundings. Blocks start at that temperature, except those with a temperature of their own such as
 *   lava, and blocks shown in a phase start in it, so the ice of a warm biome starts at its melting point
 *   rather than as water;</li>
 *   <li>{@linkplain #reconcile reports} every block that changes. A change that leaves the same matter in the
 *   block, such as water the engine froze now shown as ice, keeps the block's physical state; anything else
 *   replaces it, declared in the conservation ledger like any edit from outside;</li>
 *   <li>calls {@link #tick} once per simulated step and shows the {@linkplain PhaseChange phase changes} it
 *   returns, reporting each new block back.</li>
 * </ul>
 *
 * <p>Imported sections start asleep unless something in them is out of balance: a heat source, blocks at
 * different temperatures, or a neighbour with a different climate. A change wakes its section, and heat runs
 * only where {@link ThermalActivity} keeps sections awake, so the cost follows what is happening rather than
 * how much of the world is loaded.
 *
 * <p>Where temperatures change too steeply across a block for it to follow them as a whole, as under a face
 * that lava glows on, {@link ThermalRefinement} splits it into smaller cells, and merges them back once they
 * have evened out. The host never sees the cells: it reads, and saves, each block's totals.
 *
 * <p>A host whose world has a sky {@linkplain #setSky sets it} every step, and says where the open sky begins in
 * each column ({@link #setSkyHeight}); the {@link SkyModel} then warms the ground by day and cools it by night,
 * in sleeping sections too.
 */
public final class HostedWorld {

    /** Mass may differ by this fraction before a block counts as holding a different amount of matter. */
    static final double MASS_TOLERANCE = 0.1;

    /** Temperature differences below this do not wake a newly imported section, in kelvin. */
    private static final double BALANCE_TOLERANCE_K = 1e-6;

    /** Points this far up a block or higher, as a fraction of its height, read the temperature of its top. */
    private static final double SURFACE_DEPTH = 15.0 / 16.0;

    /** Host ids from zero up to this are looked up in an array, larger or negative ones in a map. */
    private static final int ARRAY_IDS = 1 << 20;

    /** Blocks with less of their matter solid than this carry no load. */
    static final double MIN_SOLID = 1e-3;

    /**
     * A built block's structure is checked again once heat has changed its strength or stiffness by this fraction
     * since it was last checked.
     */
    static final double STRENGTH_DRIFT = 0.02;

    /** Each simulated section looks for built blocks that heat has weakened or strengthened once in this many steps. */
    static final int STRENGTH_CHECK_STEPS = 8;

    private static final SortedSet<Long> NOWHERE = Collections.unmodifiableSortedSet(new TreeSet<>());

    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * How a hosted world runs.
     *
     * @param tickSeconds simulated seconds per {@link #tick}
     * @param budgetPerTick work units the scheduler may spend per tick
     * @param relaxationSeconds how quickly gas returns to its section's climate, as a time constant
     * @param defaultEnvironmentK the climate of sections imported without one, in kelvin
     * @param calmRate the rate of change below which a section counts as calm, in kelvin per second
     * @param calmSteps how many calm steps in a row put a section to sleep
     * @param auditInterval audit conservation every this many ticks
     * @param refinement when blocks are split into smaller cells, and merged back
     */
    public record Settings(double tickSeconds, long budgetPerTick, double relaxationSeconds,
            double defaultEnvironmentK, double calmRate, int calmSteps, int auditInterval,
            ThermalRefinement.Settings refinement) {

        /**
         * Validates the settings.
         *
         * @param tickSeconds the step length
         * @param budgetPerTick the work budget
         * @param relaxationSeconds the climate time constant
         * @param defaultEnvironmentK the default climate
         * @param calmRate the calm rate
         * @param calmSteps the calm steps
         * @param auditInterval the audit interval
         * @param refinement the refinement settings
         */
        public Settings {
            Objects.requireNonNull(refinement, "refinement");
            if (!(tickSeconds > 0) || !Double.isFinite(tickSeconds)) {
                throw new IllegalArgumentException("tick length must be positive: " + tickSeconds);
            }
            if (!(defaultEnvironmentK > 0) || !Double.isFinite(defaultEnvironmentK)) {
                throw new IllegalArgumentException("default climate must be positive: " + defaultEnvironmentK);
            }
        }

        /**
         * Returns settings for a world where a step covers 3.6 simulated seconds, which matches Minecraft's
         * day of 24 000 ticks to 24 hours when every game tick is a step.
         *
         * @return the default settings
         */
        public static Settings defaults() {
            return new Settings(3.6, 50_000_000L, AtmosphereModel.DEFAULT_RELAXATION_SECONDS, 288.15,
                    ThermalActivity.DEFAULT_CALM_RATE, ThermalActivity.DEFAULT_CALM_STEPS, 20,
                    ThermalRefinement.Settings.DEFAULT);
        }

        /**
         * Returns these settings with a different step length.
         *
         * @param seconds simulated seconds per tick
         * @return the new settings
         */
        public Settings withTickSeconds(double seconds) {
            return new Settings(seconds, budgetPerTick, relaxationSeconds, defaultEnvironmentK, calmRate, calmSteps,
                    auditInterval, refinement);
        }

        /**
         * Returns these settings with different refinement.
         *
         * @param settings when blocks are split into smaller cells; {@link ThermalRefinement.Settings#OFF} for
         *     never
         * @return the new settings
         */
        public Settings withRefinement(ThermalRefinement.Settings settings) {
            return new Settings(tickSeconds, budgetPerTick, relaxationSeconds, defaultEnvironmentK, calmRate,
                    calmSteps, auditInterval, settings);
        }
    }

    /**
     * What one tick did.
     *
     * @param report the scheduler's report
     * @param phaseChanges blocks the host should now show differently, in block order
     * @param simulatedSections how many sections heat ran in
     * @param awakeSections how many sections are awake after the tick
     */
    public record TickResult(TickReport report, List<PhaseChange> phaseChanges, int simulatedSections,
            int awakeSections) {

        /**
         * Takes an unmodifiable copy of the phase changes.
         *
         * @param report the report
         * @param phaseChanges the changes
         * @param simulatedSections the simulated sections
         * @param awakeSections the awake sections
         */
        public TickResult {
            phaseChanges = List.copyOf(phaseChanges);
        }
    }

    /**
     * Everything the engine knows about one block, for inspectors and thermometers.
     *
     * @param pos the block
     * @param material the material id
     * @param materialName the material's display name
     * @param massKg the mass in kilograms
     * @param enthalpyJ the enthalpy in joules
     * @param state the thermal state, or {@code null} for an empty block
     * @param phase the phase most of the matter is in, or {@code null} for an empty block
     * @param provenance where the state came from
     * @param refined whether the block is refined into smaller cells; the other values are then totals
     * @param coolestK the temperature of the block's coolest cell, in kelvin: its temperature unless it is
     *     refined, or {@link Double#NaN} if it holds no matter
     * @param hottestK the temperature of the block's hottest cell, in kelvin, likewise
     * @param awake whether the block's section is awake
     * @param simulated whether heat ran in the block's section in the last tick
     * @param environmentK the climate of the block's section, in kelvin
     * @param source the block's heat source, or {@code null}
     * @param appearance how the host describes the block
     * @param surfaceK the temperature of the block's top where it lies open to the sky, which the sun and the sky
     *     warm and cool faster than the block as a whole, in kelvin; {@link Double#NaN} for a block that is not
     *     such a surface
     * @param sunlightW the sunlight the block takes in now, in watts per square metre of its top
     */
    public record Inspection(GridPos pos, String material, String materialName, double massKg, double enthalpyJ,
            ThermalState state, Phase phase, Provenance provenance, boolean refined, double coolestK,
            double hottestK, boolean awake, boolean simulated, double environmentK, HeatSourceModel.Source source,
            BlockAppearance appearance, double surfaceK, double sunlightW) {

        /**
         * Returns the temperature.
         *
         * @return the temperature in kelvin, or {@link Double#NaN} for an empty block
         */
        public double temperatureK() {
            return state == null ? Double.NaN : state.temperatureK();
        }
    }

    /**
     * A summary of the hosted world.
     *
     * @param sections imported sections
     * @param awakeSections awake sections
     * @param simulatedSections sections heat ran in during the last tick
     * @param sources heat sources
     * @param radiatingFaces block faces that radiated the last time radiation ran
     * @param refinedBlocks blocks split into smaller cells
     * @param refinedCells the cells those blocks are split into
     * @param tick ticks simulated
     * @param simulatedSeconds simulated time in seconds
     * @param phaseChanges phase changes handed to the host so far
     * @param reconciled block changes from the host that changed something
     * @param conserved whether the last conservation audit balanced
     * @param skySurfaces surfaces open to the sky whose exchange with it the sky balances
     * @param sunlightW the sunlight on level ground under the open sky now, in W/m², or {@link Double#NaN} if the
     *     world has no sky
     */
    public record Status(int sections, int awakeSections, int simulatedSections, int sources, int radiatingFaces,
            int refinedBlocks, int refinedCells, long tick, double simulatedSeconds, long phaseChanges,
            long reconciled, boolean conserved, int skySurfaces, double sunlightW) {
    }

    /**
     * What {@linkplain #restore restoring} a snapshot did.
     *
     * @param blocks the blocks in the box
     * @param changed how many of them changed
     * @param afresh how many saved blocks started afresh instead, because their matter no longer fits the host's
     *     block there or is no longer registered
     */
    public record Restored(int blocks, int changed, int afresh) {
    }

    /**
     * What {@linkplain #settle settling} a structure's analysis into the world did.
     *
     * @param stale whether a block the survey looked at changed before the analysis came back, so that nothing was
     *     settled and the structure waits to be checked again
     * @param cracks the joints that cracked, in the order they gave way
     * @param falling the blocks left with nothing to hold them up, in position order, for the host to let fall
     */
    public record Settled(boolean stale, List<StructuralAnalysis.Crack> cracks, List<GridPos> falling) {

        /**
         * Takes unmodifiable copies of the lists.
         *
         * @param stale whether the survey was stale
         * @param cracks the cracks
         * @param falling the falling blocks
         */
        public Settled {
            cracks = List.copyOf(cracks);
            falling = List.copyOf(falling);
        }
    }

    /** The host's ids for the blocks of one imported section. */
    private static final class Hosted {
        private final int uniform;
        private int[] dense;
        /** Blocks whose appearance may need changing when their phase changes. */
        int presentable;
        /** The section version the last phase-change scan saw. */
        long scannedVersion = Long.MIN_VALUE;
        /** The section version right after it was imported. */
        long importedVersion;
        /** Whether some blocks were restored from a snapshot, so the section differs from a fresh import. */
        boolean restored;
        /** Each block's {@linkplain StructureFlags structural flags}, or {@code null} while no block has any. */
        byte[] structure;
        /** Grows whenever a block's structural flags change. */
        long structureVersion;
        /** Built blocks whose structure has to be checked, or {@code null} while there are none. */
        BitSet unchecked;
        /**
         * Each block's temperature in kelvin when its structure was last checked, NaN if it has not been since it
         * last changed; {@code null} while no block has been checked.
         */
        float[] checkedK;
        /** Each block's solid fraction when its structure was last checked. */
        float[] checkedSolid;

        Hosted(int uniform) {
            this.uniform = uniform;
        }

        void checked(int index, double temperatureK, double solidFraction) {
            if (checkedK == null) {
                checkedK = new float[SectionPos.BLOCKS];
                checkedSolid = new float[SectionPos.BLOCKS];
                Arrays.fill(checkedK, Float.NaN);
            }
            checkedK[index] = (float) temperatureK;
            checkedSolid[index] = (float) solidFraction;
        }

        void forgetChecked(int index) {
            if (checkedK != null) {
                checkedK[index] = Float.NaN;
            }
        }

        int flags(int index) {
            return structure == null ? 0 : structure[index];
        }

        void setFlags(int index, int flags) {
            if (flags(index) == flags) {
                return;
            }
            if (structure == null) {
                structure = new byte[SectionPos.BLOCKS];
            }
            structure[index] = (byte) flags;
            structureVersion++;
        }

        int get(int index) {
            return dense == null ? uniform : dense[index];
        }

        void set(int index, int id) {
            if (dense == null) {
                if (id == uniform) {
                    return;
                }
                dense = new int[SectionPos.BLOCKS];
                Arrays.fill(dense, uniform);
            }
            dense[index] = id;
        }
    }

    /**
     * A host id's appearance, with the registry indices of its material and of the material of its frame, or -1 if
     * its own matter carries its loads.
     */
    private record Resolved(BlockAppearance appearance, int material, int frame) {
    }

    /** How a block carries loads: the mechanics of what carries them, at its temperature and solid fraction. */
    private record Bearing(Mechanics mechanics, double temperatureK, double solidFraction, double massKg) {
    }

    private final PhysicalWorld world;
    private final IntFunction<BlockAppearance> appearances;
    private final Settings settings;
    private final Scheduler scheduler;
    private final HeatSourceModel sources = new HeatSourceModel();
    private final AtmosphereModel atmosphere;
    private final SkyModel sky;
    private final RadiationModel radiation;
    private final ThermalRefinement refinement;
    private final ThermalActivity activity;
    private final TreeMap<Long, Hosted> hosted = new TreeMap<>();
    /** The sections with built blocks whose structure has to be checked. */
    private final TreeSet<Long> uncheckedSections = new TreeSet<>();
    private Resolved[] resolvedById = new Resolved[0];
    private final TreeMap<Integer, Resolved> resolvedByLargeId = new TreeMap<>();
    private SortedSet<Long> lastScope = NOWHERE;
    /** For each material's registry index, its specific enthalpy where things begin to glow, or NaN if not known. */
    private double[] glowEnthalpies = new double[0];
    private long phaseChanges;
    private long reconciled;
    private boolean conserved = true;

    /**
     * Creates a hosted world around an empty physical world.
     *
     * @param world the physical world; the hosted world owns it from now on, and nothing else should edit it
     * @param appearances what the host's block with each id is made of; every material it names must be
     *     registered in the world
     * @param settings how the world runs
     */
    public HostedWorld(PhysicalWorld world, IntFunction<BlockAppearance> appearances, Settings settings) {
        this.world = Objects.requireNonNull(world, "world");
        this.appearances = Objects.requireNonNull(appearances, "appearances");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.atmosphere = new AtmosphereModel(settings.relaxationSeconds(), settings.defaultEnvironmentK());
        this.refinement = new ThermalRefinement(settings.refinement());
        this.activity = new ThermalActivity(world.materials(), settings.calmRate(), settings.calmSteps());
        world.addWriteListener(activity);
        this.sky = new SkyModel(atmosphere::environment, atmosphere::humidity, sources.sources()::containsKey,
                activity, this::albedo);
        this.radiation = new RadiationModel(atmosphere::environment, refinement, sky);
        this.scheduler = new Scheduler(settings.tickSeconds(), settings.budgetPerTick(), 4, settings.auditInterval());
        scheduler.register(sources);
        scheduler.register(sky);
        scheduler.register(new ConductionModel(refinement, sky));
        scheduler.register(radiation);
        scheduler.register(atmosphere);
    }

    /**
     * Returns the physical world, for reading.
     *
     * @return the world
     */
    public PhysicalWorld world() {
        return world;
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
     * Tells whether a section is imported.
     *
     * @param sectionKey the packed section position
     * @return {@code true} if it is
     */
    public boolean isImported(long sectionKey) {
        return hosted.containsKey(sectionKey);
    }

    /**
     * Returns the imported sections.
     *
     * @return an unmodifiable view, in ascending key order
     */
    public SortedSet<Long> importedSections() {
        return Collections.unmodifiableSortedSet(hosted.navigableKeySet());
    }

    /**
     * Forgets what the host's ids look like, for when the host's descriptions change. Blocks already in the
     * world keep their state until they change.
     */
    public void appearancesChanged() {
        resolvedById = new Resolved[0];
        resolvedByLargeId.clear();
    }

    /**
     * Brings a section of the host's world in, replacing any section already at that position.
     *
     * @param sectionKey the packed section position
     * @param hostIdAt the host's id for the block at each local index, as numbered by
     *     {@link SectionPos#localIndex}
     * @param environmentK the temperature of the section's surroundings, which its air returns to
     */
    public void importSection(long sectionKey, IntUnaryOperator hostIdAt, double environmentK) {
        importSection(sectionKey, hostIdAt, environmentK, null);
    }

    /**
     * Brings a section of the host's world in, like
     * {@link #importSection(long, IntUnaryOperator, double, SectionSnapshot)}, with the humidity of the air around
     * it.
     *
     * @param sectionKey the packed section position
     * @param hostIdAt the host's id for the block at each local index, as numbered by
     *     {@link SectionPos#localIndex}
     * @param environmentK the temperature of the section's surroundings, which its air returns to
     * @param relativeHumidity the relative humidity of the air around the section, from 0 to 1, which decides how
     *     fast water evaporates there and how much heat the sky sends back down
     * @param saved the section's saved state, from {@link #snapshot}, or {@code null} for none
     * @return how many blocks were restored
     */
    public int importSection(long sectionKey, IntUnaryOperator hostIdAt, double environmentK,
            double relativeHumidity, SectionSnapshot saved) {
        if (!(relativeHumidity >= 0 && relativeHumidity <= 1)) {
            throw new IllegalArgumentException("relative humidity must lie between 0 and 1: " + relativeHumidity);
        }
        int restored = importSection(sectionKey, hostIdAt, environmentK, saved);
        atmosphere.setHumidity(sectionKey, relativeHumidity);
        return restored;
    }

    /**
     * Brings a section of the host's world in, like {@link #importSection(long, IntUnaryOperator, double)}, and
     * gives the blocks a {@linkplain #snapshot snapshot} saved back the state they were saved in. A saved block
     * whose matter no longer fits the host's block there, because the block changed while the section was not
     * simulated, starts afresh, and so does one whose material is no longer registered.
     *
     * @param sectionKey the packed section position
     * @param hostIdAt the host's id for the block at each local index, as numbered by
     *     {@link SectionPos#localIndex}
     * @param environmentK the temperature of the section's surroundings, which its air returns to
     * @param saved the section's saved state, from {@link #snapshot}, or {@code null} for none
     * @return how many blocks were restored
     */
    public int importSection(long sectionKey, IntUnaryOperator hostIdAt, double environmentK,
            SectionSnapshot saved) {
        if (!(environmentK > 0) || !Double.isFinite(environmentK)) {
            throw new IllegalArgumentException("climate must be positive: " + environmentK);
        }
        Hosted ids = new Hosted(hostIdAt.applyAsInt(0));
        for (int i = 1; i < SectionPos.BLOCKS; i++) {
            ids.set(i, hostIdAt.applyAsInt(i));
        }
        CellState[] cells = new CellState[SectionPos.BLOCKS];
        TreeMap<Integer, CellState> made = new TreeMap<>();
        double coldest = Double.POSITIVE_INFINITY;
        double hottest = Double.NEGATIVE_INFINITY;
        boolean heated = false;
        int previous = 0;
        CellState previousCell = null;
        for (int i = 0; i < SectionPos.BLOCKS; i++) {
            int id = ids.get(i);
            if (previousCell == null || id != previous) {
                CellState cell = made.get(id);
                if (cell == null) {
                    Resolved r = resolve(id);
                    cell = cellFor(r, environmentK);
                    made.put(id, cell);
                    double t = temperature(cell);
                    if (!Double.isNaN(t)) {
                        coldest = Math.min(coldest, t);
                        hottest = Math.max(hottest, t);
                    }
                    heated |= r.appearance().source() != null;
                }
                previous = id;
                previousCell = cell;
            }
            cells[i] = previousCell;
        }
        int restored = saved == null ? 0 : restore(cells, ids, saved);
        if (saved != null) {
            int[] blocks = saved.structureBlocks();
            byte[] flags = saved.structureFlags();
            for (int k = 0; k < blocks.length; k++) {
                // A block that no longer carries loads, having changed while the section was away, is not built.
                int i = blocks[k];
                boolean carries = carries(resolve(ids.get(i)), cells[i]);
                ids.setFlags(i, carries ? flags[k] : flags[k] & ~StructureFlags.BUILT);
            }
        }
        if (restored > 0) {
            for (CellState cell : cells) {
                double t = temperature(cell);
                if (!Double.isNaN(t)) {
                    coldest = Math.min(coldest, t);
                    hottest = Math.max(hottest, t);
                }
            }
        }
        Section section = world.importSection(sectionKey, i -> cells[i],
                restored > 0 ? "restored from the host's save" : "imported from the host");
        ids.importedVersion = section.version();
        ids.restored = restored > 0;
        sources.removeSection(sectionKey);
        for (int i = 0; i < SectionPos.BLOCKS; i++) {
            BlockAppearance a = resolve(ids.get(i)).appearance();
            if (a.source() != null) {
                sources.put(GridPos.of(sectionKey, i), a.source());
            }
            if (a.presentable()) {
                ids.presentable++;
            }
        }
        hosted.put(sectionKey, ids);
        atmosphere.setEnvironment(sectionKey, environmentK);
        uncheckImported(sectionKey, ids);
        boolean unbalanced = heated || hottest - coldest > BALANCE_TOLERANCE_K;
        for (Direction d : DIRECTIONS) {
            long neighbour = SectionPos.offset(sectionKey, d.dx(), d.dy(), d.dz());
            if (hosted.containsKey(neighbour) && atmosphere.environment(neighbour) != environmentK) {
                unbalanced = true;
            }
        }
        if (unbalanced) {
            activity.wake(sectionKey);
        }
        return restored;
    }

    /**
     * Returns what a host has to save of an imported section for its blocks to come back as they are now, to
     * hand back to {@link #importSection(long, IntUnaryOperator, double, SectionSnapshot)} later: the blocks
     * whose material, mass, enthalpy or owner differ from what importing the section afresh would give them.
     *
     * @param sectionKey the packed section position
     * @return the snapshot, or empty if the section is not imported or a fresh import would rebuild every
     *     block exactly
     */
    public Optional<SectionSnapshot> snapshot(long sectionKey) {
        Hosted ids = hosted.get(sectionKey);
        Section s = world.section(sectionKey);
        if (ids == null || s == null || (!ids.restored && s.version() == ids.importedVersion
                && ids.structure == null)) {
            return Optional.empty();
        }
        int[] structureBlocks = new int[0];
        byte[] structureFlags = new byte[0];
        if (ids.structure != null) {
            int count = 0;
            for (byte f : ids.structure) {
                count += f != 0 ? 1 : 0;
            }
            structureBlocks = new int[count];
            structureFlags = new byte[count];
            int k = 0;
            for (int i = 0; i < SectionPos.BLOCKS; i++) {
                if (ids.structure[i] != 0) {
                    structureBlocks[k] = i;
                    structureFlags[k] = ids.structure[i];
                    k++;
                }
            }
        }
        int[] material = new int[SectionPos.BLOCKS];
        double[] mass = new double[SectionPos.BLOCKS];
        double[] enthalpy = new double[SectionPos.BLOCKS];
        long[] owner = new long[SectionPos.BLOCKS];
        s.copyBlocks(material, mass, enthalpy, owner, 0);
        double climate = atmosphere.environment(sectionKey);
        MaterialRegistry registry = world.materials();
        TreeMap<Integer, CellState> fresh = new TreeMap<>();
        List<SectionSnapshot.Entry> palette = new ArrayList<>();
        int[] blocks = new int[SectionPos.BLOCKS];
        int[] entries = new int[SectionPos.BLOCKS];
        double[] savedMass = new double[SectionPos.BLOCKS];
        double[] savedEnthalpy = new double[SectionPos.BLOCKS];
        int saved = 0;
        int previousId = 0;
        CellState expected = null;
        int entry = -1;
        for (int i = 0; i < SectionPos.BLOCKS; i++) {
            int id = ids.get(i);
            if (expected == null || id != previousId) {
                expected = fresh.computeIfAbsent(id, k -> cellFor(resolve(k), climate));
                previousId = id;
            }
            if (material[i] == expected.material() && mass[i] == expected.mass()
                    && enthalpy[i] == expected.enthalpy() && owner[i] == expected.owner()) {
                continue;
            }
            Provenance provenance = s.provenance(i);
            if (entry < 0 || !matches(palette.get(entry), registry, material[i], owner[i], provenance)) {
                entry = -1;
                for (int e = 0; e < palette.size() && entry < 0; e++) {
                    if (matches(palette.get(e), registry, material[i], owner[i], provenance)) {
                        entry = e;
                    }
                }
                if (entry < 0) {
                    entry = palette.size();
                    palette.add(new SectionSnapshot.Entry(registry.id(material[i]), owner[i], provenance));
                }
            }
            blocks[saved] = i;
            entries[saved] = entry;
            savedMass[saved] = mass[i];
            savedEnthalpy[saved] = enthalpy[i];
            saved++;
        }
        if (saved == 0 && structureBlocks.length == 0) {
            return Optional.empty();
        }
        return Optional.of(new SectionSnapshot(palette, Arrays.copyOf(blocks, saved), Arrays.copyOf(entries, saved),
                Arrays.copyOf(savedMass, saved), Arrays.copyOf(savedEnthalpy, saved), structureBlocks,
                structureFlags));
    }

    private static boolean matches(SectionSnapshot.Entry e, MaterialRegistry registry, int material, long owner,
            Provenance provenance) {
        return e.owner() == owner && e.provenance() == provenance && e.material().equals(registry.id(material));
    }

    /**
     * Returns a number that grows whenever anything in an imported section changes, so a host can tell when
     * a snapshot it saved has gone out of date.
     *
     * @param sectionKey the packed section position
     * @return the section's version, or {@link Long#MIN_VALUE} if it is not imported
     */
    public long sectionVersion(long sectionKey) {
        Hosted ids = hosted.get(sectionKey);
        Section s = ids != null ? world.section(sectionKey) : null;
        return s == null ? Long.MIN_VALUE : s.version() + ids.structureVersion;
    }

    /**
     * Lets a section go, declaring the matter that leaves with it. Its state is lost.
     *
     * @param sectionKey the packed section position
     * @return {@code true} if the section was imported
     */
    public boolean removeSection(long sectionKey) {
        if (hosted.remove(sectionKey) == null) {
            return false;
        }
        uncheckedSections.remove(sectionKey);
        world.removeSection(sectionKey);
        sources.removeSection(sectionKey);
        atmosphere.removeEnvironment(sectionKey);
        activity.forget(sectionKey);
        return true;
    }

    /**
     * Takes in a block the host changed. A block in a section that is not imported is ignored; it will be
     * read when its section is imported. A change between two ids that look the same, such as redstone dust
     * changing its power, only records the new id: it neither touches the block's state nor wakes its section.
     *
     * <p>New matter that carries loads in a block that held none, as a placed block's does, is {@linkplain
     * #isBuilt built}, and so is new matter in a built block; matter that replaces ground where it lies, as moss
     * spreading over stone does, stays ground. Every joint of a block with new matter starts intact, and the built
     * blocks around a change that touches loads wait to have their structure {@linkplain #nextStructure checked}.
     *
     * @param pos the block
     * @param hostId the host's id for the block now
     * @param temperatureHintK the temperature new matter in the block has, such as the steam a
     *     {@link PhaseChange} leaves behind, or {@link Double#NaN} for the section's climate; a block with a
     *     temperature of its own, such as lava, keeps that
     * @return {@code true} if the block's id changed
     */
    public boolean reconcile(GridPos pos, int hostId, double temperatureHintK) {
        long key = pos.sectionKey();
        Hosted ids = hosted.get(key);
        if (ids == null) {
            return false;
        }
        int index = pos.indexInSection();
        int previousId = ids.get(index);
        if (previousId == hostId) {
            return false;
        }
        if (!retake(ids, pos, hostId)) {
            // Only the host's id changed, as when redstone dust changes its power: the physics is untouched.
            return true;
        }
        Resolved after = resolve(hostId);
        CellState now = world.readBlock(pos);
        boolean carried = carries(resolve(previousId), now);
        boolean replaced = !keeps(now, after);
        if (replaced) {
            double surroundings = temperatureHintK > 0 && Double.isFinite(temperatureHintK)
                    ? temperatureHintK : atmosphere.environment(key);
            now = cellFor(after, surroundings);
            world.setBlock(pos, now);
        }
        boolean carries = carries(after, now);
        if (replaced || carries != carried) {
            // New matter, or the same matter now carrying loads or no longer, as when a fence is put up in air.
            renewStructure(pos, carries && ((ids.flags(index) & StructureFlags.BUILT) != 0 || !carried));
        }
        if (carried || carries) {
            // Its joints may differ even with the same matter, in a block of another shape.
            uncheckAround(pos);
        }
        activity.wake(key);
        reconciled++;
        return true;
    }

    /** Makes a block whose matter was replaced new to structures, built or ground, with every joint around it intact. */
    private void renewStructure(GridPos pos, boolean built) {
        Hosted ids = hosted.get(pos.sectionKey());
        ids.setFlags(pos.indexInSection(), built ? StructureFlags.BUILT : 0);
        ids.forgetChecked(pos.indexInSection());
        for (int axis = 0; axis < 3; axis++) {
            uncrack(pos.offset(Direction.POSITIVE.get(axis).opposite()), axis);
        }
    }

    /** Clears the crack, if any, in a block's joint with the next block along an axis. */
    private void uncrack(GridPos pos, int axis) {
        Hosted ids = hosted.get(pos.sectionKey());
        if (ids != null) {
            int i = pos.indexInSection();
            ids.setFlags(i, ids.flags(i) & ~StructureFlags.cracked(axis));
        }
    }

    /**
     * Tells whether a block carries loads: its matter has mechanics and is mostly solid, and its shape reaches a face
     * of its cube, where it can touch a neighbour.
     */
    private boolean carries(Resolved r, CellState cell) {
        return bearing(r, cell.material(), cell.mass(), cell.enthalpy()) != null;
    }

    /**
     * Returns how a block carries loads, or {@code null} if it does not. A block with a frame carries them in its
     * frame's material, at the temperature of its matter, with the mass of its shape.
     */
    private Bearing bearing(Resolved r, int material, double mass, double enthalpy) {
        Shape shape = r.appearance().shape();
        if (!shape.reachesAFace()) {
            return null;
        }
        boolean empty = material == MaterialRegistry.VACUUM || !(mass > 0);
        if (r.frame() >= 0) {
            Material m = world.materials().get(r.frame());
            if (m.mechanics() == null) {
                return null;
            }
            double t = empty ? Mechanics.REFERENCE_K : world.materials().get(material).temperatureFor(enthalpy / mass);
            ThermalState state = m.stateFor(m.specificEnthalpy(t));
            double solid = m.phaseFractions(state)[Phase.SOLID.ordinal()];
            // A block is one cubic metre.
            return solid < MIN_SOLID ? null : new Bearing(m.mechanics(), t, solid, shape.volume() * m.density(state));
        }
        if (empty) {
            return null;
        }
        Material m = world.materials().get(material);
        if (m.mechanics() == null) {
            return null;
        }
        double h = enthalpy / mass;
        double solid = solidFraction(m, h);
        return solid < MIN_SOLID ? null : new Bearing(m.mechanics(), m.temperatureFor(h), solid, mass);
    }

    /** Returns the fraction of matter that is solid at a specific enthalpy. */
    private static double solidFraction(Material m, double specificEnthalpy) {
        return m.phaseFractions(m.stateFor(specificEnthalpy))[Phase.SOLID.ordinal()];
    }

    /** Marks a built block to have its structure checked. */
    private void uncheck(GridPos pos) {
        long key = pos.sectionKey();
        Hosted ids = hosted.get(key);
        if (ids == null) {
            return;
        }
        int i = pos.indexInSection();
        if ((ids.flags(i) & StructureFlags.BUILT) == 0) {
            return;
        }
        if (ids.unchecked == null) {
            ids.unchecked = new BitSet(SectionPos.BLOCKS);
        }
        ids.unchecked.set(i);
        uncheckedSections.add(key);
    }

    /** Marks a block and the built blocks around it to have their structure checked. */
    private void uncheckAround(GridPos pos) {
        uncheck(pos);
        for (Direction d : DIRECTIONS) {
            uncheck(pos.offset(d));
        }
    }

    /**
     * Marks the built blocks of a section just imported to have their structure checked, as anything may have
     * happened to them while it was away, and the built blocks of its neighbours that face it, which were taken to
     * stand on whatever it held.
     */
    private void uncheckImported(long sectionKey, Hosted ids) {
        if (ids.structure != null) {
            for (int i = 0; i < SectionPos.BLOCKS; i++) {
                if ((ids.structure[i] & StructureFlags.BUILT) != 0) {
                    if (ids.unchecked == null) {
                        ids.unchecked = new BitSet(SectionPos.BLOCKS);
                    }
                    ids.unchecked.set(i);
                    uncheckedSections.add(sectionKey);
                }
            }
        }
        for (Direction d : DIRECTIONS) {
            long neighbour = SectionPos.offset(sectionKey, d.dx(), d.dy(), d.dz());
            Hosted other = hosted.get(neighbour);
            if (other == null || other.structure == null) {
                continue;
            }
            // The neighbour's layer of blocks that touches this section.
            int axis = d.axis();
            int layer = d.isPositive() ? 0 : 15;
            for (int a = 0; a < 16; a++) {
                for (int b = 0; b < 16; b++) {
                    int lx = axis == 0 ? layer : a;
                    int ly = axis == 1 ? layer : axis == 0 ? a : b;
                    int lz = axis == 2 ? layer : b;
                    uncheck(GridPos.of(neighbour, SectionPos.localIndex(lx, ly, lz)));
                }
            }
        }
    }

    /**
     * Sets a block's temperature and keeps its matter, as the starting condition of an experiment. The change
     * is declared in the conservation ledger like any edit from outside, and wakes the block's section; if the
     * new temperature puts the matter in another phase, the next {@link #tick} reports it.
     *
     * @param pos the block
     * @param temperatureK the new temperature in kelvin
     * @return {@code true} if the block was set; {@code false} if its section is not imported or it holds no
     *     matter
     */
    public boolean setTemperature(GridPos pos, double temperatureK) {
        if (!(temperatureK > 0) || !Double.isFinite(temperatureK)) {
            throw new IllegalArgumentException("temperature must be positive: " + temperatureK);
        }
        long key = pos.sectionKey();
        if (!hosted.containsKey(key)) {
            return false;
        }
        CellState c = world.readBlock(pos);
        if (c.material() == MaterialRegistry.VACUUM || c.mass() == 0) {
            return false;
        }
        Material m = world.materials().get(c.material());
        world.setBlock(pos, new CellState(c.material(), c.mass(), c.mass() * m.specificEnthalpy(temperatureK),
                c.owner(), Provenance.INITIAL));
        activity.wake(key);
        return true;
    }

    /**
     * Compares a section with the host's blocks and takes in every block that differs, for changes the host
     * did not report.
     *
     * @param sectionKey the packed section position
     * @param hostIdAt the host's id for the block at each local index
     * @return how many blocks differed
     */
    public int verifySection(long sectionKey, IntUnaryOperator hostIdAt) {
        Hosted ids = hosted.get(sectionKey);
        if (ids == null) {
            return 0;
        }
        int differed = 0;
        for (int i = 0; i < SectionPos.BLOCKS; i++) {
            int id = hostIdAt.applyAsInt(i);
            if (id != ids.get(i) && reconcile(GridPos.of(sectionKey, i), id, Double.NaN)) {
                differed++;
            }
        }
        return differed;
    }

    /**
     * Tells whether every section a box of blocks touches is imported, as saving and restoring the box need.
     *
     * @param min the box's lowest corner, inclusive
     * @param max the box's highest corner, inclusive
     * @return {@code true} if they all are
     */
    public boolean importsAll(GridPos min, GridPos max) {
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("no box runs from " + coordinates(min) + " to " + coordinates(max));
        }
        for (int sy = min.y() >> 4; sy <= max.y() >> 4; sy++) {
            for (int sz = min.z() >> 4; sz <= max.z() >> 4; sz++) {
                for (int sx = min.x() >> 4; sx <= max.x() >> 4; sx++) {
                    if (!hosted.containsKey(SectionPos.pack(sx, sy, sz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Saves the state of a box of blocks, every cell of it exactly, to {@linkplain #restore rewind} the box to later.
     * Blocks the host now shows with other ids than it last reported are taken in first, as by {@link #reconcile}.
     *
     * @param min the box's lowest corner, inclusive
     * @param max the box's highest corner, inclusive
     * @param hostIdAt the host's id for each block of the box
     * @return the snapshot
     * @throws IllegalStateException if a section the box touches is not imported
     */
    public RegionSnapshot capture(GridPos min, GridPos max, ToIntFunction<GridPos> hostIdAt) {
        requireImported(min, max);
        int sizeX = max.x() - min.x() + 1;
        int sizeY = max.y() - min.y() + 1;
        int sizeZ = max.z() - min.z() + 1;
        if ((long) sizeX * sizeY * sizeZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("a box of " + sizeX + " by " + sizeY + " by " + sizeZ
                    + " blocks cannot be saved");
        }
        MaterialRegistry registry = world.materials();
        FreshStates fresh = new FreshStates();
        RegionSnapshot.Builder snapshot = new RegionSnapshot.Builder(sizeX, sizeY, sizeZ);
        for (int dy = 0; dy < sizeY; dy++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int dx = 0; dx < sizeX; dx++) {
                    GridPos pos = min.offset(dx, dy, dz);
                    reconcile(pos, hostIdAt.applyAsInt(pos), Double.NaN);
                    int flags = hosted.get(pos.sectionKey()).flags(pos.indexInSection());
                    // Joints with blocks outside the box are not the box's to keep.
                    flags &= ~((dx == sizeX - 1 ? StructureFlags.cracked(0) : 0)
                            | (dy == sizeY - 1 ? StructureFlags.cracked(1) : 0)
                            | (dz == sizeZ - 1 ? StructureFlags.cracked(2) : 0));
                    if (flags != 0) {
                        snapshot.structure(dx + sizeX * (dz + sizeZ * dy), flags);
                    }
                    BlockCopy copy = world.copyBlock(pos);
                    if (!copy.isRefined() && copy.total().equals(fresh.of(pos))) {
                        continue;
                    }
                    snapshot.block(dx + sizeX * (dz + sizeZ * dy));
                    for (int c = 0; c < copy.cellCount(); c++) {
                        CellState state = copy.state(c);
                        snapshot.cell(copy.cell(c), new SectionSnapshot.Entry(registry.id(state.material()),
                                state.owner(), state.provenance()), state.mass(), state.enthalpy());
                    }
                }
            }
        }
        return snapshot.build();
    }

    /**
     * Rewinds a box of blocks to a snapshot. This is a deliberate jump back in time, not a physical process: the
     * conservation ledger declares it like any edit from outside, and the event log records it. The host first puts
     * its own blocks back as they were when the snapshot was taken, then hands over their ids, and every block of
     * the box gets the state it had, cell for cell. A saved block whose matter no longer fits the host's block there,
     * or whose material is no longer registered, starts afresh, as do the blocks the snapshot left out. The sections
     * the box touches wake up, and the surfaces in it start their skins again from their blocks' temperatures, as
     * when a world loads.
     *
     * @param snapshot the snapshot
     * @param min where the box's lowest corner goes
     * @param hostIdAt the host's id for each block of the box, now that the host has put its blocks back
     * @return what was restored
     * @throws IllegalStateException if a section the box touches is not imported
     */
    public Restored restore(RegionSnapshot snapshot, GridPos min, ToIntFunction<GridPos> hostIdAt) {
        GridPos max = min.offset(snapshot.sizeX() - 1, snapshot.sizeY() - 1, snapshot.sizeZ() - 1);
        requireImported(min, max);
        MaterialRegistry registry = world.materials();
        List<SectionSnapshot.Entry> palette = snapshot.palette();
        int[] materialOf = new int[palette.size()];
        for (int e = 0; e < materialOf.length; e++) {
            materialOf[e] = registry.indexOf(palette.get(e).material());
        }
        FreshStates fresh = new FreshStates();
        List<GridPos> positions = new ArrayList<>(snapshot.volume());
        List<BlockCopy> copies = new ArrayList<>(snapshot.volume());
        int afresh = 0;
        int k = 0;
        for (int dy = 0; dy < snapshot.sizeY(); dy++) {
            for (int dz = 0; dz < snapshot.sizeZ(); dz++) {
                for (int dx = 0; dx < snapshot.sizeX(); dx++) {
                    GridPos pos = min.offset(dx, dy, dz);
                    Hosted ids = hosted.get(pos.sectionKey());
                    int id = hostIdAt.applyAsInt(pos);
                    if (ids.get(pos.indexInSection()) != id) {
                        retake(ids, pos, id);
                    }
                    BlockCopy copy = null;
                    if (k < snapshot.size() && snapshot.block(k) == snapshot.indexOf(dx, dy, dz)) {
                        copy = saved(snapshot, k++, materialOf, resolve(id));
                        afresh += copy == null ? 1 : 0;
                    }
                    positions.add(pos);
                    copies.add(copy != null ? copy : BlockCopy.whole(fresh.of(pos)));
                }
            }
        }
        int changed = world.restoreBlocks(positions, copies, coordinates(min) + " to " + coordinates(max));
        int box = 0;
        for (int dy = 0; dy < snapshot.sizeY(); dy++) {
            for (int dz = 0; dz < snapshot.sizeZ(); dz++) {
                for (int dx = 0; dx < snapshot.sizeX(); dx++) {
                    GridPos pos = positions.get(box);
                    int flags = snapshot.structureAt(box++);
                    Hosted ids = hosted.get(pos.sectionKey());
                    int i = pos.indexInSection();
                    if (!carries(resolve(ids.get(i)), world.readBlock(pos))) {
                        flags &= ~StructureFlags.BUILT;
                    }
                    ids.setFlags(i, flags);
                    ids.forgetChecked(i);
                    // Joints from blocks outside the box into it start intact.
                    if (dx == 0) {
                        uncrack(pos.offset(-1, 0, 0), 0);
                    }
                    if (dy == 0) {
                        uncrack(pos.offset(0, -1, 0), 1);
                    }
                    if (dz == 0) {
                        uncrack(pos.offset(0, 0, -1), 2);
                    }
                }
            }
        }
        // Every built block of the box, and those around it, may now stand differently.
        for (GridPos pos : positions) {
            uncheckAround(pos);
        }
        for (int sy = min.y() >> 4; sy <= max.y() >> 4; sy++) {
            for (int sz = min.z() >> 4; sz <= max.z() >> 4; sz++) {
                for (int sx = min.x() >> 4; sx <= max.x() >> 4; sx++) {
                    activity.wake(SectionPos.pack(sx, sy, sz));
                }
            }
        }
        sky.forgetSkins(min, max);
        return new Restored(snapshot.volume(), changed, afresh);
    }

    /**
     * Tells whether a block was built: its matter came after its section was first simulated, as a placed block's
     * does. Built blocks stand or fall by their {@linkplain #nextStructure structure}; the world as it was found is
     * ground, which holds still.
     *
     * @param pos the block
     * @return {@code true} if it is built; {@code false} if it is ground, carries no loads or is not imported
     */
    public boolean isBuilt(GridPos pos) {
        Hosted ids = hosted.get(pos.sectionKey());
        return ids != null && (ids.flags(pos.indexInSection()) & StructureFlags.BUILT) != 0;
    }

    /**
     * Tells whether the joint between a block and its neighbour has cracked, so that it carries only what pressing
     * and friction can.
     *
     * @param pos the block
     * @param toward where the neighbour is
     * @return {@code true} if the joint has cracked; {@code false} if it is intact or not imported
     */
    public boolean isCracked(GridPos pos, Direction toward) {
        GridPos negative = toward.isPositive() ? pos : pos.offset(toward);
        Hosted ids = hosted.get(negative.sectionKey());
        return ids != null && (ids.flags(negative.indexInSection()) & StructureFlags.cracked(toward.axis())) != 0;
    }

    /**
     * Makes the blocks of a box built, so that they stand or fall by their structure, or ground, so that they hold
     * still whatever happens around them. Only blocks that carry loads can be built. The blocks that change, and
     * the built blocks around them, wait to have their structure checked.
     *
     * @param min the box's lowest corner, inclusive
     * @param max the box's highest corner, inclusive
     * @param built {@code true} to make the blocks built, {@code false} to make them ground
     * @return how many blocks changed
     * @throws IllegalStateException if a section the box touches is not imported
     */
    public int setBuilt(GridPos min, GridPos max, boolean built) {
        requireImported(min, max);
        int changed = 0;
        for (int y = min.y(); y <= max.y(); y++) {
            for (int z = min.z(); z <= max.z(); z++) {
                for (int x = min.x(); x <= max.x(); x++) {
                    GridPos pos = new GridPos(x, y, z);
                    Hosted ids = hosted.get(pos.sectionKey());
                    int i = pos.indexInSection();
                    int flags = ids.flags(i);
                    boolean now = built && carries(resolve(ids.get(i)), world.readBlock(pos));
                    int next = now ? flags | StructureFlags.BUILT : flags & ~StructureFlags.BUILT;
                    if (next != flags) {
                        ids.setFlags(i, next);
                        ids.forgetChecked(i);
                        uncheckAround(pos);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /**
     * Returns how many built blocks wait to have their structure checked.
     *
     * @return the number of blocks
     */
    public int uncheckedStructures() {
        int n = 0;
        for (long key : uncheckedSections) {
            n += hosted.get(key).unchecked.cardinality();
        }
        return n;
    }

    /**
     * Reads the structure a built block belongs to, to analyse it. The survey spreads from the block through the
     * built blocks joined to it, nearest first, up to a limit; built blocks beyond the limit, and blocks in sections
     * that are not imported, are taken to hold still. Natural blocks that carry loads are the ground the structure
     * stands on.
     *
     * @param pos the built block
     * @param limit the most built blocks to take in
     * @return the survey, or empty if the block is not a built block that carries loads
     * @throws IllegalArgumentException if the limit is less than one
     */
    public Optional<StructureSurvey> survey(GridPos pos, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a survey takes in at least one block: " + limit);
        }
        Examined first = examine(pos);
        if (!first.built || !first.carries) {
            return Optional.empty();
        }
        TreeMap<GridPos, Examined> seen = new TreeMap<>();
        seen.put(pos, first);
        Frame.Builder frame = Frame.builder();
        first.role = Examined.FREE;
        frame.block(pos, first.mechanics, first.temperatureK, first.solidFraction, first.massKg);
        ArrayDeque<GridPos> queue = new ArrayDeque<>();
        queue.add(pos);
        int free = 1;
        int edge = 0;
        while (!queue.isEmpty()) {
            GridPos p = queue.poll();
            Examined here = seen.get(p);
            for (Direction d : DIRECTIONS) {
                GridPos q = p.offset(d);
                Examined there = seen.get(q);
                if (there == null) {
                    there = examine(q);
                    seen.put(q, there);
                }
                if (!there.carries || (there.role == Examined.FREE && there.done)) {
                    continue;
                }
                Contact contact = d.isPositive() ? Shape.contact(here.shape, there.shape, d.axis())
                        : Shape.contact(there.shape, here.shape, d.axis());
                if (contact == null) {
                    continue;
                }
                if (there.role == Examined.UNSEEN) {
                    if (there.built && there.flags != StructureSurvey.NOT_SIMULATED && free < limit) {
                        there.role = Examined.FREE;
                        frame.block(q, there.mechanics, there.temperatureK, there.solidFraction, there.massKg);
                        queue.add(q);
                        free++;
                    } else if (there.built || there.flags == StructureSurvey.NOT_SIMULATED) {
                        // Beyond the limit, or not simulated: held still where the survey stops.
                        there.role = Examined.GROUND;
                        frame.ground(q, null, there.flags == StructureSurvey.NOT_SIMULATED ? here.temperatureK
                                : there.temperatureK, 1.0);
                        edge++;
                    } else {
                        there.role = Examined.GROUND;
                        frame.ground(q, there.mechanics, there.temperatureK, there.solidFraction);
                    }
                }
                Examined negative = d.isPositive() ? here : there;
                boolean cracked = negative.flags > 0 && (negative.flags & StructureFlags.cracked(d.axis())) != 0;
                frame.bond(p, d, contact, cracked ? Frame.Joint.CRACKED : Frame.Joint.INTACT);
            }
            here.done = true;
        }
        GridPos[] examined = new GridPos[seen.size()];
        int[] hostIds = new int[seen.size()];
        byte[] flags = new byte[seen.size()];
        int k = 0;
        for (Examined e : seen.values()) {
            examined[k] = e.pos;
            hostIds[k] = e.hostId;
            flags[k] = e.flags;
            k++;
        }
        return Optional.of(new StructureSurvey(this, pos, frame.build(), free, edge, examined, hostIds, flags));
    }

    /**
     * Takes the next built block that waits to have its structure checked, in a fixed order, and reads its
     * structure as {@link #survey} does. Every built block the survey takes in counts as checked at its present
     * temperature from then on; heat that changes a built block's strength or stiffness by more than
     * {@value #STRENGTH_DRIFT} of what it was makes it wait again.
     *
     * @param limit the most built blocks to take in
     * @return the survey, or empty if no built block waits
     * @throws IllegalArgumentException if the limit is less than one
     */
    public Optional<StructureSurvey> nextStructure(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a survey takes in at least one block: " + limit);
        }
        while (!uncheckedSections.isEmpty()) {
            long key = uncheckedSections.first();
            Hosted ids = hosted.get(key);
            int i = ids.unchecked.nextSetBit(0);
            ids.unchecked.clear(i);
            if (ids.unchecked.isEmpty()) {
                ids.unchecked = null;
                uncheckedSections.remove(key);
            }
            Optional<StructureSurvey> survey = survey(GridPos.of(key, i), limit);
            if (survey.isEmpty()) {
                continue;
            }
            for (Frame.Block b : survey.get().frame().blocks()) {
                if (b.ground()) {
                    continue;
                }
                long blockKey = b.pos().sectionKey();
                Hosted blockIds = hosted.get(blockKey);
                int index = b.pos().indexInSection();
                blockIds.checked(index, b.temperatureK(), b.solidFraction());
                if (blockIds.unchecked != null) {
                    blockIds.unchecked.clear(index);
                    if (blockIds.unchecked.isEmpty()) {
                        blockIds.unchecked = null;
                        uncheckedSections.remove(blockKey);
                    }
                }
            }
            return survey;
        }
        return Optional.empty();
    }

    /**
     * Settles the analysis of a structure into the world: its joints that cracked or let go are cracked from now
     * on, and the blocks it left with nothing to hold them up are handed back for the host to let fall. If any block
     * the survey looked at has changed since, nothing is settled and the structure waits to be checked again.
     *
     * @param survey the survey the analysis worked from, made by this hosted world
     * @param result the analysis of the survey's frame
     * @return what was settled
     * @throws IllegalArgumentException if another hosted world made the survey
     */
    public Settled settle(StructureSurvey survey, StructuralAnalysis.Result result) {
        if (survey.world() != this) {
            throw new IllegalArgumentException("the survey was made by another hosted world");
        }
        for (int k = 0; k < survey.examinedCount(); k++) {
            GridPos pos = survey.examined(k);
            Hosted ids = hosted.get(pos.sectionKey());
            boolean same = survey.flags(k) == StructureSurvey.NOT_SIMULATED ? ids == null
                    : ids != null && ids.get(pos.indexInSection()) == survey.hostId(k)
                            && ids.flags(pos.indexInSection()) == survey.flags(k);
            if (!same) {
                uncheck(survey.start());
                return new Settled(true, List.of(), List.of());
            }
        }
        List<GridPos> falling = result.falling();
        for (StructuralAnalysis.BondResult b : result.bonds()) {
            // A joint that let go between blocks that still stand is cracked from now on; one that only stopped
            // holding because a block it joins fell goes with that block.
            boolean goes = Collections.binarySearch(falling, b.pos()) >= 0
                    || Collections.binarySearch(falling, b.pos().offset(Direction.POSITIVE.get(b.axis()))) >= 0;
            if (b.state() == Frame.Joint.CRACKED || (!b.holds() && !goes)) {
                Hosted ids = hosted.get(b.pos().sectionKey());
                if (ids != null) {
                    int i = b.pos().indexInSection();
                    ids.setFlags(i, ids.flags(i) | StructureFlags.cracked(b.axis()));
                }
            }
        }
        return new Settled(false, result.cracks(), falling);
    }

    /**
     * Marks the built blocks of simulated sections whose strength or stiffness heat has changed by more than
     * {@link #STRENGTH_DRIFT} since their structure was last checked, looking at each section once in
     * {@link #STRENGTH_CHECK_STEPS} steps.
     */
    private void checkStrength(SortedSet<Long> scope) {
        long tick = world.tick();
        for (long key : scope) {
            Hosted ids = hosted.get(key);
            if (ids == null || ids.checkedK == null || Math.floorMod(tick + key, STRENGTH_CHECK_STEPS) != 0) {
                continue;
            }
            Section s = world.section(key);
            for (int i = 0; i < SectionPos.BLOCKS; i++) {
                float checkedK = ids.checkedK[i];
                if (!Float.isNaN(checkedK) && (ids.flags(i) & StructureFlags.BUILT) != 0
                        && drifted(resolve(ids.get(i)), s, i, checkedK, ids.checkedSolid[i])) {
                    ids.forgetChecked(i);
                    uncheck(GridPos.of(key, i));
                }
            }
        }
    }

    /** Tells whether a block's strength or stiffness has changed by more than {@link #STRENGTH_DRIFT}. */
    private boolean drifted(Resolved r, Section s, int i, double checkedK, double checkedSolid) {
        Bearing b = bearing(r, s.material(i), s.mass(i), s.enthalpy(i));
        if (b == null) {
            return true;
        }
        Mechanics mechanics = b.mechanics();
        double t = b.temperatureK();
        double solid = b.solidFraction();
        return differs(mechanics.tensileStrength(t) * solid, mechanics.tensileStrength(checkedK) * checkedSolid)
                || differs(mechanics.compressiveStrength(t) * solid,
                        mechanics.compressiveStrength(checkedK) * checkedSolid)
                || differs(mechanics.youngsModulus(t) * solid, mechanics.youngsModulus(checkedK) * checkedSolid);
    }

    private static boolean differs(double a, double b) {
        return Math.abs(a - b) > STRENGTH_DRIFT * Math.max(Math.abs(a), Math.abs(b));
    }

    /** What a survey found at one position. */
    private static final class Examined {
        static final int UNSEEN = 0;
        static final int FREE = 1;
        static final int GROUND = 2;

        final GridPos pos;
        final int hostId;
        final byte flags;
        final Shape shape;
        final boolean built;
        final boolean carries;
        final Mechanics mechanics;
        final double temperatureK;
        final double solidFraction;
        final double massKg;
        int role = UNSEEN;
        boolean done;

        Examined(GridPos pos, int hostId, byte flags, Shape shape, boolean carries, Mechanics mechanics,
                double temperatureK, double solidFraction, double massKg) {
            this.pos = pos;
            this.hostId = hostId;
            this.flags = flags;
            this.shape = shape;
            this.built = flags != StructureSurvey.NOT_SIMULATED && (flags & StructureFlags.BUILT) != 0;
            this.carries = carries;
            this.mechanics = mechanics;
            this.temperatureK = temperatureK;
            this.solidFraction = solidFraction;
            this.massKg = massKg;
        }
    }

    /**
     * Looks at a block for a survey. A block in a section that is not imported is taken to be a full block that
     * carries loads, as whatever is there held up what stands on it before.
     */
    private Examined examine(GridPos pos) {
        long key = pos.sectionKey();
        Hosted ids = hosted.get(key);
        if (ids == null) {
            return new Examined(pos, 0, StructureSurvey.NOT_SIMULATED, Shape.FULL, true, null, Double.NaN, 1.0, 0.0);
        }
        int i = pos.indexInSection();
        int hostId = ids.get(i);
        Resolved r = resolve(hostId);
        Section s = world.section(key);
        byte flags = (byte) ids.flags(i);
        Bearing b = bearing(r, s.material(i), s.mass(i), s.enthalpy(i));
        if (b == null) {
            return new Examined(pos, hostId, flags, r.appearance().shape(), false, null, Double.NaN, 0.0, 0.0);
        }
        return new Examined(pos, hostId, flags, r.appearance().shape(), true, b.mechanics(), b.temperatureK(),
                b.solidFraction(), b.massKg());
    }

    /**
     * Simulates one step: blocks are split or merged as the last step found them to need, heat runs in the
     * awake sections and their neighbours, sections fall asleep or wake up, and blocks whose shown phase has
     * gone are reported.
     *
     * @return what happened
     */
    public TickResult tick() {
        SortedSet<Long> scope = activity.scope(world);
        SortedMap<GridPos, HeatSourceModel.Source> held = sources.sources();
        refinement.update(world, scope, held::containsKey);
        TickReport report = scheduler.tick(world, (w, domain) -> domain == Domain.THERMAL ? scope : NOWHERE);
        boolean ran = true;
        for (TickReport.ModelRun run : report.runs()) {
            ran &= run.status() != TickReport.Status.DEFERRED;
        }
        refinement.endStep(ran);
        if (ran) {
            activity.endStep(world, settings.tickSeconds());
            checkStrength(scope);
        }
        if (report.audit() != null) {
            conserved = report.audit().balanced();
        }
        List<PhaseChange> changes = phaseChanges(scope);
        phaseChanges += changes.size();
        lastScope = scope;
        return new TickResult(report, changes, scope.size(), activity.awakeSections().size());
    }

    /**
     * Returns what the engine knows about a block.
     *
     * @param pos the block
     * @return the inspection, or empty if the block's section is not imported
     */
    public Optional<Inspection> inspect(GridPos pos) {
        long key = pos.sectionKey();
        Hosted ids = hosted.get(key);
        if (ids == null) {
            return Optional.empty();
        }
        CellState c = world.readBlock(pos);
        MaterialRegistry registry = world.materials();
        ThermalState state = null;
        Phase phase = null;
        String name = "Vacuum";
        if (c.material() != MaterialRegistry.VACUUM) {
            Material m = registry.get(c.material());
            name = m.name();
            if (c.mass() > 0) {
                state = m.stateFor(c.specificEnthalpy());
                phase = m.dominantPhase(state);
            }
        }
        double[] range = {Double.NaN, Double.NaN};
        RefinedBlock block = world.refinedBlock(pos);
        if (block == null) {
            range[0] = state == null ? Double.NaN : state.temperatureK();
            range[1] = range[0];
        } else {
            block.forEachLeaf((cell, leaf) -> {
                double t = temperature(leaf);
                if (!Double.isNaN(t)) {
                    range[0] = Double.isNaN(range[0]) ? t : Math.min(range[0], t);
                    range[1] = Double.isNaN(range[1]) ? t : Math.max(range[1], t);
                }
            });
        }
        return Optional.of(new Inspection(pos, registry.id(c.material()), name, c.mass(), c.enthalpy(), state, phase,
                c.provenance(), block != null, range[0], range[1], activity.isAwake(key), lastScope.contains(key),
                atmosphere.environment(key), sources.sources().get(pos), resolve(ids.get(pos.indexInSection()))
                        .appearance(), sky.surfaceTemperature(pos), sky.absorbedSunlight(pos)));
    }

    /**
     * Returns a block's temperature.
     *
     * @param pos the block
     * @return the temperature in kelvin, or {@link Double#NaN} if the block is empty or not imported
     */
    public double temperature(GridPos pos) {
        return hosted.containsKey(pos.sectionKey()) ? temperature(world.readBlock(pos)) : Double.NaN;
    }

    /**
     * Returns the temperature at a point: that of the block holding it or, if the block is refined, of its
     * cell there, so a refined block's face shows where it is hotter or colder. A point in the top sixteenth of a
     * block open to the sky reads the temperature of its top, which the sun and the sky warm and cool faster than
     * the block as a whole.
     *
     * @param x the point's x coordinate, in blocks
     * @param y the point's y coordinate, in blocks
     * @param z the point's z coordinate, in blocks
     * @return the temperature in kelvin, or {@link Double#NaN} if the matter there is empty or not imported
     */
    public double temperatureAt(double x, double y, double z) {
        GridPos pos = new GridPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        if (!hosted.containsKey(pos.sectionKey())) {
            return Double.NaN;
        }
        if (y - pos.y() >= SURFACE_DEPTH) {
            double surface = sky.surfaceTemperature(pos);
            if (!Double.isNaN(surface)) {
                return surface;
            }
        }
        RefinedBlock block = world.refinedBlock(pos);
        if (block == null) {
            return temperature(pos);
        }
        int level = block.depth();
        int size = 1 << level;
        CellId finest = new CellId(pos, level, cellIndex(x - pos.x(), size), cellIndex(y - pos.y(), size),
                cellIndex(z - pos.z(), size));
        return temperature(world.readLeaf(block.leafCovering(finest)));
    }

    /** Returns which of {@code size} cells along an edge a position within a block, from 0 to 1, falls in. */
    private static int cellIndex(double within, int size) {
        return Math.max(0, Math.min(size - 1, (int) (within * size)));
    }

    /**
     * Lists the blocks of a section hot enough to glow in the dark, with the temperatures across the faces they
     * show. A face counts as shown unless the block next to it hides it; blocks that give off light of their own in
     * the host, as lava does, are left out, since the host already shows them glowing, and so is gas, which has no
     * surface to glow from. A block glows if any spot of its faces does, by {@link Incandescence#glow} at the
     * emissivity of its matter.
     *
     * <p>Most blocks are passed over by comparing their enthalpy with that of their material at the lowest
     * temperature anything glows at, so a section without glowing blocks costs little more than reading it.
     *
     * @param sectionKey the packed section position
     * @param hides tells whether the host block with a given id hides the faces of the blocks next to it
     * @param shines tells whether the host block with a given id gives off light of its own
     * @return the glowing blocks in index order; empty if none glow or the section is not imported
     */
    public List<GlowingBlock> glowingBlocks(long sectionKey, IntPredicate hides, IntPredicate shines) {
        Hosted ids = hosted.get(sectionKey);
        Section s = ids == null ? null : world.section(sectionKey);
        if (s == null) {
            return List.of();
        }
        MaterialRegistry registry = world.materials();
        List<GlowingBlock> found = new ArrayList<>();
        for (int i = 0; i < SectionPos.BLOCKS; i++) {
            if (!mayGlow(s, i) || shines.test(ids.get(i))) {
                continue;
            }
            GridPos pos = s.blockPos(i);
            int faces = 0;
            for (Direction d : DIRECTIONS) {
                GridPos next = pos.offset(d);
                Hosted around = hosted.get(next.sectionKey());
                if (around == null || !hides.test(around.get(next.indexInSection()))) {
                    faces |= 1 << d.ordinal();
                }
            }
            CellState c = s.blockState(i);
            if (faces == 0 || c.material() == MaterialRegistry.VACUUM || !(c.mass() > 0)) {
                continue;
            }
            Material m = registry.get(c.material());
            ThermalState state = m.stateFor(c.specificEnthalpy());
            if (m.dominantPhase(state) == Phase.GAS) {
                continue;
            }
            double emissivity = m.emissivity(state);
            double[] temperatures = faceTemperatures(pos, s.isRefined(i), faces, state.temperatureK());
            GlowingBlock block = new GlowingBlock(i, emissivity, faces, temperatures);
            if (Incandescence.glow(block.hottest(), emissivity).visible()) {
                found.add(block);
            }
        }
        return found;
    }

    /** Tells whether a block holds matter hot enough that it might glow, in any of its cells. */
    private boolean mayGlow(Section s, int i) {
        if (!s.isRefined(i)) {
            return mayGlow(s.material(i), s.mass(i), s.enthalpy(i));
        }
        boolean[] hot = {false};
        s.refinedBlock(i).forEachLeaf((cell, leaf) -> hot[0] |= mayGlow(leaf.material(), leaf.mass(), leaf.enthalpy()));
        return hot[0];
    }

    private boolean mayGlow(int material, double mass, double enthalpy) {
        return material != MaterialRegistry.VACUUM && mass > 0 && enthalpy / mass >= glowEnthalpy(material);
    }

    /** Returns the specific enthalpy of a material at the lowest temperature anything glows at, remembered. */
    private double glowEnthalpy(int material) {
        if (material >= glowEnthalpies.length) {
            int known = glowEnthalpies.length;
            glowEnthalpies = Arrays.copyOf(glowEnthalpies, Math.max(material + 1, 2 * known));
            Arrays.fill(glowEnthalpies, known, glowEnthalpies.length, Double.NaN);
        }
        double h = glowEnthalpies[material];
        if (Double.isNaN(h)) {
            h = world.materials().get(material).specificEnthalpy(Incandescence.glowsFromK(1.0));
            glowEnthalpies[material] = h;
        }
        return h;
    }

    /**
     * Reads the temperatures of a block's shown faces: once for a whole block, with its top read apart where it is
     * open to the sky, or spot by spot for a refined one.
     */
    private double[] faceTemperatures(GridPos pos, boolean refined, int faces, double wholeK) {
        double[] temperatures = new double[DIRECTIONS.length * GlowingBlock.SAMPLES];
        Arrays.fill(temperatures, Double.NaN);
        double top = sky.surfaceTemperature(pos);
        for (Direction d : DIRECTIONS) {
            if ((faces & 1 << d.ordinal()) == 0) {
                continue;
            }
            for (int v = 0; v < GlowingBlock.GRID; v++) {
                for (int u = 0; u < GlowingBlock.GRID; u++) {
                    double t;
                    if (d == Direction.UP && !Double.isNaN(top)) {
                        t = top;
                    } else if (!refined) {
                        t = wholeK;
                    } else {
                        double[] p = GlowingBlock.point(d, u, v);
                        t = temperatureAt(pos.x() + p[0], pos.y() + p[1], pos.z() + p[2]);
                    }
                    temperatures[GlowingBlock.sample(d, u, v)] = t;
                }
            }
        }
        return temperatures;
    }

    /**
     * Returns a summary of the world.
     *
     * @return the status
     */
    public Status status() {
        int refinedBlocks = 0;
        for (long key : hosted.keySet()) {
            Section s = world.section(key);
            if (s != null) {
                refinedBlocks += s.refinedBlocks().size();
            }
        }
        Sky now = sky.sky();
        return new Status(hosted.size(), activity.awakeSections().size(), lastScope.size(), sources.sources().size(),
                radiation.lastRadiatingFaces(), refinedBlocks, world.leafCount(), world.tick(),
                world.tick() * settings.tickSeconds(), phaseChanges, reconciled, conserved,
                sky.lastStep().surfaces(), now == null ? Double.NaN : SkyPhysics.sunlightOnLevelGround(now));
    }

    /**
     * Sets the sky over the world for the steps that follow; a host with a sky sets it every step, from its own
     * clock and weather.
     *
     * @param newSky the sky, or {@code null} for a world without one, such as a cave world, which is the default
     */
    public void setSky(Sky newSky) {
        sky.setSky(newSky);
    }

    /**
     * Returns the sky over the world.
     *
     * @return the sky, or {@code null} if the world has none
     */
    public Sky sky() {
        return sky.sky();
    }

    /**
     * Says where the open sky begins in a column of the host's world: everything from that height up is open to
     * the sky. Give the heights of a column after importing its sections; without one, the sky begins above the
     * column's highest imported section.
     *
     * @param x the column's x coordinate, in blocks
     * @param z the column's z coordinate, in blocks
     * @param firstOpenY the lowest y at which the column is open to the sky
     */
    public void setSkyHeight(int x, int z, int firstOpenY) {
        sky.setSkyHeight(x, z, firstOpenY);
    }

    /**
     * Returns what the sky did in the last step.
     *
     * @return the summary
     */
    public SkyModel.StepSummary lastSky() {
        return sky.lastStep();
    }

    /**
     * Returns what splitting and merging cells did at the start of the last tick.
     *
     * @return the report
     */
    public ThermalRefinement.Report lastRefinement() {
        return refinement.lastReport();
    }

    // ---- internals ----

    /**
     * Returns the albedo the host gives a block's top, while the block holds the matter it is shown with, in the
     * phase it is shown in; otherwise NaN, for the material's own.
     */
    private double albedo(long sectionKey, int block) {
        Hosted ids = hosted.get(sectionKey);
        Section s = world.section(sectionKey);
        if (ids == null || s == null) {
            return Double.NaN;
        }
        Resolved r = resolve(ids.get(block));
        BlockAppearance a = r.appearance();
        double mass = s.mass(block);
        if (Double.isNaN(a.albedo()) || s.material(block) != r.material() || mass == 0) {
            return Double.NaN;
        }
        if (a.phase() != null) {
            Material m = world.materials().get(r.material());
            if (m.dominantPhase(m.stateFor(s.enthalpy(block) / mass)) != a.phase()) {
                return Double.NaN;
            }
        }
        return a.albedo();
    }

    private Resolved resolve(int id) {
        if (id >= 0 && id < ARRAY_IDS) {
            if (id >= resolvedById.length) {
                resolvedById = Arrays.copyOf(resolvedById, Math.min(ARRAY_IDS, Math.max(id + 1,
                        2 * resolvedById.length)));
            }
            Resolved r = resolvedById[id];
            if (r == null) {
                r = describe(id);
                resolvedById[id] = r;
            }
            return r;
        }
        Resolved r = resolvedByLargeId.get(id);
        if (r == null) {
            r = describe(id);
            resolvedByLargeId.put(id, r);
        }
        return r;
    }

    private Resolved describe(int id) {
        BlockAppearance a = appearances.apply(id);
        if (a == null) {
            throw new IllegalStateException("the host gave no appearance for its block " + id);
        }
        int material = world.materials().indexOf(a.material());
        if (material < 0) {
            throw new IllegalStateException("the host's block " + id + " is made of " + a.material()
                    + ", which is not registered");
        }
        int frame = a.frame() == null ? -1 : world.materials().indexOf(a.frame());
        if (a.frame() != null && frame < 0) {
            throw new IllegalStateException("the host's block " + id + " is held up by " + a.frame()
                    + ", which is not registered");
        }
        return new Resolved(a, material, frame);
    }

    /**
     * Records the host's new id for a block, and its heat source, without touching its state.
     *
     * @return {@code true} if the block looks different now, {@code false} if only the id changed
     */
    private boolean retake(Hosted ids, GridPos pos, int hostId) {
        int index = pos.indexInSection();
        Resolved before = resolve(ids.get(index));
        Resolved after = resolve(hostId);
        ids.set(index, hostId);
        if (before.equals(after)) {
            return false;
        }
        ids.presentable += (after.appearance().presentable() ? 1 : 0) - (before.appearance().presentable() ? 1 : 0);
        if (after.appearance().source() != null) {
            sources.put(pos, after.appearance().source());
        } else {
            sources.remove(pos);
        }
        return true;
    }

    /** Returns a saved block as it was, or {@code null} if its matter no longer fits the host's block there. */
    private BlockCopy saved(RegionSnapshot snapshot, int k, int[] materialOf, Resolved r) {
        int first = snapshot.firstCell(k);
        long[] cells = new long[snapshot.cellCount(k)];
        List<CellState> states = new ArrayList<>(cells.length);
        for (int c = 0; c < cells.length; c++) {
            int material = materialOf[snapshot.paletteIndex(first + c)];
            double mass = snapshot.mass(first + c);
            if (material < 0 || (material == MaterialRegistry.VACUUM && mass != 0)) {
                return null;
            }
            SectionSnapshot.Entry e = snapshot.entry(first + c);
            cells[c] = snapshot.cell(first + c);
            states.add(new CellState(material, mass, snapshot.enthalpy(first + c), e.owner(), e.provenance()));
        }
        BlockCopy copy = BlockCopy.of(cells, states);
        return keeps(copy.total(), r) ? copy : null;
    }

    private void requireImported(GridPos min, GridPos max) {
        if (!importsAll(min, max)) {
            throw new IllegalStateException("not every section from " + coordinates(min) + " to " + coordinates(max)
                    + " is imported");
        }
    }

    private static String coordinates(GridPos pos) {
        return pos.x() + " " + pos.y() + " " + pos.z();
    }

    /** The states blocks start in, made once for each climate and host id. */
    private final class FreshStates {
        private final TreeMap<Double, TreeMap<Integer, CellState>> made = new TreeMap<>();

        /** Returns the state a block starts in; the caller must not change it. */
        CellState of(GridPos pos) {
            long key = pos.sectionKey();
            int id = hosted.get(key).get(pos.indexInSection());
            double climate = atmosphere.environment(key);
            return made.computeIfAbsent(climate, c -> new TreeMap<>()).computeIfAbsent(id,
                    i -> cellFor(resolve(i), climate));
        }
    }

    /** Puts saved states into a section being imported where they still fit, and counts them. */
    private int restore(CellState[] cells, Hosted ids, SectionSnapshot saved) {
        MaterialRegistry registry = world.materials();
        List<SectionSnapshot.Entry> palette = saved.palette();
        int[] materialOf = new int[palette.size()];
        for (int e = 0; e < materialOf.length; e++) {
            materialOf[e] = registry.indexOf(palette.get(e).material());
        }
        int restored = 0;
        for (int k = 0; k < saved.size(); k++) {
            int material = materialOf[saved.paletteIndex(k)];
            double mass = saved.mass(k);
            if (material < 0 || (material == MaterialRegistry.VACUUM && mass != 0)) {
                continue;
            }
            SectionSnapshot.Entry e = saved.entry(k);
            CellState cell = new CellState(material, mass, saved.enthalpy(k), e.owner(), e.provenance());
            int i = saved.block(k);
            if (keeps(cell, resolve(ids.get(i)))) {
                cells[i] = cell;
                restored++;
            }
        }
        return restored;
    }

    /** Returns the state a block with an appearance starts in. */
    private CellState cellFor(Resolved r, double surroundingsK) {
        if (r.material() == MaterialRegistry.VACUUM) {
            return CellState.vacuum(Provenance.INITIAL);
        }
        BlockAppearance a = r.appearance();
        Material m = world.materials().get(r.material());
        double t = Double.isNaN(a.temperatureK()) ? surroundingsK : a.temperatureK();
        double h = m.specificEnthalpy(t);
        if (a.phase() != null) {
            double inPhase = m.nearestSpecificEnthalpyIn(a.phase(), h);
            if (!Double.isNaN(inPhase)) {
                h = inPhase;
            }
        }
        double mass = a.fill() * m.density(m.stateFor(h)); // a block is one cubic metre
        return new CellState(r.material(), mass, mass * h, 0L, Provenance.INITIAL);
    }

    /** Tells whether a block's matter can stay as it is under a new appearance. */
    private boolean keeps(CellState current, Resolved r) {
        if (current.material() != r.material()) {
            return false;
        }
        if (r.material() == MaterialRegistry.VACUUM || current.mass() == 0) {
            return true;
        }
        Material m = world.materials().get(r.material());
        ThermalState s = m.stateFor(current.specificEnthalpy());
        BlockAppearance a = r.appearance();
        if (a.phase() != null && !m.canAppearAs(s, a.phase())) {
            return false;
        }
        if (m.dominantPhase(s) == Phase.GAS) {
            return true;
        }
        double expected = a.fill() * m.density(s);
        return Math.abs(current.mass() - expected) <= MASS_TOLERANCE * expected;
    }

    private double temperature(CellState c) {
        if (c.material() == MaterialRegistry.VACUUM || c.mass() == 0) {
            return Double.NaN;
        }
        return world.materials().get(c.material()).temperatureFor(c.specificEnthalpy());
    }

    /** Finds the blocks in the scope whose shown phase has gone and that have a replacement for the new one. */
    private List<PhaseChange> phaseChanges(SortedSet<Long> scope) {
        List<PhaseChange> changes = new ArrayList<>();
        MaterialRegistry registry = world.materials();
        for (long key : scope) {
            Hosted ids = hosted.get(key);
            Section s = world.section(key);
            if (ids == null || s == null || ids.presentable == 0 || s.version() == ids.scannedVersion) {
                continue;
            }
            ids.scannedVersion = s.version();
            int previous = 0;
            Resolved r = null;
            for (int i = 0; i < SectionPos.BLOCKS; i++) {
                int id = ids.get(i);
                if (r == null || id != previous) {
                    r = resolve(id);
                    previous = id;
                }
                BlockAppearance a = r.appearance();
                if (!a.presentable() || s.material(i) != r.material()) {
                    continue;
                }
                double mass = s.mass(i);
                if (mass == 0) {
                    continue;
                }
                Material m = registry.get(r.material());
                ThermalState state = m.stateFor(s.enthalpy(i) / mass);
                if (m.canAppearAs(state, a.phase())) {
                    continue;
                }
                Phase now = m.dominantPhase(state);
                String replacement = a.becomes().get(now);
                if (replacement != null) {
                    changes.add(new PhaseChange(s.blockPos(i), a.phase(), now, replacement, state.temperatureK()));
                }
            }
        }
        return changes;
    }
}
