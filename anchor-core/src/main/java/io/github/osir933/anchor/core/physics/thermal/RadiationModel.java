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
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
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
 * at the first block that does not let radiation through, on the face they enter it by. Each ray carries the
 * same share of the face's view, so what one face sends and receives is right however the rays split it up. A
 * ray that travels further than the range, or that leaves the world's matter, reaches surroundings at the
 * face's section's environment temperature, a black body; that exchange is declared.
 *
 * <p>A face of a refined block is made up of the faces of the cells that touch it. Each radiates at its own
 * temperature in proportion to its area and emissivity, and what the face receives is shared among them in the
 * same proportion, as if it fell evenly across the face. Whether a refined block lets radiation through is
 * judged from the block as a whole, so refining a block changes no rays.
 *
 * <p>Radiation between surfaces at nearly the same temperature is small, so only faces whose block is more
 * than {@value #DEFAULT_RADIATING_DIFFERENCE_K} kelvin (by default) hotter or colder than its section's
 * environment cast rays, which keeps the cost with what is hot or cold rather than with every surface; a
 * refined block radiates if any of its outer cells is. Such a face exchanges with whatever it sees, even blocks
 * outside the step's scope; heat that reaches them wakes them as any other change does. When two radiating
 * faces see each other, both estimate their exchange and each counts half. The rays of a face are kept until a
 * block on their way changes whether it lets radiation through, so a steady scene casts no rays at all.
 *
 * <p>The exchange is explicit, with temperatures updated as often as the hottest, smallest cells need to stay
 * stable, at most {@value #MAX_SUBSTEPS} times a step; emissivities and heat capacities are evaluated once per
 * step. Cells that would need more substeps than that have their exchange damped, which keeps them stable but
 * slow, and the step reports them.
 *
 * <p>Given a {@link ThermalRefinement}, the model reports the temperature drop each cell needs between its
 * centre and a radiating face to pass on what it takes in or gives off there, at the step's starting
 * temperatures, so that cells too coarse for strong radiation, such as beside lava, can be split.
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

    /**
     * The radiation a face must take in or give off, in watts per square metre, before the cells under it are
     * reported for splitting: five times full sunlight. Weaker radiation would need days to build the drop it
     * calls for, and splitting every block in the sun or in a hot block's distant glow would cost far more than
     * it shows; the cells that radiation this strong falls on are those beside lava and flames.
     */
    static final double REFINING_FLUX = 5000.0;

    private static final double SIGMA = PhysicalConstants.STEFAN_BOLTZMANN;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int FACES = DIRECTIONS.length;
    private static final int ALL_FACES = (1 << FACES) - 1;
    private static final int WORDS = SectionPos.BLOCKS / 64;

    private final LongToDoubleFunction environment;
    private final RayPattern pattern;
    private final double range;
    private final double radiatingDifferenceK;
    private final ThermalRefinement refinement;

    private final TreeMap<Long, Opacity> opacity = new TreeMap<>();
    private final TreeMap<Long, SectionRays> raysBySection = new TreeMap<>();
    private long nextStamp = 1;

    /** Per material, the specific enthalpy above which it is mostly gas, and so lets radiation through. */
    private double[] gasAbove = new double[0];
    /** Per material, the specific enthalpies between which matter does not radiate, for {@link #hotFor}. */
    private double[] calmFrom = new double[0];
    private double[] calmTo = new double[0];
    private double calmFor = Double.NaN;

    private final int[] scratchMaterial = new int[SectionPos.BLOCKS];
    private final double[] scratchMass = new double[SectionPos.BLOCKS];
    private final double[] scratchEnthalpy = new double[SectionPos.BLOCKS];
    private final long[] scratchClear = new long[WORDS];
    // Separate from the ones above, which hold the section being scanned while neighbours are looked at.
    private final int[] opacityMaterial = new int[SectionPos.BLOCKS];
    private final double[] opacityMass = new double[SectionPos.BLOCKS];
    private final double[] opacityEnthalpy = new double[SectionPos.BLOCKS];

    // Where the last march stopped, and the face of the block it entered by.
    private long hitSection;
    private int hitBlock;
    private int hitFace;
    // Whether refinedHot has found an outer cell that radiates.
    private boolean foundHot;

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
        this(environment, null);
    }

    /**
     * Creates the model with the default rays, range and radiating difference, reporting cells too coarse for
     * the radiation they take in or give off.
     *
     * @param environment the environment temperature of each section, in kelvin, by packed section key
     * @param refinement where to report cells that should be split, or {@code null} to report none
     */
    public RadiationModel(LongToDoubleFunction environment, ThermalRefinement refinement) {
        this(environment, DEFAULT_RAYS_PER_FACE, DEFAULT_RANGE_BLOCKS, DEFAULT_RADIATING_DIFFERENCE_K, refinement);
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
        this(environment, raysPerFace, rangeBlocks, radiatingDifferenceK, null);
    }

    /**
     * Creates the model, reporting cells too coarse for the radiation they take in or give off.
     *
     * @param environment the environment temperature of each section, in kelvin, by packed section key
     * @param raysPerFace how many rays each face casts; more sample the view factors more finely
     * @param rangeBlocks how far rays travel, in blocks
     * @param radiatingDifferenceK how far from its environment's temperature a block must be for its faces to
     *     radiate, in kelvin
     * @param refinement where to report cells that should be split, or {@code null} to report none
     */
    public RadiationModel(LongToDoubleFunction environment, int raysPerFace, double rangeBlocks,
            double radiatingDifferenceK, ThermalRefinement refinement) {
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
        this.refinement = refinement;
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
                + "temperature, declared. The faces of refined blocks radiate from the cells that touch them and "
                + "share what they receive by area and emissivity. Cells that would need more than "
                + MAX_SUBSTEPS + " substeps are damped.";
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
        SortedSet<Long> scope = context.sections(Domain.THERMAL);
        Exchange x = exchange;
        x.clear();
        int faces = 0;
        long cast = 0;
        for (long key : scope) {
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
                boolean radiates = section.isRefined(b) ? refinedHot(section.refinedBlock(b), own, b)
                        : hot(scratchMaterial[b], scratchMass[b], scratchEnthalpy[b], own, b);
                if (!radiates) {
                    continue;
                }
                int open = clearFaces(world, key, own, b, ambientClear);
                if (open == 0) {
                    continue;
                }
                if (cache == null) {
                    cache = validCache(world, key, section);
                }
                int slot = slot(world, key, b);
                x.emits(slot, environmentK);
                for (Direction d : DIRECTIONS) {
                    if ((open & (1 << d.ordinal())) == 0) {
                        continue;
                    }
                    faces++;
                    int faceKey = b * FACES + d.ordinal();
                    FaceRays rays = cache.faces.get(faceKey);
                    if (rays == null) {
                        rays = cast(world, key, b, d, cache, ambientClear);
                        cache.faces.put(faceKey, rays);
                        cast += pattern.rays();
                    }
                    int from = surface(slot, d.ordinal());
                    x.escape(from, rays.escaped);
                    for (int t = 0; t < rays.count.length; t++) {
                        int target = slot(world, rays.section[t], rays.block[t]);
                        x.link(from, surface(target, rays.face[t]), rays.count[t]);
                    }
                }
            }
        }
        List<ValidityIssue> found = new ArrayList<>();
        lastEnergy = 0;
        int substeps = x.slots == 0 ? 0 : integrate(world, context.dt(), scope, found);
        lastRadiatingFaces = faces;
        lastRaysCast = cast;
        lastWork = cast * (long) Math.ceil(range) + (long) (x.links + x.members) * Math.max(1, substeps);
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

    /** Works out, per material, the specific enthalpies of matter too close to an environment to radiate. */
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
        return !isClear(own, block) && farFromEnvironment(material, mass, enthalpy);
    }

    /** Tells whether matter is far enough from its environment's temperature to radiate. */
    private boolean farFromEnvironment(int material, double mass, double enthalpy) {
        if (material == MaterialRegistry.VACUUM || mass == 0) {
            return false;
        }
        double h = enthalpy / mass;
        return h < calmFrom[material] || h > calmTo[material];
    }

    /**
     * Tells whether a refined block radiates: as a whole it blocks radiation, and one of the cells on its
     * faces is far enough from its environment.
     */
    private boolean refinedHot(RefinedBlock block, Opacity own, int index) {
        if (isClear(own, index)) {
            return false;
        }
        foundHot = false;
        block.forEachLeaf((cell, state) -> {
            if (!foundHot && touching(cell) != 0) {
                foundHot = farFromEnvironment(state.material(), state.mass(), state.enthalpy());
            }
        });
        return foundHot;
    }

    /** Returns, one bit per direction, the faces of its block that a cell touches. */
    static int touching(CellId cell) {
        int last = (1 << cell.level()) - 1;
        int faces = 0;
        for (int axis = 0; axis < 3; axis++) {
            int sub = cell.sub(axis);
            if (sub == 0) {
                faces |= 1 << (2 * axis);
            }
            if (sub == last) {
                faces |= 1 << (2 * axis + 1);
            }
        }
        return faces;
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
     * Returns which blocks of a section let radiation through, working them out again if the section changed;
     * a refined block is judged by its aggregate. Each different answer gets a new stamp, so kept rays can tell
     * whether their way and the blocks they end at are still as they were.
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
        Arrays.fill(clear, 0L);
        if (s.isUniform()) {
            if (clear(s.material(0), s.mass(0), s.enthalpy(0))) {
                Arrays.fill(clear, -1L);
            }
        } else {
            s.copyBlocks(opacityMaterial, opacityMass, opacityEnthalpy, null, 0);
            for (int b = 0; b < SectionPos.BLOCKS; b++) {
                if (clear(opacityMaterial[b], opacityMass[b], opacityEnthalpy[b])) {
                    clear[b >>> 6] |= 1L << b;
                }
            }
        }
        if (fresh || !Arrays.equals(clear, o.clear)) {
            System.arraycopy(clear, 0, o.clear, 0, WORDS);
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

    /** Casts the rays of one face and gathers where they end: on which face of which block. */
    private FaceRays cast(PhysicalWorld world, long key, int block, Direction face, SectionRays cache,
            boolean ambientClear) {
        int bx = (SectionPos.x(key) << 4) | SectionPos.localX(block);
        int by = (SectionPos.y(key) << 4) | SectionPos.localY(block);
        int bz = (SectionPos.z(key) << 4) | SectionPos.localZ(block);
        int n = pattern.rays();
        long[] sections = new long[n];
        int[] blocks = new int[n];
        byte[] faces = new byte[n];
        int[] counts = new int[n];
        int targets = 0;
        int escaped = 0;
        for (int ray = 0; ray < n; ray++) {
            if (!march(world, bx, by, bz, face, ray, cache, ambientClear)) {
                escaped++;
                continue;
            }
            int t = 0;
            while (t < targets && (sections[t] != hitSection || blocks[t] != hitBlock || faces[t] != hitFace)) {
                t++;
            }
            if (t == targets) {
                sections[t] = hitSection;
                blocks[t] = hitBlock;
                faces[t] = (byte) hitFace;
                targets++;
            }
            counts[t]++;
        }
        return new FaceRays(escaped, Arrays.copyOf(sections, targets), Arrays.copyOf(blocks, targets),
                Arrays.copyOf(faces, targets), Arrays.copyOf(counts, targets));
    }

    /**
     * Follows one ray from a face, block by block, until it meets a block that stops radiation or runs out of
     * range. Every section it passes through becomes something the kept rays depend on.
     *
     * @return {@code true} if it met a block, with {@link #hitSection}, {@link #hitBlock} and {@link #hitFace}
     *     set; {@code false} if it escaped
     */
    private boolean march(PhysicalWorld world, int bx, int by, int bz, Direction face, int ray,
            SectionRays cache, boolean ambientClear) {
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
            // The face of the next block the ray enters by: the one facing back along its step.
            int entered;
            if (nextX <= nextY && nextX <= nextZ) {
                t = nextX;
                cx += sx;
                nextX += stepX;
                entered = sx > 0 ? Direction.WEST.ordinal() : Direction.EAST.ordinal();
            } else if (nextY <= nextZ) {
                t = nextY;
                cy += sy;
                nextY += stepY;
                entered = sy > 0 ? Direction.DOWN.ordinal() : Direction.UP.ordinal();
            } else {
                t = nextZ;
                cz += sz;
                nextZ += stepZ;
                entered = sz > 0 ? Direction.NORTH.ordinal() : Direction.SOUTH.ordinal();
            }
            if (t > range) {
                return false;
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
                return false;
            }
            if (isClear(o, index)) {
                continue;
            }
            hitSection = key;
            hitBlock = index;
            hitFace = entered;
            return true;
        }
    }

    // ---- who takes part ----

    /**
     * Returns the exchange's number for a block, adding it if it is new, with its cells that touch its faces:
     * the block itself, or the outer leaves of a refined block.
     */
    private int slot(PhysicalWorld world, long key, int block) {
        Exchange x = exchange;
        int[] table = x.table(key);
        int slot = table[block] - 1;
        if (slot >= 0) {
            return slot;
        }
        slot = x.addSlot(key, block);
        table[block] = slot + 1;
        Section s = world.section(key);
        RefinedBlock refined = s.refinedBlock(block);
        if (refined == null) {
            x.addCell(slot, null, ALL_FACES, s.material(block), s.mass(block), s.enthalpy(block), s.owner(block));
        } else {
            int number = slot;
            refined.forEachLeaf((cell, state) -> {
                int faces = touching(cell);
                if (faces != 0) {
                    x.addCell(number, cell, faces, state.material(), state.mass(), state.enthalpy(), state.owner());
                }
            });
        }
        return slot;
    }

    /** Returns the exchange's number for a face of a block taking part, adding it if it is new. */
    private int surface(int slot, int face) {
        int s = exchange.slotSurface[slot * FACES + face] - 1;
        return s >= 0 ? s : exchange.addSurface(slot, face);
    }

    // ---- exchange ----

    /**
     * Moves the step's radiation between the cells taking part and the surroundings.
     *
     * <p>Two faces that each have a single cell, whole blocks, exchange {@code σ·w₁·w₂·c·(T₁⁴ − T₂⁴)}, where
     * {@code w = ε·A} is a cell's weight and {@code c} the share of rays from one that reach the other. Faces
     * with more cells exchange the same in total, {@code c·(W₂·E₁ − W₁·E₂)} with {@code W} the sum of their
     * cells' weights and {@code E} the sum of their {@code w·σT⁴}, shared among the cells: cell {@code i} of
     * face 1 takes in {@code c·wᵢ·(E₂ − W₂·σTᵢ⁴)}. Summed over a face's links, that is
     * {@code wᵢ·(J − K·σTᵢ⁴)} with {@code J = Σ c·E₂} and {@code K = Σ c·W₂}, so a substep costs one pass over
     * the links and one over the cells, however many cells the faces have.
     */
    private int integrate(PhysicalWorld world, double dt, SortedSet<Long> scope, List<ValidityIssue> found) {
        Exchange x = exchange;
        MaterialRegistry registry = world.materials();
        x.prepare();
        int n = x.cells;
        boolean hints = refinement != null && refinement.threshold() < Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            int material = x.material[i];
            double mass = x.mass[i];
            x.enthalpy[i] = x.startEnthalpy[i];
            if (material == MaterialRegistry.VACUUM || mass == 0) {
                x.materials[i] = null;
                x.temperature[i] = 0;
                x.emissivity[i] = 0;
                x.capacity[i] = 0;
                x.halfResistance[i] = 0;
                continue;
            }
            Material m = registry.get(material);
            x.materials[i] = m;
            ThermalState state = m.stateFor(x.enthalpy[i] / mass);
            x.temperature[i] = state.temperatureK();
            x.emissivity[i] = m.emissivity(state);
            x.capacity[i] = mass * m.specificHeat(state);
            double k = hints ? m.conductivity(state) : 0;
            x.halfResistance[i] = k > 0 ? 0.5 * x.edge[i] / k : 0;
        }
        int surfaces = x.surfaces;
        for (int s = 0; s < surfaces; s++) {
            double weight = 0;
            double hottest = 0;
            for (int m = x.memberStart[s], end = m + x.memberCount[s]; m < end; m++) {
                int c = x.memberCell[m];
                double w = x.emissivity[c] * x.edge[c] * x.edge[c];
                x.memberWeight[m] = w;
                weight += w;
                hottest = Math.max(hottest, x.temperature[c]);
            }
            x.weight[s] = weight;
            x.hottest[s] = hottest;
            x.grouped[s] = false;
            x.groupConductance[s] = 0;
            x.pairConductance[s] = 0;
        }
        double share = 1.0 / pattern.rays();
        double[] conductance = x.conductance;
        Arrays.fill(conductance, 0, n, 0.0);
        boolean anyGroup = false;
        for (int l = 0; l < x.links; l++) {
            int f = x.linkFrom[l];
            int g = x.linkTo[l];
            double c = x.linkCount[l] * share * (x.slotEmits[x.surfaceSlot[g]] ? 0.5 : 1.0);
            double hottest = Math.max(x.hottest[f], x.hottest[g]);
            if (x.memberCount[f] == 1 && x.memberCount[g] == 1) {
                int a = x.memberCell[x.memberStart[f]];
                int b = x.memberCell[x.memberStart[g]];
                double coefficient = SIGMA * x.weight[f] * x.weight[g] * c;
                x.linkPair[l] = true;
                x.linkA[l] = a;
                x.linkB[l] = b;
                x.linkCoefficient[l] = coefficient;
                double pair = 4 * coefficient * hottest * hottest * hottest;
                x.pairConductance[f] += pair;
                x.pairConductance[g] += pair;
                conductance[a] += pair;
                conductance[b] += pair;
            } else {
                anyGroup = true;
                x.linkPair[l] = false;
                x.linkCoefficient[l] = c;
                x.grouped[f] = true;
                x.grouped[g] = true;
                double cube = 4 * SIGMA * c * hottest * hottest * hottest;
                x.groupConductance[f] += cube * x.weight[g];
                x.groupConductance[g] += cube * x.weight[f];
            }
        }
        double[] escape = x.escapeCoefficient;
        Arrays.fill(escape, 0, n, 0.0);
        for (int s = 0; s < surfaces; s++) {
            int escaped = x.surfaceEscaped[s];
            boolean grouped = x.grouped[s];
            if (escaped == 0 && !grouped) {
                continue;
            }
            for (int m = x.memberStart[s], end = m + x.memberCount[s]; m < end; m++) {
                int c = x.memberCell[m];
                // Rays times area, which sums exactly; turned into a coefficient below.
                escape[c] += escaped * x.edge[c] * x.edge[c];
                if (grouped) {
                    conductance[c] += x.memberWeight[m] * x.groupConductance[s];
                }
            }
        }
        for (int i = 0; i < n; i++) {
            if (escape[i] == 0 || x.materials[i] == null) {
                escape[i] = 0;
                continue;
            }
            double coefficient = SIGMA * x.emissivity[i] * escape[i] * share;
            escape[i] = coefficient;
            double hottest = Math.max(x.temperature[i], x.slotSurroundings[x.cellSlot[i]]);
            conductance[i] += 4 * coefficient * hottest * hottest * hottest;
        }
        if (anyGroup) {
            groupRates();
        }
        if (hints) {
            reportDrops(scope, share, anyGroup);
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
            if (anyGroup) {
                groupRates();
            }
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
                if (!x.linkPair[l] || c == 0) {
                    continue;
                }
                int a = x.linkA[l];
                int b = x.linkB[l];
                double q = c * fourthPowerDifference(t[a], t[b]) * h;
                x.enthalpy[a] -= q;
                x.enthalpy[b] += q;
            }
            if (anyGroup) {
                gather();
                for (int s = 0; s < surfaces; s++) {
                    if (!x.grouped[s]) {
                        continue;
                    }
                    double received = x.received[s];
                    double rate = x.groupRate[s];
                    for (int m = x.memberStart[s], end = m + x.memberCount[s]; m < end; m++) {
                        int c = x.memberCell[m];
                        if (x.materials[c] != null) {
                            x.enthalpy[c] += x.memberWeight[m] * (received - rate * x.power[c]) * h;
                        }
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                double c = escape[i];
                if (c == 0) {
                    continue;
                }
                double q = c * fourthPowerDifference(t[i], x.slotSurroundings[x.cellSlot[i]]) * h;
                x.enthalpy[i] -= q;
                given -= q;
                absolute += Math.abs(q);
            }
        }
        CellState written = CellState.vacuum(Provenance.SIMULATED);
        for (int i = 0; i < n; i++) {
            if (Double.doubleToRawLongBits(x.enthalpy[i]) == Double.doubleToRawLongBits(x.startEnthalpy[i])) {
                continue;
            }
            written.set(x.material[i], x.mass[i], x.enthalpy[i], x.owner[i], Provenance.SIMULATED);
            int slot = x.cellSlot[i];
            if (x.leaf[i] == null) {
                world.writeBlock(x.slotSection[slot], x.slotBlock[slot], written);
            } else {
                world.writeLeaf(x.leaf[i], written);
            }
        }
        if (absolute > 0) {
            // Declared every step, so not logged as an event; lastStepEnergy() reports it.
            world.recordExchange(new Totals(0, given, absolute, new EnumMap<>(Element.class)), null);
        }
        lastEnergy = -given;
        return substeps;
    }

    /** Works out, for each face in a link with more than one cell, {@code K}: the links' {@code Σ c·W}. */
    private void groupRates() {
        Exchange x = exchange;
        Arrays.fill(x.groupRate, 0, x.surfaces, 0.0);
        for (int l = 0; l < x.links; l++) {
            if (x.linkPair[l]) {
                continue;
            }
            double c = x.linkCoefficient[l];
            int f = x.linkFrom[l];
            int g = x.linkTo[l];
            x.groupRate[f] += c * x.weight[g];
            x.groupRate[g] += c * x.weight[f];
        }
    }

    /**
     * Works out, at the cells' current temperatures, each cell's {@code σT⁴} and, for each face in a link with
     * more than one cell, what it gives off, {@code E}, and what it receives, {@code J = Σ c·E}.
     */
    private void gather() {
        Exchange x = exchange;
        double[] t = x.temperature;
        for (int i = 0; i < x.cells; i++) {
            x.power[i] = x.materials[i] == null ? 0 : SIGMA * (t[i] * t[i]) * (t[i] * t[i]);
        }
        for (int s = 0; s < x.surfaces; s++) {
            if (!x.grouped[s]) {
                continue;
            }
            double emitted = 0;
            for (int m = x.memberStart[s], end = m + x.memberCount[s]; m < end; m++) {
                emitted += x.memberWeight[m] * x.power[x.memberCell[m]];
            }
            x.emitted[s] = emitted;
            x.received[s] = 0;
        }
        for (int l = 0; l < x.links; l++) {
            if (x.linkPair[l]) {
                continue;
            }
            double c = x.linkCoefficient[l];
            int f = x.linkFrom[l];
            int g = x.linkTo[l];
            x.received[f] += c * x.emitted[g];
            x.received[g] += c * x.emitted[f];
        }
    }

    /**
     * Reports to the refinement the temperature drop each cell in the scope needs between its centre and each
     * radiating face, at the step's starting temperatures. With {@code q} the net radiation through the face,
     * {@code G} the rate at which it grows with the cell's temperature, {@code A} the face's area and
     * {@code R = e/(2k)} the cell's resistance between centre and face, the drop is {@code q·R/(A + G·R)}: the
     * steady drop when the half cell and the radiation pass the heat on in turn, which is never more than the
     * difference between the cell and what it exchanges with. Only faces taking in or giving off at least
     * {@link #REFINING_FLUX} count. Refined blocks report their largest drop, so their cells do not merge while
     * radiation still needs them.
     */
    private void reportDrops(SortedSet<Long> scope, double share, boolean anyGroup) {
        Exchange x = exchange;
        int n = x.cells;
        double[] t = x.temperature;
        for (int slot = 0; slot < x.slots; slot++) {
            x.slotInScope[slot] = scope.contains(x.slotSection[slot]);
        }
        if (anyGroup) {
            gather();
        }
        Arrays.fill(x.pairFlow, 0, x.surfaces, 0.0);
        for (int l = 0; l < x.links; l++) {
            if (x.linkPair[l]) {
                double q = x.linkCoefficient[l] * fourthPowerDifference(t[x.linkA[l]], t[x.linkB[l]]);
                x.pairFlow[x.linkFrom[l]] -= q;
                x.pairFlow[x.linkTo[l]] += q;
            }
        }
        double[] drop = x.drop;
        Arrays.fill(drop, 0, n, 0.0);
        for (int s = 0; s < x.surfaces; s++) {
            int slot = x.surfaceSlot[s];
            if (!x.slotInScope[slot]) {
                continue;
            }
            double escaped = x.surfaceEscaped[s] * share;
            double surroundings = x.slotSurroundings[slot];
            for (int m = x.memberStart[s], end = m + x.memberCount[s]; m < end; m++) {
                int c = x.memberCell[m];
                double r = x.halfResistance[c];
                if (r == 0) {
                    continue;
                }
                double w = x.memberWeight[m];
                double q = x.pairFlow[s];
                double g = x.pairConductance[s];
                if (x.grouped[s]) {
                    q += w * (x.received[s] - x.groupRate[s] * x.power[c]);
                    g += w * x.groupConductance[s];
                }
                if (escaped > 0) {
                    double e = SIGMA * w * escaped;
                    double hottest = Math.max(t[c], surroundings);
                    q -= e * fourthPowerDifference(t[c], surroundings);
                    g += 4 * e * hottest * hottest * hottest;
                }
                double area = x.edge[c] * x.edge[c];
                if (Math.abs(q) >= REFINING_FLUX * area) {
                    drop[c] = Math.max(drop[c], Math.abs(q) * r / (area + g * r));
                }
            }
        }
        double threshold = refinement.threshold();
        for (int slot = 0; slot < x.slots; slot++) {
            if (!x.slotInScope[slot]) {
                continue;
            }
            int first = x.slotFirstCell[slot];
            int end = first + x.slotCellCount[slot];
            if (first == end) {
                continue;
            }
            GridPos pos = GridPos.of(x.slotSection[slot], x.slotBlock[slot]);
            if (x.leaf[first] == null) {
                if (drop[first] > threshold) {
                    refinement.suggestSplit(CellId.of(pos), drop[first]);
                }
                continue;
            }
            double largest = 0;
            for (int c = first; c < end; c++) {
                largest = Math.max(largest, drop[c]);
                if (drop[c] > threshold) {
                    refinement.suggestSplit(x.leaf[c], drop[c]);
                }
            }
            refinement.reportBlockDrop(pos, largest);
        }
    }

    /**
     * Damps the exchange of cells that even the most substeps cannot keep stable, such as tiny cells next to
     * very hot ones, as an implicit step would: each exchange is divided by one plus the substep times the
     * rates at which the temperatures on either side respond, the fastest cell's on each face. Temperatures
     * then stay between those of the cells and surroundings they exchange with, but change more slowly than
     * they should. Cells that are stable are hardly affected, as their rates are small.
     */
    private void damp(int n, double h, List<ValidityIssue> found) {
        Exchange x = exchange;
        double[] rate = x.rate;
        int tooFast = 0;
        for (int i = 0; i < n; i++) {
            rate[i] = x.capacity[i] > 0 ? x.conductance[i] / x.capacity[i] : 0.0;
            if (h * rate[i] > SAFETY) {
                tooFast++;
            }
        }
        for (int s = 0; s < x.surfaces; s++) {
            double fastest = 0;
            for (int m = x.memberStart[s], end = m + x.memberCount[s]; m < end; m++) {
                fastest = Math.max(fastest, rate[x.memberCell[m]]);
            }
            x.fastest[s] = fastest;
        }
        for (int l = 0; l < x.links; l++) {
            x.linkCoefficient[l] /= 1 + h * (x.fastest[x.linkFrom[l]] + x.fastest[x.linkTo[l]]);
        }
        for (int i = 0; i < n; i++) {
            x.escapeCoefficient[i] /= 1 + h * rate[i];
        }
        found.add(new ValidityIssue(ID, null, tooFast + " cells need more than " + MAX_SUBSTEPS
                + " radiation substeps; their exchange is damped, so their temperatures change more slowly than"
                + " they should"));
    }

    /** Returns {@code a⁴ − b⁴}, factored so that equal temperatures give exactly zero. */
    private static double fourthPowerDifference(double a, double b) {
        return (a * a + b * b) * (a + b) * (a - b);
    }

    // ---- kept state ----

    /** Which blocks of a section let radiation through, as of a version of the section. */
    private static final class Opacity {
        final Section section;
        final long[] clear = new long[WORDS];
        long version = Long.MIN_VALUE;
        long stamp;

        Opacity(Section section) {
            this.section = section;
        }
    }

    /**
     * Where the rays of one face end: how many escape, and how many reach each face of each block they hit,
     * the faces numbered as {@link Direction}.
     */
    private record FaceRays(int escaped, long[] section, int[] block, byte[] face, int[] count) {
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
     * One step's exchange: the blocks taking part, called slots; their cells that touch their faces; their
     * faces that radiate or are reached by rays, called surfaces, each with the cells on it as members; and the
     * links between surfaces. Its arrays are kept from step to step.
     */
    private static final class Exchange {
        int slots;
        long[] slotSection = new long[64];
        int[] slotBlock = new int[64];
        boolean[] slotEmits = new boolean[64];
        double[] slotSurroundings = new double[64];
        boolean[] slotInScope = new boolean[64];
        int[] slotFirstCell = new int[64];
        int[] slotCellCount = new int[64];
        /** Per slot and face, the surface number plus one; zero where the face takes no part. */
        int[] slotSurface = new int[64 * FACES];

        int cells;
        int[] cellSlot = new int[64];
        /** The cell, or {@code null} where it is a whole block. */
        CellId[] leaf = new CellId[64];
        /** One bit per direction: the faces of its block the cell touches. */
        byte[] cellFaces = new byte[64];
        double[] edge = new double[64];
        int[] material = new int[64];
        double[] mass = new double[64];
        double[] startEnthalpy = new double[64];
        long[] owner = new long[64];

        int surfaces;
        int[] surfaceSlot = new int[64];
        int[] surfaceEscaped = new int[64];
        int[] memberStart = new int[64];
        int[] memberCount = new int[64];

        int members;
        int[] memberCell = new int[256];
        double[] memberWeight = new double[256];

        int links;
        int[] linkFrom = new int[256];
        int[] linkTo = new int[256];
        int[] linkCount = new int[256];

        /** Per section, each block's slot number plus one; zero where the block takes no part. */
        private final TreeMap<Long, int[]> numbers = new TreeMap<>();
        private final List<int[]> spare = new ArrayList<>();
        private long lastKey = Long.MIN_VALUE;
        private int[] lastNumbers;

        // Per cell, filled by integrate().
        double[] enthalpy = new double[0];
        Material[] materials = new Material[0];
        double[] temperature = new double[0];
        double[] emissivity = new double[0];
        double[] capacity = new double[0];
        double[] halfResistance = new double[0];
        double[] conductance = new double[0];
        double[] escapeCoefficient = new double[0];
        double[] rate = new double[0];
        double[] power = new double[0];
        double[] drop = new double[0];
        // Per surface.
        double[] weight = new double[0];
        double[] hottest = new double[0];
        boolean[] grouped = new boolean[0];
        double[] groupRate = new double[0];
        double[] groupConductance = new double[0];
        double[] pairConductance = new double[0];
        double[] pairFlow = new double[0];
        double[] emitted = new double[0];
        double[] received = new double[0];
        double[] fastest = new double[0];
        // Per link.
        boolean[] linkPair = new boolean[0];
        int[] linkA = new int[0];
        int[] linkB = new int[0];
        double[] linkCoefficient = new double[0];

        void clear() {
            Arrays.fill(slotSurface, 0, slots * FACES, 0);
            Arrays.fill(slotEmits, 0, slots, false);
            Arrays.fill(leaf, 0, cells, null);
            slots = 0;
            cells = 0;
            surfaces = 0;
            members = 0;
            links = 0;
            for (int[] table : numbers.values()) {
                Arrays.fill(table, 0);
                spare.add(table);
            }
            numbers.clear();
            lastKey = Long.MIN_VALUE;
            lastNumbers = null;
        }

        /** Returns a section's table of slot numbers, by block. */
        int[] table(long key) {
            if (key == lastKey && lastNumbers != null) {
                return lastNumbers;
            }
            int[] table = numbers.get(key);
            if (table == null) {
                table = spare.isEmpty() ? new int[SectionPos.BLOCKS] : spare.remove(spare.size() - 1);
                numbers.put(key, table);
            }
            lastKey = key;
            lastNumbers = table;
            return table;
        }

        int addSlot(long key, int block) {
            int s = slots++;
            if (s == slotSection.length) {
                int size = s * 2;
                slotSection = Arrays.copyOf(slotSection, size);
                slotBlock = Arrays.copyOf(slotBlock, size);
                slotEmits = Arrays.copyOf(slotEmits, size);
                slotSurroundings = Arrays.copyOf(slotSurroundings, size);
                slotInScope = Arrays.copyOf(slotInScope, size);
                slotFirstCell = Arrays.copyOf(slotFirstCell, size);
                slotCellCount = Arrays.copyOf(slotCellCount, size);
                slotSurface = Arrays.copyOf(slotSurface, size * FACES);
            }
            slotSection[s] = key;
            slotBlock[s] = block;
            slotFirstCell[s] = cells;
            slotCellCount[s] = 0;
            return s;
        }

        /** Adds a cell to the slot added last. */
        void addCell(int slot, CellId cell, int faces, int material, double mass, double enthalpy, long owner) {
            int c = cells++;
            if (c == cellSlot.length) {
                int size = c * 2;
                cellSlot = Arrays.copyOf(cellSlot, size);
                leaf = Arrays.copyOf(leaf, size);
                cellFaces = Arrays.copyOf(cellFaces, size);
                edge = Arrays.copyOf(edge, size);
                this.material = Arrays.copyOf(this.material, size);
                this.mass = Arrays.copyOf(this.mass, size);
                startEnthalpy = Arrays.copyOf(startEnthalpy, size);
                this.owner = Arrays.copyOf(this.owner, size);
            }
            cellSlot[c] = slot;
            leaf[c] = cell;
            cellFaces[c] = (byte) faces;
            edge[c] = cell == null ? 1.0 : cell.edgeLength();
            this.material[c] = material;
            this.mass[c] = mass;
            startEnthalpy[c] = enthalpy;
            this.owner[c] = owner;
            slotCellCount[slot]++;
        }

        int addSurface(int slot, int face) {
            int s = surfaces++;
            if (s == surfaceSlot.length) {
                int size = s * 2;
                surfaceSlot = Arrays.copyOf(surfaceSlot, size);
                surfaceEscaped = Arrays.copyOf(surfaceEscaped, size);
                memberStart = Arrays.copyOf(memberStart, size);
                memberCount = Arrays.copyOf(memberCount, size);
            }
            surfaceSlot[s] = slot;
            surfaceEscaped[s] = 0;
            memberStart[s] = members;
            int first = slotFirstCell[slot];
            for (int c = first, end = first + slotCellCount[slot]; c < end; c++) {
                if ((cellFaces[c] & (1 << face)) == 0) {
                    continue;
                }
                if (members == memberCell.length) {
                    memberCell = Arrays.copyOf(memberCell, members * 2);
                    memberWeight = Arrays.copyOf(memberWeight, members * 2);
                }
                memberCell[members++] = c;
            }
            memberCount[s] = members - memberStart[s];
            slotSurface[slot * FACES + face] = s + 1;
            return s;
        }

        void emits(int slot, double surroundingsK) {
            slotEmits[slot] = true;
            slotSurroundings[slot] = surroundingsK;
        }

        void escape(int surface, int rays) {
            surfaceEscaped[surface] += rays;
        }

        void link(int from, int to, int rays) {
            if (links == linkFrom.length) {
                int size = links * 2;
                linkFrom = Arrays.copyOf(linkFrom, size);
                linkTo = Arrays.copyOf(linkTo, size);
                linkCount = Arrays.copyOf(linkCount, size);
            }
            linkFrom[links] = from;
            linkTo[links] = to;
            linkCount[links] = rays;
            links++;
        }

        /** Makes the arrays filled while integrating fit. */
        void prepare() {
            if (enthalpy.length < cells) {
                int size = Math.max(cells, enthalpy.length * 2);
                enthalpy = new double[size];
                materials = new Material[size];
                temperature = new double[size];
                emissivity = new double[size];
                capacity = new double[size];
                halfResistance = new double[size];
                conductance = new double[size];
                escapeCoefficient = new double[size];
                rate = new double[size];
                power = new double[size];
                drop = new double[size];
            }
            if (weight.length < surfaces) {
                int size = Math.max(surfaces, weight.length * 2);
                weight = new double[size];
                hottest = new double[size];
                grouped = new boolean[size];
                groupRate = new double[size];
                groupConductance = new double[size];
                pairConductance = new double[size];
                pairFlow = new double[size];
                emitted = new double[size];
                received = new double[size];
                fastest = new double[size];
            }
            if (linkPair.length < links) {
                int size = Math.max(links, linkPair.length * 2);
                linkPair = new boolean[size];
                linkA = new int[size];
                linkB = new int[size];
                linkCoefficient = new double[size];
            }
        }
    }
}
