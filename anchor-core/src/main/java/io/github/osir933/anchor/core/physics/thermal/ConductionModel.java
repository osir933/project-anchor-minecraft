package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.matter.ThermalState;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.model.PhysicsModel;
import io.github.osir933.anchor.core.model.StepContext;
import io.github.osir933.anchor.core.model.ValidityIssue;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.RefinedBlock;
import io.github.osir933.anchor.core.world.Section;
import java.util.ArrayList;
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
     * Thermal diffusivity no starter material exceeds by much, in m²/s, with margin. Diamond leads at about
     * 1.3e-3 m²/s; most metals are below 1.7e-4.
     */
    private static final double MAX_DIFFUSIVITY = 1.5e-3;

    private final Isotherms isotherms = new Isotherms();
    private final ThermalGraph g = new ThermalGraph();
    private List<ValidityIssue> issues = List.of();

    // Work arrays, kept from step to step so a steady simulation allocates almost nothing.
    private double[] temperature = new double[0];
    private double[] conductivity = new double[0];
    private double[] capacity = new double[0];
    private boolean[] gas = new boolean[0];
    private Material[] materials = new Material[0];
    private double[] conductanceSum = new double[0];
    private double[] working = new double[0];
    private byte[] level = new byte[0];
    private int[] cellOrder = new int[0];
    private double[] conductance = new double[0];
    private byte[] faceLevel = new byte[0];
    private int[] faceOrder = new int[0];

    /** Face transfers and temperature updates in the last step, for tests of the time stepping. */
    long lastFaceUpdates;
    long lastCellUpdates;

    /** Creates the model. */
    public ConductionModel() {
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
                + BUOYANT_MIXING_COEFFICIENT + " sqrt(dT L) W/(m2 K) instead. Liquids conduct but do not "
                + "convect, and there is no radiation yet. Faces to regions outside the thermal scope are "
                + "insulated, and temperature differences below " + Isotherms.TOLERANCE_K + " K do not wake "
                + "idle sections.";
    }

    @Override
    public long estimateCost(StepContext context) {
        PhysicalWorld world = context.world();
        SortedSet<Long> scope = context.sections(Domain.THERMAL);
        long cells = 0;
        int finest = 0;
        for (long key : scope) {
            Section s = world.section(key);
            if (s == null || isotherms.isQuiet(world, scope, s)) {
                continue;
            }
            cells += SectionPos.BLOCKS - s.refinedBlocks().size() + s.leafCount();
            for (RefinedBlock block : s.refinedBlocks()) {
                finest = Math.max(finest, block.depth());
            }
        }
        double e = CellId.edgeLength(finest);
        long substeps = substeps(context.dt(), e * e / (6 * MAX_DIFFUSIVITY));
        return cells * 4 * substeps;
    }

    @Override
    public void step(StepContext context) {
        PhysicalWorld world = context.world();
        isotherms.prune(world);
        g.rebuild(world, context.sections(Domain.THERMAL), isotherms);
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
            conductanceSum[i] = 0;
            if (s.extrapolated()) {
                extrapolated.merge(m.id(), 1, Integer::sum);
            }
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
                if (gas[a]) {
                    ra = Math.min(ra, 1 / CONVECTION_COEFFICIENT);
                } else if (gas[b]) {
                    rb = Math.min(rb, 1 / CONVECTION_COEFFICIENT);
                }
                resistance = ra + rb;
            }
            conductance[f] = g.faceArea[f] / resistance;
            conductanceSum[a] += conductance[f];
            conductanceSum[b] += conductance[f];
        }
        List<ValidityIssue> found = new ArrayList<>();
        double[] enthalpy = working;
        System.arraycopy(g.enthalpy, 0, enthalpy, 0, n);
        integrate(context.dt(), n, enthalpy, found);
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
            materials = new Material[size];
            conductanceSum = new double[size];
            working = new double[size];
            level = new byte[size];
            cellOrder = new int[size];
        }
        int faceSize = ThermalGraph.capacity(conductance.length, faces);
        if (faceSize != conductance.length) {
            conductance = new double[faceSize];
            faceLevel = new byte[faceSize];
            faceOrder = new int[faceSize];
        }
    }

    private static long substeps(double dt, double stable) {
        double count = Math.ceil(dt / (SAFETY * stable));
        return count < 1 ? 1 : count > Long.MAX_VALUE / 2 ? Long.MAX_VALUE / 2 : (long) count;
    }
}
