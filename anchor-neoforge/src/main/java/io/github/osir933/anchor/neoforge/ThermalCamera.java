package io.github.osir933.anchor.neoforge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * The thermal camera at work. While a player holds one, it takes a thermal image of what they look at twice a
 * second and shows it to them alone: coloured dots on the surfaces in view, or in the air that is warmer or colder
 * than the rest of it, and above the hotbar the temperature at the crosshair and the scale the colours follow.
 * The images are made on the server from what the simulation knows, so the client needs nothing but vanilla
 * particles.
 */
final class ThermalCamera {

    /** What the camera shows. */
    enum View {
        /** The surfaces in view, as a real thermal camera sees them: glass and water too, which stop heat rays. */
        SURFACES,
        /** The air in view that is warmer or colder than most of it, such as the plume above a fire. */
        AIR
    }

    /**
     * One dot of a thermal image.
     *
     * @param at where the dot is shown
     * @param kelvin the temperature it shows
     */
    record Dot(Vec3 at, double kelvin) {
    }

    /**
     * A thermal image.
     *
     * @param dots the dots to show
     * @param spotK the temperature of the surface at the centre of the view, or {@link Double#NaN} if nothing
     *     simulated is there
     * @param coldestK the coldest temperature in view, or {@link Double#NaN} if nothing in view is simulated
     * @param hottestK the hottest temperature in view, or {@link Double#NaN} if nothing in view is simulated
     */
    record Frame(List<Dot> dots, double spotK, double coldestK, double hottestK) {

        /**
         * Copies the dots.
         *
         * @param dots the dots to show
         * @param spotK the temperature at the centre of the view
         * @param coldestK the coldest temperature in view
         * @param hottestK the hottest temperature in view
         */
        Frame {
            dots = List.copyOf(dots);
        }
    }

    /** Game ticks between images. */
    static final int FRAME_TICKS = 10;

    /** How far the camera sees, in blocks. */
    static final double RANGE = 24.0;

    /** The rays across the image. */
    private static final int COLUMNS = 24;

    /** The rays down the image: with the columns, at most 336 dots about 2° apart. */
    private static final int ROWS = 14;

    /** Half the image's width as a tangent: the image spans 48° across. */
    private static final double HALF_WIDTH = Math.tan(Math.toRadians(24.0));

    /** Half the image's height as a tangent, keeping the rays as far apart down as across. */
    private static final double HALF_HEIGHT = HALF_WIDTH * ROWS / COLUMNS;

    /** How far off a surface its dot sits, in blocks, so that the block does not hide it. */
    private static final double SURFACE_OFFSET = 0.05;

    /** Surfaces nearer than this, in blocks, are the ones the camera is inside of, such as water; not shown. */
    private static final double NEAREST = 0.5;

    /** How far apart the air is read along each ray, in blocks. */
    private static final double AIR_STEP = 0.5;

    /** How far in front of the camera the air is first read, in blocks, past the player's own head. */
    private static final double AIR_START = 1.0;

    /** Air is shown when it differs from most of the air in view by at least this much, in kelvin... */
    private static final double AIR_CONTRAST_K = 0.3;

    /** ...and by at least this share of the span between the coldest and the hottest air in view. */
    private static final double AIR_CONTRAST_SHARE = 0.15;

    /** The most air dots one image shows; the air that differs most comes first. */
    private static final int MAX_AIR_DOTS = 400;

    /** Game ticks after a switch in which using the camera does nothing, as holding the use key repeats it. */
    private static final int SWITCH_TICKS = 8;

    /** Cells in the scale shown above the hotbar. */
    private static final int SCALE_CELLS = 12;

    /** Each player's camera settings, kept until they leave. */
    private static final Map<UUID, Settings> SETTINGS = new HashMap<>();

    /** What one player's camera shows, and when. */
    private static final class Settings {
        private final ThermalScale scale = new ThermalScale();
        private View view = View.SURFACES;
        private int ticksUntilImage = 1;
        private int lastSwitch = Integer.MIN_VALUE / 2;
    }

    /** The coldest and the hottest temperatures seen. */
    private static final class Extremes {
        private double coldest = Double.NaN;
        private double hottest = Double.NaN;

        private void add(double kelvin) {
            if (Double.isNaN(coldest) || kelvin < coldest) {
                coldest = kelvin;
            }
            if (Double.isNaN(hottest) || kelvin > hottest) {
                hottest = kelvin;
            }
        }
    }

    private ThermalCamera() {
    }

    /**
     * Registers the listeners.
     *
     * @param bus NeoForge's game event bus
     */
    static void register(IEventBus bus) {
        bus.addListener(ThermalCamera::onPlayerTick);
        bus.addListener(ThermalCamera::onLoggedOut);
        bus.addListener(ThermalCamera::onServerStopped);
    }

    /**
     * Uses a player's camera: switches between surfaces and air, or while sneaking locks or frees its scale.
     *
     * @param player the player
     */
    static void use(ServerPlayer player) {
        Settings settings = settings(player);
        if (player.tickCount - settings.lastSwitch < SWITCH_TICKS) {
            return;
        }
        settings.lastSwitch = player.tickCount;
        if (player.isSecondaryUseActive()) {
            settings.scale.lock(!settings.scale.locked());
        } else {
            settings.view = settings.view == View.SURFACES ? View.AIR : View.SURFACES;
            settings.scale.reset();
        }
        settings.ticksUntilImage = 1;
    }

    /**
     * Takes a thermal image.
     *
     * @param level the level
     * @param heat the level's heat
     * @param eye where the camera is
     * @param yRot which way it faces, as Minecraft's yaw in degrees
     * @param xRot how far down it looks, as Minecraft's pitch in degrees
     * @param view what it shows
     * @return the image
     */
    static Frame capture(ServerLevel level, LevelHeat heat, Vec3 eye, float yRot, float xRot, View view) {
        Vec3 forward = Vec3.directionFromRotation(xRot, yRot);
        double yaw = Math.toRadians(yRot);
        Vec3 right = new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw));
        Vec3 up = right.cross(forward);
        BlockHitResult centre = look(level, eye, forward);
        double spot = centre.getType() == HitResult.Type.BLOCK ? heat.temperature(centre.getBlockPos()) : Double.NaN;
        Extremes extremes = new Extremes();
        List<Dot> dots = new ArrayList<>();
        TreeMap<Long, Double> air = new TreeMap<>();
        for (int row = 0; row < ROWS; row++) {
            double v = (1.0 - (row + 0.5) * 2.0 / ROWS) * HALF_HEIGHT;
            for (int column = 0; column < COLUMNS; column++) {
                double u = ((column + 0.5) * 2.0 / COLUMNS - 1.0) * HALF_WIDTH;
                Vec3 direction = forward.add(right.scale(u)).add(up.scale(v)).normalize();
                BlockHitResult hit = look(level, eye, direction);
                boolean blocked = hit.getType() == HitResult.Type.BLOCK;
                double distance = blocked ? hit.getLocation().distanceTo(eye) : RANGE;
                if (view == View.AIR) {
                    for (double d = AIR_START; d < distance; d += AIR_STEP) {
                        BlockPos pos = BlockPos.containing(eye.add(direction.scale(d)));
                        air.computeIfAbsent(pos.asLong(),
                                key -> level.getBlockState(pos).isAir() ? heat.temperature(pos) : Double.NaN);
                    }
                } else if (blocked && distance >= NEAREST) {
                    double kelvin = heat.temperature(hit.getBlockPos());
                    if (!Double.isNaN(kelvin)) {
                        Vec3 off = hit.getDirection().getUnitVec3().scale(SURFACE_OFFSET);
                        dots.add(new Dot(hit.getLocation().add(off), kelvin));
                        extremes.add(kelvin);
                    }
                }
            }
        }
        if (view == View.AIR) {
            addAir(air, extremes, dots);
        }
        return new Frame(dots, spot, extremes.coldest, extremes.hottest);
    }

    /**
     * Returns the line shown above the hotbar: what the camera shows, the temperature at the crosshair and the
     * scale.
     *
     * @param frame the image
     * @param view what the camera shows
     * @param scale the scale the image is shown in
     * @return the line
     */
    static Component legend(Frame frame, View view, ThermalScale scale) {
        MutableComponent line = Component.empty();
        line.append((view == View.SURFACES
                ? Component.translatableWithFallback("message.anchor.thermal_camera.surfaces", "Surfaces")
                : Component.translatableWithFallback("message.anchor.thermal_camera.air", "Air"))
                .withStyle(ChatFormatting.GRAY));
        line.append("  ");
        if (!scale.ready()) {
            return line.append(Component.translatableWithFallback("message.anchor.thermal_camera.nothing",
                    "Nothing in view is simulated yet").withStyle(ChatFormatting.GRAY));
        }
        String spot = Double.isNaN(frame.spotK()) ? "-" : HeatText.celsius(frame.spotK());
        line.append(Component.translatableWithFallback("message.anchor.thermal_camera.spot", "Spot %s", spot)
                .withStyle(ChatFormatting.WHITE));
        line.append("   ");
        line.append(Component.literal(HeatText.celsius(scale.low()) + " ").withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < SCALE_CELLS; i++) {
            line.append(Component.literal("█").withColor(ThermalScale.color(i / (SCALE_CELLS - 1.0))));
        }
        line.append(Component.literal(" " + HeatText.celsius(scale.high())).withStyle(ChatFormatting.GRAY));
        if (scale.locked()) {
            line.append("  ").append(Component.translatableWithFallback("message.anchor.thermal_camera.locked",
                    "locked").withStyle(ChatFormatting.GOLD));
        }
        return line;
    }

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !player.isHolding(AnchorItems.THERMAL_CAMERA.get())) {
            return;
        }
        Settings settings = settings(player);
        if (--settings.ticksUntilImage > 0) {
            return;
        }
        settings.ticksUntilImage = FRAME_TICKS;
        ServerLevel level = player.level();
        Optional<LevelHeat> heat = HeatEvents.of(level);
        if (heat.isEmpty()) {
            player.sendOverlayMessage(Component.translatableWithFallback("message.anchor.thermometer.off",
                    "Temperatures are not simulated here"));
            return;
        }
        Frame frame = capture(level, heat.get(), player.getEyePosition(), player.getYRot(), player.getXRot(),
                settings.view);
        settings.scale.follow(frame.coldestK(), frame.hottestK());
        if (settings.scale.ready()) {
            // Each dot lasts until the next image replaces it and stays where it is put.
            for (Dot dot : frame.dots()) {
                Vec3 at = dot.at();
                TrailParticleOption particle = new TrailParticleOption(at,
                        0xFF000000 | settings.scale.colorOf(dot.kelvin()), FRAME_TICKS + 2);
                level.sendParticles(player, particle, true, true, at.x(), at.y(), at.z(), 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
        player.sendOverlayMessage(legend(frame, settings.view, settings.scale));
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SETTINGS.remove(event.getEntity().getUUID());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        SETTINGS.clear();
    }

    private static Settings settings(ServerPlayer player) {
        return SETTINGS.computeIfAbsent(player.getUUID(), id -> new Settings());
    }

    private static BlockHitResult look(ServerLevel level, Vec3 eye, Vec3 direction) {
        return level.clip(new ClipContext(eye, eye.add(direction.scale(RANGE)), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.ANY, CollisionContext.empty()));
    }

    /**
     * Adds the air that stands out from the rest of the air in view: warmer or colder than the typical reading by
     * enough to matter, the air that differs most first.
     */
    private static void addAir(TreeMap<Long, Double> air, Extremes extremes, List<Dot> dots) {
        double[] readings = air.values().stream().mapToDouble(Double::doubleValue).filter(k -> !Double.isNaN(k))
                .sorted().toArray();
        if (readings.length == 0) {
            return;
        }
        extremes.add(readings[0]);
        extremes.add(readings[readings.length - 1]);
        double typical = readings[readings.length / 2];
        double contrast = Math.max(AIR_CONTRAST_K, AIR_CONTRAST_SHARE * (readings[readings.length - 1] - readings[0]));
        List<Map.Entry<Long, Double>> standing = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : air.entrySet()) {
            if (Math.abs(entry.getValue() - typical) >= contrast) {
                standing.add(entry);
            }
        }
        standing.sort(Comparator.comparingDouble(entry -> -Math.abs(entry.getValue() - typical)));
        for (Map.Entry<Long, Double> entry : standing.subList(0, Math.min(MAX_AIR_DOTS, standing.size()))) {
            dots.add(new Dot(Vec3.atCenterOf(BlockPos.of(entry.getKey())), entry.getValue()));
        }
    }
}
