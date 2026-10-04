package io.github.osir933.anchor.neoforge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import io.github.osir933.anchor.core.host.GlowingBlock;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/**
 * Draws hot blocks glowing, as the server describes them: on each face a block shows, a thin sheet of light just in
 * front of it, in the colour and brightness of a black body at the face's temperature, spot by spot. The light adds
 * to whatever is drawn behind it, as the light a hot surface gives off adds to the light it reflects, so a block
 * glows dull red in the dark yet hardly shows in daylight until it is hot enough to outshine the day.
 *
 * <p>Blocks that give off light of their own, such as lava, are left as the game draws them, and so are faces that a
 * full block next to them hides.
 */
final class GlowClient {

    /** How far in front of a face its glow is drawn, in blocks, so that the face does not hide it. */
    private static final double LIFT = 0.002;

    private static final Direction[] FACES = Direction.values();
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The glowing blocks of each section the server has told about, by the core's packed section position. */
    private static final Map<Long, List<Glowing>> SECTIONS = new HashMap<>();
    /** The level the glowing blocks are in; a client that changes level forgets them. */
    private static ClientLevel shownIn;

    /** A glowing block, ready to draw. */
    private record Glowing(BlockPos pos, int faces, int uniform, int[] colours) {
    }

    private GlowClient() {
    }

    /**
     * Registers the listeners.
     *
     * @param bus NeoForge's game event bus
     */
    static void register(IEventBus bus) {
        bus.addListener(GlowClient::submit);
        bus.addListener(GlowClient::loggedOut);
    }

    /**
     * Takes in what the server says glows in a section.
     *
     * @param payload the message
     * @param context where it came from
     */
    static void receive(GlowPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> take(payload));
    }

    /**
     * Returns how many glowing blocks the client knows about.
     *
     * @return the number of blocks
     */
    static int glowingBlocks() {
        int count = 0;
        for (List<Glowing> blocks : SECTIONS.values()) {
            count += blocks.size();
        }
        return count;
    }

    private static void take(GlowPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (level != shownIn) {
            SECTIONS.clear();
            shownIn = level;
        }
        List<GlowData.Block> blocks;
        try {
            blocks = GlowData.decode(payload.data());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Anchor got damaged glow data for section {}: {}", SectionPos.toString(payload.section()),
                    e.getMessage());
            return;
        }
        if (blocks.isEmpty()) {
            SECTIONS.remove(payload.section());
            return;
        }
        List<Glowing> glowing = new ArrayList<>(blocks.size());
        for (GlowData.Block block : blocks) {
            int[] colours = block.colours();
            int uniform = 0;
            for (Direction face : FACES) {
                if (GlowData.Block.uniform(colours, face)) {
                    uniform |= 1 << face.ordinal();
                }
            }
            GridPos pos = GridPos.of(payload.section(), block.index());
            glowing.add(new Glowing(new BlockPos(pos.x(), pos.y(), pos.z()), block.faces(), uniform, colours));
        }
        SECTIONS.put(payload.section(), glowing);
    }

    private static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SECTIONS.clear();
        shownIn = null;
    }

    private static void submit(SubmitCustomGeometryEvent event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != shownIn) {
            SECTIONS.clear();
            shownIn = level;
        }
        if (level == null || SECTIONS.isEmpty()) {
            return;
        }
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        Quads quads = new Quads();
        for (List<Glowing> blocks : SECTIONS.values()) {
            for (Glowing block : blocks) {
                add(level, block, camera, quads);
            }
        }
        if (quads.count > 0) {
            event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.lightning(),
                    quads::emit);
        }
    }

    /** Adds the glowing faces of a block, along the boxes of its shape, so that slabs and stairs glow where they are. */
    private static void add(ClientLevel level, Glowing block, Vec3 camera, Quads quads) {
        BlockState state = level.getBlockState(block.pos());
        if (state.isAir() || state.getLightEmission() > 0) {
            return;
        }
        VoxelShape shape = state.getShape(level, block.pos());
        if (shape.isEmpty()) {
            return;
        }
        List<AABB> boxes = shape.toAabbs();
        for (Direction face : FACES) {
            if ((block.faces() & 1 << face.ordinal()) == 0) {
                continue;
            }
            boolean covered = level.getBlockState(block.pos().offset(face.dx(), face.dy(), face.dz())).isSolidRender();
            for (AABB box : boxes) {
                double[] min = {box.minX, box.minY, box.minZ};
                double[] max = {box.maxX, box.maxY, box.maxZ};
                int axis = face.axis();
                boolean outer = face.isPositive() ? max[axis] >= 1.0 - 1e-6 : min[axis] <= 1e-6;
                if (!(outer && covered)) {
                    addFace(block, face, min, max, camera, quads);
                }
            }
        }
    }

    /** Adds one face of one box, whole if it glows evenly, else spot by spot. */
    private static void addFace(Glowing block, Direction face, double[] min, double[] max, Vec3 camera,
            Quads quads) {
        int u = GlowingBlock.uAxis(face);
        int v = GlowingBlock.vAxis(face);
        double plane = face.isPositive() ? max[face.axis()] + LIFT : min[face.axis()] - LIFT;
        if ((block.uniform() & 1 << face.ordinal()) != 0) {
            int colour = block.colours()[GlowingBlock.sample(face, 0, 0)];
            if (colour != 0) {
                quads.add(block.pos(), face, plane, min[u], max[u], min[v], max[v], colour, camera);
            }
            return;
        }
        for (int sv = 0; sv < GlowingBlock.GRID; sv++) {
            double v0 = Math.max(min[v], (double) sv / GlowingBlock.GRID);
            double v1 = Math.min(max[v], (sv + 1.0) / GlowingBlock.GRID);
            if (v1 <= v0) {
                continue;
            }
            for (int su = 0; su < GlowingBlock.GRID; su++) {
                double u0 = Math.max(min[u], (double) su / GlowingBlock.GRID);
                double u1 = Math.min(max[u], (su + 1.0) / GlowingBlock.GRID);
                int colour = block.colours()[GlowingBlock.sample(face, su, sv)];
                if (u1 > u0 && colour != 0) {
                    quads.add(block.pos(), face, plane, u0, u1, v0, v1, colour, camera);
                }
            }
        }
    }

    /** Quads ready to send to the GPU, in camera-relative coordinates so that they stay precise far from spawn. */
    private static final class Quads {
        private float[] corners = new float[12 * 64];
        private int[] colours = new int[64];
        private int count;

        /** Adds a rectangle on a face's plane, wound so that it faces out of the block. */
        void add(BlockPos pos, Direction face, double plane, double u0, double u1, double v0, double v1, int colour,
                Vec3 camera) {
            if (count == colours.length) {
                colours = Arrays.copyOf(colours, 2 * count);
                corners = Arrays.copyOf(corners, 24 * count);
            }
            int u = GlowingBlock.uAxis(face);
            int v = GlowingBlock.vAxis(face);
            // Counter-clockwise seen from outside: u then v, unless u, v and the outward normal are left-handed.
            boolean rightHanded = (v - u + 3) % 3 == 1;
            boolean flip = rightHanded != face.isPositive();
            double[][] uv = flip
                    ? new double[][] {{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}}
                    : new double[][] {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
            double[] p = new double[3];
            for (int c = 0; c < 4; c++) {
                p[face.axis()] = plane;
                p[u] = uv[c][0];
                p[v] = uv[c][1];
                int i = 12 * count + 3 * c;
                corners[i] = (float) (pos.getX() + p[0] - camera.x);
                corners[i + 1] = (float) (pos.getY() + p[1] - camera.y);
                corners[i + 2] = (float) (pos.getZ() + p[2] - camera.z);
            }
            colours[count++] = colour;
        }

        /** Sends the quads' corners, each with its glow's colour and its level as alpha. */
        void emit(PoseStack.Pose pose, VertexConsumer consumer) {
            for (int q = 0; q < count; q++) {
                for (int c = 0; c < 4; c++) {
                    int i = 12 * q + 3 * c;
                    consumer.addVertex(pose, corners[i], corners[i + 1], corners[i + 2]).setColor(colours[q]);
                }
            }
        }
    }
}
