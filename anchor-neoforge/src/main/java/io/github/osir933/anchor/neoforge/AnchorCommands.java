package io.github.osir933.anchor.neoforge;

import com.mojang.brigadier.context.CommandContext;
import io.github.osir933.anchor.core.diagnostics.SelfTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** The {@code /anchor} command tree. */
final class AnchorCommands {

    private AnchorCommands() {
    }

    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("anchor")
                .then(Commands.literal("selftest").executes(AnchorCommands::selfTest)));
    }

    /** Runs the core's self-test inside the game and prints the report to whoever ran the command. */
    private static int selfTest(CommandContext<CommandSourceStack> context) {
        SelfTest.Report report = SelfTest.run();
        for (String line : report.lines()) {
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return report.passed() ? 1 : 0;
    }
}
