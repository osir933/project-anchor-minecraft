package io.github.osir933.anchor.core.diagnostics;

import io.github.osir933.anchor.core.matter.Element;
import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.PhaseTransition;
import io.github.osir933.anchor.core.matter.ThermalState;
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
