package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.matter.Material;
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
 * same flow leaves one cell and enters the other, so the model conserves energy to rounding. The step is
 * split into substeps short enough to be stable for the most conductive, least massive cell. Phase changes
 * happen by themselves: heat goes into enthalpy, and a cell at its melting point absorbs latent heat at
 * constant temperature until it has melted.
 */
public final class ConductionModel implements PhysicsModel {

    /** The model id. */
    public static final String ID = "anchor:conduction";

    /** Fraction of the stability limit used for each substep. */
    static final double SAFETY = 0.45;

    /** The most substeps a single step may take. */
    static final int MAX_SUBSTEPS = 4096;

    /** Thermal diffusivity no starter material exceeds by much (gold, about 1.3e-4 m²/s), with margin. */
    private static final double MAX_DIFFUSIVITY = 2e-4;

    private List<ValidityIssue> issues = List.of();

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
                + "once per step. Gases conduct but do not flow, and there is no radiation yet. Faces to "
                + "regions outside the thermal scope are insulated.";
    }

    @Override
    public long estimateCost(StepContext context) {
        PhysicalWorld world = context.world();
        SortedSet<Long> scope = context.sections(Domain.THERMAL);
        long cells = 0;
        int finest = 0;
        for (long key : scope) {
            Section s = world.section(key);
            if (s == null || ThermalGraph.isQuiet(world, scope, s)) {
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
        ThermalGraph g = ThermalGraph.build(world, context.sections(Domain.THERMAL));
        MaterialRegistry registry = world.materials();
        int n = g.leafCount;
        double[] temperature = new double[n];
        double[] conductivity = new double[n];
        double[] capacity = new double[n];
        Material[] materials = new Material[n];
        TreeMap<String, Integer> extrapolated = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            if (g.material[i] == MaterialRegistry.VACUUM || g.mass[i] == 0) {
                continue;
            }
            Material m = registry.get(g.material[i]);
            materials[i] = m;
            ThermalState s = m.stateFor(g.enthalpy[i] / g.mass[i]);
            temperature[i] = s.temperatureK();
            conductivity[i] = m.conductivity(s);
            capacity[i] = g.mass[i] * m.specificHeat(s);
            if (s.extrapolated()) {
                extrapolated.merge(m.id(), 1, Integer::sum);
            }
        }
        double[] conductance = new double[g.faceCount];
        double[] conductanceSum = new double[n];
        for (int f = 0; f < g.faceCount; f++) {
            int a = g.faceA[f];
            int b = g.faceB[f];
            if (materials[a] == null || materials[b] == null) {
                continue;
            }
            double resistance = g.edge[a] / (2 * conductivity[a]) + g.edge[b] / (2 * conductivity[b]);
            conductance[f] = g.faceArea[f] / resistance;
            conductanceSum[a] += conductance[f];
            conductanceSum[b] += conductance[f];
        }
        double stable = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (conductanceSum[i] > 0) {
                stable = Math.min(stable, capacity[i] / conductanceSum[i]);
            }
        }
        List<ValidityIssue> found = new ArrayList<>();
        if (stable == Double.POSITIVE_INFINITY) {
            issues = found;
            return;
        }
        long wanted = substeps(context.dt(), stable);
        int substeps = (int) Math.min(wanted, MAX_SUBSTEPS);
        if (wanted > MAX_SUBSTEPS) {
            found.add(new ValidityIssue(ID, null, "the step needs " + wanted + " substeps but at most "
                    + MAX_SUBSTEPS + " are allowed; temperatures may overshoot"));
        }
        double h = context.dt() / substeps;
        double[] enthalpy = g.enthalpy.clone();
        for (int step = 0; step < substeps; step++) {
            if (step > 0) {
                for (int i = 0; i < n; i++) {
                    if (materials[i] != null) {
                        temperature[i] = materials[i].stateFor(enthalpy[i] / g.mass[i]).temperatureK();
                    }
                }
            }
            for (int f = 0; f < g.faceCount; f++) {
                double gf = conductance[f];
                if (gf == 0) {
                    continue;
                }
                int a = g.faceA[f];
                int b = g.faceB[f];
                double q = gf * (temperature[a] - temperature[b]) * h;
                enthalpy[a] -= q;
                enthalpy[b] += q;
            }
        }
        for (int i = 0; i < n; i++) {
            if (Double.doubleToRawLongBits(enthalpy[i]) != Double.doubleToRawLongBits(g.enthalpy[i])) {
                world.writeLeaf(g.cells[i], new CellState(g.material[i], g.mass[i], enthalpy[i], g.owner[i],
                        Provenance.SIMULATED));
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

    private static long substeps(double dt, double stable) {
        double count = Math.ceil(dt / (SAFETY * stable));
        return count < 1 ? 1 : count > Long.MAX_VALUE / 2 ? Long.MAX_VALUE / 2 : (long) count;
    }
}
