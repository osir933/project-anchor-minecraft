package io.github.osir933.anchor.core.diagnostics;

import io.github.osir933.anchor.core.matter.Element;
import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.PhaseTransition;
import io.github.osir933.anchor.core.matter.ThermalState;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.CoarseningRule;
import io.github.osir933.anchor.core.world.ConservationLedger;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.TransitionReport;
import io.github.osir933.anchor.core.world.WorldSettings;
import io.github.osir933.anchor.core.world.WorldSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A quick check of the engine's own rules that runs anywhere, including inside the game through
 * {@code /anchor selftest}. It is a smoke test for a running installation; the full test suite lives with
 * the source.
 */
public final class SelfTest {

    private final List<String> failures = new ArrayList<>();
    private int checks;

    private SelfTest() {
    }

    /**
     * Runs every check.
     *
     * @return the report
     */
    public static Report run() {
        SelfTest test = new SelfTest();
        test.materials();
        test.world();
        List<String> lines = new ArrayList<>();
        if (test.failures.isEmpty()) {
            lines.add("Anchor self-test passed: " + test.checks + " checks.");
        } else {
            lines.add("Anchor self-test failed " + test.failures.size() + " of " + test.checks + " checks:");
            lines.addAll(test.failures);
        }
        return new Report(test.failures.isEmpty(), lines);
    }

    private void materials() {
        for (Material m : MaterialLibrary.all()) {
            double low = m.thermal().minTemperatureK();
            double high = m.thermal().maxTemperatureK();
            for (int i = 1; i < 10; i++) {
                double t = low + (high - low) * i / 10.0;
                double back = m.stateFor(m.specificEnthalpy(t)).temperatureK();
                check(Math.abs(back - t) <= 1e-9 * t, m.id() + ": temperature " + t + " K came back as " + back
                        + " K");
            }
            for (int i = 0; i < m.thermal().transitions().size(); i++) {
                PhaseTransition tr = m.thermal().transitions().get(i);
                double h = m.specificEnthalpy(tr.temperatureK()) + 0.5 * tr.latentHeat();
                ThermalState state = m.stateFor(h);
                check(state.inTransition() && state.region() == i
                                && Math.abs(state.transitionFraction() - 0.5) <= 1e-9
                                && state.temperatureK() == tr.temperatureK(),
                        m.id() + ": half-way through " + tr.name() + " gave " + state);
            }
            double sum = 0.0;
            for (Map.Entry<Element, Double> e : m.composition().elementMassFractions().entrySet()) {
                sum += e.getValue();
            }
            check(Math.abs(sum - 1.0) <= 1e-9, m.id() + ": element mass fractions sum to " + sum);
        }
    }

    private void world() {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(0), MaterialRegistry.withLibrary());
        GridPos pos = new GridPos(0, 0, 0);
        world.placeMaterial(pos, MaterialLibrary.GRANITE, 300.0);
        CellState before = world.readBlock(pos);
        TransitionReport refine = world.refine(new CellId(pos, 4, 3, 5, 7));
        check(refine.applied(), "refining a granite block was refused: " + refine.reason());
        CellState refined = world.readBlock(pos);
        check(refined.mass() == before.mass() && refined.enthalpy() == before.enthalpy(),
                "refinement changed the block's mass or energy");
        TransitionReport coarsen = world.coarsen(CellId.of(pos), CoarseningRule.DEFAULT);
        check(coarsen.applied(), "merging an untouched block was refused: " + coarsen.reason());
        CellState merged = world.readBlock(pos);
        check(merged.mass() == before.mass() && merged.enthalpy() == before.enthalpy(),
                "refining and merging did not give back the original block");
        ConservationLedger.Audit audit = world.audit();
        check(audit.balanced(), "conservation audit failed: " + audit.discrepancies());
        WorldSnapshot snapshot = world.snapshot();
        world.clearBlock(pos);
        world.restore(snapshot);
        check(world.stateHash().equals(snapshot.stateHash()), "restoring a snapshot did not reproduce the world");
    }

    private void check(boolean ok, String failure) {
        checks++;
        if (!ok) {
            failures.add(failure);
        }
    }

    /**
     * The outcome of a self-test.
     *
     * @param passed whether every check passed
     * @param lines a summary line followed by one line per failure
     */
    public record Report(boolean passed, List<String> lines) {

        /**
         * Copies the lines.
         *
         * @param passed whether every check passed
         * @param lines the report lines
         */
        public Report {
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        }
    }
}
