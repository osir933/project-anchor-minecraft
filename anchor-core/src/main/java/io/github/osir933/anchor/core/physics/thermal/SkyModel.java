package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.matter.Element;
import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.matter.PhaseRegion;
import io.github.osir933.anchor.core.matter.Surface;
import io.github.osir933.anchor.core.matter.ThermalState;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.model.PhysicsModel;
import io.github.osir933.anchor.core.model.StepContext;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.units.PhysicalConstants;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.RefinedBlock;
import io.github.osir933.anchor.core.world.Section;
import io.github.osir933.anchor.core.world.Totals;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.function.LongToDoubleFunction;
import java.util.function.Predicate;

/**
 * Sunlight and the sky. By day the sun warms whatever it shines on; by night the ground loses heat to a clear sky
 * that is colder than the air, and less of it under cloud; wet ground and open water cool as their water
 * evaporates, and take heat back as dew or frost settles on them.
 *
 * <p><b>Where the sun shines.</b> Light falls straight down each column of blocks, as Minecraft's skylight does,
 * so a shadow lies right under whatever casts it, however high the sun stands. The host says where the open sky
 * begins in each column ({@link #setSkyHeight}); where it has not, the sky begins above the world's highest
 * section there. Gas and vacuum let light through. The first block below the sky that holds anything else is the
 * column's surface: it reflects the share of the light its albedo says and takes in the rest, unless it is
 * translucent. Translucent matter, such as water, ice and glass, takes in a part of each band of the light per
 * metre ({@link Surface}) and lets the rest on down, where the next opaque block reflects and takes in what is
 * left; the bed under a body of liquid hands what it takes in to the liquid on it, as a sunlit pond bed warms its
 * water. Light stops once less than {@value #FAINTEST} of it is left, or {@value #DEEPEST} blocks below the
 * surface; what remains is taken in by the translucent block it stops in. Only top faces see the sun: the sides
 * of blocks stay in shade.
 *
 * <p>Heat flows below the surface only where the thermal models simulate it, so light bound for a block in a
 * section they leave alone warms the column's surface instead, as the sunlit bed of a deep pond warms water that
 * rises to the top: kept where it fell, it would pile up there without end. For the same reason, a surface whose
 * top face this model leaves to conduction, as below, takes in no light while its own section is left alone.
 *
 * <p><b>The surface balance.</b> Where the surface is an ordinary solid or liquid, this model takes the exchange
 * across its top face over from conduction and radiation, and balances it: the sunlight it takes in, the thermal
 * radiation of the sky ({@link SkyPhysics#skyRadiation}), its own emission at its emissivity, the heat the air
 * carries off at {@link ConductionModel#CONVECTION_COEFFICIENT} W/(m²·K), and the latent heat of water
 * evaporating from a wet surface or condensing on it, held back by the air and by the surface's own
 * {@linkplain Surface#evaporationResistance() resistance}. The air near the ground is moister in rain, up to a
 * relative humidity of {@value #RAINY_HUMIDITY}.
 *
 * <p>A block is a cubic metre, much thicker than the layer the daily cycle of warming and cooling reaches into, a
 * few centimetres to decimetres, so a solid surface carries a skin: the temperature of that layer, which follows
 * the force-restore method (Deardorff, J. Geophys. Res. 83 (1978) 1889-1903). The skin has the heat capacity per
 * area that the daily cycle stirs, {@code e/√(2ω)} for the thermal effusivity {@code e = √(kρc)} of the block's
 * matter and the day's angular frequency {@code ω}, up to half the block's own, and the method pulls it back
 * towards the block's temperature at the rate {@code ω}. It is no warmer than the melting point of a solid that
 * melts, so sunlit snow stays at 0 °C and melts. The skin is part of its block: every joule crossing the surface
 * goes into the block's enthalpy. A liquid mixes, so the surface of a liquid is the whole block.
 *
 * <p>The heat the air carries off goes to the open atmosphere at the section's environment temperature, as wind
 * would take it, rather than into the cell of air above the surface, which would warm by tens of kelvin over
 * sunlit ground. That cell only relaxes towards the environment temperature through the surface, so the heat a
 * fire puts into the air above the ground still reaches the ground.
 *
 * <p>A surface that is refined, holds a heat source or lies under one, or under anything but gas or vacuum, only
 * takes in sunlight; conduction and radiation keep its top face, so flames and lava behave as without the sky. So
 * does a solid more than {@value #WEATHER_RANGE_K} K hotter or colder than its section's environment, which
 * something other than the weather heated or chilled, so the air above it feels it: the warm air over a hot block
 * of iron rises from it as before.
 *
 * <p><b>Cost.</b> The heat the sky brings in or takes out is {@linkplain ThermalActivity#forced declared as
 * forced}, so it keeps no section awake, and it reaches sleeping sections too: the ground warms and cools with
 * the day where nothing else is simulated, every {@value #SLEEPING_STRIDE}th step where none of the column's
 * sections is in the thermal scope. Heating or cooling that finishes a phase change or starts one from the other
 * side, such as snow melting away or a pond starting to freeze, wakes its section, so a host that shows phases
 * sees it. Everything exchanged with the sky and the atmosphere is declared.
 *
 * <p><b>Limits.</b> Every surface sees the whole sky, even at the bottom of a pit; light reflected below the
 * surface leaves the world; water that evaporates or condenses leaves the mass of its block as it was; the model
 * remembers each skin only while it runs, so skins start again at their blocks' temperatures when a world loads.
 */
public final class SkyModel implements PhysicsModel {

    /** The model id. */
    public static final String ID = "anchor:sky";

    /** Columns where no section is simulated are updated every this many steps, each over the time it waited. */
    public static final int SLEEPING_STRIDE = 4;

    /** Light stops once less than this fraction of what fell on the surface is left. */
    public static final double FAINTEST = 0.01;

    /** Light reaches at most this many blocks below the surface. */
    public static final int DEEPEST = 32;

    /** The length of a day, which sets how deep the daily cycle reaches into the ground, in seconds. */
    public static final double DAY_SECONDS = 86_400.0;

    /** The relative humidity steady rain or snow brings the air near the ground to. */
    public static final double RAINY_HUMIDITY = 0.95;

    /** A column whose open sky the host has not given. */
    public static final int UNKNOWN_SKY = Integer.MIN_VALUE;

    /**
     * The furthest a solid surface may be from its section's environment temperature, in kelvin, for this model to
     * keep its top face. Sunlit ground warms by a few kelvin as a whole and frosty ground cools by less; ice in the
     * hottest desert is 45 K colder than the air.
     */
    public static final double WEATHER_RANGE_K = 50.0;

    /** The angular frequency of the daily cycle, in radians per second. */
    static final double DAY_FREQUENCY = 2 * Math.PI / DAY_SECONDS;

    /** The skin holds at most this share of its block's heat capacity, so a thin layer stays part of its block. */
    static final double MOST_SKIN = 0.5;

    private static final double SIGMA = PhysicalConstants.STEFAN_BOLTZMANN;
    private static final double MELTING_ICE = PhysicalConstants.ZERO_CELSIUS;
    /** Kilograms of vapour per kilogram of air for each pascal of vapour pressure, at standard pressure. */
    private static final double VAPOUR_PER_PASCAL = SkyPhysics.MOLAR_MASS_RATIO / PhysicalConstants.STANDARD_ATMOSPHERE;
    private static final int COLUMNS = 256;
    private static final long Y_BITS = SectionPos.pack(0, -1, 0);

    // What a column's surface is.
    private static final byte NONE = 0;
    /** A solid this model keeps the top face of, with a skin. */
    private static final byte SOLID = 1;
    /** A liquid this model keeps the top face of, whose surface is the whole block. */
    private static final byte LIQUID = 2;
    /** A surface that only takes in sunlight. */
    private static final byte FORCED = 3;

    // What lies above an owned surface.
    /** No cell of the world: the open atmosphere. */
    private static final byte OPEN = 0;
    /** A cell of gas in the world. */
    private static final byte AIR = 1;
    /** Vacuum, which carries neither heat nor vapour away. */
    private static final byte EMPTY = 2;

    /**
     * What a host knows about the colour of its blocks that their materials do not say, such as the dye of a
     * block of wool.
     */
    @FunctionalInterface
    public interface Albedos {
        /**
         * Returns the albedo of a block.
         *
         * @param sectionKey the packed section position
         * @param block the block's index in the section
         * @return the fraction of sunlight the block's top reflects, or {@link Double#NaN} to use its material's
         */
        double albedo(long sectionKey, int block);
    }

    /**
     * What the sky did in one step.
     *
     * @param sunlightJ the sunlight the world took in, in joules
     * @param thermalJ the thermal radiation surfaces took from the sky less what they gave off, in joules
     * @param airJ the heat surfaces took from the open atmosphere, in joules; negative when they warmed it
     * @param vapourJ the latent heat surfaces took in as dew or frost settled, less what evaporation took from
     *     them, in joules
     * @param surfaces how many surfaces the model keeps the top faces of
     * @param updated how many columns it updated
     */
    public record StepSummary(double sunlightJ, double thermalJ, double airJ, double vapourJ, int surfaces,
            int updated) {
    }

    /** The 16 by 16 columns of blocks above one section column, with what lies in each. */
    private static final class Group {
        final int sectionX;
        final int sectionZ;
        /** The lowest open y of each column, or {@link #UNKNOWN_SKY}. */
        final int[] skyFrom = new int[COLUMNS];
        /** The world's sections in the column, from the bottom up, and their heights. */
        Section[] sections = new Section[0];
        int[] heights = new int[0];
        boolean dirty = true;
        boolean present;

        final byte[] kind = new byte[COLUMNS];
        final long[] surfaceKey = new long[COLUMNS];
        final int[] surfaceBlock = new int[COLUMNS];
        /** The fraction of the sunlight on level ground that the surface takes in. */
        final double[] surfaceShare = new double[COLUMNS];
        final byte[] above = new byte[COLUMNS];
        final long[] aboveKey = new long[COLUMNS];
        final int[] aboveBlock = new int[COLUMNS];
        /** The skin of a solid surface, in kelvin, or {@link Double#NaN} before its first update. */
        final double[] skin = new double[COLUMNS];
        /** The temperature of each owned surface after its last update, in kelvin. */
        final double[] surfaceK = new double[COLUMNS];
        /** The time each column has waited since its last update, in seconds. */
        final double[] owed = new double[COLUMNS];

        /** Where each column's deposits below the surface start; the last entry is the end. */
        final int[] depositFrom = new int[COLUMNS + 1];
        long[] depositKey = new long[COLUMNS];
        int[] depositBlock = new int[COLUMNS];
        double[] depositShare = new double[COLUMNS];
        /** Whether the light enters the block from below, as a liquid's bed hands it on. */
        boolean[] depositFromBelow = new boolean[COLUMNS];
        int deposits;

        Group(int sectionX, int sectionZ) {
            this.sectionX = sectionX;
            this.sectionZ = sectionZ;
            Arrays.fill(skyFrom, UNKNOWN_SKY);
            Arrays.fill(surfaceBlock, -1);
            Arrays.fill(skin, Double.NaN);
            Arrays.fill(surfaceK, Double.NaN);
        }

        /** Returns the section at a section height, or {@code null} if the world holds none there. */
        Section at(int height) {
            int i = Arrays.binarySearch(heights, height);
            return i >= 0 ? sections[i] : null;
        }

        void addDeposit(long key, int block, double share, boolean fromBelow) {
            if (deposits == depositKey.length) {
                int grown = deposits * 2;
                depositKey = Arrays.copyOf(depositKey, grown);
                depositBlock = Arrays.copyOf(depositBlock, grown);
                depositShare = Arrays.copyOf(depositShare, grown);
                depositFromBelow = Arrays.copyOf(depositFromBelow, grown);
            }
            depositKey[deposits] = key;
            depositBlock[deposits] = block;
            depositShare[deposits] = share;
            depositFromBelow[deposits] = fromBelow;
            deposits++;
        }
    }

    private final LongToDoubleFunction environment;
    private final LongToDoubleFunction humidity;
    private final Predicate<GridPos> held;
    private final ThermalActivity activity;
    private final Albedos albedos;

    private final TreeMap<Long, Group> groups = new TreeMap<>();
    /** For each section, one bit per block whose top face this model keeps. */
    private final TreeMap<Long, long[]> openTops = new TreeMap<>();
    private Sky sky;
    private long steps;
    /** The sections the thermal models simulated in the last step. */
    private SortedSet<Long> simulated = Collections.emptySortedSet();

    // Scratch, kept from step to step.
    private final CellState written = CellState.vacuum(Provenance.SIMULATED);
    private final List<Section> column = new ArrayList<>();
    private final List<CellId> leaves = new ArrayList<>();
    private final List<CellState> leafStates = new ArrayList<>();
    private MaterialRegistry registry;
    private double sunlightW;
    private double weatherK = Double.NaN;
    private double weatherHumidity = Double.NaN;
    private double weatherVapourPa;
    private double weatherSkyW;

    // What the step exchanged.
    private double given;
    private double absolute;
    private double sunlightJ;
    private double thermalJ;
    private double airJ;
    private double vapourJ;
    private int updated;
    private StepSummary last = new StepSummary(0, 0, 0, 0, 0, 0);

    /**
     * Creates the model with no sky, for a world where nothing is a heat source and no tracker decides what is
     * simulated.
     *
     * @param environment the environment temperature of each section, in kelvin, by packed section key
     * @param humidity the relative humidity of the air around each section, from 0 to 1, by packed section key
     */
    public SkyModel(LongToDoubleFunction environment, LongToDoubleFunction humidity) {
        this(environment, humidity, null, null, null);
    }

    /**
     * Creates the model with no sky.
     *
     * @param environment the environment temperature of each section, in kelvin, by packed section key
     * @param humidity the relative humidity of the air around each section, from 0 to 1, by packed section key
     * @param held the blocks that are heat sources, whose top faces the model leaves alone, or {@code null} for
     *     none
     * @param activity the tracker to tell about forced heat and phase changes, or {@code null} for none
     * @param albedos what the host knows about the colour of its blocks, or {@code null} for nothing
     */
    public SkyModel(LongToDoubleFunction environment, LongToDoubleFunction humidity, Predicate<GridPos> held,
            ThermalActivity activity, Albedos albedos) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.humidity = Objects.requireNonNull(humidity, "humidity");
        this.held = held;
        this.activity = activity;
        this.albedos = albedos;
    }

    /**
     * Sets the sky for the steps that follow.
     *
     * @param newSky the sky, or {@code null} for a world without one, such as a cave world, where the model does
     *     nothing
     */
    public void setSky(Sky newSky) {
        if (newSky == null && sky != null) {
            openTops.clear();
            for (Group g : groups.values()) {
                g.dirty = true;
                Arrays.fill(g.kind, NONE);
            }
        }
        sky = newSky;
    }

    /**
     * Returns the sky.
     *
     * @return the sky, or {@code null} if the world has none
     */
    public Sky sky() {
        return sky;
    }

    /**
     * Says where the open sky begins in a column: everything from that height up is open to the sky. Heights of
     * columns where the world holds no section are forgotten at the next step, so give them after importing the
     * sections.
     *
     * @param x the column's x coordinate, in blocks
     * @param z the column's z coordinate, in blocks
     * @param firstOpenY the lowest y at which the column is open to the sky, or {@link #UNKNOWN_SKY} to forget
     *     it, so that the sky begins above the world's highest section there
     */
    public void setSkyHeight(int x, int z, int firstOpenY) {
        Group g = groups.computeIfAbsent(groupKey(x >> 4, z >> 4), k -> new Group(x >> 4, z >> 4));
        int col = (x & 15) | ((z & 15) << 4);
        if (g.skyFrom[col] != firstOpenY) {
            g.skyFrom[col] = firstOpenY;
            g.dirty = true;
        }
    }

    /**
     * Returns where the open sky begins in a column, as the host gave it.
     *
     * @param x the column's x coordinate, in blocks
     * @param z the column's z coordinate, in blocks
     * @return the lowest open y, or {@link #UNKNOWN_SKY}
     */
    public int skyHeight(int x, int z) {
        Group g = groups.get(groupKey(x >> 4, z >> 4));
        return g == null ? UNKNOWN_SKY : g.skyFrom[(x & 15) | ((z & 15) << 4)];
    }

    /**
     * Returns the temperature of a surface whose top face this model keeps: the skin of a solid, or a liquid's
     * own temperature, after its last update.
     *
     * @param pos the block
     * @return the temperature in kelvin, or {@link Double#NaN} if the block is no such surface or has not been
     *     updated yet
     */
    public double surfaceTemperature(GridPos pos) {
        Group g = groups.get(groupKey(pos.x() >> 4, pos.z() >> 4));
        if (g == null) {
            return Double.NaN;
        }
        int col = (pos.x() & 15) | ((pos.z() & 15) << 4);
        boolean owned = g.kind[col] == SOLID || g.kind[col] == LIQUID;
        return owned && isSurface(g, col, pos) ? g.surfaceK[col] : Double.NaN;
    }

    /**
     * Forgets the skins of the surfaces in a box of blocks, so that they start again from their blocks'
     * temperatures, as when a world loads; for blocks set from outside the simulation, such as a restored snapshot.
     *
     * @param min the box's lowest corner, inclusive
     * @param max the box's highest corner, inclusive
     */
    public void forgetSkins(GridPos min, GridPos max) {
        for (int z = min.z(); z <= max.z(); z++) {
            for (int x = min.x(); x <= max.x(); x++) {
                Group g = groups.get(groupKey(x >> 4, z >> 4));
                if (g == null) {
                    continue;
                }
                g.dirty = true;
                int col = (x & 15) | ((z & 15) << 4);
                if (g.surfaceBlock[col] < 0) {
                    continue;
                }
                int y = GridPos.of(g.surfaceKey[col], g.surfaceBlock[col]).y();
                if (y >= min.y() && y <= max.y()) {
                    g.skin[col] = Double.NaN;
                    g.surfaceK[col] = Double.NaN;
                }
            }
        }
    }

    /**
     * Returns the sunlight a block takes in now, at its surface or below it.
     *
     * @param pos the block
     * @return the power per square metre of the column, in W/m²; zero in shade, at night or without a sky
     */
    public double absorbedSunlight(GridPos pos) {
        Group g = groups.get(groupKey(pos.x() >> 4, pos.z() >> 4));
        if (g == null || sky == null) {
            return 0.0;
        }
        int col = (pos.x() & 15) | ((pos.z() & 15) << 4);
        if (g.kind[col] == NONE) {
            return 0.0;
        }
        double share = isSurface(g, col, pos) ? surfaceIntake(g, col) : 0.0;
        long key = pos.sectionKey();
        int block = pos.indexInSection();
        if (simulated.contains(key)) {
            for (int d = g.depositFrom[col]; d < g.depositFrom[col + 1]; d++) {
                if (g.depositKey[d] == key && g.depositBlock[d] == block) {
                    share += g.depositShare[d];
                }
            }
        }
        return share * SkyPhysics.sunlightOnLevelGround(sky);
    }

    private static boolean isSurface(Group g, int col, GridPos pos) {
        return g.surfaceKey[col] == pos.sectionKey() && g.surfaceBlock[col] == pos.indexInSection();
    }

    /**
     * Returns, one bit per block, the blocks of a section whose top face this model keeps: conduction and radiation
     * leave those faces to it.
     *
     * @param sectionKey the packed section position
     * @return 64 words of bits in block order, or {@code null} if the model keeps no top face there; the array is
     *     the model's own and must not be changed
     */
    long[] openTops(long sectionKey) {
        return openTops.get(sectionKey);
    }

    /**
     * Tells whether this model keeps the top face of a block.
     *
     * @param sectionKey the packed section position
     * @param block the block's index in the section
     * @return {@code true} if conduction and radiation leave the block's top face to this model
     */
    public boolean keepsTopOf(long sectionKey, int block) {
        long[] tops = openTops.get(sectionKey);
        return tops != null && (tops[block >>> 6] & (1L << block)) != 0;
    }

    /**
     * Returns what the sky did in the last step.
     *
     * @return the summary
     */
    public StepSummary lastStep() {
        return last;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Domain domain() {
        return Domain.THERMAL;
    }

    @Override
    public String assumptions() {
        return "Sunlight falls straight down each column of blocks and is taken in by the first block that is not "
                + "gas, or partly by translucent blocks below it; only top faces see the sun and every surface sees "
                + "the whole sky. Solid surfaces carry a force-restore skin. Surfaces exchange thermal radiation with "
                + "the sky, heat with the open atmosphere at " + ConductionModel.CONVECTION_COEFFICIENT
                + " W/(m2 K), and latent heat where they are wet; evaporated water is not removed. All of it is "
                + "declared.";
    }

    @Override
    public int priority() {
        return -5;
    }

    @Override
    public long estimateCost(StepContext context) {
        return sky == null ? 0 : (long) groups.size() * COLUMNS;
    }

    @Override
    public void step(StepContext context) {
        PhysicalWorld world = context.world();
        registry = world.materials();
        steps++;
        syncGroups(world);
        given = 0;
        absolute = 0;
        sunlightJ = 0;
        thermalJ = 0;
        airJ = 0;
        vapourJ = 0;
        updated = 0;
        weatherK = Double.NaN;
        if (sky == null) {
            last = new StepSummary(0, 0, 0, 0, 0, 0);
            return;
        }
        sunlightW = SkyPhysics.sunlightOnLevelGround(sky);
        SortedSet<Long> scope = context.sections(Domain.THERMAL);
        simulated = scope;
        int surfaces = 0;
        for (Group g : groups.values()) {
            boolean live = false;
            for (Section s : g.sections) {
                live |= scope.contains(s.key());
            }
            if (g.dirty || live) {
                trace(g);
            }
            for (int col = 0; col < COLUMNS; col++) {
                byte kind = g.kind[col];
                if (kind == NONE) {
                    g.owed[col] = 0;
                    continue;
                }
                g.owed[col] += context.dt();
                if (kind == SOLID || kind == LIQUID) {
                    surfaces++;
                }
                if (!live && (steps + col) % SLEEPING_STRIDE != 0) {
                    continue;
                }
                double dt = g.owed[col];
                g.owed[col] = 0;
                update(world, g, col, dt);
                updated++;
            }
        }
        if (absolute > 0) {
            // Declared every step, so not logged as an event; lastStep() reports it.
            world.recordExchange(new Totals(0, given, absolute, new EnumMap<>(Element.class)), null);
        }
        last = new StepSummary(sunlightJ, thermalJ, airJ, vapourJ, surfaces, updated);
    }

    // ---- which columns exist ----

    private static long groupKey(int sectionX, int sectionZ) {
        return SectionPos.pack(sectionX, 0, sectionZ);
    }

    /** Brings the groups in line with the world's sections, marking those whose sections changed. */
    private void syncGroups(PhysicalWorld world) {
        for (Group g : groups.values()) {
            g.present = false;
        }
        // The world keeps sections in key order, which keeps each column's sections together.
        long current = 0;
        column.clear();
        for (Section s : world.sections()) {
            long key = s.key() & ~Y_BITS;
            if (!column.isEmpty() && key != current) {
                adopt(current);
            }
            current = key;
            column.add(s);
        }
        if (!column.isEmpty()) {
            adopt(current);
        }
        for (Iterator<Map.Entry<Long, Group>> it = groups.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Group> e = it.next();
            if (!e.getValue().present) {
                clearTops(e.getKey());
                it.remove();
            }
        }
    }

    /** Takes in the sections of one column, gathered in {@link #column}. */
    private void adopt(long key) {
        int x = SectionPos.x(key);
        int z = SectionPos.z(key);
        Group g = groups.computeIfAbsent(key, k -> new Group(x, z));
        g.present = true;
        column.sort(Comparator.comparingInt(s -> SectionPos.y(s.key())));
        boolean same = column.size() == g.sections.length;
        for (int i = 0; same && i < g.sections.length; i++) {
            same = column.get(i) == g.sections[i];
        }
        if (!same) {
            g.sections = column.toArray(new Section[0]);
            g.heights = new int[g.sections.length];
            for (int i = 0; i < g.heights.length; i++) {
                g.heights[i] = SectionPos.y(g.sections[i].key());
            }
            g.dirty = true;
        }
        column.clear();
    }

    private void clearTops(long groupKey) {
        openTops.subMap(groupKey, true, groupKey | Y_BITS, true).clear();
    }

    // ---- where the light goes ----

    /** Works out again what lies in each column of a group, and which top faces the model keeps. */
    private void trace(Group g) {
        long key = groupKey(g.sectionX, g.sectionZ);
        clearTops(key);
        g.deposits = 0;
        int highest = g.heights.length == 0 ? Integer.MIN_VALUE : (g.heights[g.heights.length - 1] << 4) + 15;
        for (int col = 0; col < COLUMNS; col++) {
            g.depositFrom[col] = g.deposits;
            long oldKey = g.surfaceKey[col];
            int oldBlock = g.surfaceBlock[col];
            byte oldKind = g.kind[col];
            traceColumn(g, col, highest);
            if (g.surfaceBlock[col] != oldBlock || g.surfaceKey[col] != oldKey || g.kind[col] != oldKind) {
                g.skin[col] = Double.NaN;
                g.surfaceK[col] = Double.NaN;
            }
            if (g.kind[col] == SOLID || g.kind[col] == LIQUID) {
                long[] tops = openTops.computeIfAbsent(g.surfaceKey[col], k -> new long[SectionPos.BLOCKS / 64]);
                int b = g.surfaceBlock[col];
                tops[b >>> 6] |= 1L << b;
            }
        }
        g.depositFrom[COLUMNS] = g.deposits;
        g.dirty = false;
    }

    /** Follows the light down one column. */
    private void traceColumn(Group g, int col, int highest) {
        g.kind[col] = NONE;
        g.surfaceBlock[col] = -1;
        g.surfaceShare[col] = 0;
        int lx = col & 15;
        int lz = col >>> 4;
        int y = g.skyFrom[col] == UNKNOWN_SKY ? highest : g.skyFrom[col] - 1;
        if (y > highest || highest == Integer.MIN_VALUE) {
            // Covered by something the world does not hold.
            return;
        }
        double blueGreen = Surface.BLUE_GREEN_SHARE;
        double red = 1.0 - Surface.BLUE_GREEN_SHARE;
        boolean found = false;
        boolean fromClear = true;
        // The liquid block the light last crossed, if it lies right above the current block.
        long liquidKey = 0;
        int liquidBlock = -1;
        int depth = 0;
        while (true) {
            Section s = g.at(y >> 4);
            if (s == null) {
                return; // the rest of the light leaves the world
            }
            int b = SectionPos.localIndex(lx, y & 15, lz);
            int material = s.material(b);
            double mass = s.mass(b);
            Material m = material == MaterialRegistry.VACUUM || mass == 0 ? null : registry.get(material);
            double specific = m == null ? 0.0 : s.enthalpy(b) / mass;
            Surface surface = m == null ? null : m.surface(specific);
            if (surface == null) {
                if (found && ++depth > DEEPEST) {
                    return;
                }
                fromClear = true;
                liquidBlock = -1;
                y--;
                continue;
            }
            double albedo = surface.albedo();
            if (albedos != null) {
                double painted = albedos.albedo(s.key(), b);
                if (painted >= 0 && painted <= 1) {
                    albedo = painted;
                }
            }
            boolean translucent = surface.translucent();
            if (!translucent || fromClear) {
                blueGreen *= 1.0 - albedo;
                red *= 1.0 - albedo;
            }
            double absorbed;
            boolean last;
            if (translucent) {
                double fill = Math.min(1.0, mass / m.density(m.stateFor(specific)));
                double passBlueGreen = StrictMath.pow(surface.blueGreenTransmittance(), fill);
                double passRed = StrictMath.pow(surface.redInfraredTransmittance(), fill);
                absorbed = blueGreen * (1.0 - passBlueGreen) + red * (1.0 - passRed);
                blueGreen *= passBlueGreen;
                red *= passRed;
                last = blueGreen + red < FAINTEST || (found && depth >= DEEPEST);
                if (last) {
                    absorbed += blueGreen + red;
                }
            } else {
                absorbed = blueGreen + red;
                last = true;
            }
            Phase phase = m.thermal().regions().get(m.dominantRegion(specific)).phase();
            if (!found) {
                found = true;
                surface(g, col, s, b, y, phase, m.temperatureFor(specific));
                g.surfaceShare[col] = absorbed;
            } else if (!translucent && liquidBlock >= 0) {
                g.addDeposit(liquidKey, liquidBlock, absorbed, true);
            } else {
                g.addDeposit(s.key(), b, absorbed, false);
            }
            if (last) {
                return;
            }
            fromClear = false;
            if (phase == Phase.LIQUID) {
                liquidKey = s.key();
                liquidBlock = b;
            } else {
                liquidBlock = -1;
            }
            depth++;
            y--;
        }
    }

    /** Records a column's surface and decides whether the model keeps its top face. */
    private void surface(Group g, int col, Section s, int b, int y, Phase phase, double temperatureK) {
        g.surfaceKey[col] = s.key();
        g.surfaceBlock[col] = b;
        boolean forced = s.isRefined(b) || (held != null && held.test(s.blockPos(b)))
                || (phase == Phase.SOLID
                        && Math.abs(temperatureK - environment.applyAsDouble(s.key())) > WEATHER_RANGE_K);
        Section up = g.at((y + 1) >> 4);
        g.above[col] = OPEN;
        if (up != null) {
            int a = SectionPos.localIndex(col & 15, (y + 1) & 15, col >>> 4);
            int material = up.material(a);
            double mass = up.mass(a);
            if (material == MaterialRegistry.VACUUM || mass == 0) {
                g.above[col] = EMPTY;
            } else if (registry.get(material).surface(up.enthalpy(a) / mass) == null) {
                g.above[col] = AIR;
                g.aboveKey[col] = up.key();
                g.aboveBlock[col] = a;
                forced |= up.isRefined(a) || (held != null && held.test(up.blockPos(a)));
            } else {
                // Matter the host's sky height left out lies on the surface; leave the face to conduction.
                forced = true;
            }
        }
        if (forced) {
            g.kind[col] = FORCED;
        } else {
            g.kind[col] = phase == Phase.LIQUID ? LIQUID : SOLID;
        }
    }

    // ---- what each column does in a step ----

    private void update(PhysicalWorld world, Group g, int col, double dt) {
        double share = surfaceIntake(g, col);
        if (g.kind[col] != FORCED) {
            balance(world, g, col, dt, share);
        } else if (sunlightW > 0 && share > 0) {
            double joules = sunlightW * share * dt;
            deposit(world, g, g.surfaceKey[col], g.surfaceBlock[col], joules, false);
            sunlightJ += joules;
        }
        if (sunlightW > 0) {
            for (int d = g.depositFrom[col]; d < g.depositFrom[col + 1]; d++) {
                if (simulated.contains(g.depositKey[d])) {
                    double joules = sunlightW * g.depositShare[d] * dt;
                    deposit(world, g, g.depositKey[d], g.depositBlock[d], joules, g.depositFromBelow[d]);
                    sunlightJ += joules;
                }
            }
        }
    }

    /**
     * Returns the fraction of the sunlight on level ground that a column's surface takes in now: its own share, and
     * the shares bound for blocks below it in sections the thermal models do not simulate, where nothing would carry
     * the heat away. A surface whose top face the model leaves to conduction takes in nothing while its own section
     * is not simulated, for the same reason.
     */
    private double surfaceIntake(Group g, int col) {
        if (g.kind[col] == FORCED && !simulated.contains(g.surfaceKey[col])) {
            return 0.0;
        }
        double share = g.surfaceShare[col];
        for (int d = g.depositFrom[col]; d < g.depositFrom[col + 1]; d++) {
            if (!simulated.contains(g.depositKey[d])) {
                share += g.depositShare[d];
            }
        }
        return share;
    }

    /** Sets the weather for a section's environment temperature and humidity, keeping the last one's values. */
    private void weather(double environmentK, double relativeHumidity) {
        if (environmentK == weatherK && relativeHumidity == weatherHumidity) {
            return;
        }
        weatherK = environmentK;
        weatherHumidity = relativeHumidity;
        double rh = relativeHumidity;
        if (rh < RAINY_HUMIDITY) {
            rh += (RAINY_HUMIDITY - rh) * sky.precipitation();
        }
        weatherVapourPa = rh * SkyPhysics.saturationOverWater(environmentK);
        weatherSkyW = SkyPhysics.skyRadiation(environmentK, weatherVapourPa, sky.cloudCover());
    }

    /**
     * Balances the energy at a surface whose top face the model keeps, and moves the heat that crosses it, with the
     * surface taking in the given fraction of the sunlight on level ground.
     */
    private void balance(PhysicalWorld world, Group g, int col, double dt, double share) {
        long key = g.surfaceKey[col];
        int b = g.surfaceBlock[col];
        Section s = world.section(key);
        int material = s.material(b);
        double mass = s.mass(b);
        if (material == MaterialRegistry.VACUUM || mass == 0) {
            // Emptied since the column was traced; trace it again next step.
            g.dirty = true;
            return;
        }
        double enthalpy = s.enthalpy(b);
        Material m = registry.get(material);
        ThermalState state = m.stateFor(enthalpy / mass);
        double blockK = state.temperatureK();
        List<PhaseRegion> regions = m.thermal().regions();
        int dominant = m.dominantRegion(enthalpy / mass);
        PhaseRegion region = regions.get(dominant);
        if (region.phase() == Phase.GAS) {
            // Boiled away, or replaced by the host, since the column was traced; trace it again next step.
            g.dirty = true;
            return;
        }
        double environmentK = environment.applyAsDouble(key);
        weather(environmentK, humidity.applyAsDouble(key));
        double vapourPa = weatherVapourPa;
        double emissivity = m.emissivity(state);
        double sunW = sunlightW * share;

        byte above = g.above[col];
        double coefficient = above == EMPTY ? 0.0 : ConductionModel.CONVECTION_COEFFICIENT;
        double cellK = environmentK;
        Section cellSection = null;
        Material cellMaterial = null;
        int cell = g.aboveBlock[col];
        if (above == AIR) {
            cellSection = world.section(g.aboveKey[col]);
            int cellMaterialIndex = cellSection.material(cell);
            if (cellMaterialIndex == MaterialRegistry.VACUUM || cellSection.mass(cell) == 0) {
                above = OPEN;
                g.dirty = true;
            } else {
                cellMaterial = registry.get(cellMaterialIndex);
                cellK = cellMaterial.temperatureFor(cellSection.enthalpy(cell) / cellSection.mass(cell));
            }
        }
        Surface surface = region.surfaceOrDefault();
        boolean wet = coefficient > 0 && surface.wet();

        double t;
        double capacity;
        double restore;
        if (g.kind[col] == SOLID) {
            t = Double.isNaN(g.skin[col]) ? blockK : g.skin[col];
            double c = m.specificHeat(state);
            double effusivity = Math.sqrt(m.conductivity(state) * m.density(state) * c);
            capacity = Math.min(effusivity / Math.sqrt(2.0 * DAY_FREQUENCY), MOST_SKIN * mass * c);
            restore = capacity * DAY_FREQUENCY;
        } else {
            t = blockK;
            capacity = mass * m.specificHeat(state);
            restore = 0.0;
        }

        double cube = t * t * t;
        double emitted = emissivity * SIGMA * cube * t;
        double emittedSlope = 4.0 * emissivity * SIGMA * cube;
        double latent = 0.0;
        double latentSlope = 0.0;
        if (wet) {
            boolean ice = t < MELTING_ICE;
            double saturation = ice ? SkyPhysics.saturationOverIce(t) : SkyPhysics.saturationOverWater(t);
            double rise = ice ? SkyPhysics.saturationOverIceSlope(t) : SkyPhysics.saturationOverWaterSlope(t);
            double heat = ice ? SkyPhysics.SUBLIMATION_HEAT : SkyPhysics.vaporisationHeat(t);
            double airDensity = SkyPhysics.airDensity(environmentK);
            // The air holds vapour back as it holds heat back; dew and frost settle without the surface's say.
            double resistance = airDensity * SkyPhysics.AIR_SPECIFIC_HEAT / coefficient
                    + (saturation > vapourPa ? surface.evaporationResistance() : 0.0);
            double conductance = heat * airDensity * VAPOUR_PER_PASCAL / resistance;
            latent = conductance * (saturation - vapourPa);
            latentSlope = conductance * rise;
        }
        double flux = sunW + emissivity * weatherSkyW - emitted - coefficient * (t - cellK) - latent;
        double slope = -emittedSlope - coefficient - latentSlope;
        double next = (capacity * t / dt + flux - slope * t + restore * blockK) / (capacity / dt - slope + restore);
        if (g.kind[col] == SOLID) {
            if (dominant + 1 < regions.size() && regions.get(dominant + 1).phase() != Phase.SOLID) {
                next = Math.min(next, region.toK());
            }
            g.skin[col] = next;
        } else if (state.inTransition()) {
            // Melting or freezing, the liquid stays at the temperature of its change until the change is done.
            next = blockK;
        } else {
            next = Math.max(region.fromK(), Math.min(region.toK(), next));
        }
        g.surfaceK[col] = next;

        // What crosses the surface over the step, with each flux taken as linear in the surface temperature.
        double change = next - t;
        double sun = sunW * dt;
        double thermal = (emissivity * weatherSkyW - emitted - emittedSlope * change) * dt;
        double air = -coefficient * (next - environmentK) * dt;
        double vapour = -(latent + latentSlope * change) * dt;
        double external = sun + thermal + air + vapour;
        double fromCell = 0.0;
        if (above == AIR) {
            double cellMass = cellSection.mass(cell);
            double cellEnthalpy = cellSection.enthalpy(cell);
            double cellCapacity = cellMass * cellMaterial.specificHeat(cellMaterial.stateFor(cellEnthalpy / cellMass));
            double target = cellMass * cellMaterial.nearestSpecificEnthalpyIn(Phase.GAS,
                    cellMaterial.specificEnthalpy(environmentK));
            fromCell = (cellEnthalpy - target) * -StrictMath.expm1(-coefficient * dt / cellCapacity);
            if (fromCell != 0) {
                written.set(cellSection.material(cell), cellMass, cellEnthalpy - fromCell, cellSection.owner(cell),
                        Provenance.SIMULATED);
                world.writeBlock(g.aboveKey[col], cell, written);
            }
        }
        double after = enthalpy + external + fromCell;
        written.set(material, mass, after, s.owner(b), Provenance.SIMULATED);
        world.writeBlock(key, b, written);
        if (activity != null) {
            activity.forced(key, b, external);
        }
        changedPhase(g, key, m, mass, enthalpy, after);
        sunlightJ += sun;
        thermalJ += thermal;
        airJ += air;
        vapourJ += vapour;
        given += external;
        absolute += Math.abs(sun) + Math.abs(thermal) + Math.abs(air) + Math.abs(vapour);
    }

    /**
     * Puts sunlight into a block: all of it into a block that is not refined, or into the cells of a refined
     * block that touch the face it enters by, in proportion to their area.
     */
    private void deposit(PhysicalWorld world, Group g, long key, int b, double joules, boolean fromBelow) {
        Section s = world.section(key);
        given += joules;
        absolute += Math.abs(joules);
        RefinedBlock refined = s.refinedBlock(b);
        if (refined == null) {
            int material = s.material(b);
            double mass = s.mass(b);
            double before = s.enthalpy(b);
            written.set(material, mass, before + joules, s.owner(b), Provenance.SIMULATED);
            world.writeBlock(key, b, written);
            if (activity != null) {
                activity.forced(key, b, joules);
            }
            changedPhase(g, key, registry.get(material), mass, before, before + joules);
            return;
        }
        int face = 1 << (fromBelow ? Direction.DOWN : Direction.UP).ordinal();
        leaves.clear();
        leafStates.clear();
        double[] area = {0.0, 0.0};
        refined.forEachLeaf((leaf, state) -> {
            if (state.material() == MaterialRegistry.VACUUM || state.mass() == 0) {
                return;
            }
            boolean touching = (RadiationModel.touching(leaf) & face) != 0;
            double a = leaf.edgeLength() * leaf.edgeLength();
            area[0] += touching ? a : 0.0;
            area[1] += a;
            leaves.add(leaf);
            leafStates.add(state.copy());
        });
        // Should no cell with matter touch the face, the light reaches the block's matter wherever it lies.
        boolean anyTouching = area[0] > 0;
        double total = anyTouching ? area[0] : area[1];
        for (int i = 0; i < leaves.size(); i++) {
            CellId leaf = leaves.get(i);
            if (anyTouching && (RadiationModel.touching(leaf) & face) == 0) {
                continue;
            }
            CellState state = leafStates.get(i);
            double share = joules * leaf.edgeLength() * leaf.edgeLength() / total;
            double after = state.enthalpy() + share;
            world.writeLeaf(leaf, new CellState(state.material(), state.mass(), after, state.owner(),
                    Provenance.SIMULATED));
            if (activity != null) {
                activity.forced(key, b, leaf, share);
            }
            changedPhase(g, key, registry.get(state.material()), state.mass(), state.enthalpy(), after);
        }
    }

    /**
     * Traces a column again when heat changed which phase most of some matter is in, which changes how it meets the
     * light, and wakes its section when the matter finished a phase change or passed into another phase: a phase it
     * could be shown in may have gone, and a host looks for such blocks in the sections being simulated.
     */
    private void changedPhase(Group g, long key, Material m, double mass, double before, double after) {
        double from = before / mass;
        double to = after / mass;
        if (m.dominantRegion(from) != m.dominantRegion(to)) {
            g.dirty = true;
        }
        if (activity != null) {
            ThermalState was = m.stateFor(from);
            ThermalState is = m.stateFor(to);
            if (was.region() != is.region() || (was.inTransition() && !is.inTransition())) {
                activity.wake(key);
            }
        }
    }
}
