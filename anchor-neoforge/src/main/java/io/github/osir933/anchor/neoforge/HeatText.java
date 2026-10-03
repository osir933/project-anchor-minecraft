package io.github.osir933.anchor.neoforge;

import io.github.osir933.anchor.core.host.BlockAppearance;
import io.github.osir933.anchor.core.host.HostedWorld;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.thermal.HeatSourceModel;
import io.github.osir933.anchor.core.world.Provenance;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Words and numbers for what the heat simulation knows, as players read them. */
final class HeatText {

    private static final double ZERO_CELSIUS_K = 273.15;

    private HeatText() {
    }

    /**
     * Formats a temperature in degrees Celsius.
     *
     * @param kelvin the temperature in kelvin
     * @return for example {@code 21.4 °C}
     */
    static String celsius(double kelvin) {
        return String.format(Locale.ROOT, "%.1f °C", kelvin - ZERO_CELSIUS_K);
    }

    /**
     * Formats a temperature in degrees Celsius and kelvin.
     *
     * @param kelvin the temperature in kelvin
     * @return for example {@code 21.4 °C (294.6 K)}
     */
    static String temperature(double kelvin) {
        return String.format(Locale.ROOT, "%s (%.1f K)", celsius(kelvin), kelvin);
    }

    /**
     * Formats a length of time.
     *
     * @param seconds the time in seconds
     * @return for example {@code 45 s}, {@code 12 min 30 s}, {@code 3 h 5 min} or {@code 2 d 4 h}
     */
    static String duration(double seconds) {
        long s = Math.round(seconds);
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return (s / 60) + " min " + (s % 60) + " s";
        }
        if (s < 86_400) {
            return (s / 3600) + " h " + (s % 3600 / 60) + " min";
        }
        return (s / 86_400) + " d " + (s % 86_400 / 3600) + " h";
    }

    /**
     * Formats an amount of energy with a fitting prefix.
     *
     * @param joules the energy in joules
     * @return for example {@code 1.23 MJ}
     */
    static String energy(double joules) {
        double a = Math.abs(joules);
        if (a >= 1e9) {
            return String.format(Locale.ROOT, "%.2f GJ", joules / 1e9);
        }
        if (a >= 1e6) {
            return String.format(Locale.ROOT, "%.2f MJ", joules / 1e6);
        }
        if (a >= 1e3) {
            return String.format(Locale.ROOT, "%.2f kJ", joules / 1e3);
        }
        return String.format(Locale.ROOT, "%.1f J", joules);
    }

    /**
     * Formats a power with a fitting prefix.
     *
     * @param watts the power in watts
     * @return for example {@code 1.5 kW}
     */
    static String power(double watts) {
        if (Math.abs(watts) >= 1e6) {
            return String.format(Locale.ROOT, "%.3g MW", watts / 1e6);
        }
        if (Math.abs(watts) >= 1e3) {
            return String.format(Locale.ROOT, "%.3g kW", watts / 1e3);
        }
        return String.format(Locale.ROOT, "%.3g W", watts);
    }

    /**
     * Returns a phase's name.
     *
     * @param phase the phase
     * @return {@code solid}, {@code liquid} or {@code gas}
     */
    static String phase(Phase phase) {
        return phase.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Describes everything the simulation knows about a block.
     *
     * @param i the inspection
     * @param blockName the host's name for the block
     * @return the lines to show
     */
    static List<String> describe(HostedWorld.Inspection i, String blockName) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "%s at %d %d %d: %s (%s)", blockName, i.pos().x(), i.pos().y(),
                i.pos().z(), i.materialName(), i.material()));
        if (i.state() == null) {
            lines.add("  Empty: nothing here to have a temperature.");
        } else {
            String temperature = "  Temperature " + temperature(i.temperatureK()) + ", " + phase(i.phase());
            if (i.state().inTransition()) {
                temperature += String.format(Locale.ROOT, ", %.0f%% through a phase change",
                        100 * i.state().transitionFraction());
            }
            if (i.state().extrapolated()) {
                temperature += " (beyond the measured range of its data)";
            }
            lines.add(temperature);
            lines.add(String.format(Locale.ROOT, "  Mass %.1f kg, enthalpy %s%s", i.massKg(), energy(i.enthalpyJ()),
                    i.refined() ? ", totals over finer cells" : ""));
        }
        BlockAppearance a = i.appearance();
        if (a.presentable()) {
            StringBuilder shown = new StringBuilder("  Shown as " + phase(a.phase()) + "; becomes ");
            boolean first = true;
            for (Map.Entry<Phase, String> e : a.becomes().entrySet()) {
                shown.append(first ? "" : ", ").append(e.getValue()).append(" as ").append(phase(e.getKey()));
                first = false;
            }
            lines.add(shown.toString());
        }
        HeatSourceModel.Source source = i.source();
        if (source != null) {
            lines.add("  Heat source: holds " + celsius(source.temperatureK()) + " with up to "
                    + power(source.powerW()));
        }
        lines.add("  Section " + (i.awake() ? "awake" : "asleep")
                + (i.simulated() ? ", simulated in the last step" : "")
                + "; surroundings " + celsius(i.environmentK()));
        lines.add("  State " + provenance(i.provenance()));
        return lines;
    }

    /**
     * Summarises a level's heat simulation.
     *
     * @param dimension the level's name
     * @param s the status
     * @return the lines to show
     */
    static List<String> status(String dimension, HeatReport s) {
        List<String> lines = new ArrayList<>();
        HostedWorld.Status w = s.world();
        lines.add("Heat in " + dimension + (s.failure() == null ? "" : ": stopped after an error"));
        if (s.failure() != null) {
            lines.add("  " + s.failure());
            lines.add("  The log has the details. Heat restarts when the world is loaded again.");
        }
        lines.add(String.format(Locale.ROOT, "  %d sections loaded, %d awake, %d simulated in the last step; "
                + "%d heat sources", w.sections(), w.awakeSections(), w.simulatedSections(), w.sources()));
        lines.add(String.format(Locale.ROOT, "  %s simulated in %d steps of %s; the last took %.2f ms, on average"
                + " %.2f ms", duration(w.simulatedSeconds()), w.tick(), duration(s.stepSeconds()),
                s.lastStepMillis(), s.averageStepMillis()));
        lines.add(String.format(Locale.ROOT, "  %d block changes followed; %d blocks changed to show melting, "
                + "freezing or boiling", w.reconciled(), s.shownPhaseChanges()));
        lines.add("  Energy and mass " + (w.conserved() ? "balanced" : "NOT balanced") + " at the last audit");
        return lines;
    }

    private static String provenance(Provenance p) {
        return switch (p) {
            case INITIAL -> "set from the block when it was loaded or changed";
            case AMBIENT -> "assumed from the surroundings";
            case SIMULATED -> "simulated";
            case RECONSTRUCTED -> "reconstructed when the block was refined";
            case COARSENED -> "merged from finer cells";
        };
    }
}
