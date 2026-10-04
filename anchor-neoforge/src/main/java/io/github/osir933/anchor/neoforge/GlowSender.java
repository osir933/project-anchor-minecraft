package io.github.osir933.anchor.neoforge;

import com.mojang.logging.LogUtils;
import io.github.osir933.anchor.core.host.GlowingBlock;
import io.github.osir933.anchor.core.host.HostedWorld;
import io.github.osir933.anchor.core.space.SectionPos;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Tells the players of a level which blocks near them glow with heat, so their clients can draw it.
 *
 * <p>Every {@value #INTERVAL_TICKS} game ticks it goes through the simulated sections near each player. A section is
 * scanned again only when it has changed, or every {@value #RESCAN_TICKS} ticks in case a neighbour has, and each
 * player is sent the sections whose glow differs from what they were sent before; a section that stops glowing, or
 * that the player leaves behind, is sent with no blocks, so the client forgets it.
 */
final class GlowSender {

    /** Game ticks between looks at what glows. */
    static final int INTERVAL_TICKS = 10;

    /** A section unchanged this long, in game ticks, is scanned again anyway, as a neighbour may have changed. */
    static final int RESCAN_TICKS = 100;

    /** How far from a player, in blocks along each axis, a section's centre may be for its glow to be sent. */
    static final int RANGE_BLOCKS = 128;

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ServerLevel level;
    private final HostedWorld hosted;
    private final TreeMap<Long, Scan> scans = new TreeMap<>();
    private final Map<ServerPlayer, TreeMap<Long, byte[]>> sent = new IdentityHashMap<>();
    private long ticks;
    private boolean failed;

    /** A section's glow as last scanned. */
    private record Scan(long version, long tick, byte[] data) {
    }

    /**
     * Creates the sender for a level.
     *
     * @param level the level
     * @param hosted the level's simulated world
     */
    GlowSender(ServerLevel level, HostedWorld hosted) {
        this.level = level;
        this.hosted = hosted;
    }

    /** Runs one game tick, sending what has changed every {@value #INTERVAL_TICKS} ticks. */
    void tick() {
        if (failed || ++ticks % INTERVAL_TICKS != 0) {
            return;
        }
        try {
            send();
        } catch (RuntimeException e) {
            // The glow is only for show: heat goes on without it.
            failed = true;
            LOGGER.error("Anchor stopped showing glowing blocks in {}", level.dimension().identifier(), e);
        }
    }

    /**
     * Forgets what a player was sent, as their client forgets it on leaving the level.
     *
     * @param player the player
     */
    void forget(ServerPlayer player) {
        sent.remove(player);
    }

    /**
     * Lists the blocks of a section that glow, as players near it are told.
     *
     * @param hosted the simulated world
     * @param key the packed section position
     * @return the glowing blocks
     */
    static List<GlowingBlock> glowingBlocks(HostedWorld hosted, long key) {
        return hosted.glowingBlocks(key, GlowSender::hides, GlowSender::shines);
    }

    private void send() {
        scans.keySet().removeIf(key -> !hosted.isImported(key));
        List<ServerPlayer> players = level.players();
        sent.keySet().removeIf(player -> !players.contains(player));
        for (ServerPlayer player : players) {
            TreeMap<Long, byte[]> known = sent.computeIfAbsent(player, p -> new TreeMap<>());
            TreeSet<Long> near = new TreeSet<>();
            for (long key : hosted.importedSections()) {
                if (near(player, key)) {
                    near.add(key);
                }
            }
            for (long key : near) {
                byte[] data = scan(key);
                byte[] had = known.get(key);
                if (data.length == 0) {
                    if (had != null) {
                        PacketDistributor.sendToPlayer(player, new GlowPayload(key, GlowData.NONE));
                        known.remove(key);
                    }
                } else if (had == null || !Arrays.equals(had, data)) {
                    PacketDistributor.sendToPlayer(player, new GlowPayload(key, data));
                    known.put(key, data);
                }
            }
            for (Iterator<Long> it = known.keySet().iterator(); it.hasNext();) {
                long key = it.next();
                if (!near.contains(key)) {
                    PacketDistributor.sendToPlayer(player, new GlowPayload(key, GlowData.NONE));
                    it.remove();
                }
            }
        }
    }

    private byte[] scan(long key) {
        long version = hosted.sectionVersion(key);
        Scan scan = scans.get(key);
        if (scan == null || scan.version() != version || ticks - scan.tick() >= RESCAN_TICKS) {
            scan = new Scan(version, ticks, GlowData.encode(glowingBlocks(hosted, key)));
            scans.put(key, scan);
        }
        return scan.data();
    }

    private static boolean near(ServerPlayer player, long key) {
        return Math.abs(SectionPos.x(key) * 16 + 8 - player.getX()) <= RANGE_BLOCKS
                && Math.abs(SectionPos.y(key) * 16 + 8 - player.getY()) <= RANGE_BLOCKS
                && Math.abs(SectionPos.z(key) * 16 + 8 - player.getZ()) <= RANGE_BLOCKS;
    }

    /** A full, opaque block hides the faces of the blocks next to it. */
    private static boolean hides(int stateId) {
        return Block.stateById(stateId).isSolidRender();
    }

    /** A block that gives off light, as lava and magma do, is already drawn glowing by the game. */
    private static boolean shines(int stateId) {
        return Block.stateById(stateId).getLightEmission() > 0;
    }
}
