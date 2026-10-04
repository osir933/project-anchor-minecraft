package io.github.osir933.anchor.core.host;

import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.matter.ThermalState;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.model.Scheduler;
import io.github.osir933.anchor.core.model.TickReport;
import io.github.osir933.anchor.core.physics.thermal.AtmosphereModel;
import io.github.osir933.anchor.core.physics.thermal.ConductionModel;
import io.github.osir933.anchor.core.physics.thermal.HeatSourceModel;
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
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.RefinedBlock;
import io.github.osir933.anchor.core.world.Section;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

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

        Hosted(int uniform) {
            this.uniform = uniform;
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

    /** A host id's appearance, with its material's registry index. */
    private record Resolved(BlockAppearance appearance, int material) {
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
    private Resolved[] resolvedById = new Resolved[0];
    private final TreeMap<Integer, Resolved> resolvedByLargeId = new TreeMap<>();
    private SortedSet<Long> lastScope = NOWHERE;
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
        if (ids == null || s == null || (!ids.restored && s.version() == ids.importedVersion)) {
            return Optional.empty();
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
        if (saved == 0) {
            return Optional.empty();
        }
        return Optional.of(new SectionSnapshot(palette, Arrays.copyOf(blocks, saved), Arrays.copyOf(entries, saved),
                Arrays.copyOf(savedMass, saved), Arrays.copyOf(savedEnthalpy, saved)));
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
        Section s = hosted.containsKey(sectionKey) ? world.section(sectionKey) : null;
        return s == null ? Long.MIN_VALUE : s.version();
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
        int old = ids.get(index);
        if (old == hostId) {
            return false;
        }
        Resolved before = resolve(old);
        Resolved after = resolve(hostId);
        ids.set(index, hostId);
        if (before.equals(after)) {
            // Only the host's id changed, as when redstone dust changes its power: the physics is untouched.
            return true;
        }
        ids.presentable += (after.appearance().presentable() ? 1 : 0) - (before.appearance().presentable() ? 1 : 0);
        if (after.appearance().source() != null) {
            sources.put(pos, after.appearance().source());
        } else {
            sources.remove(pos);
        }
        if (!keeps(world.readBlock(pos), after)) {
            double surroundings = temperatureHintK > 0 && Double.isFinite(temperatureHintK)
                    ? temperatureHintK : atmosphere.environment(key);
            world.setBlock(pos, cellFor(after, surroundings));
        }
        activity.wake(key);
        reconciled++;
        return true;
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
        return new Resolved(a, material);
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
