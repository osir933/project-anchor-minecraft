package io.github.osir933.anchor.neoforge;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis;
import io.github.osir933.anchor.core.space.Direction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

/**
 * The {@code /anchor structure} commands:
 * <ul>
 *   <li>{@code /anchor structure inspect} tells whether the block being looked at is built or natural, which of its
 *   joints have cracked, how close uneven heat comes to cracking it through and, for a built block, how loaded its
 *   structure is now, how near it comes to buckling and which cracked joints pivot on an edge, as an arch's do;
 *   operators can name any block with {@code /anchor structure inspect <pos>};</li>
 *   <li>{@code /anchor structure mark <from> <to> built|natural} lets operators make a box of blocks built, so that
 *   it stands or falls by its strength, or natural, so that it holds still whatever happens around it.</li>
 * </ul>
 */
final class StructureCommands {

    /** How far away a looked-at block can be inspected, in blocks. */
    private static final double REACH = 20.0;

    /** The most blocks one mark may change, as many as {@code /fill} may. */
    static final int MAX_MARKED = 32768;

    private StructureCommands() {
    }

    /**
     * Builds the command tree under {@code /anchor structure}.
     *
     * @return the tree
     */
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("structure")
                .then(Commands.literal("inspect").executes(StructureCommands::inspectLookedAt)
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(context -> inspect(context,
                                        BlockPosArgument.getLoadedBlockPos(context, "pos")))))
                .then(Commands.literal("mark")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.literal("built").executes(context -> mark(context, true)))
                                        .then(Commands.literal("natural")
                                                .executes(context -> mark(context, false))))));
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
        Optional<LevelStructures.Look> look = heat.flatMap(h -> h.lookAtStructure(pos));
        if (look.isEmpty()) {
            source.sendFailure(Component.literal("The block at " + where(pos) + " is not simulated: structures stand "
                    + "or fall where heat runs, near players."));
            return 0;
        }
        String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        for (String line : describe(block, pos, look.get())) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int mark(CommandContext<CommandSourceStack> context, boolean built) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos a = BlockPosArgument.getLoadedBlockPos(context, "from");
        BlockPos b = BlockPosArgument.getLoadedBlockPos(context, "to");
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ()));
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1)
                * (max.getZ() - min.getZ() + 1);
        if (volume > MAX_MARKED) {
            source.sendFailure(Component.literal("That box holds " + volume + " blocks; at most " + MAX_MARKED
                    + " can be marked at once."));
            return 0;
        }
        Optional<LevelHeat> heat = HeatEvents.of(source.getLevel());
        if (heat.isEmpty() || !heat.get().simulates(min, max)) {
            source.sendFailure(Component.literal("Heat does not run in all of that box, so its blocks cannot be marked "
                    + "yet: wait a moment after arriving and try again."));
            return 0;
        }
        int changed = heat.get().setBuilt(min, max, built);
        source.sendSuccess(() -> Component.literal("Marked " + changed + (changed == 1 ? " block " : " blocks ")
                + (built ? "built: they now stand or fall by their strength." : "natural: they now hold still.")),
                true);
        return changed;
    }

    /**
     * Describes the look at a block's structure, as {@code /anchor structure inspect} shows it.
     *
     * @param block the block's name
     * @param pos where it is
     * @param look what was found
     * @return the lines to show
     */
    static List<String> describe(String block, BlockPos pos, LevelStructures.Look look) {
        List<String> lines = new ArrayList<>();
        if (!look.built()) {
            lines.add(block + " at " + where(pos) + ": natural, or carries no load, so it holds still whatever "
                    + "happens around it");
        } else {
            lines.add(block + " at " + where(pos) + ": built, so it stands or falls by its strength");
            lines.add("  Analysed with " + look.blocks() + (look.blocks() == 1 ? " built block" : " built blocks")
                    + (look.edge() == 0 ? ", the whole structure"
                            : ", and " + look.edge() + " more beyond them held still"));
            if (look.falls()) {
                lines.add("  It would fall" + (look.falling() > 1 ? ", and " + (look.falling() - 1) + " more with it"
                        : ""));
            } else {
                lines.add("  It stands; its most loaded joint takes " + percent(look.load()) + " of what it can");
                if (look.falling() > 0) {
                    lines.add("  " + look.falling() + " other blocks of the structure would fall");
                }
            }
            if (look.buckling() <= 1) {
                lines.add("  The structure is too slender for its loads: it buckles under them");
            } else if (look.buckling() < Double.POSITIVE_INFINITY) {
                lines.add(String.format(Locale.ROOT, "  The structure would buckle under %.2f times its loads, so it "
                        + "bows under them", look.buckling()));
            }
            if (look.hinges() > 0) {
                lines.add("  " + look.hinges() + (look.hinges() == 1 ? " cracked joint of the structure pivots"
                        : " cracked joints of the structure pivot") + " on an edge, pressing on as an arch's do");
            }
            String ground = ground(look.footing());
            if (ground != null) {
                lines.add("  " + ground);
            }
            StructuralAnalysis.BondResult worst = look.worst();
            if (worst != null) {
                Direction toward = StructuralAnalysis.direction(worst.axis());
                String heat = Math.abs(worst.load() - worst.withoutHeat()) < 0.005 ? ""
                        : "; without the strain of heat it would take " + percent(worst.withoutHeat());
                String bow = Math.abs(worst.load() - worst.unbowed()) < 0.005 ? ""
                        : "; standing straight it would take " + percent(worst.unbowed());
                lines.add("  The structure's most loaded joint takes " + percent(worst.load()) + " of what it can, "
                        + mode(worst.mode()) + ", between " + worst.pos().x() + " " + worst.pos().y() + " "
                        + worst.pos().z() + " and the block " + name(toward) + " of it" + heat + bow);
            }
            if (!look.settled()) {
                lines.add("  The analysis did not come to rest: more of it may give way");
            }
        }
        String strain = HeatText.thermalStress(look.thermalStress(), look.built(), look.fractured(),
                look.thermalShock());
        if (strain != null) {
            lines.add("  " + strain);
        }
        String stretched = HeatText.expansion(look.expansion(), look.unstrainedK());
        if (stretched != null) {
            lines.add("  " + stretched);
        }
        if (!look.crackedToward().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Direction d : look.crackedToward()) {
                names.add(name(d));
            }
            lines.add("  Cracked toward " + String.join(", ", names) + ": those joints hold only by pressing and "
                    + "friction");
        }
        if (!look.pivotsToward().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Direction d : look.pivotsToward()) {
                names.add(name(d));
            }
            lines.add("  Its " + (names.size() == 1 ? "joint" : "joints") + " toward " + String.join(", ", names)
                    + (names.size() == 1 ? " pivots" : " pivot") + " on an edge, carrying the push of an arch");
        }
        return lines;
    }

    /**
     * Describes how the ground holds up a structure's most loaded footing: how hard it is pressed against what it
     * bears, how far it has settled, and whether it gives way.
     *
     * @param footing the footing, or {@code null} if the structure stands on no ground that gives
     * @return the line, or {@code null} if there is nothing to say
     */
    static String ground(StructuralAnalysis.Footing footing) {
        if (footing == null) {
            return null;
        }
        String pressed = "Its most loaded footing presses the ground with " + percent(footing.load()) + " of what the "
                + "ground bears there";
        int sunk = footing.sunk().size();
        if (sunk > 0) {
            return pressed + ", so the ground gives way: " + sunk + (sunk == 1 ? " block" : " blocks") + " of it would "
                    + "be pushed aside";
        }
        double mm = 1000 * footing.settlement();
        return pressed + ", and has settled " + (mm < 0.05 ? "less than 0.1 mm"
                : String.format(Locale.ROOT, mm < 10 ? "%.1f mm" : "%.0f mm", mm)) + " into it";
    }

    /** Names the way a joint is loaded, as its share of what it can take does not say. */
    private static String mode(StructuralAnalysis.Mode mode) {
        return switch (mode) {
            case TENSION -> "pulled apart";
            case COMPRESSION -> "crushed";
            case SHEAR -> "sheared";
            case YIELDING -> "yielding";
            case PULLED_APART -> "its crack pulled open";
            case TIPPING -> "tipping over its edge";
            case SLIDING -> "sliding";
            case CRUSHING -> "its crack crushed";
            case SINKING -> "sinking into the ground";
        };
    }

    private static String name(Direction d) {
        return d.name().toLowerCase(Locale.ROOT);
    }

    private static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.0f%%", 100.0 * fraction);
    }

    private static String where(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
