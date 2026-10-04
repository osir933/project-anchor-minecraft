package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.matter.PhaseRegion;
import io.github.osir933.anchor.core.matter.ThermalState;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.model.PhysicsModel;
import io.github.osir933.anchor.core.model.StepContext;
import io.github.osir933.anchor.core.model.ValidityIssue;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.units.PhysicalConstants;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.Section;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeMap;

/**
 * Heat conduction between touching cells by Fourier's law, solved with explicit finite volumes.
 *
 * <p>Each face between two cells carries a heat flow {@code G·(T_a − T_b)}, where the conductance
 * {@code G = A / (e_a/(2k_a) + e_b/(2k_b))} combines the face area and the two half-cells in series. The
 * same flow leaves one cell and enters the other, so the model conserves energy to rounding. Phase changes
 * happen by themselves: heat goes into enthalpy, and a cell at its melting point absorbs latent heat at
 * constant temperature until it has melted.
 *
 * <p>Each cell takes substeps short enough to be stable for it alone: the step divided by the smallest power
 * of two that brings it within {@code SAFETY · C / ΣG}, with {@code C} the cell's heat capacity and
 * {@code ΣG} the conductance of its faces. A face moves heat as often as the finer of its two cells, and a
 * coarser cell keeps its temperature between its own substeps. A hot plume above a flame can then take
 * hundreds of substeps while the rock around it takes one, and every face still moves the same heat out of
 * one cell and into the other.
 *
 * <p>Still gas conducts very poorly, yet surfaces in air lose heat quickly because the air next to them
 * moves, and warm air rises. Until the fluid model exists, two correlations stand in for that motion:
 * <ul>
 *   <li>the gas side of a face between a gas and other matter uses a natural-convection coefficient of
 *   {@value #CONVECTION_COEFFICIENT} W/(m²·K), a typical value for still air (2 to 25);</li>
 *   <li>a horizontal face between two gases with the warmer one below mixes them with a coefficient of
 *   {@value #BUOYANT_MIXING_COEFFICIENT}·√(ΔT·L) W/(m²·K), with ΔT the temperature difference in kelvin and
 *   L the face's edge in metres, an order-of-magnitude estimate for the exchange a rising plume drives.</li>
 * </ul>
 * Each applies only where it conducts better than the gas itself. Both are evaluated once per step.
 *
 * <p>Liquids move too: a liquid warmed or cooled at a surface rises or sinks along it, and a body of liquid
 * lighter than the liquid above it turns over. Liquids carry far more heat per volume than gas, so this matters
 * more for them, and their properties are known well enough to estimate it from the liquid's own viscosity,
 * thermal expansion and diffusivity rather than from one coefficient:
 * <ul>
 *   <li>the liquid side of a face between a liquid and other matter uses the natural-convection correlations
 *   for a plate in a large body of liquid (Incropera, 7th ed., ch. 9), with the liquid cell's size as the
 *   plate's: Churchill and Chu's for a wall, and for a floor that makes the liquid on it lighter, or a ceiling
 *   that makes it heavier, {@code 0.54·Ra^1/4} or {@code 0.15·Ra^1/3}. Liquid made heavier by a floor or lighter
 *   by a ceiling lies still against it and only conducts. The Rayleigh number takes its buoyancy from the
 *   heaviest or lightest the liquid gets between its own temperature and the other side's, kept within the
 *   liquid's range: a liquid against something colder than its freezing point convects as if against its
 *   freezing point, and water cooled from above keeps sinking until it reaches 4 °C, its densest;</li>
 *   <li>two bodies of one liquid, such as water and melted snow, exchange heat with a coefficient of
 *   {@value #LIQUID_MIXING_EFFICIENCY}·ρ·c·v, with v the speed buoyancy drives between them: √(g′·L) for a
 *   lighter body below a denser one, half that sideways, where the denser body slumps under the lighter one,
 *   and never more than the speed viscosity allows, {@code g′·L²/(18ν)}; g′ is gravity times their difference
 *   in density over their mean density. Bodies stacked lighter over denser stay layered, which is how water,
 *   densest at 4 °C, keeps its coldest water on top and freezes from the surface down.</li>
 * </ul>
 * Like the correlations for gas, these apply only where they pass on more heat than the liquid would conduct,
 * and are evaluated once per step. Liquids in a phase change, and liquids without viscosity data, only conduct.
 *
 * <p>Given a {@link ThermalRefinement}, the model reports the temperature drop each cell needs between its
 * centre and its faces to pass on the heat that flows through them, so that cells too coarse for the
 * gradient around them can be split.
 *
 * <p>Given a {@link SkyModel}, the model leaves the top faces that the sky model balances to it.
 */
public final class ConductionModel implements PhysicsModel {

    /** The model id. */
    public static final String ID = "anchor:conduction";

    /** Fraction of the stability limit used for each substep. */
    static final double SAFETY = 0.45;

    /** The finest time level: no cell takes more than 2 to this power substeps in one step. */
    static final int MAX_LEVEL = 12;

    /** The most substeps any cell may take in a single step. */
    static final int MAX_SUBSTEPS = 1 << MAX_LEVEL;

    /** Heat transfer coefficient on the gas side of a surface in still air, in W/(m²·K). */
    public static final double CONVECTION_COEFFICIENT = 10.0;

    /**
     * Scale of the mixing between a warm gas and a cooler gas above it, in W/(m²·K) per √(K·m). Buoyancy
     * moves gas at about √(g·L·ΔT/T), roughly 0.2·√(ΔT·L) m/s near room temperature; carrying air's heat
     * capacity per volume at a fifth of that speed gives about 30·√(ΔT·L) W/(m²·K).
     */
    public static final double BUOYANT_MIXING_COEFFICIENT = 30.0;

    /**
     * Share of the heat a buoyant flow could carry, {@code ρ·c·v·ΔT}, that crosses a face between two bodies of
     * one liquid. It is the share that turns the same estimate for air at 290 K into
     * {@link #BUOYANT_MIXING_COEFFICIENT}.
     */
    public static final double LIQUID_MIXING_EFFICIENCY = 0.135;

    private final Isotherms isotherms = new Isotherms();
    private final ThermalGraph g = new ThermalGraph();
    private final ThermalRefinement refinement;
    private final SkyModel sky;
    private List<ValidityIssue> issues = List.of();

    // Work arrays, kept from step to step so a steady simulation allocates almost nothing.
    private double[] temperature = new double[0];
    private double[] conductivity = new double[0];
    private double[] capacity = new double[0];
    private boolean[] gas = new boolean[0];
    /** The phase region of each cell that convects as a liquid, otherwise null. */
    private PhaseRegion[] liquid = new PhaseRegion[0];
    private double[] density = new double[0];
    private double[] kinematicViscosity = new double[0];
    private double[] diffusivity = new double[0];
    private double[] heatPerVolume = new double[0];
    private Material[] materials = new Material[0];
    private double[] conductanceSum = new double[0];
    private double[] working = new double[0];
    private byte[] level = new byte[0];
    private int[] cellOrder = new int[0];
    private double[] drop = new double[0];
    private double[] conductance = new double[0];
    private byte[] faceLevel = new byte[0];
    private int[] faceOrder = new int[0];

    /** Face transfers and temperature updates in the last step, for tests of the time stepping. */
    long lastFaceUpdates;
    long lastCellUpdates;
    /** The work the last step did and the time it covered, from which the next step's cost is estimated. */
    private long lastWork;
    private double lastDt;

    /** Creates the model, reporting to no refinement. */
    public ConductionModel() {
        this(null);
    }

    /**
     * Creates the model.
     *
     * @param refinement where to report the drops cells need, or {@code null} for nowhere
     */
    public ConductionModel(ThermalRefinement refinement) {
        this(refinement, null);
    }

    /**
     * Creates the model, leaving the top faces open to the sky to a sky model.
     *
     * @param refinement where to report the drops cells need, or {@code null} for nowhere
     * @param sky the sky model that keeps the top faces of surfaces open to the sky, or {@code null} for none
     */
    public ConductionModel(ThermalRefinement refinement, SkyModel sky) {
        this.refinement = refinement;
        this.sky = sky;
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
        return "Fourier conduction between touching cells, explicit finite volumes. Conductivity is evaluated "
                + "once per step. Gases do not flow; surfaces in gas use a natural-convection coefficient of "
                + CONVECTION_COEFFICIENT + " W/(m2 K), and warm gas below cooler gas mixes with a coefficient of "
                + BUOYANT_MIXING_COEFFICIENT + " sqrt(dT L) W/(m2 K) instead. Liquids do not flow either; their "
                + "surfaces use natural-convection correlations for a plate in a large body of liquid, and two "
                + "bodies of one liquid mix at " + LIQUID_MIXING_EFFICIENCY + " rho c v, with v the buoyant speed "
                + "between them. Radiation is a separate model. Faces to regions outside the thermal scope are "
                + "insulated, and temperature differences below " + Isotherms.TOLERANCE_K + " K do not wake "
                + "idle sections.";
    }

    /**
     * Estimates the work of a step: four units per cell for a single substep, or, once the model has run, the
     * substeps the last step actually took, scaled to the time this step covers. Cells take as many substeps
     * as their own size and material need, so the last step is a far better guide than a bound for the
     * finest, most conductive cell would be.
     */
    @Override
    public long estimateCost(StepContext context) {
        PhysicalWorld world = context.world();
        SortedSet<Long> scope = context.sections(Domain.THERMAL);
        long cells = 0;
        for (long key : scope) {
            Section s = world.section(key);
            if (s == null || isotherms.isQuiet(world, scope, s)) {
                continue;
            }
            cells += SectionPos.BLOCKS - s.refinedBlocks().size() + s.leafCount();
        }
        long single = cells * 4;
        if (lastDt > 0 && lastWork > single) {
            double scaled = lastWork * Math.max(1.0, context.dt() / lastDt);
            return scaled >= Long.MAX_VALUE / 2 ? Long.MAX_VALUE / 2 : Math.max(single, (long) scaled);
        }
        return single;
    }

    @Override
    public void step(StepContext context) {
        PhysicalWorld world = context.world();
        isotherms.prune(world);
        g.rebuild(world, context.sections(Domain.THERMAL), isotherms, sky);
        MaterialRegistry registry = world.materials();
        int n = g.leafCount;
        resize(n, g.faceCount);
        TreeMap<String, Integer> extrapolated = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            if (g.material[i] == MaterialRegistry.VACUUM || g.mass[i] == 0) {
                materials[i] = null;
                conductanceSum[i] = 0;
                continue;
            }
            Material m = registry.get(g.material[i]);
            materials[i] = m;
            ThermalState s = m.stateFor(g.enthalpy[i] / g.mass[i]);
            temperature[i] = s.temperatureK();
            conductivity[i] = m.conductivity(s);
            capacity[i] = g.mass[i] * m.specificHeat(s);
            gas[i] = m.dominantPhase(s) == Phase.GAS;
            liquid[i] = null;
            double viscosity = m.viscosity(s);
            if (!Double.isNaN(viscosity)) {
                PhaseRegion region = m.thermal().regions().get(s.region());
                double rho = region.density().at(s.temperatureK());
                liquid[i] = region;
                density[i] = rho;
                kinematicViscosity[i] = viscosity / rho;
                heatPerVolume[i] = rho * m.specificHeat(s);
                diffusivity[i] = conductivity[i] / heatPerVolume[i];
            }
            conductanceSum[i] = 0;
            if (s.extrapolated()) {
                extrapolated.merge(m.id(), 1, Integer::sum);
            }
        }
        boolean reporting = refinement != null && refinement.settings().enabled();
        if (reporting) {
            Arrays.fill(drop, 0, n, 0.0);
        }
        for (int f = 0; f < g.faceCount; f++) {
            int a = g.faceA[f];
            int b = g.faceB[f];
            if (materials[a] == null || materials[b] == null) {
                conductance[f] = 0;
                continue;
            }
            double ra = g.edge[a] / (2 * conductivity[a]);
            double rb = g.edge[b] / (2 * conductivity[b]);
            double resistance;
            if (gas[a] && gas[b]) {
                resistance = ra + rb;
                double rise = temperature[a] - temperature[b];
                if (g.faceAxis[f] == 1 && rise > 0) {
                    double mixing = BUOYANT_MIXING_COEFFICIENT * Math.sqrt(rise * Math.sqrt(g.faceArea[f]));
                    resistance = Math.min(resistance, 1 / mixing);
                }
            } else {
                // Whether each side only conducts here; where matter moves, finer cells would not help it.
                boolean stillA = !gas[a];
                boolean stillB = !gas[b];
                if (liquid[a] != null && liquid[a] == liquid[b]) {
                    double mixing = mixingCoefficient(f, a, b);
                    boolean mixed = mixing > 0 && 1 / mixing < ra + rb;
                    stillA = stillB = !mixed;
                    resistance = mixed ? 1 / mixing : ra + rb;
                } else {
                    if (gas[a]) {
                        ra = Math.min(ra, 1 / CONVECTION_COEFFICIENT);
                    } else if (liquid[a] != null) {
                        double moving = 1 / surfaceCoefficient(f, a, b, false);
                        stillA = !(moving < ra);
                        ra = Math.min(ra, moving);
                    }
                    if (gas[b]) {
                        rb = Math.min(rb, 1 / CONVECTION_COEFFICIENT);
                    } else if (liquid[b] != null) {
                        double moving = 1 / surfaceCoefficient(f, b, a, true);
                        stillB = !(moving < rb);
                        rb = Math.min(rb, moving);
                    }
                    resistance = ra + rb;
                }
                if (reporting) {
                    // The drop inside each side is its share of the resistance times the whole difference.
                    double difference = Math.abs(temperature[a] - temperature[b]);
                    if (stillA) {
                        drop[a] = Math.max(drop[a], difference * ra / resistance);
                    }
                    if (stillB) {
                        drop[b] = Math.max(drop[b], difference * rb / resistance);
                    }
                }
            }
            conductance[f] = g.faceArea[f] / resistance;
            conductanceSum[a] += conductance[f];
            conductanceSum[b] += conductance[f];
        }
        if (reporting) {
            reportDrops(n);
        }
        List<ValidityIssue> found = new ArrayList<>();
        double[] enthalpy = working;
        System.arraycopy(g.enthalpy, 0, enthalpy, 0, n);
        integrate(context.dt(), n, enthalpy, found);
        lastWork = lastFaceUpdates + lastCellUpdates;
        lastDt = context.dt();
        CellState written = CellState.vacuum(Provenance.SIMULATED);
        for (int i = 0; i < n; i++) {
            if (Double.doubleToRawLongBits(enthalpy[i]) != Double.doubleToRawLongBits(g.enthalpy[i])) {
                written.set(g.material[i], g.mass[i], enthalpy[i], g.owner[i], Provenance.SIMULATED);
                CellId cell = g.cells[i];
                if (cell == null) {
                    world.writeBlock(g.sections[g.leafSection[i]].key(), g.leafBlock[i], written);
                } else {
                    world.writeLeaf(cell, written);
                }
            }
        }
        for (var e : extrapolated.entrySet()) {
            found.add(new ValidityIssue(ID, null, e.getValue() + " cells of " + e.getKey()
                    + " are outside the temperatures its data covers; their properties are extrapolated"));
        }
        issues = found;
    }

    @Override
    public List<ValidityIssue> checkValidity(PhysicalWorld world) {
        return issues;
    }

    /**
     * Returns the natural-convection coefficient on the liquid side of a face between a liquid and other matter,
     * or 0 where the liquid at the surface lies still.
     *
     * @param f the face
     * @param cell the liquid cell
     * @param other the cell on the face's other side
     * @param above whether the liquid lies above the face, which is then its floor
     */
    private double surfaceCoefficient(int f, int cell, int other, boolean above) {
        PhaseRegion region = liquid[cell];
        double surface = Math.max(region.fromK(), Math.min(region.toK(), temperature[other]));
        // The liquid against the surface takes every temperature between the surface's and the rest's. What
        // drives it is its heaviest part, which sinks, and its lightest, which rises: water at 6 °C cooled from
        // above still sinks, because water near 4 °C is heavier, while water at 3 °C stays on top.
        double heavier = region.density().max(surface, temperature[cell]) - density[cell];
        double lighter = density[cell] - region.density().min(surface, temperature[cell]);
        boolean wall = g.faceAxis[f] != 1;
        // Liquid rises off a floor and sinks off a ceiling. Liquid that would sink onto a floor or rise against a
        // ceiling stays where it is; a floor or ceiling is mostly part of a wider one, so that liquid cannot
        // spill off its edges either, and only conducts.
        double difference = wall ? Math.max(heavier, lighter) : above ? lighter : heavier;
        double reduced = PhysicalConstants.STANDARD_GRAVITY * difference / density[cell];
        if (!(reduced > 0)) {
            return 0;
        }
        double nu = kinematicViscosity[cell];
        double alpha = diffusivity[cell];
        // The liquid cell's own size sets the scale, however finely the other side is divided.
        double edge = g.edge[cell];
        if (wall) {
            // Churchill and Chu, for a wall as tall as the cell, at any Rayleigh number.
            double rayleigh = reduced * edge * edge * edge / (nu * alpha);
            double prandtl = nu / alpha;
            double root = 0.825 + 0.387 * StrictMath.pow(rayleigh, 1.0 / 6.0)
                    / StrictMath.pow(1 + StrictMath.pow(0.492 / prandtl, 9.0 / 16.0), 8.0 / 27.0);
            return root * root * conductivity[cell] / edge;
        }
        // The length of a floor or ceiling as wide as the cell is its area over its perimeter.
        double length = edge / 4;
        double rayleigh = reduced * length * length * length / (nu * alpha);
        double nusselt = rayleigh < 1e7 ? 0.54 * Math.sqrt(Math.sqrt(rayleigh)) : 0.15 * StrictMath.cbrt(rayleigh);
        return nusselt * conductivity[cell] / length;
    }

    /**
     * Returns the coefficient with which buoyancy mixes two bodies of one liquid across a face, or 0 where they
     * stay layered.
     *
     * @param f the face
     * @param a the cell on its negative side, below it for a face between two layers
     * @param b the cell on its positive side
     */
    private double mixingCoefficient(int f, int a, int b) {
        double heavierAbove = density[b] - density[a];
        boolean layered = g.faceAxis[f] == 1;
        if (layered ? !(heavierAbove > 0) : heavierAbove == 0) {
            return 0;
        }
        double mean = 0.5 * (density[a] + density[b]);
        double reduced = PhysicalConstants.STANDARD_GRAVITY * Math.abs(heavierAbove) / mean;
        double edge = Math.sqrt(g.faceArea[f]);
        double inertial = Math.sqrt(reduced * edge);
        double viscous = reduced * edge * edge / (9 * (kinematicViscosity[a] + kinematicViscosity[b]));
        double speed = Math.min(inertial, viscous) * (layered ? 1.0 : 0.5);
        return LIQUID_MIXING_EFFICIENCY * 0.5 * (heatPerVolume[a] + heatPerVolume[b]) * speed;
    }

    /**
     * Hands the drops found this step to the refinement: every cell of a whole block whose drop calls for a
     * split, and for each refined block the largest drop of any of its cells, with its cells that call for a
     * split. The cells of a refined block are next to each other in the graph.
     */
    private void reportDrops(int n) {
        double threshold = refinement.threshold();
        int i = 0;
        while (i < n) {
            if (g.cells[i] == null) {
                if (drop[i] > threshold && materials[i] != null) {
                    refinement.suggestSplit(CellId.of(g.sections[g.leafSection[i]].blockPos(g.leafBlock[i])), drop[i]);
                }
                i++;
                continue;
            }
            GridPos block = g.cells[i].block();
            double largest = 0;
            while (i < n && g.cells[i] != null && g.cells[i].block().equals(block)) {
                largest = Math.max(largest, drop[i]);
                if (drop[i] > threshold && materials[i] != null) {
                    refinement.suggestSplit(g.cells[i], drop[i]);
                }
                i++;
            }
            refinement.reportBlockDrop(block, largest);
        }
    }

    /**
     * Moves heat across the faces for one step, each cell at its own time level, starting from the
     * temperatures already in {@link #temperature}.
     */
    private void integrate(double dt, int n, double[] enthalpy, List<ValidityIssue> found) {
        lastFaceUpdates = 0;
        lastCellUpdates = 0;
        int[] cellsAt = new int[MAX_LEVEL + 1];
        int tooFine = 0;
        for (int i = 0; i < n; i++) {
            if (materials[i] == null || conductanceSum[i] == 0) {
                level[i] = -1;
                continue;
            }
            double stable = SAFETY * capacity[i] / conductanceSum[i];
            double step = dt;
            int k = 0;
            while (step > stable && k < MAX_LEVEL) {
                step *= 0.5;
                k++;
            }
            if (step > stable) {
                tooFine++;
            }
            level[i] = (byte) k;
            cellsAt[k]++;
        }
        if (tooFine > 0) {
            found.add(new ValidityIssue(ID, null, tooFine + " cells need more than " + MAX_SUBSTEPS
                    + " substeps; their temperatures may overshoot"));
        }
        int[] facesAt = new int[MAX_LEVEL + 1];
        int deepest = 0;
        for (int f = 0; f < g.faceCount; f++) {
            if (conductance[f] == 0) {
                faceLevel[f] = -1;
                continue;
            }
            int k = Math.max(level[g.faceA[f]], level[g.faceB[f]]);
            faceLevel[f] = (byte) k;
            facesAt[k]++;
            deepest = Math.max(deepest, k);
        }
        // Order cells and faces from the finest level down, so the ones due at any substep form a prefix.
        int[] cellsFrom = new int[MAX_LEVEL + 2];
        int[] facesFrom = new int[MAX_LEVEL + 2];
        for (int k = MAX_LEVEL; k >= 0; k--) {
            cellsFrom[k] = cellsFrom[k + 1] + cellsAt[k];
            facesFrom[k] = facesFrom[k + 1] + facesAt[k];
        }
        int[] cellNext = new int[MAX_LEVEL + 1];
        int[] faceNext = new int[MAX_LEVEL + 1];
        for (int k = 0; k <= MAX_LEVEL; k++) {
            cellNext[k] = cellsFrom[k + 1];
            faceNext[k] = facesFrom[k + 1];
        }
        for (int i = 0; i < n; i++) {
            if (level[i] >= 0) {
                cellOrder[cellNext[level[i]]++] = i;
            }
        }
        for (int f = 0; f < g.faceCount; f++) {
            if (faceLevel[f] >= 0) {
                faceOrder[faceNext[faceLevel[f]]++] = f;
            }
        }
        double[] stepAt = new double[MAX_LEVEL + 1];
        for (int k = 0; k <= MAX_LEVEL; k++) {
            stepAt[k] = Math.scalb(dt, -k);
        }
        long substeps = 1L << deepest;
        for (long m = 0; m < substeps; m++) {
            // At substep m, the levels whose own substeps start now are those at least this fine.
            int due = m == 0 ? 0 : deepest - Long.numberOfTrailingZeros(m);
            if (m > 0) {
                int cells = cellsFrom[due];
                for (int c = 0; c < cells; c++) {
                    int i = cellOrder[c];
                    temperature[i] = materials[i].temperatureFor(enthalpy[i] / g.mass[i]);
                }
                lastCellUpdates += cells;
            }
            int faces = facesFrom[due];
            for (int c = 0; c < faces; c++) {
                int f = faceOrder[c];
                int a = g.faceA[f];
                int b = g.faceB[f];
                double q = conductance[f] * (temperature[a] - temperature[b]) * stepAt[faceLevel[f]];
                enthalpy[a] -= q;
                enthalpy[b] += q;
            }
            lastFaceUpdates += faces;
        }
    }

    /** Makes the work arrays fit a graph, reusing them when their size is still reasonable. */
    private void resize(int leaves, int faces) {
        int size = ThermalGraph.capacity(temperature.length, leaves);
        if (size != temperature.length) {
            temperature = new double[size];
            conductivity = new double[size];
            capacity = new double[size];
            gas = new boolean[size];
            liquid = new PhaseRegion[size];
            density = new double[size];
            kinematicViscosity = new double[size];
            diffusivity = new double[size];
            heatPerVolume = new double[size];
            materials = new Material[size];
            conductanceSum = new double[size];
            working = new double[size];
            level = new byte[size];
            cellOrder = new int[size];
            drop = new double[size];
        }
        int faceSize = ThermalGraph.capacity(conductance.length, faces);
        if (faceSize != conductance.length) {
            conductance = new double[faceSize];
            faceLevel = new byte[faceSize];
            faceOrder = new int[faceSize];
        }
    }
}
