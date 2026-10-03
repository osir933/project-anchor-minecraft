package io.github.osir933.anchor.neoforge;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Anchor's settings. They are kept with each world and take effect when the world is next loaded, except
 * where a setting says otherwise.
 */
final class AnchorConfig {

    static final ModConfigSpec SPEC;

    static final ModConfigSpec.BooleanValue HEAT_ENABLED;
    static final ModConfigSpec.DoubleValue SECONDS_PER_GAME_TICK;
    static final ModConfigSpec.IntValue GAME_TICKS_PER_STEP;
    static final ModConfigSpec.IntValue RADIUS;
    static final ModConfigSpec.IntValue VERTICAL_RADIUS;
    static final ModConfigSpec.IntValue SECTIONS_LOADED_PER_STEP;
    static final ModConfigSpec.DoubleValue CALM_KELVIN_PER_HOUR;
    static final ModConfigSpec.BooleanValue SHOW_PHASE_CHANGES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Heat: temperatures, heat flow, and melting, freezing and boiling.").push("heat");
        HEAT_ENABLED = b.comment("Whether heat is simulated at all.")
                .worldRestart()
                .define("enabled", true);
        SECONDS_PER_GAME_TICK = b.comment("Simulated seconds per game tick. 3.6 makes a Minecraft day of 20 "
                        + "minutes last 24 simulated hours; 0.05 is real time.")
                .worldRestart()
                .defineInRange("secondsPerGameTick", 3.6, 0.05, 60.0);
        GAME_TICKS_PER_STEP = b.comment("Game ticks per simulation step. Larger values cost less and react more "
                        + "slowly; each step covers secondsPerGameTick times this many simulated seconds.")
                .worldRestart()
                .defineInRange("gameTicksPerStep", 4, 1, 100);
        RADIUS = b.comment("How many 16-block sections to each side of a player are simulated.")
                .worldRestart()
                .defineInRange("radius", 2, 0, 8);
        VERTICAL_RADIUS = b.comment("How many 16-block sections above and below a player are simulated.")
                .worldRestart()
                .defineInRange("verticalRadius", 2, 0, 8);
        SECTIONS_LOADED_PER_STEP = b.comment("The most sections brought into the simulation per step, so walking "
                        + "into new land spreads the work over several steps.")
                .worldRestart()
                .defineInRange("sectionsLoadedPerStep", 8, 1, 64);
        CALM_KELVIN_PER_HOUR = b.comment("A section whose temperatures change more slowly than this, in kelvin "
                        + "per simulated hour, falls asleep until something in it changes. Smaller values are "
                        + "more accurate and cost more.")
                .worldRestart()
                .defineInRange("calmKelvinPerHour", 1.0, 0.0, 1000.0);
        SHOW_PHASE_CHANGES = b.comment("Whether melting, freezing and boiling change blocks, such as ice melting "
                        + "into water or water boiling away. Takes effect at once.")
                .define("showPhaseChanges", true);
        b.pop();
        SPEC = b.build();
    }

    private AnchorConfig() {
    }

    /** Reads a setting, or its default before the world's settings are loaded. */
    static boolean get(ModConfigSpec.BooleanValue value) {
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return value.getDefault();
        }
    }

    /** Reads a setting, or its default before the world's settings are loaded. */
    static double get(ModConfigSpec.DoubleValue value) {
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return value.getDefault();
        }
    }

    /** Reads a setting, or its default before the world's settings are loaded. */
    static int get(ModConfigSpec.IntValue value) {
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return value.getDefault();
        }
    }
}
