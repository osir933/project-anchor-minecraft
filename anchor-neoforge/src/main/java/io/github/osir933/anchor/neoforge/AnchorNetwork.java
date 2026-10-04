package io.github.osir933.anchor.neoforge;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** The messages Anchor sends between server and client. The client registers how it handles them itself. */
final class AnchorNetwork {

    /** The version of Anchor's messages; a client and server with different versions cannot talk. */
    private static final String VERSION = "1";

    private AnchorNetwork() {
    }

    /**
     * Registers the messages.
     *
     * @param event the registration event on the mod bus
     */
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION).playToClient(GlowPayload.TYPE, GlowPayload.CODEC);
    }
}
