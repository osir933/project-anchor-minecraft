package io.github.osir933.anchor.neoforge;

import io.github.osir933.anchor.core.host.BlockAppearance;
import io.github.osir933.anchor.core.host.HostedWorld;
import io.github.osir933.anchor.core.host.Pacer;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.structure.ThermalShock;
import io.github.osir933.anchor.core.physics.thermal.HeatSourceModel;
import io.github.osir933.anchor.core.world.Provenance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Words and numbers for what the heat simulation knows, as players read them. */
final class HeatText {

    private static final double ZERO_CELSIUS_K = 273.15;

    /** One part of a length of time as players type it: a number and a unit, such as {@code 1.5h}. */
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(d|h|min|m|s)");

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
     * Converts a temperature to degrees Celsius.
     *
     * @param kelvin the temperature in kelvin
     * @return the temperature in degrees Celsius
     */
    static double toCelsius(double kelvin) {
        return kelvin - ZERO_CELSIUS_K;
    }

    /**
     * Describes how fast a temperature is changing, per simulated minute or, if slower, per simulated hour.
     *
     * @param kelvinPerSecond the rate in kelvin per simulated second, or {@link Double#NaN} if it is not known
     * @return for example {@code rising 3.2 K/min}, {@code falling 0.4 K/h} or {@code steady}; empty if the rate is
     *     not known
     */
    static String rate(double kelvinPerSecond) {
        if (Double.isNaN(kelvinPerSecond)) {
            return "";
        }
        String way = kelvinPerSecond > 0 ? "rising " : "falling ";
        double perMinute = Math.abs(kelvinPerSecond) * 60.0;
        if (perMinute >= 0.1) {
            return way + String.format(Locale.ROOT, "%.1f K/min", perMinute);
        }
        double perHour = perMinute * 60.0;
        if (perHour >= 0.1) {
            return way + String.format(Locale.ROOT, "%.1f K/h", perHour);
        }
        return "steady";
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
     * @param thermalShock whether built blocks crack through when uneven heat strains them past their strength
     * @return the lines to show
     */
    static List<String> describe(HostedWorld.Inspection i, String blockName, boolean thermalShock) {
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
            lines.add(String.format(Locale.ROOT, "  Mass %.1f kg, enthalpy %s", i.massKg(), energy(i.enthalpyJ())));
            if (i.refined()) {
                lines.add("  Refined into smaller cells from " + celsius(i.coolestK()) + " to " + celsius(i.hottestK())
                        + "; the figures above are for the whole block");
            }
            String strain = thermalStress(i.thermalStress(), i.built(), i.fractured(), thermalShock);
            if (strain != null) {
                lines.add("  " + strain);
            }
            if (!Double.isNaN(i.surfaceK())) {
                lines.add("  Its top, open to the sky, is at " + celsius(i.surfaceK()));
            }
            if (i.sunlightW() > 0) {
                lines.add("  Taking in " + power(i.sunlightW()) + " of sunlight");
            }
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
     * Describes how uneven heat strains a block: how close the thermal stress in its most loaded cell comes to
     * cracking it, and whether it has cracked through.
     *
     * @param stress the thermal stress in its most loaded cell, or {@code null} for a block that is not refined or
     *     is not of brittle matter
     * @param built whether the block is built; the world as it was found does not crack
     * @param fractured whether uneven heat has cracked the block through
     * @param enabled whether built blocks crack through when uneven heat strains them past their strength
     * @return the line to show, or {@code null} if there is nothing to say
     */
    static String thermalStress(ThermalShock.Result stress, boolean built, boolean fractured, boolean enabled) {
        if (fractured) {
            return "Cracked through by uneven heat";
        }
        if (stress == null || stress.load() < 0.005) {
            return null;
        }
        String text = String.format(Locale.ROOT, "Uneven heat strains it to %.0f%% of what cracks it, %s",
                100 * stress.load(), stress.tension() ? "pulling its cooler part apart" : "crushing its hotter part");
        if (!built) {
            return text + "; it is natural, so it does not crack";
        }
        return enabled ? text : text + "; cracking from heat is switched off in Anchor's settings";
    }

    /**
     * Formats a multiple, such as a speed, with at most two decimals.
     *
     * @param multiple the multiple
     * @return for example {@code 10}, {@code 0.25} or {@code 3.33}
     */
    static String multiple(double multiple) {
        return BigDecimal.valueOf(multiple).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString();
    }

    /**
     * Describes how a level's heat is paced, as {@code /anchor time} and {@code /anchor heat status} show it.
     *
     * @param pace where the level's pacer stands
     * @param stepSeconds simulated seconds per step
     * @param ticksPerStep how many game ticks apart steps come at normal speed
     * @return for example {@code Running at normal speed, a step of 14 s every 4 game ticks}
     */
    static String pace(Pacer.Status pace, double stepSeconds, int ticksPerStep) {
        String step = duration(stepSeconds);
        if (pace.requested() > 0) {
            long total = pace.requestTotal();
            return "Going ahead: " + duration((total - pace.requested()) * stepSeconds) + " of "
                    + duration(total * stepSeconds) + " done, at " + multiple(pace.achievedSpeed())
                    + "× normal speed lately, then " + (pace.paused() ? "paused again" : "running on");
        }
        if (pace.paused()) {
            return "Paused: temperatures hold until /anchor time resume, and /anchor time step takes steps by hand";
        }
        double factor = (double) pace.speed() / Pacer.NORMAL_SPEED;
        String speed = pace.speed() == Pacer.NORMAL_SPEED ? "normal speed" : multiple(factor) + "× normal speed";
        double perTick = factor / ticksPerStep;
        String rhythm;
        if (perTick > 1.0) {
            rhythm = multiple(perTick) + " steps of " + step + " every game tick";
        } else if (perTick == 1.0) {
            rhythm = "a step of " + step + " every game tick";
        } else {
            rhythm = "a step of " + step + " every " + multiple(1.0 / perTick) + " game ticks";
        }
        String text = "Running at " + speed + ", " + rhythm;
        return pace.keepingUp() ? text
                : text + "; this server keeps up with only " + multiple(pace.achievedSpeed()) + "× lately";
    }

    /**
     * Reads a length of time as players type it: numbers with the units {@code d}, {@code h}, {@code min} or
     * {@code m}, and {@code s}, one after another, such as {@code 10h}, {@code 1.5d} or {@code 1h30m}.
     *
     * @param text what was typed
     * @return the time in seconds, more than 0
     * @throws IllegalArgumentException if the text is not such a time
     */
    static double parseDuration(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        Matcher m = DURATION_PART.matcher(t);
        double seconds = 0.0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) {
                break;
            }
            double unit = switch (m.group(2)) {
                case "d" -> 86_400.0;
                case "h" -> 3600.0;
                case "min", "m" -> 60.0;
                default -> 1.0;
            };
            seconds += Double.parseDouble(m.group(1)) * unit;
            end = m.end();
        }
        if (end == 0 || end != t.length() || !(seconds > 0.0) || !Double.isFinite(seconds)) {
            throw new IllegalArgumentException("'" + text + "' is not a length of time such as 90s, 15m, 10h or 2d");
        }
        return seconds;
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
                + "%d heat sources, %d faces radiating", w.sections(), w.awakeSections(), w.simulatedSections(),
                w.sources(), w.radiatingFaces()));
        lines.add(String.format(Locale.ROOT, "  %s simulated in %d steps of %s; the last took %.2f ms, on average"
                + " %.2f ms", duration(w.simulatedSeconds()), w.tick(), duration(s.stepSeconds()),
                s.lastStepMillis(), s.averageStepMillis()));
        lines.add("  " + pace(s.pace(), s.stepSeconds(), s.ticksPerStep()));
        lines.add(String.format(Locale.ROOT, "  %d blocks refined into %d smaller cells where temperatures change "
                + "steeply", w.refinedBlocks(), w.refinedCells()));
        if (s.laboratory()) {
            lines.add("  A laboratory: no sun or night sky, so what nothing heats or cools settles at the air's "
                    + "temperature");
        } else if (Double.isNaN(w.sunlightW())) {
            lines.add("  No sun or night sky here");
        } else {
            lines.add((w.sunlightW() > 0 ? "  Sunlight " + power(w.sunlightW()) + "/m² on level ground" : "  Night")
                    + "; " + w.skySurfaces() + " surfaces open to the sky");
        }
        lines.add(String.format(Locale.ROOT, "  %d block changes followed; %d blocks changed to show melting, "
                + "freezing or boiling", w.reconciled(), s.shownPhaseChanges()));
        if (s.restoredBlocks() > 0) {
            lines.add(String.format(Locale.ROOT, "  %d blocks in %d sections came back as they were saved",
                    s.restoredBlocks(), s.restoredSections()));
        }
        lines.add("  Energy and mass " + (w.conserved() ? "balanced" : "NOT balanced") + " at the last audit");
        lines.add("  " + structures(s.structures()));
        lines.add("  " + thermalShock(s.structures().thermalShock(), w.fractures()));
        return lines;
    }

    /**
     * Describes in one line how many built blocks uneven heat has cracked through.
     *
     * @param enabled whether built blocks crack through when uneven heat strains them past their strength
     * @param fractures how many have cracked through since the level was loaded
     * @return the line
     */
    static String thermalShock(boolean enabled, long fractures) {
        if (!enabled) {
            return "Built blocks do not crack from uneven heat: switched off in Anchor's settings";
        }
        return fractures == 0 ? "No built block has cracked from uneven heat yet"
                : count(fractures, "built block", "built blocks") + " cracked through by uneven heat";
    }

    /**
     * Describes in one line what a level's structures have done.
     *
     * @param r what they have done
     * @return the line
     */
    static String structures(LevelStructures.Report r) {
        if (r.failure() != null) {
            return "Structures stopped after an error, while heat carries on: " + r.failure();
        }
        if (!r.enabled()) {
            return "Structures do not stand or fall by their strength: switched off in Anchor's settings";
        }
        String waiting = r.waiting() == 0 ? "" : "; " + count(r.waiting(), "built block waits", "built blocks wait")
                + " to be checked";
        if (r.analyses() == 0) {
            return "Structures: none analysed yet" + waiting;
        }
        return String.format(Locale.ROOT, "Structures: %s analysed, %d in the background%s; the largest had %s and "
                + "the last took %.1f ms; %s cracked and %s fell%s", count(r.analyses(), "structure", "structures"),
                r.inBackground(), r.analysing() ? ", one of them now" : "", count(r.largest(), "block", "blocks"),
                r.lastMillis(), count(r.cracks(), "joint", "joints"), count(r.fallen(), "block", "blocks"), waiting);
    }

    /** Counts things, as "1 block" or "3 blocks". */
    private static String count(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
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
