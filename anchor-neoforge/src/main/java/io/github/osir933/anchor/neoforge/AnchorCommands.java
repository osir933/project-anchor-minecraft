package io.github.osir933.anchor.neoforge;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.osir933.anchor.core.diagnostics.SelfTest;
import io.github.osir933.anchor.core.host.HostedWorld;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The {@code /anchor} command tree:
 * <ul>
 *   <li>{@code /anchor selftest} checks the engine;</li>
 *   <li>{@code /anchor heat status} summarises heat in the current dimension;</li>
 *   <li>{@code /anchor heat inspect} shows what the simulation knows about the block being looked at, and
 *   operators can name any block with {@code /anchor heat inspect <pos>};</li>
 *   <li>{@code /anchor heat set <pos> <celsius>} lets operators set a block's temperature, keeping its matter,
 *   to start an experiment;</li>
 *   <li>{@code /anchor probe ...} records temperatures over time and charts them; see {@link ProbeCommands};</li>
 *   <li>{@code /anchor time ...} pauses heat, steps it by hand, runs it faster or slower or sends it ahead; see
 *   {@link TimeCommands};</li>
 *   <li>{@code /anchor snapshot ...} saves a box of blocks with its heat and restores it later; see
 *   {@link SnapshotCommands}.</li>
 * </ul>
 */
final class AnchorCommands {

    /** How far away a looked-at block can be inspected, in blocks. */
    private static final double REACH = 20.0;

    /** The temperatures operators may set, in degrees Celsius: from just above absolute zero to far past lava. */
    private static final double COLDEST_C = -273.0;
    private static final double HOTTEST_C = 10_000.0;

    private AnchorCommands() {
    }

    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("anchor")
                .then(Commands.literal("selftest").executes(AnchorCommands::selfTest))
                .then(Commands.literal("heat")
                        .then(Commands.literal("status").executes(AnchorCommands::status))
                        .then(Commands.literal("inspect").executes(AnchorCommands::inspectLookedAt)
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                        .executes(context -> inspect(context,
                                                BlockPosArgument.getLoadedBlockPos(context, "pos")))))
                        .then(setCommand()))
                .then(ProbeCommands.build())
                .then(TimeCommands.build())
                .then(SnapshotCommands.build()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> setCommand() {
        return Commands.literal("set")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("celsius", DoubleArgumentType.doubleArg(COLDEST_C, HOTTEST_C))
                                .executes(context -> set(context, BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                        DoubleArgumentType.getDouble(context, "celsius")))));
    }

    /** Runs the core's self-test inside the game and prints the report to whoever ran the command. */
    private static int selfTest(CommandContext<CommandSourceStack> context) {
        SelfTest.Report report = SelfTest.run();
        for (String line : report.lines()) {
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return report.passed() ? 1 : 0;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Optional<LevelHeat> heat = HeatEvents.of(level);
        if (heat.isEmpty()) {
            source.sendFailure(Component.literal("Heat is not simulated in this dimension. It can be switched on "
                    + "in Anchor's world settings."));
            return 0;
        }
        send(source, HeatText.status(level.dimension().identifier().toString(), heat.get().report()));
        return 1;
    }

    private static int inspectLookedAt(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Entity entity = source.getEntity();
        BlockPos pos = BlockPos.containing(source.getPosition());
        if (entity != null) {
            HitResult hit = entity.pick(REACH, 1.0f, false);
            if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult block) {
                pos = block.getBlockPos();
            }
        }
        return inspect(context, pos);
    }

    private static int inspect(CommandContext<CommandSourceStack> context, BlockPos pos) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Optional<LevelHeat> heat = HeatEvents.of(level);
        if (heat.isEmpty()) {
            source.sendFailure(Component.literal("Heat is not simulated in this dimension."));
            return 0;
        }
        Optional<HostedWorld.Inspection> found = heat.get().inspect(pos);
        if (found.isEmpty()) {
            source.sendFailure(Component.literal("The block at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                    + " is not simulated: it is too far from players, or still being loaded."));
            return 0;
        }
        String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        send(source, HeatText.describe(found.get(), block));
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> context, BlockPos pos, double celsius) {
        CommandSourceStack source = context.getSource();
        Optional<LevelHeat> heat = HeatEvents.of(source.getLevel());
        double kelvin = celsius + 273.15;
        if (heat.isEmpty() || !heat.get().setTemperature(pos, kelvin)) {
            source.sendFailure(Component.literal("The block at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                    + " is not simulated or holds nothing to heat."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Set the block at " + pos.getX() + " " + pos.getY() + " "
                + pos.getZ() + " to " + HeatText.temperature(kelvin)), true);
        return 1;
    }

    private static void send(CommandSourceStack source, List<String> lines) {
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
    }
}
