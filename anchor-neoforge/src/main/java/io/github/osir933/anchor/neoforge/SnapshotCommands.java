package io.github.osir933.anchor.neoforge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.osir933.anchor.core.host.HostedWorld;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;

/**
 * The {@code /anchor snapshot} commands, which save experiments to rewind them; see {@link Snapshots}:
 * <ul>
 *   <li>{@code /anchor snapshot list}, or just {@code /anchor snapshot}, shows the snapshots saved in the player's
 *   dimension;</li>
 *   <li>{@code /anchor snapshot save <name> [<from> <to>]} saves a box of blocks with the heat of every cell in it:
 *   the blocks from one corner to the other, or those within {@value Snapshots#REACH} blocks of the player;</li>
 *   <li>{@code /anchor snapshot restore <name> [<corner>]} puts them back as they were saved, heat and all, where
 *   they were saved or with the box's lowest corner somewhere else, as for a second run beside the first;</li>
 *   <li>{@code /anchor snapshot remove <name>} deletes one.</li>
 * </ul>
 * All but the list are for operators.
 */
final class SnapshotCommands {

    private static final Predicate<CommandSourceStack> GAMEMASTERS = Commands.hasPermission(
            Commands.LEVEL_GAMEMASTERS);

    private SnapshotCommands() {
    }

    /**
     * Builds the {@code snapshot} branch of the {@code /anchor} command.
     *
     * @return the branch
     */
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("snapshot")
                .executes(SnapshotCommands::list)
                .then(Commands.literal("list").executes(SnapshotCommands::list))
                .then(Commands.literal("save").requires(GAMEMASTERS)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(SnapshotCommands::saveAround)
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .executes(context -> save(context,
                                                        BlockPosArgument.getLoadedBlockPos(context, "from"),
                                                        BlockPosArgument.getLoadedBlockPos(context, "to")))))))
                .then(Commands.literal("restore").requires(GAMEMASTERS)
                        .then(snapshotName().executes(context -> restore(context, null))
                                .then(Commands.argument("corner", BlockPosArgument.blockPos())
                                        .executes(context -> restore(context,
                                                BlockPosArgument.getLoadedBlockPos(context, "corner"))))))
                .then(Commands.literal("remove").requires(GAMEMASTERS)
                        .then(snapshotName().executes(SnapshotCommands::remove)));
    }

    /** The name of a saved snapshot, with the names of the dimension's snapshots as suggestions. */
    private static RequiredArgumentBuilder<CommandSourceStack, String> snapshotName() {
        return Commands.argument("name", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        Snapshots.names(context.getSource().getLevel()), builder));
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        List<String> names = Snapshots.names(level);
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No snapshots are saved in " + dimension(level)
                    + "; operators save one with /anchor snapshot save <name>."), false);
            return 0;
        }
        String folder = Snapshots.relativeFolder(level).toString().replace('\\', '/');
        source.sendSuccess(() -> Component.literal((names.size() == 1 ? "1 snapshot" : names.size() + " snapshots")
                + " in " + dimension(level) + ", kept in " + folder + " in the world's folder:"), false);
        long now = System.currentTimeMillis();
        for (String name : names) {
            MutableComponent line = Component.literal("  ")
                    .append(Component.literal(name).withStyle(ChatFormatting.GOLD));
            try {
                Snapshots.Saved saved = Snapshots.read(Snapshots.file(level, name));
                String age = saved.savedAtMillis() > 0 ? ", saved " + HeatText.duration(Math.max(0L,
                        now - saved.savedAtMillis()) / 1000.0) + " ago" : "";
                line.append(Component.literal(": " + size(saved.size()) + " blocks from " + position(saved.origin())
                        + ", " + ownHeat(saved.heat().size()) + age));
            } catch (IOException e) {
                line.append(Component.literal(": cannot be read, as " + e.getMessage())
                        .withStyle(ChatFormatting.RED));
            }
            source.sendSuccess(() -> line, false);
        }
        return names.size();
    }

    /** Saves the blocks within reach of the player, as far as the world reaches up and down. */
    private static int saveAround(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        int bottom = Math.max(level.getMinY(), at.getY() - Snapshots.REACH);
        int top = Math.min(level.getMinY() + level.getHeight() - 1, at.getY() + Snapshots.REACH);
        if (bottom > top) {
            source.sendFailure(Component.literal("There are no blocks of the world within " + Snapshots.REACH
                    + " blocks of you; give the box's corners instead."));
            return 0;
        }
        return save(context, new BlockPos(at.getX() - Snapshots.REACH, bottom, at.getZ() - Snapshots.REACH),
                new BlockPos(at.getX() + Snapshots.REACH, top, at.getZ() + Snapshots.REACH));
    }

    private static int save(CommandContext<CommandSourceStack> context, BlockPos from, BlockPos to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Optional<String> name = name(context);
        if (name.isEmpty()) {
            return 0;
        }
        Optional<LevelHeat> heat = TimeCommands.running(source);
        if (heat.isEmpty()) {
            return 0;
        }
        BlockPos min = BlockPos.min(from, to);
        BlockPos max = BlockPos.max(from, to);
        Vec3i size = new Vec3i(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
        if (Math.max(size.getX(), Math.max(size.getY(), size.getZ())) > Snapshots.MAX_EDGE) {
            source.sendFailure(Component.literal("A snapshot's box is at most " + Snapshots.MAX_EDGE
                    + " blocks along each side, and that one is " + size(size) + "."));
            return 0;
        }
        boolean replacing = Files.exists(Snapshots.file(level, name.get()));
        Optional<Snapshots.Saved> saved;
        try {
            saved = Snapshots.save(level, heat.get(), name.get(), min, max);
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not write snapshot " + name.get() + ": " + e.getMessage()));
            return 0;
        }
        if (saved.isEmpty()) {
            notTaken(source, heat.get());
            return 0;
        }
        String own = ownHeat(saved.get().heat().size());
        source.sendSuccess(() -> Component.literal("Saved snapshot " + name.get() + ": the " + size(size)
                + " blocks from " + position(min) + " to " + position(max) + " and the heat of every cell in them, "
                + own + (replacing ? ", in place of the snapshot saved under that name before" : "")
                + ". /anchor snapshot restore " + name.get() + " brings them back as they are now."), true);
        return 1;
    }

    private static int restore(CommandContext<CommandSourceStack> context, BlockPos corner) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Optional<String> name = name(context);
        if (name.isEmpty()) {
            return 0;
        }
        Optional<LevelHeat> heat = TimeCommands.running(source);
        if (heat.isEmpty()) {
            return 0;
        }
        Optional<Snapshots.Saved> saved = read(source, level, name.get());
        if (saved.isEmpty()) {
            return 0;
        }
        BlockPos at = corner == null ? saved.get().origin() : corner;
        Vec3i size = saved.get().size();
        if (at.getY() < level.getMinY() || at.getY() + size.getY() > level.getMinY() + level.getHeight()) {
            source.sendFailure(Component.literal("Snapshot " + name.get() + " is " + size.getY() + " blocks tall "
                    + "and does not fit between the bottom and the top of the world from " + position(at) + "."));
            return 0;
        }
        Optional<HostedWorld.Restored> restored;
        try {
            restored = Snapshots.restore(level, heat.get(), saved.get(), at);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Snapshot " + name.get() + " cannot be restored: "
                    + e.getMessage() + "."));
            return 0;
        }
        if (restored.isEmpty()) {
            notTaken(source, heat.get());
            return 0;
        }
        HostedWorld.Restored r = restored.get();
        String afresh = r.afresh() == 0 ? "" : " " + (r.afresh() == 1 ? "1 block starts" : r.afresh()
                + " blocks start") + " afresh instead, as Anchor no longer takes them for what they held.";
        source.sendSuccess(() -> Component.literal("Restored snapshot " + name.get() + " at " + position(at)
                + ": its " + size(size) + " blocks are back as they were saved, heat and all, and "
                + (r.changed() == 0 ? "none of them" : r.changed() == 1 ? "1 of them" : r.changed() + " of them")
                + " had changed." + afresh
                + " Things that move, such as items and animals, stay as they are."), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Optional<String> name = name(context);
        if (name.isEmpty()) {
            return 0;
        }
        try {
            if (!Files.deleteIfExists(Snapshots.file(level, name.get()))) {
                source.sendFailure(noSuchSnapshot(level, name.get()));
                return 0;
            }
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not remove snapshot " + name.get() + ": " + e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Removed snapshot " + name.get() + "."), true);
        return 1;
    }

    /** Returns the snapshot name given in a command, in lower case, or tells the player why it is no name. */
    private static Optional<String> name(CommandContext<CommandSourceStack> context) {
        try {
            return Optional.of(Snapshots.checkName(StringArgumentType.getString(context, "name")));
        } catch (IllegalArgumentException e) {
            context.getSource().sendFailure(Component.literal(e.getMessage()));
            return Optional.empty();
        }
    }

    /** Reads a snapshot, or tells the player why it cannot be read. */
    private static Optional<Snapshots.Saved> read(CommandSourceStack source, ServerLevel level, String name) {
        try {
            return Optional.of(Snapshots.read(Snapshots.file(level, name)));
        } catch (NoSuchFileException e) {
            source.sendFailure(noSuchSnapshot(level, name));
        } catch (IOException e) {
            source.sendFailure(Component.literal("Snapshot " + name + " cannot be read, as " + e.getMessage() + "."));
        }
        return Optional.empty();
    }

    /** Tells the player why heat did not save or restore a snapshot. */
    private static void notTaken(CommandSourceStack source, LevelHeat heat) {
        if (heat.report().failure() != null) {
            source.sendFailure(Component.literal("Heat here stopped after an error; /anchor heat status says more."));
        } else {
            source.sendFailure(Component.literal("Heat does not run in all of that box. It runs near players, and a "
                    + "snapshot takes every block of its box: come closer, or wait a moment after arriving, and try "
                    + "again."));
        }
    }

    /** Says how many of a snapshot's blocks differ from blocks just placed, which take their surroundings' heat. */
    private static String ownHeat(int blocks) {
        return blocks == 0 ? "none of them with heat of its own" : blocks == 1 ? "1 of them with heat of its own"
                : blocks + " of them with heat of their own";
    }

    private static Component noSuchSnapshot(ServerLevel level, String name) {
        return Component.literal("There is no snapshot called " + name + " in " + dimension(level)
                + "; /anchor snapshot list shows them.");
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private static String size(Vec3i size) {
        return size.getX() + "×" + size.getY() + "×" + size.getZ();
    }

    private static String position(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
