package io.github.osir933.anchor.neoforge;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.osir933.anchor.core.host.HostedWorld;
import io.github.osir933.anchor.core.host.Pacer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * The {@code /anchor time} commands, which pace the simulation in the player's dimension for experiments:
 * <ul>
 *   <li>{@code /anchor time} tells how heat is paced and what its steps cost;</li>
 *   <li>{@code /anchor time pause} holds every temperature while the game runs on, and {@code /anchor time resume}
 *   lets heat run again;</li>
 *   <li>{@code /anchor time step [<count>]} takes steps by hand, one unless a count is given;</li>
 *   <li>{@code /anchor time speed <multiple>} runs heat from 0.01 to 1000 times as fast as normal, as far as the
 *   server keeps up;</li>
 *   <li>{@code /anchor time advance <time>} sends heat ahead by a length of simulated time, such as {@code 10h}, as
 *   fast as the server allows, showing players a bar of how far it has come, and {@code /anchor time cancel} stops
 *   it.</li>
 * </ul>
 * All but the first are for operators. Pausing and the speed are saved with the world; steps still waiting are not.
 * The sun and the weather keep the game's own time, so heat sent ahead or run faster sees the sun move more slowly
 * than it would; vanilla's {@code /tick sprint} runs the whole game faster instead, the sun included.
 */
final class TimeCommands {

    /** The longest stretch of simulated time heat can be sent ahead by at once, in seconds: a year. */
    static final double MAX_ADVANCE_SECONDS = 365.0 * 86_400.0;

    /** The most steps {@code /anchor time step} takes at once. */
    static final int MAX_STEPS = 100_000;

    private static final Predicate<CommandSourceStack> GAMEMASTERS = Commands.hasPermission(
            Commands.LEVEL_GAMEMASTERS);

    private TimeCommands() {
    }

    /**
     * Builds the {@code time} branch of the {@code /anchor} command.
     *
     * @return the branch
     */
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("time")
                .executes(TimeCommands::status)
                .then(Commands.literal("pause").requires(GAMEMASTERS).executes(TimeCommands::pause))
                .then(Commands.literal("resume").requires(GAMEMASTERS).executes(TimeCommands::resume))
                .then(Commands.literal("step").requires(GAMEMASTERS)
                        .executes(context -> step(context, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_STEPS))
                                .executes(context -> step(context, IntegerArgumentType.getInteger(context,
                                        "count")))))
                .then(Commands.literal("speed").requires(GAMEMASTERS)
                        .then(Commands.argument("multiple", DoubleArgumentType.doubleArg(
                                        (double) Pacer.MIN_SPEED / Pacer.NORMAL_SPEED,
                                        (double) Pacer.MAX_SPEED / Pacer.NORMAL_SPEED))
                                .executes(context -> speed(context, DoubleArgumentType.getDouble(context,
                                        "multiple")))))
                .then(Commands.literal("advance").requires(GAMEMASTERS)
                        .then(Commands.argument("time", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        List.of("1h", "6h", "1d", "7d"), builder))
                                .executes(context -> advance(context, StringArgumentType.getString(context,
                                        "time")))))
                .then(Commands.literal("cancel").requires(GAMEMASTERS).executes(TimeCommands::cancel));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Optional<LevelHeat> heat = running(source);
        if (heat.isEmpty()) {
            return 0;
        }
        HeatReport report = heat.get().report();
        HostedWorld.Status world = report.world();
        source.sendSuccess(() -> Component.literal("Heat in " + dimension(source) + ": "
                + HeatText.pace(report.pace(), report.stepSeconds(), report.ticksPerStep())), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "  %s simulated in %d steps; a step "
                + "takes %.2f ms on average", HeatText.duration(world.simulatedSeconds()), world.tick(),
                report.averageStepMillis())), false);
        return 1;
    }

    private static int pause(CommandContext<CommandSourceStack> context) {
        return change(context, heat -> {
            heat.pause();
            return "Paused heat in " + dimension(context.getSource()) + ": temperatures hold while the game runs "
                    + "on. /anchor time step takes steps by hand and /anchor time resume lets heat run again.";
        });
    }

    private static int resume(CommandContext<CommandSourceStack> context) {
        return change(context, heat -> {
            heat.resume();
            return "Heat in " + dimension(context.getSource()) + " runs again. "
                    + HeatText.pace(heat.pace(), heat.stepSeconds(), heat.ticksPerStep()) + ".";
        });
    }

    private static int step(CommandContext<CommandSourceStack> context, int count) {
        return change(context, heat -> {
            heat.request(count);
            String what = count == 1 ? "a step" : count + " steps";
            return "Taking " + what + " of heat in " + dimension(context.getSource()) + ", "
                    + HeatText.duration(count * heat.stepSeconds()) + " of simulated time"
                    + (heat.pace().paused() ? ", then pausing again." : ", then running on as before.");
        });
    }

    private static int speed(CommandContext<CommandSourceStack> context, double multiple) {
        return change(context, heat -> {
            int hundredths = Pacer.speedOf(multiple);
            heat.setSpeed(hundredths);
            String speed = hundredths == Pacer.NORMAL_SPEED ? "normal speed"
                    : HeatText.multiple((double) hundredths / Pacer.NORMAL_SPEED) + "× normal speed";
            return "Heat in " + dimension(context.getSource()) + " runs at " + speed
                    + (hundredths > Pacer.NORMAL_SPEED ? " as far as this server keeps up; /anchor time tells how it "
                            + "manages." : ".")
                    + (heat.pace().paused() ? " It is paused; /anchor time resume lets it run." : "");
        });
    }

    private static int advance(CommandContext<CommandSourceStack> context, String text) {
        return change(context, heat -> {
            double seconds = HeatText.parseDuration(text);
            if (seconds > MAX_ADVANCE_SECONDS) {
                throw new IllegalArgumentException("Heat can be sent ahead by at most "
                        + HeatText.duration(MAX_ADVANCE_SECONDS) + " at a time.");
            }
            long steps = Pacer.stepsFor(seconds, heat.stepSeconds());
            heat.request(steps);
            return "Sending heat in " + dimension(context.getSource()) + " ahead by "
                    + HeatText.duration(steps * heat.stepSeconds()) + ", " + steps + " steps, as fast as this server "
                    + "allows; /anchor time cancel stops it."
                    + (heat.hasSky() ? " The sun and the weather keep the game's time meanwhile." : "");
        });
    }

    private static int cancel(CommandContext<CommandSourceStack> context) {
        return change(context, heat -> {
            long dropped = heat.cancel();
            return dropped == 0 ? "No steps of heat were waiting in " + dimension(context.getSource()) + "."
                    : "Dropped " + dropped + " steps of heat in " + dimension(context.getSource())
                            + " that were still waiting.";
        });
    }

    /**
     * Applies a change to the heat of the player's dimension and tells operators what it did, or tells the player
     * why it could not be made.
     */
    private static int change(CommandContext<CommandSourceStack> context, Function<LevelHeat, String> change) {
        CommandSourceStack source = context.getSource();
        Optional<LevelHeat> heat = running(source);
        if (heat.isEmpty()) {
            return 0;
        }
        String message;
        try {
            message = change.apply(heat.get());
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    /**
     * Returns the heat of the player's dimension, or tells the player why there is none running.
     *
     * @param source who ran the command
     * @return the heat, or empty if it is switched off or stopped after an error
     */
    static Optional<LevelHeat> running(CommandSourceStack source) {
        Optional<LevelHeat> heat = HeatEvents.of(source.getLevel());
        if (heat.isEmpty()) {
            source.sendFailure(Component.literal("Temperatures are not simulated here."));
            return Optional.empty();
        }
        if (heat.get().report().failure() != null) {
            source.sendFailure(Component.literal("Heat here stopped after an error; /anchor heat status says more."));
            return Optional.empty();
        }
        return heat;
    }

    private static String dimension(CommandSourceStack source) {
        return source.getLevel().dimension().identifier().toString();
    }
}
