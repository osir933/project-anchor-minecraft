package io.github.osir933.anchor.neoforge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.osir933.anchor.core.instrument.ChartImage;
import io.github.osir933.anchor.core.instrument.ProbeSet;
import io.github.osir933.anchor.core.instrument.Sparkline;
import io.github.osir933.anchor.core.instrument.TimeSeries;
import io.github.osir933.anchor.core.instrument.TimeSeriesCsv;
import io.github.osir933.anchor.core.space.GridPos;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The {@code /anchor probe} commands, for probes that record the temperature at a point every simulation step:
 * <ul>
 *   <li>{@code /anchor probe add} puts a probe where the player is looking; operators can put one in the middle of
 *   any block with {@code /anchor probe add <pos> [<name>]}. Sneaking and using a thermometer on a block does the same
 *   as looking at it and adding a probe;</li>
 *   <li>{@code /anchor probe list} shows every probe with its latest reading and a line of what it recorded, and
 *   {@code /anchor probe show <name>} tells more about one;</li>
 *   <li>{@code /anchor probe chart [<names>]} turns an empty map into a chart of as many as four probes, which Anchor
 *   keeps drawing as they record;</li>
 *   <li>{@code /anchor probe export [<name>]} writes recordings into the world's folder as CSV files;</li>
 *   <li>{@code rename}, {@code reset} and {@code remove} look after one probe, and operators can remove them all with
 *   {@code /anchor probe clear}.</li>
 * </ul>
 */
final class ProbeCommands {

    /** How far away a looked-at block can be probed, in blocks. */
    private static final double REACH = 20.0;

    /** How many bars the line of a recording has in a list. */
    private static final int LIST_BARS = 24;

    /** How many bars it has when one probe is shown. */
    private static final int SHOW_BARS = 40;

    /** The folder in the world's folder that recordings are written to. */
    private static final String EXPORT_FOLDER = "anchor/probes";

    private static final Predicate<CommandSourceStack> GAMEMASTERS = Commands.hasPermission(
            Commands.LEVEL_GAMEMASTERS);

    private ProbeCommands() {
    }

    /**
     * Builds the {@code probe} branch of the {@code /anchor} command.
     *
     * @return the branch
     */
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("probe")
                .then(Commands.literal("list").executes(ProbeCommands::list))
                .then(Commands.literal("add").executes(ProbeCommands::addLookedAt)
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .requires(GAMEMASTERS)
                                .executes(context -> addAt(context, null))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(context -> addAt(context,
                                                StringArgumentType.getString(context, "name"))))))
                .then(Commands.literal("show").then(probeName().executes(ProbeCommands::show)))
                .then(Commands.literal("remove").then(probeName().executes(ProbeCommands::remove)))
                .then(Commands.literal("reset").then(probeName().executes(ProbeCommands::reset)))
                .then(Commands.literal("rename").then(probeName()
                        .then(Commands.argument("new_name", StringArgumentType.word())
                                .executes(ProbeCommands::rename))))
                .then(Commands.literal("chart").executes(context -> chart(context, ""))
                        .then(Commands.argument("names", StringArgumentType.greedyString())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        names(context.getSource().getLevel()), builder))
                                .executes(context -> chart(context, StringArgumentType.getString(context,
                                        "names")))))
                .then(Commands.literal("export")
                        .requires(source -> GAMEMASTERS.test(source) || !source.getServer().isDedicatedServer())
                        .executes(context -> export(context, null))
                        .then(probeName().executes(context -> export(context,
                                StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("clear").requires(GAMEMASTERS).executes(ProbeCommands::clear));
    }

    /** The name of an existing probe, with the level's probe names as suggestions. */
    private static RequiredArgumentBuilder<CommandSourceStack, String> probeName() {
        return Commands.argument("name", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        names(context.getSource().getLevel()), builder));
    }

    private static List<String> names(ServerLevel level) {
        List<String> names = new ArrayList<>();
        for (ProbeSet.Probe p : probes(level).set().probes()) {
            names.add(p.name());
        }
        return names;
    }

    /**
     * Returns a level's probes.
     *
     * @param level the level
     * @return its probes, saved with it
     */
    static LevelProbes probes(ServerLevel level) {
        return level.getData(AnchorAttachments.PROBES);
    }

    /**
     * What adding or removing a probe did.
     *
     * @param done whether it was done
     * @param message what to tell the player
     */
    record Outcome(boolean done, Component message) {
    }

    /**
     * Adds a probe that measures a point of a block.
     *
     * @param level the level
     * @param pos the block
     * @param at the point, which is moved into the block if it lies on its outline
     * @param name the probe's name, or {@code null} to number it
     * @return what happened
     */
    static Outcome add(ServerLevel level, BlockPos pos, Vec3 at, String name) {
        if (HeatEvents.of(level).isEmpty()) {
            return new Outcome(false, Component.translatableWithFallback("message.anchor.thermometer.off",
                    "Temperatures are not simulated here"));
        }
        ProbeSet set = probes(level).set();
        if (!set.canAdd()) {
            return new Outcome(false, Component.translatableWithFallback("message.anchor.probe.full",
                    "A dimension holds at most %s probes; remove one with /anchor probe remove", set.limit()));
        }
        if (name != null && !ProbeSet.validName(name)) {
            return new Outcome(false, Component.translatableWithFallback("message.anchor.probe.bad_name",
                    "Probe names are letters, digits, _ and -, at most %s of them", ProbeSet.MAX_NAME_LENGTH));
        }
        if (name != null && set.get(name).isPresent()) {
            return new Outcome(false, Component.translatableWithFallback("message.anchor.probe.taken",
                    "A probe is already called %s", ProbeSet.normalize(name)));
        }
        ProbeSet.Probe probe = set.add(name, grid(pos), at.x(), at.y(), at.z());
        return new Outcome(true, Component.translatableWithFallback("message.anchor.probe.added",
                "Recording %s as %s", level.getBlockState(pos).getBlock().getName(), probe.name()));
    }

    /**
     * Adds a probe where a player touched a block, or removes the probe already in the block.
     *
     * @param level the level
     * @param pos the block
     * @param at where it was touched
     * @return what happened
     */
    static Outcome toggle(ServerLevel level, BlockPos pos, Vec3 at) {
        ProbeSet set = probes(level).set();
        Optional<ProbeSet.Probe> there = set.in(grid(pos));
        if (there.isEmpty()) {
            return add(level, pos, at, null);
        }
        set.remove(there.get().name());
        return new Outcome(true, Component.translatableWithFallback("message.anchor.probe.removed",
                "Stopped recording %s (%s)", level.getBlockState(pos).getBlock().getName(), there.get().name()));
    }

    private static int addLookedAt(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Entity entity = source.getEntity();
        HitResult hit = entity == null ? null : entity.pick(REACH, 1.0f, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Look at a block to probe it, or give its position."));
            return 0;
        }
        return report(source, add(source.getLevel(), block.getBlockPos(), block.getLocation(), null));
    }

    private static int addAt(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        return report(context.getSource(), add(context.getSource().getLevel(), pos, Vec3.atCenterOf(pos), name));
    }

    private static int report(CommandSourceStack source, Outcome outcome) {
        if (outcome.done()) {
            source.sendSuccess(outcome::message, false);
            return 1;
        }
        source.sendFailure(outcome.message());
        return 0;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        ProbeSet set = probes(level).set();
        if (set.size() == 0) {
            source.sendSuccess(() -> Component.literal("No probes here yet. Sneak and use a thermometer on a block, "
                    + "or look at a block and run /anchor probe add."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%d of at most %d probes in %s, "
                + "recording every %s of simulated time:", set.size(), set.limit(),
                level.dimension().identifier(), stepText(level))), false);
        for (ProbeSet.Probe p : set.probes()) {
            TimeSeries s = p.series();
            MutableComponent line = Component.literal("  ")
                    .append(Component.literal(p.name()).withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(" at " + position(p.block()) + ", "));
            line.append(blockName(level, p.block())).append(Component.literal(": " + now(s) + " "));
            line.append(sparkline(s, LIST_BARS));
            source.sendSuccess(() -> line, false);
        }
        return set.size();
    }

    private static int show(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Optional<ProbeSet.Probe> found = probe(context);
        if (found.isEmpty()) {
            return 0;
        }
        ProbeSet.Probe p = found.get();
        TimeSeries s = p.series();
        MutableComponent title = Component.literal("Probe ")
                .append(Component.literal(p.name()).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" at " + position(p.block()) + ", measuring " + where(p) + " of "))
                .append(blockName(level, p.block()));
        source.sendSuccess(() -> title, false);
        source.sendSuccess(() -> Component.literal("  Now " + now(s)), false);
        if (!Double.isNaN(s.minimum())) {
            source.sendSuccess(() -> Component.literal("  Lowest " + HeatText.celsius(s.minimum()) + ", highest "
                    + HeatText.celsius(s.maximum()) + ", mean " + HeatText.celsius(s.mean())), false);
        }
        long readings = 0;
        for (int i = 0; i < s.size(); i++) {
            readings += s.bucket(i).readings();
        }
        long missing = s.samples() - readings;
        String span = s.isEmpty() ? "nothing yet" : HeatText.duration(s.lastTime() - s.firstTime())
                + " of simulated time in " + s.samples() + " readings"
                + (missing > 0 ? ", " + missing + " of them missing while the block was not simulated" : "");
        source.sendSuccess(() -> Component.literal("  Recorded " + span), false);
        if (s.size() > 0) {
            source.sendSuccess(() -> Component.literal("  ").append(sparkline(s, SHOW_BARS)), false);
        }
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> context) {
        Optional<ProbeSet.Probe> found = probe(context);
        if (found.isEmpty()) {
            return 0;
        }
        probes(context.getSource().getLevel()).set().remove(found.get().name());
        context.getSource().sendSuccess(() -> Component.literal("Removed probe " + found.get().name()
                + " and what it recorded"), false);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        Optional<ProbeSet.Probe> found = probe(context);
        if (found.isEmpty()) {
            return 0;
        }
        found.get().series().clear();
        context.getSource().sendSuccess(() -> Component.literal("Probe " + found.get().name()
                + " starts recording afresh"), false);
        return 1;
    }

    private static int rename(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Optional<ProbeSet.Probe> found = probe(context);
        if (found.isEmpty()) {
            return 0;
        }
        String old = found.get().name();
        String wanted = StringArgumentType.getString(context, "new_name");
        ProbeSet set = probes(source.getLevel()).set();
        if (!ProbeSet.validName(wanted)) {
            source.sendFailure(Component.literal("Probe names are letters, digits, _ and -, at most "
                    + ProbeSet.MAX_NAME_LENGTH + " of them."));
            return 0;
        }
        if (!set.rename(old, wanted)) {
            source.sendFailure(Component.literal("A probe is already called " + ProbeSet.normalize(wanted) + "."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Probe " + old + " is now called " + found.get().name()), false);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        ProbeSet set = probes(context.getSource().getLevel()).set();
        int n = set.size();
        set.clear();
        context.getSource().sendSuccess(() -> Component.literal("Removed " + n + " probes and what they recorded"),
                true);
        return n;
    }

    /**
     * Turns an empty map from the player's inventory into a chart of some probes; players in creative mode and
     * operators need no map.
     */
    private static int chart(CommandContext<CommandSourceStack> context, String names) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        LevelProbes probes = probes(level);
        List<ProbeSet.Probe> shown = new ArrayList<>();
        for (String name : names.trim().split("[\\s,]+")) {
            if (name.isEmpty()) {
                continue;
            }
            Optional<ProbeSet.Probe> p = probes.set().get(name);
            if (p.isEmpty()) {
                source.sendFailure(Component.literal("No probe is called " + name + "; /anchor probe list names "
                        + "them all."));
                return 0;
            }
            if (!shown.contains(p.get())) {
                shown.add(p.get());
            }
        }
        if (shown.isEmpty()) {
            shown.addAll(probes.set().probes());
        }
        if (shown.isEmpty()) {
            source.sendFailure(Component.literal("There are no probes here to chart. Sneak and use a thermometer on "
                    + "a block to start recording it."));
            return 0;
        }
        if (shown.size() > ChartImage.MAX_TRACES) {
            shown = new ArrayList<>(shown.subList(0, ChartImage.MAX_TRACES));
            source.sendSuccess(() -> Component.literal("A chart shows at most " + ChartImage.MAX_TRACES
                    + " probes; this one shows the first of them."), false);
        }
        if (!player.isCreative() && !GAMEMASTERS.test(source) && !takeEmptyMap(player.getInventory())) {
            source.sendFailure(Component.literal("A chart is drawn on an empty map; carry one and try again."));
            return 0;
        }
        ItemStack chart = ProbeCharts.create(level, probes, shown);
        Component name = chart.getHoverName();
        AnchorItems.give(player, chart);
        source.sendSuccess(() -> Component.literal("Drew a ").append(name).append(Component.literal(
                "; it keeps up while the probes record, and it can hang in an item frame")), false);
        return 1;
    }

    /** Takes one empty map from an inventory, if it holds one. */
    private static boolean takeEmptyMap(Inventory inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(Items.MAP)) {
                stack.shrink(1);
                inventory.setChanged();
                return true;
            }
        }
        return false;
    }

    /** Writes recordings as CSV files into the world's folder, one file per probe. */
    private static int export(CommandContext<CommandSourceStack> context, String name) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        ProbeSet set = probes(level).set();
        List<ProbeSet.Probe> chosen = new ArrayList<>();
        if (name == null) {
            chosen.addAll(set.probes());
        } else {
            Optional<ProbeSet.Probe> p = probe(context);
            if (p.isEmpty()) {
                return 0;
            }
            chosen.add(p.get());
        }
        if (chosen.isEmpty()) {
            source.sendFailure(Component.literal("There are no probes here to export."));
            return 0;
        }
        Identifier dimension = level.dimension().identifier();
        Path relative = Path.of(EXPORT_FOLDER, dimension.getNamespace(), dimension.getPath());
        Path folder = source.getServer().getWorldPath(LevelResource.ROOT).resolve(relative).normalize();
        try {
            Files.createDirectories(folder);
            for (ProbeSet.Probe p : chosen) {
                Files.writeString(folder.resolve(p.name() + ".csv"),
                        TimeSeriesCsv.write(p.series(), "C", HeatText::toCelsius), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not write the recordings to " + folder + ": "
                    + e.getMessage()));
            return 0;
        }
        String files = chosen.size() == 1 ? chosen.get(0).name() + ".csv" : chosen.size() + " files";
        source.sendSuccess(() -> Component.literal("Wrote " + files + " to " + relative.toString().replace('\\', '/')
                + " in the world's folder: a row per stretch of time, with the lowest, mean and highest "
                + "temperature in it in degrees Celsius"), false);
        return chosen.size();
    }

    /** Finds the probe named in a command, telling the player if there is none. */
    private static Optional<ProbeSet.Probe> probe(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        Optional<ProbeSet.Probe> p = probes(context.getSource().getLevel()).set().get(name);
        if (p.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No probe is called " + name
                    + "; /anchor probe list names them all."));
        }
        return p;
    }

    /** The latest reading and how fast it is changing. */
    private static String now(TimeSeries s) {
        double last = s.last();
        if (s.isEmpty()) {
            return "no reading yet";
        }
        if (Double.isNaN(last)) {
            return "no reading: the block is not simulated";
        }
        String rate = HeatText.rate(s.recentRate());
        return HeatText.celsius(last) + (rate.isEmpty() ? "" : ", " + rate);
    }

    /** A line of bars of what a probe recorded; parts without readings are grey. */
    private static MutableComponent sparkline(TimeSeries s, int bars) {
        MutableComponent line = Component.empty();
        for (int level : Sparkline.levels(s, bars)) {
            line.append(level == Sparkline.GAP
                    ? Component.literal(String.valueOf(Sparkline.BARS.charAt(0))).withStyle(ChatFormatting.DARK_GRAY)
                    : Component.literal(String.valueOf(Sparkline.BARS.charAt(level))).withStyle(ChatFormatting.AQUA));
        }
        return line;
    }

    /** The name of the block a probe measures, if its chunk is loaded. */
    private static Component blockName(ServerLevel level, GridPos block) {
        BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
        return level.isLoaded(pos) ? level.getBlockState(pos).getBlock().getName()
                : Component.literal("a block that is not loaded");
    }

    /** Says which part of its block a probe measures. */
    private static String where(ProbeSet.Probe p) {
        double fx = p.x() - p.block().x();
        double fy = p.y() - p.block().y();
        double fz = p.z() - p.block().z();
        double edge = 1.0 / 16.0;
        if (fy >= 1.0 - edge) {
            return "the top";
        }
        if (fy < edge) {
            return "the bottom";
        }
        if (fx < edge) {
            return "the west side";
        }
        if (fx >= 1.0 - edge) {
            return "the east side";
        }
        if (fz < edge) {
            return "the north side";
        }
        if (fz >= 1.0 - edge) {
            return "the south side";
        }
        return "the inside";
    }

    private static String stepText(ServerLevel level) {
        return HeatEvents.of(level).map(heat -> HeatText.duration(heat.report().stepSeconds()))
                .orElse("step (heat is off here)");
    }

    private static String position(GridPos block) {
        return block.x() + " " + block.y() + " " + block.z();
    }

    private static GridPos grid(BlockPos pos) {
        return new GridPos(pos.getX(), pos.getY(), pos.getZ());
    }
}
