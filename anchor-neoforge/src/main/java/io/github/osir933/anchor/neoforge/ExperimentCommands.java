package io.github.osir933.anchor.neoforge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.osir933.anchor.core.instrument.ProbeSet;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@code /anchor experiment} commands, for ready-made experiments; see {@link Experiments}:
 * <ul>
 *   <li>{@code /anchor experiment list}, or just {@code /anchor experiment}, names them and tells what each shows;</li>
 *   <li>{@code /anchor experiment build <name>} builds one in front of the player and starts it, with probes where its
 *   results show, gives the player a chart of the probes and tells them what to watch for.</li>
 * </ul>
 * Building is for operators.
 */
final class ExperimentCommands {

    private static final Predicate<CommandSourceStack> GAMEMASTERS = Commands.hasPermission(
            Commands.LEVEL_GAMEMASTERS);

    private ExperimentCommands() {
    }

    /**
     * Builds the {@code experiment} branch of the {@code /anchor} command.
     *
     * @return the branch
     */
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("experiment")
                .executes(ExperimentCommands::list)
                .then(Commands.literal("list").executes(ExperimentCommands::list))
                .then(Commands.literal("build").requires(GAMEMASTERS)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(Experiments.names(),
                                        builder))
                                .executes(ExperimentCommands::buildOne)));
    }

    /** Names the experiments and tells what each shows; operators can click a name to start building it. */
    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean builds = GAMEMASTERS.test(source);
        source.sendSuccess(() -> Component.translatableWithFallback("message.anchor.experiment.list",
                "Ready-made experiments, which operators build in front of them with /anchor experiment build "
                        + "<name>:"), false);
        for (Experiments.Experiment e : Experiments.ALL) {
            MutableComponent name = Component.literal(e.name()).withStyle(ChatFormatting.GOLD);
            MutableComponent line = Component.literal("  ")
                    .append(builds ? ChatLinks.type(name, "/anchor experiment build " + e.name()) : name)
                    .append(Component.literal(": ")).append(e.titleText()).append(Component.literal(". "))
                    .append(e.summaryText());
            source.sendSuccess(() -> line, false);
        }
        return Experiments.ALL.size();
    }

    private static int buildOne(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        Optional<Experiments.Experiment> found = Experiments.find(name);
        if (found.isEmpty()) {
            source.sendFailure(Component.translatableWithFallback("message.anchor.experiment.unknown",
                    "There is no experiment called %s; %s names them.", name,
                    ChatLinks.run("/anchor experiment list")));
            return 0;
        }
        Optional<LevelHeat> heat = TimeCommands.running(source);
        if (heat.isEmpty()) {
            return 0;
        }
        ServerLevel level = source.getLevel();
        Experiments.Experiment experiment = found.get();
        Experiments.Outcome outcome = Experiments.build(level, heat.get(), experiment,
                Experiments.Frame.inFrontOf(source.getPosition(), source.getRotation().y));
        Experiments.Built built = outcome.built();
        if (built == null) {
            source.sendFailure(outcome.refusal());
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        boolean charted = player != null && !built.probes().isEmpty();
        if (charted) {
            AnchorItems.give(player, ProbeCharts.create(level, ProbeCommands.probes(level), built.probes()));
        }
        source.sendSuccess(() -> Component.translatableWithFallback("message.anchor.experiment.built", "Built %s.",
                experiment.titleText().copy().withStyle(ChatFormatting.GOLD)), true);
        source.sendSuccess(experiment::summaryText, false);
        source.sendSuccess(() -> experiment.watchText().copy().withStyle(ChatFormatting.GRAY), false);
        if (!built.probes().isEmpty()) {
            MutableComponent names = Component.empty();
            for (ProbeSet.Probe p : built.probes()) {
                if (!names.getSiblings().isEmpty()) {
                    names.append(Component.literal(", "));
                }
                names.append(Component.literal(p.name()).withStyle(ChatFormatting.GOLD));
            }
            source.sendSuccess(() -> charted
                    ? Component.translatableWithFallback("message.anchor.experiment.chart", "Probes record it as it "
                            + "runs: %s. The chart you were given keeps drawing what they record.", names)
                    : Component.translatableWithFallback("message.anchor.experiment.probes", "Probes record it as it "
                            + "runs: %s.", names), false);
        }
        if (heat.get().pace().paused()) {
            source.sendSuccess(() -> Component.translatableWithFallback("message.anchor.experiment.paused",
                    "Heat is paused here, so nothing happens yet; %s lets it run.",
                    ChatLinks.run("/anchor time resume")), false);
        }
        if (experiment.speed() > 1) {
            source.sendSuccess(() -> Component.translatableWithFallback("message.anchor.experiment.speed",
                    "Speed heat up to see the result within minutes: %s, and %s to go back.",
                    ChatLinks.run("/anchor time speed " + experiment.speed()), ChatLinks.run("/anchor time speed 1")),
                    false);
        }
        if (built.snapshot() != null) {
            source.sendSuccess(() -> Component.translatableWithFallback("message.anchor.experiment.again",
                    "%s runs it again from the start.", ChatLinks.type("/anchor snapshot restore "
                            + built.snapshot())), false);
        }
        for (Component problem : built.problems()) {
            source.sendFailure(problem);
        }
        return 1;
    }
}
