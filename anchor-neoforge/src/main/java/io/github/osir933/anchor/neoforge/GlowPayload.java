package io.github.osir933.anchor.neoforge;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Tells a player which blocks of one section glow, and how: sent from the server whenever that changes, and with no
 * blocks when nothing there glows any more.
 *
 * @param section the section, packed as the core packs it
 * @param data the glowing blocks, as {@link GlowData#encode} packs them
 */
record GlowPayload(long section, byte[] data) implements CustomPacketPayload {

    /** The payload's type. */
    static final CustomPacketPayload.Type<GlowPayload> TYPE = new CustomPacketPayload.Type<>(AnchorMod.id("glow"));

    /** How the payload is written and read. */
    static final StreamCodec<ByteBuf, GlowPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.LONG, GlowPayload::section,
            ByteBufCodecs.byteArray(GlowData.MAX_BYTES), GlowPayload::data,
            GlowPayload::new);

    @Override
    public CustomPacketPayload.Type<GlowPayload> type() {
        return TYPE;
    }
}
