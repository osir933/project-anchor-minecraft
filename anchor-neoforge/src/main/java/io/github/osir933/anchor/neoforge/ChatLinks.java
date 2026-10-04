package io.github.osir933.anchor.neoforge;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** Commands in chat messages that players can click. */
final class ChatLinks {

    private ChatLinks() {
    }

    /**
     * Returns a command that players can click to start typing it, so that they finish it or confirm it themselves.
     *
     * @param command the command, starting with a slash
     * @return the command, underlined
     */
    static MutableComponent type(String command) {
        return type(Component.literal(command), command);
    }

    /**
     * Returns some text that players can click to start typing a command, so that they finish it or confirm it
     * themselves.
     *
     * @param text the text
     * @param command the command, starting with a slash
     * @return the text, underlined
     */
    static MutableComponent type(MutableComponent text, String command) {
        return link(text, new ClickEvent.SuggestCommand(command + " "),
                Component.translatableWithFallback("message.anchor.link.type", "Click to type it"));
    }

    /**
     * Returns a command that players can click to run it at once, for commands that are easily undone.
     *
     * @param command the command, starting with a slash
     * @return the command, underlined
     */
    static MutableComponent run(String command) {
        return link(Component.literal(command), new ClickEvent.RunCommand(command),
                Component.translatableWithFallback("message.anchor.link.run", "Click to run it"));
    }

    private static MutableComponent link(MutableComponent text, ClickEvent click, Component hover) {
        return text.withStyle(style -> style
                .withClickEvent(click)
                .withHoverEvent(new HoverEvent.ShowText(hover))
                .withUnderlined(true));
    }
}
