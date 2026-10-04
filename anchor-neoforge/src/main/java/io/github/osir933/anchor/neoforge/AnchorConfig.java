package io.github.osir933.anchor.neoforge;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Anchor's settings, in {@code config/anchor-synced.toml}. Every world shares them unless it has its own copy in its
 * {@code serverconfig} folder, and they take effect when a world is next loaded, except where a setting says
 * otherwise.
 */
final class AnchorConfig {

    static final ModConfigSpec SPEC;

    static final ModConfigSpec.BooleanValue HEAT_ENABLED;
    static final ModConfigSpec.DoubleValue SECONDS_PER_GAME_TICK;
    static final ModConfigSpec.IntValue GAME_TICKS_PER_STEP;
    static final ModConfigSpec.DoubleValue STEP_BUDGET_MILLIS;
    static final ModConfigSpec.IntValue RADIUS;
    static final ModConfigSpec.IntValue VERTICAL_RADIUS;
    static final ModConfigSpec.IntValue SECTIONS_LOADED_PER_STEP;
    static final ModConfigSpec.DoubleValue CALM_KELVIN_PER_HOUR;
    static final ModConfigSpec.IntValue REFINEMENT_LEVELS;
    static final ModConfigSpec.IntValue MAX_REFINED_CELLS;
    static final ModConfigSpec.BooleanValue SHOW_PHASE_CHANGES;
    static final ModConfigSpec.BooleanValue SUN_AND_SKY;

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
        STEP_BUDGET_MILLIS = b.comment("Milliseconds of each game tick that heat may spend on steps beyond its "
                        + "usual pace, when /anchor time makes it run faster than normal or sends it ahead. A game "
                        + "tick lasts 50 ms, so larger values run heat faster at the cost of the game's own speed. "
                        + "The usual steps are always taken.")
                .worldRestart()
                .defineInRange("stepBudgetMillis", 20.0, 1.0, 1000.0);
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
        REFINEMENT_LEVELS = b.comment("How many times a block may be halved where its temperature changes steeply, "
                        + "such as stone beside lava: 0 never, 1 into 50 cm cells, 2 into 25 cm cells, 3 into "
                        + "12.5 cm cells. Finer cells follow heat near very hot and very cold things more truly and "
                        + "cost more.")
                .worldRestart()
                .defineInRange("refinementLevels", 2, 0, 3);
        MAX_REFINED_CELLS = b.comment("The most cells refined blocks may hold in each dimension. Once it is "
                        + "reached, no more blocks are split until others even out and merge back.")
                .worldRestart()
                .defineInRange("maxRefinedCells", 16384, 0, 262144);
        SHOW_PHASE_CHANGES = b.comment("Whether melting, freezing and boiling change blocks, such as ice melting "
                        + "into water or water boiling away. Takes effect at once.")
                .define("showPhaseChanges", true);
        SUN_AND_SKY = b.comment("Whether the sun warms what it shines on and the ground cools under the night sky, in "
                        + "dimensions with a sun, such as the Overworld. Dark blocks take in more sunlight than pale "
                        + "ones, rain and storms dim the sun and keep the nights mild, and water evaporates into dry "
                        + "air. Laboratory worlds have neither, whatever this says. Takes effect at once.")
                .define("sunAndSky", true);
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
