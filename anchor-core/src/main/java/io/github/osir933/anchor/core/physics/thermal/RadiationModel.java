package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.matter.Element;
import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.matter.PhaseRegion;
import io.github.osir933.anchor.core.matter.ThermalState;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.model.PhysicsModel;
import io.github.osir933.anchor.core.model.StepContext;
import io.github.osir933.anchor.core.model.ValidityIssue;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.units.PhysicalConstants;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.Section;
import io.github.osir933.anchor.core.world.Totals;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.LongToDoubleFunction;

/**
 * Thermal radiation between surfaces, across gas and vacuum. A hot block glows on the blocks it faces even
 * with air between them, and a block of ice in a warm room takes in the room's radiation.
 *
 * <p>Surfaces are grey and diffuse, and gases are transparent: the faces of a solid or liquid block that touch
 * gas or vacuum radiate. Two faces that see each other exchange {@code σ·ε₁·ε₂·A·F·(T₁⁴ − T₂⁴)}, with
 * {@code F} the view factor from one to the other and reflections left out, which is close for the rough,
 * oxidised surfaces most blocks have (emissivity 0.7 to 0.97) and too low for polished metal facing polished
 * metal. Glass and water are opaque at these wavelengths: they take in radiation and pass it on as heat.
 *
 * <p>View factors are sampled with a fixed {@linkplain RayPattern pattern of rays} from each face, which stop
 * at the first block that does not let radiation through. Each ray carries the same share of the face's view,
 * so what one face sends and receives is right however the rays split it up. A ray that travels further than
 * the range, or that leaves the world's matter, reaches surroundings at the face's section's environment
 * temperature, a black body; that exchange is declared. A ray that meets a refined block is not counted:
 * refined blocks do not radiate yet.
 *
 * <p>Radiation between surfaces at nearly the same temperature is small, so only faces whose block is more
 * than {@value #DEFAULT_RADIATING_DIFFERENCE_K} kelvin (by default) hotter or colder than its section's
 * environment cast rays, which keeps the cost with what is hot or cold rather than with every surface. Such a
 * face exchanges with whatever it sees, even blocks outside the step's scope; heat that reaches them wakes
 * them as any other change does. When two radiating faces see each other, both estimate their exchange and
 * each counts half. The rays of a face are kept until a block on their way changes whether it lets radiation
 * through or is refined, so a steady scene casts no rays at all.
 *
 * <p>The exchange is explicit, with temperatures updated as often as the hottest, smallest blocks need to stay
 * stable, at most {@value #MAX_SUBSTEPS} times a step; emissivities and heat capacities are evaluated once per
 * step. Blocks that would need more substeps than that have their exchange damped, which keeps them stable
 * but slow, and the step reports them.
 */
public final class RadiationModel implements PhysicsModel {

    /** The model id. */
    public static final String ID = "anchor:radiation";

    /** The default number of rays each face casts. */
    public static final int DEFAULT_RAYS_PER_FACE = 32;

    /** The default distance rays travel, in blocks; one section's width. */
    public static final double DEFAULT_RANGE_BLOCKS = 16.0;

    /** By default, how far from its environment's temperature a block must be for its faces to radiate. */
    public static final double DEFAULT_RADIATING_DIFFERENCE_K = 10.0;

    /** Fraction of the stability limit used for each substep. */
    static final double SAFETY = 0.45;

    /** The most substeps a step may take. */
    static final int MAX_SUBSTEPS = 64;

    private static final double SIGMA = PhysicalConstants.STEFAN_BOLTZMANN;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int WORDS = SectionPos.BLOCKS / 64;

    /** What {@link #march} found. */
    private static final int ESCAPED = 0;
    private static final int HIT = 1;
    private static final int LOST = 2;

    private final LongToDoubleFunction environment;
    private final RayPattern pattern;
    private final double range;
    private final double radiatingDifferenceK;

    private final TreeMap<Long, Opacity> opacity = new TreeMap<>();
    private final TreeMap<Long, SectionRays> raysBySection = new TreeMap<>();
    private long nextStamp = 1;

    /** Per material, the specific enthalpy above which it is mostly gas, and so lets radiation through. */
    private double[] gasAbove = new double[0];
    /** Per material, the specific enthalpies between which a block does not radiate, for {@link #hotFor}. */
    private double[] calmFrom = new double[0];
    private double[] calmTo = new double[0];
    private double calmFor = Double.NaN;

    private final int[] scratchMaterial = new int[SectionPos.BLOCKS];
    private final double[] scratchMass = new double[SectionPos.BLOCKS];
    private final double[] scratchEnthalpy = new double[SectionPos.BLOCKS];
    private final long[] scratchClear = new long[WORDS];
    private final long[] scratchRefined = new long[WORDS];
    // Separate from the ones above, which hold the section being scanned while neighbours are looked at.
    private final int[] opacityMaterial = new int[SectionPos.BLOCKS];
    private final double[] opacityMass = new double[SectionPos.BLOCKS];
    private final double[] opacityEnthalpy = new double[SectionPos.BLOCKS];

    // Where the last march stopped.
    private long hitSection;
    private int hitBlock;

    private final Exchange exchange = new Exchange();
    private List<ValidityIssue> issues = List.of();
    private double lastEnergy;
    private int lastRadiatingFaces;
    private long lastRaysCast;
    private long lastWork;

    /**
     * Creates the model with the default rays, range and radiating difference.
     *
     * @param environment the environment temperature of each section, in kelvin, by packed section key
     */
    public RadiationModel(LongToDoubleFunction environment) {
        this(environment, DEFAULT_RAYS_PER_FACE, DEFAULT_RANGE_BLOCKS, DEFAULT_RADIATING_DIFFERENCE_K);
    }

    /**
     * Creates the model.
     *
     * @param environment the environment temperature of each section, in kelvin, by packed section key
     * @param raysPerFace how many rays each face casts; more sample the view factors more finely
     * @param rangeBlocks how far rays travel, in blocks
     * @param radiatingDifferenceK how far from its environment's temperature a block must be for its faces to
     *     radiate, in kelvin
     */
    public RadiationModel(LongToDoubleFunction environment, int raysPerFace, double rangeBlocks,
            double radiatingDifferenceK) {
        this.environment = Objects.requireNonNull(environment, "environment");
        if (!(rangeBlocks > 0) || !Double.isFinite(rangeBlocks)) {
            throw new IllegalArgumentException("range must be positive: " + rangeBlocks);
        }
        if (!(radiatingDifferenceK >= 0) || !Double.isFinite(radiatingDifferenceK)) {
            throw new IllegalArgumentException("radiating difference must be finite and non-negative: "
                    + radiatingDifferenceK);
        }
        this.pattern = new RayPattern(raysPerFace);
        this.range = rangeBlocks;
        this.radiatingDifferenceK = radiatingDifferenceK;
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
        return "Grey diffuse surfaces exchange thermal radiation across gas and vacuum by the Stefan-Boltzmann "
                + "law, without reflections; view factors are sampled by " + pattern.rays() + " rays per face "
                + "over " + range + " blocks. Only blocks more than " + radiatingDifferenceK + " K from their "
                + "environment radiate; rays that go further reach black surroundings at the environment "
                + "temperature, declared. Blocks that would need more than " + MAX_SUBSTEPS + " substeps are "
                + "damped. Refined blocks do not radiate yet.";
    }

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public long estimateCost(StepContext context) {
        long blocks = 0;
        for (long key : context.sections(Domain.THERMAL)) {
            Section s = context.world().section(key);
            if (s != null) {
                blocks += s.isUniform() ? 1 : SectionPos.BLOCKS;
            }
        }
        return blocks + lastWork;
    }

    /**
     * Returns the heat radiation took from the world in the last step, sent to the surroundings; negative if
     * the surroundings gave heat.
     *
     * @return the energy in joules
     */
    public double lastStepEnergy() {
        return lastEnergy;
    }

    /**
     * Returns how many faces radiated in the last step.
     *
     * @return the number of faces
     */
    public int lastRadiatingFaces() {
        return lastRadiatingFaces;
    }

    /**
     * Returns how many rays the last step cast; faces whose rays were kept from earlier steps cast none.
     *
     * @return the number of rays
     */
    public long lastRaysCast() {
        return lastRaysCast;
    }

    @Override
    public void step(StepContext context) {
        PhysicalWorld world = context.world();
        MaterialRegistry registry = world.materials();
        learnMaterials(registry);
        forgetRemovedSections(world);
        CellState ambient = world.ambientBlock();
        boolean ambientClear = clear(ambient.material(), ambient.mass(), ambient.enthalpy());
        Exchange x = exchange;
        x.clear();
        int faces = 0;
        long cast = 0;
        for (long key : context.sections(Domain.THERMAL)) {
            Section section = world.section(key);
            if (section == null) {
                continue;
            }
            Opacity own = opacityOf(world, key);
            double environmentK = environment.applyAsDouble(key);
            hotFor(registry, environmentK);
            if (section.isUniform() && !hot(section.material(0), section.mass(0), section.enthalpy(0), own, 0)) {
                continue;
            }
            SectionRays cache = null;
            section.copyBlocks(scratchMaterial, scratchMass, scratchEnthalpy, null, 0);
            for (int b = 0; b < SectionPos.BLOCKS; b++) {
                if (!hot(scratchMaterial[b], scratchMass[b], scratchEnthalpy[b], own, b) || section.isRefined(b)) {
                    continue;
                }
                int open = clearFaces(world, key, own, b, ambientClear);
                if (open == 0) {
                    continue;
                }
                if (cache == null) {
                    cache = validCache(world, key, section);
                }
                int e = x.participant(key, b, true);
                for (Direction d : DIRECTIONS) {
                    if ((open & (1 << d.ordinal())) == 0) {
                        continue;
                    }
                    faces++;
                    FaceRays rays = cache.faces.get(b * DIRECTIONS.length + d.ordinal());
                    if (rays == null) {
                        rays = cast(world, key, b, d, cache, ambientClear);
                        cache.faces.put(b * DIRECTIONS.length + d.ordinal(), rays);
                        cast += pattern.rays();
                    }
                    x.escape(e, rays.escaped, environmentK);
                    for (int t = 0; t < rays.count.length; t++) {
                        x.link(e, x.participant(rays.section[t], rays.block[t], false), rays.count[t]);
                    }
                }
            }
        }
        List<ValidityIssue> found = new ArrayList<>();
        lastEnergy = 0;
        int substeps = x.count == 0 ? 0 : integrate(world, context.dt(), found);
        lastRadiatingFaces = faces;
        lastRaysCast = cast;
        lastWork = cast * (long) Math.ceil(range) + (long) x.links * Math.max(1, substeps);
        issues = found;
    }

    @Override
    public List<ValidityIssue> checkValidity(PhysicalWorld world) {
        return issues;
    }

    // ---- what lets radiation through ----

    /** Works out, for materials registered since the last step, where each turns into a gas. */
    private void learnMaterials(MaterialRegistry registry) {
        int size = registry.size();
        if (gasAbove.length == size) {
            return;
        }
        int from = gasAbove.length;
        gasAbove = Arrays.copyOf(gasAbove, size);
        calmFrom = Arrays.copyOf(calmFrom, size);
        calmTo = Arrays.copyOf(calmTo, size);
        for (int i = from; i < size; i++) {
            gasAbove[i] = i == MaterialRegistry.VACUUM ? Double.NEGATIVE_INFINITY : gasAbove(registry.get(i));
        }
        calmFor = Double.NaN;
    }

    /**
     * Returns the specific enthalpy above which a material is mostly gas, matching
     * {@link Material#dominantPhase}: halfway through boiling. Gas is the hottest phase, so only the regions
     * from the last non-gas one upwards matter.
     */
    static double gasAbove(Material material) {
        List<PhaseRegion> regions = material.thermal().regions();
        int first = regions.size();
        while (first > 0 && regions.get(first - 1).phase() == Phase.GAS) {
            first--;
        }
        if (first == regions.size()) {
            return Double.POSITIVE_INFINITY;
        }
        if (first == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        double end = material.thermal().regionEndEnthalpy(first - 1);
        double start = material.thermal().regionStartEnthalpy(first);
        return end + 0.5 * (start - end);
    }

    /** Tells whether matter lets radiation through: vacuum, or mostly gas. */
    private boolean clear(int material, double mass, double enthalpy) {
        return material == MaterialRegistry.VACUUM || mass == 0 || enthalpy / mass > gasAbove[material];
    }

    /** Works out, per material, the specific enthalpies of blocks too close to an environment to radiate. */
    private void hotFor(MaterialRegistry registry, double environmentK) {
        if (environmentK == calmFor) {
            return;
        }
        calmFor = environmentK;
        double low = Math.max(environmentK - radiatingDifferenceK, 1e-3);
        double high = environmentK + radiatingDifferenceK;
        for (int i = 0; i < calmFrom.length; i++) {
            if (i == MaterialRegistry.VACUUM) {
                calmFrom[i] = Double.NEGATIVE_INFINITY;
                calmTo[i] = Double.POSITIVE_INFINITY;
                continue;
            }
            Material m = registry.get(i);
            calmFrom[i] = m.specificEnthalpy(low);
            calmTo[i] = m.specificEnthalpy(high);
        }
    }

    /** Tells whether a block radiates: it blocks radiation and is far enough from its environment. */
    private boolean hot(int material, double mass, double enthalpy, Opacity own, int block) {
        if (isClear(own, block) || material == MaterialRegistry.VACUUM || mass == 0) {
            return false;
        }
        double h = enthalpy / mass;
        return h < calmFrom[material] || h > calmTo[material];
    }

    private static boolean isClear(Opacity o, int block) {
        return (o.clear[block >>> 6] & (1L << block)) != 0;
    }

    /** Returns, one bit per direction, the faces of a block that touch something radiation passes through. */
    private int clearFaces(PhysicalWorld world, long key, Opacity own, int block, boolean ambientClear) {
        int x = SectionPos.localX(block);
        int y = SectionPos.localY(block);
        int z = SectionPos.localZ(block);
        int open = 0;
        for (Direction d : DIRECTIONS) {
            int nx = x + d.dx();
            int ny = y + d.dy();
            int nz = z + d.dz();
            boolean clear;
            if ((nx | ny | nz) >= 0 && nx < 16 && ny < 16 && nz < 16) {
                clear = isClear(own, SectionPos.localIndex(nx, ny, nz));
            } else {
                Opacity o = opacityOf(world, SectionPos.offset(key, nx >> 4, ny >> 4, nz >> 4));
                clear = o == null ? ambientClear : isClear(o, SectionPos.localIndex(nx & 15, ny & 15, nz & 15));
            }
            if (clear) {
                open |= 1 << d.ordinal();
            }
        }
        return open;
    }

    /**
     * Returns which blocks of a section let radiation through, and which are refined, working them out again
     * if the section changed. Each different answer gets a new stamp, so kept rays can tell whether their way
     * and the blocks they end at are still as they were.
     *
     * @return the opacity, or {@code null} if the world holds no section there
     */
    private Opacity opacityOf(PhysicalWorld world, long key) {
        Section s = world.section(key);
        if (s == null) {
            return null;
        }
        Opacity o = opacity.get(key);
        if (o != null && o.section == s && o.version == s.version()) {
            return o;
        }
        boolean fresh = o == null || o.section != s;
        if (fresh) {
            o = new Opacity(s);
            opacity.put(key, o);
        }
        long[] clear = scratchClear;
        long[] refined = scratchRefined;
        Arrays.fill(clear, 0L);
        Arrays.fill(refined, 0L);
        if (s.isUniform()) {
            if (clear(s.material(0), s.mass(0), s.enthalpy(0))) {
                Arrays.fill(clear, -1L);
            }
        } else {
            s.copyBlocks(opacityMaterial, opacityMass, opacityEnthalpy, null, 0);
            for (int b = 0; b < SectionPos.BLOCKS; b++) {
                if (s.isRefined(b)) {
                    refined[b >>> 6] |= 1L << b;
                } else if (clear(opacityMaterial[b], opacityMass[b], opacityEnthalpy[b])) {
                    clear[b >>> 6] |= 1L << b;
                }
            }
        }
        if (fresh || !Arrays.equals(clear, o.clear) || !Arrays.equals(refined, o.refined)) {
            System.arraycopy(clear, 0, o.clear, 0, WORDS);
            System.arraycopy(refined, 0, o.refined, 0, WORDS);
            o.stamp = nextStamp++;
        }
        o.version = s.version();
        return o;
    }

    /** Drops what is kept about sections that left the world or were replaced. */
    private void forgetRemovedSections(PhysicalWorld world) {
        for (Iterator<Map.Entry<Long, Opacity>> it = opacity.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Opacity> e = it.next();
            if (world.section(e.getKey()) != e.getValue().section) {
                it.remove();
            }
        }
        for (Iterator<Map.Entry<Long, SectionRays>> it = raysBySection.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, SectionRays> e = it.next();
            if (world.section(e.getKey()) != e.getValue().section) {
                it.remove();
            }
        }
    }

    // ---- rays ----

    /** Returns the kept rays of a section's faces, emptied if a block on their way changed. */
    private SectionRays validCache(PhysicalWorld world, long key, Section section) {
        SectionRays cache = raysBySection.get(key);
        if (cache != null && cache.section == section) {
            boolean valid = true;
            for (Map.Entry<Long, Long> dep : cache.depends.entrySet()) {
                Opacity o = opacityOf(world, dep.getKey());
                if ((o == null ? 0L : o.stamp) != dep.getValue()) {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                return cache;
            }
        }
        cache = new SectionRays(section);
        raysBySection.put(key, cache);
        return cache;
    }

    /** Casts the rays of one face and gathers where they end. */
    private FaceRays cast(PhysicalWorld world, long key, int block, Direction face, SectionRays cache,
            boolean ambientClear) {
        int bx = (SectionPos.x(key) << 4) | SectionPos.localX(block);
        int by = (SectionPos.y(key) << 4) | SectionPos.localY(block);
        int bz = (SectionPos.z(key) << 4) | SectionPos.localZ(block);
        int n = pattern.rays();
        long[] sections = new long[n];
        int[] blocks = new int[n];
        int[] counts = new int[n];
        int targets = 0;
        int escaped = 0;
        for (int ray = 0; ray < n; ray++) {
            int result = march(world, bx, by, bz, face, ray, cache, ambientClear);
            if (result == ESCAPED) {
                escaped++;
            } else if (result == HIT) {
                int t = 0;
                while (t < targets && (sections[t] != hitSection || blocks[t] != hitBlock)) {
                    t++;
                }
                if (t == targets) {
                    sections[t] = hitSection;
                    blocks[t] = hitBlock;
                    targets++;
                }
                counts[t]++;
            }
        }
        return new FaceRays(escaped, Arrays.copyOf(sections, targets), Arrays.copyOf(blocks, targets),
                Arrays.copyOf(counts, targets));
    }

    /**
     * Follows one ray from a face, block by block, until it meets a block that stops radiation or runs out of
     * range. Every section it passes through becomes something the kept rays depend on.
     *
     * @return {@link #HIT} with {@link #hitSection} and {@link #hitBlock} set, {@link #ESCAPED}, or
     *     {@link #LOST} if it met a refined block
     */
    private int march(PhysicalWorld world, int bx, int by, int bz, Direction face, int ray, SectionRays cache,
            boolean ambientClear) {
        double px = bx + pattern.originX(face, ray);
        double py = by + pattern.originY(face, ray);
        double pz = bz + pattern.originZ(face, ray);
        double vx = pattern.dirX(face, ray);
        double vy = pattern.dirY(face, ray);
        double vz = pattern.dirZ(face, ray);
        int cx = bx + face.dx();
        int cy = by + face.dy();
        int cz = bz + face.dz();
        int sx = vx > 0 ? 1 : vx < 0 ? -1 : 0;
        int sy = vy > 0 ? 1 : vy < 0 ? -1 : 0;
        int sz = vz > 0 ? 1 : vz < 0 ? -1 : 0;
        double nextX = sx == 0 ? Double.POSITIVE_INFINITY : ((sx > 0 ? cx + 1 : cx) - px) / vx;
        double nextY = sy == 0 ? Double.POSITIVE_INFINITY : ((sy > 0 ? cy + 1 : cy) - py) / vy;
        double nextZ = sz == 0 ? Double.POSITIVE_INFINITY : ((sz > 0 ? cz + 1 : cz) - pz) / vz;
        double stepX = sx == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(vx);
        double stepY = sy == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(vy);
        double stepZ = sz == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(vz);
        long key = SectionPos.pack(cx >> 4, cy >> 4, cz >> 4);
        Opacity o = opacityOf(world, key);
        cache.depend(key, o);
        while (true) {
            double t;
            if (nextX <= nextY && nextX <= nextZ) {
                t = nextX;
                cx += sx;
                nextX += stepX;
            } else if (nextY <= nextZ) {
                t = nextY;
                cy += sy;
                nextY += stepY;
            } else {
                t = nextZ;
                cz += sz;
                nextZ += stepZ;
            }
            if (t > range) {
                return ESCAPED;
            }
            long k = SectionPos.pack(cx >> 4, cy >> 4, cz >> 4);
            if (k != key) {
                key = k;
                o = opacityOf(world, key);
                cache.depend(key, o);
            }
            int index = SectionPos.localIndex(cx & 15, cy & 15, cz & 15);
            if (o == null) {
                if (ambientClear) {
                    continue;
                }
                return ESCAPED;
            }
            if (isClear(o, index)) {
                continue;
            }
            if ((o.refined[index >>> 6] & (1L << index)) != 0) {
                return LOST;
            }
            hitSection = key;
            hitBlock = index;
            return HIT;
        }
    }

    // ---- exchange ----

    /** Moves the step's radiation between the participating blocks and the surroundings. */
    private int integrate(PhysicalWorld world, double dt, List<ValidityIssue> found) {
        Exchange x = exchange;
        MaterialRegistry registry = world.materials();
        int n = x.count;
        x.prepare();
        for (int i = 0; i < n; i++) {
            Section s = world.section(x.section[i]);
            int b = x.block[i];
            int material = s.material(b);
            double mass = s.mass(b);
            x.material[i] = material;
            x.mass[i] = mass;
            x.enthalpy[i] = s.enthalpy(b);
            x.owner[i] = s.owner(b);
            if (material == MaterialRegistry.VACUUM || mass == 0) {
                x.materials[i] = null;
                x.temperature[i] = 0;
                x.emissivity[i] = 0;
                x.capacity[i] = 0;
                continue;
            }
            Material m = registry.get(material);
            x.materials[i] = m;
            ThermalState state = m.stateFor(x.enthalpy[i] / mass);
            x.temperature[i] = state.temperatureK();
            x.emissivity[i] = m.emissivity(state);
            x.capacity[i] = mass * m.specificHeat(state);
        }
        double share = 1.0 / pattern.rays();
        double[] conductance = x.conductanceSum;
        Arrays.fill(conductance, 0, n, 0.0);
        for (int l = 0; l < x.links; l++) {
            int a = x.linkA[l];
            int b = x.linkB[l];
            double w = x.linkCount[l] * share * (x.emitter[b] ? 0.5 : 1.0);
            double coefficient = x.materials[a] == null || x.materials[b] == null ? 0.0
                    : SIGMA * x.emissivity[a] * x.emissivity[b] * w;
            x.linkCoefficient[l] = coefficient;
            double hottest = Math.max(x.temperature[a], x.temperature[b]);
            double g = 4 * coefficient * hottest * hottest * hottest;
            conductance[a] += g;
            conductance[b] += g;
        }
        for (int i = 0; i < n; i++) {
            if (x.escaped[i] == 0 || x.materials[i] == null) {
                x.escapeCoefficient[i] = 0;
                continue;
            }
            double coefficient = SIGMA * x.emissivity[i] * x.escaped[i] * share;
            x.escapeCoefficient[i] = coefficient;
            double hottest = Math.max(x.temperature[i], x.surroundings[i]);
            conductance[i] += 4 * coefficient * hottest * hottest * hottest;
        }
        double stable = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (conductance[i] > 0 && x.capacity[i] > 0) {
                stable = Math.min(stable, SAFETY * x.capacity[i] / conductance[i]);
            }
        }
        int substeps = 1;
        while (dt / substeps > stable && substeps < MAX_SUBSTEPS) {
            substeps *= 2;
        }
        double h = dt / substeps;
        if (h > stable) {
            damp(n, h, found);
        }
        double given = 0;
        double absolute = 0;
        double[] t = x.temperature;
        for (int step = 0; step < substeps; step++) {
            if (step > 0) {
                for (int i = 0; i < n; i++) {
                    if (x.materials[i] != null) {
                        t[i] = x.materials[i].temperatureFor(x.enthalpy[i] / x.mass[i]);
                    }
                }
            }
            for (int l = 0; l < x.links; l++) {
                double c = x.linkCoefficient[l];
                if (c == 0) {
                    continue;
                }
                int a = x.linkA[l];
                int b = x.linkB[l];
                double q = c * fourthPowerDifference(t[a], t[b]) * h;
                x.enthalpy[a] -= q;
                x.enthalpy[b] += q;
            }
            for (int i = 0; i < n; i++) {
                double c = x.escapeCoefficient[i];
                if (c == 0) {
                    continue;
                }
                double q = c * fourthPowerDifference(t[i], x.surroundings[i]) * h;
                x.enthalpy[i] -= q;
                given -= q;
                absolute += Math.abs(q);
            }
        }
        CellState written = CellState.vacuum(Provenance.SIMULATED);
        for (int i = 0; i < n; i++) {
            Section s = world.section(x.section[i]);
            int b = x.block[i];
            if (Double.doubleToRawLongBits(x.enthalpy[i]) != Double.doubleToRawLongBits(s.enthalpy(b))) {
                written.set(x.material[i], x.mass[i], x.enthalpy[i], x.owner[i], Provenance.SIMULATED);
                world.writeBlock(x.section[i], b, written);
            }
        }
        if (absolute > 0) {
            // Declared every step, so not logged as an event; lastStepEnergy() reports it.
            world.recordExchange(new Totals(0, given, absolute, new EnumMap<>(Element.class)), null);
        }
        lastEnergy = -given;
        return substeps;
    }

    /**
     * Damps the exchange of blocks that even the most substeps cannot keep stable, such as tiny blocks next to
     * very hot ones, as an implicit step would: each exchange is divided by one plus the substep times the
     * rates at which its blocks' temperatures respond. Temperatures then stay between those of the blocks and
     * surroundings they exchange with, but change more slowly than they should. Blocks that are stable are
     * hardly affected, as their rates are small.
     */
    private void damp(int n, double h, List<ValidityIssue> found) {
        Exchange x = exchange;
        double[] rate = x.rate;
        int tooFast = 0;
        for (int i = 0; i < n; i++) {
            rate[i] = x.capacity[i] > 0 ? x.conductanceSum[i] / x.capacity[i] : 0.0;
            if (h * rate[i] > SAFETY) {
                tooFast++;
            }
        }
        for (int l = 0; l < x.links; l++) {
            x.linkCoefficient[l] /= 1 + h * (rate[x.linkA[l]] + rate[x.linkB[l]]);
        }
        for (int i = 0; i < n; i++) {
            x.escapeCoefficient[i] /= 1 + h * rate[i];
        }
        found.add(new ValidityIssue(ID, null, tooFast + " blocks need more than " + MAX_SUBSTEPS
                + " radiation substeps; their exchange is damped, so their temperatures change more slowly than"
                + " they should"));
    }

    /** Returns {@code a⁴ − b⁴}, factored so that equal temperatures give exactly zero. */
    private static double fourthPowerDifference(double a, double b) {
        return (a * a + b * b) * (a + b) * (a - b);
    }

    // ---- kept state ----

    /** Which blocks of a section let radiation through and which are refined, as of a version of the section. */
    private static final class Opacity {
        final Section section;
        final long[] clear = new long[WORDS];
        final long[] refined = new long[WORDS];
        long version = Long.MIN_VALUE;
        long stamp;

        Opacity(Section section) {
            this.section = section;
        }
    }

    /** Where the rays of one face end: how many escape, and how many reach each block they hit. */
    private record FaceRays(int escaped, long[] section, int[] block, int[] count) {
    }

    /** The kept rays of a section's faces, and the stamps of the sections they pass through. */
    private static final class SectionRays {
        final Section section;
        final TreeMap<Long, Long> depends = new TreeMap<>();
        final TreeMap<Integer, FaceRays> faces = new TreeMap<>();

        SectionRays(Section section) {
            this.section = section;
        }

        void depend(long key, Opacity o) {
            depends.putIfAbsent(key, o == null ? 0L : o.stamp);
        }
    }

    /**
     * The blocks taking part in one step's exchange, and the links between them. Its arrays are kept from
     * step to step.
     */
    private static final class Exchange {
        int count;
        long[] section = new long[64];
        int[] block = new int[64];
        boolean[] emitter = new boolean[64];
        int[] escaped = new int[64];
        double[] surroundings = new double[64];
        int links;
        int[] linkA = new int[256];
        int[] linkB = new int[256];
        int[] linkCount = new int[256];
        double[] linkCoefficient = new double[256];
        /** Per section, each block's participant number plus one; zero where the block takes no part. */
        private final TreeMap<Long, int[]> numbers = new TreeMap<>();
        private final List<int[]> spare = new ArrayList<>();
        private long lastKey = Long.MIN_VALUE;
        private int[] lastNumbers;

        // Filled by prepare() and integrate().
        int[] material = new int[0];
        double[] mass = new double[0];
        double[] enthalpy = new double[0];
        long[] owner = new long[0];
        Material[] materials = new Material[0];
        double[] temperature = new double[0];
        double[] emissivity = new double[0];
        double[] capacity = new double[0];
        double[] conductanceSum = new double[0];
        double[] escapeCoefficient = new double[0];
        double[] rate = new double[0];

        void clear() {
            for (int i = 0; i < count; i++) {
                emitter[i] = false;
                escaped[i] = 0;
            }
            count = 0;
            links = 0;
            for (int[] table : numbers.values()) {
                Arrays.fill(table, 0);
                spare.add(table);
            }
            numbers.clear();
            lastKey = Long.MIN_VALUE;
            lastNumbers = null;
        }

        /** Returns the number of a block in the exchange, adding it if it is new. */
        int participant(long key, int b, boolean emits) {
            int[] table = lastNumbers;
            if (key != lastKey || table == null) {
                table = numbers.get(key);
                if (table == null) {
                    table = spare.isEmpty() ? new int[SectionPos.BLOCKS] : spare.remove(spare.size() - 1);
                    numbers.put(key, table);
                }
                lastKey = key;
                lastNumbers = table;
            }
            int number = table[b] - 1;
            if (number < 0) {
                number = count++;
                if (number == section.length) {
                    int size = number * 2;
                    section = Arrays.copyOf(section, size);
                    block = Arrays.copyOf(block, size);
                    emitter = Arrays.copyOf(emitter, size);
                    escaped = Arrays.copyOf(escaped, size);
                    surroundings = Arrays.copyOf(surroundings, size);
                }
                section[number] = key;
                block[number] = b;
                table[b] = number + 1;
            }
            if (emits) {
                emitter[number] = true;
            }
            return number;
        }

        void escape(int participant, int rays, double surroundingsK) {
            escaped[participant] += rays;
            surroundings[participant] = surroundingsK;
        }

        void link(int a, int b, int rays) {
            if (links == linkA.length) {
                int size = links * 2;
                linkA = Arrays.copyOf(linkA, size);
                linkB = Arrays.copyOf(linkB, size);
                linkCount = Arrays.copyOf(linkCount, size);
                linkCoefficient = Arrays.copyOf(linkCoefficient, size);
            }
            linkA[links] = a;
            linkB[links] = b;
            linkCount[links] = rays;
            links++;
        }

        /** Makes the per-participant arrays fit. */
        void prepare() {
            if (material.length < count) {
                int size = Math.max(count, material.length * 2);
                material = new int[size];
                mass = new double[size];
                enthalpy = new double[size];
                owner = new long[size];
                materials = new Material[size];
                temperature = new double[size];
                emissivity = new double[size];
                capacity = new double[size];
                conductanceSum = new double[size];
                escapeCoefficient = new double[size];
                rate = new double[size];
            }
        }
    }
}
