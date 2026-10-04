package io.github.osir933.anchor.neoforge;

import com.mojang.logging.LogUtils;
import io.github.osir933.anchor.core.host.BlockAppearance;
import io.github.osir933.anchor.core.host.HostedWorld;
import io.github.osir933.anchor.core.host.ImportPlanner;
import io.github.osir933.anchor.core.host.Pacer;
import io.github.osir933.anchor.core.host.PhaseChange;
import io.github.osir933.anchor.core.host.SectionSnapshot;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.thermal.AtmosphereModel;
import io.github.osir933.anchor.core.physics.thermal.Sky;
import io.github.osir933.anchor.core.physics.thermal.ThermalActivity;
import io.github.osir933.anchor.core.physics.thermal.ThermalRefinement;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Heat in one Minecraft level. It keeps the sections around players in a {@link HostedWorld}, follows every
 * block change in them, runs a simulation step every few game ticks and shows melting, freezing and boiling
 * by changing blocks.
 *
 * <p>Each game tick it takes in the blocks that changed since the last tick. Every
 * {@linkplain AnchorConfig#GAME_TICKS_PER_STEP few ticks} it brings in sections that players have come near
 * and lets go of those they have left, and compares one section with the level to catch changes nobody reported.
 * It steps the simulation when its {@link Pacer} says: every few ticks at normal speed, and as {@code /anchor time}
 * asks otherwise, paused, by hand, faster or slower, or sent ahead as fast as a budget of time per tick allows
 * (see {@link TimeCommands}); how it is paced is saved with the level as {@link LevelPace}. An error stops heat in
 * this level and is logged; the game carries on.
 *
 * <p>After each step the level's probes record the temperatures they measure, and their charts are drawn again
 * about once a second while it steps; see {@link LevelProbes}.
 *
 * <p>The state of simulated sections is saved with their chunks as {@link ChunkHeat}: when a section is let
 * go or its chunk unloads, when the level is saved, and every minute in between. A section brought in again
 * takes its saved state back.
 *
 * <p>In a level with a sun, such as the Overworld, each step takes the sun's place and the weather from the level (see
 * {@link Climate#sky}), and the simulation learns where the open sky begins in each column from the level's
 * heightmap of its highest blocks: when a section comes in, when a block in it changes, and again as each section
 * is compared with the level. The Nether, under its ceiling, and the End, under its black sky, have no sun or sky
 * in the simulation.
 */
final class LevelHeat {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The most block changes taken in per game tick; the rest wait for the next tick. */
    private static final int CHANGES_PER_TICK = 4096;

    /** The most blocks changed per game tick to show melting, freezing or boiling. */
    private static final int SHOWN_PER_TICK = 256;

    /** Work units the simulation may spend per step. */
    private static final long WORK_PER_STEP = 50_000_000L;

    /** Check conservation every this many steps. */
    private static final int AUDIT_INTERVAL = 20;

    /** Sections are let go once they are this many sections beyond the simulated radius. */
    private static final int MARGIN = 1;

    /** Changed sections are written into their chunks at least this often, in game ticks, so a crash loses little. */
    private static final int SAVE_INTERVAL_TICKS = 1200;

    /** Sending heat ahead by more than this many steps shows players a bar of how far it has come. */
    private static final long AHEAD_BAR_STEPS = 20;

    /** The bar of how far heat has gone ahead is brought up to date every this many game ticks. */
    private static final int AHEAD_BAR_TICKS = 5;

    private final ServerLevel level;
    private final BlockMapper mapper;
    private final HostedWorld hosted;
    private final ImportPlanner planner;
    private final int ticksPerStep;
    private final int sectionsPerStep;
    private final Pacer pacer;
    /** How the level is paced, as saved with it. */
    private final LevelPace pace;
    /**
     * Whether the level has a sun and a sky open above it: skylight, no ceiling and the Overworld's kind of sky with
     * its sun and moon, unlike the Nether and the End.
     */
    private final boolean hasSun;
    private final TreeSet<Long> changed = new TreeSet<>();
    private final TreeMap<Long, Integer> pinned = new TreeMap<>();
    private final ArrayDeque<PhaseChange> toShow = new ArrayDeque<>();
    private final TreeMap<String, Optional<BlockState>> replacements = new TreeMap<>();
    /** The version of each simulated section when it was last written into its chunk or brought in. */
    private final TreeMap<Long, Long> savedVersions = new TreeMap<>();
    private long shown;
    private long restoredBlocks;
    private int restoredSections;
    private int ticksUntilFollow = 1;
    private int ticksUntilSave = SAVE_INTERVAL_TICKS;
    private long lastVerified = Long.MIN_VALUE;
    private double lastStepMillis;
    private double averageStepMillis;
    private String failure;
    /** A sky held in place of the level's own, or {@code null}. */
    private Sky heldSky;
    /** The bar showing players how far heat sent ahead has come, or {@code null}. */
    private ServerBossEvent aheadBar;
    private long aheadSteps;
    private long aheadTicks;
    private long aheadStartNanos;

    /**
     * Starts heat in a level, with the current settings.
     *
     * @param level the level
     */
    LevelHeat(ServerLevel level) {
        this.level = level;
        this.ticksPerStep = AnchorConfig.get(AnchorConfig.GAME_TICKS_PER_STEP);
        this.sectionsPerStep = AnchorConfig.get(AnchorConfig.SECTIONS_LOADED_PER_STEP);
        double stepSeconds = AnchorConfig.get(AnchorConfig.SECONDS_PER_GAME_TICK) * ticksPerStep;
        double calmRate = AnchorConfig.get(AnchorConfig.CALM_KELVIN_PER_HOUR) / 3600.0;
        MaterialRegistry materials = MaterialRegistry.withLibrary();
        this.mapper = new BlockMapper(materials);
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(level.getSeed()), materials);
        ThermalRefinement.Settings refinement = ThermalRefinement.Settings.DEFAULT
                .withMaxLevel(AnchorConfig.get(AnchorConfig.REFINEMENT_LEVELS))
                .withMaxLeaves(AnchorConfig.get(AnchorConfig.MAX_REFINED_CELLS));
        HostedWorld.Settings settings = new HostedWorld.Settings(stepSeconds, WORK_PER_STEP,
                AtmosphereModel.DEFAULT_RELAXATION_SECONDS, Climate.kelvin(0.8, 64), calmRate,
                ThermalActivity.DEFAULT_CALM_STEPS, AUDIT_INTERVAL, refinement);
        this.hosted = new HostedWorld(world, mapper::forStateId, settings);
        this.planner = new ImportPlanner(AnchorConfig.get(AnchorConfig.RADIUS),
                AnchorConfig.get(AnchorConfig.VERTICAL_RADIUS), MARGIN);
        DimensionType type = level.dimensionType();
        this.hasSun = type.hasSkyLight() && !type.hasCeiling() && type.skybox() == DimensionType.Skybox.OVERWORLD;
        this.pacer = new Pacer(ticksPerStep, AnchorConfig.get(AnchorConfig.STEP_BUDGET_MILLIS));
        this.pace = level.getData(AnchorAttachments.PACE);
        pacer.setSpeed(pace.speed());
        if (pace.paused()) {
            pacer.pause();
        }
    }

    /**
     * Runs one game tick.
     *
     * @param running whether the level's time is running; while it is frozen, changes are taken in but
     *     nothing is simulated
     */
    void tick(boolean running) {
        if (failure != null) {
            return;
        }
        try {
            takeInChanges();
            if (--ticksUntilSave <= 0) {
                ticksUntilSave = SAVE_INTERVAL_TICKS;
                saveAll();
            }
            if (!running) {
                return;
            }
            show();
            // Sections follow players at the usual pace even while heat is paused, so what they walk to can be read.
            if (--ticksUntilFollow <= 0) {
                ticksUntilFollow = ticksPerStep;
                followPlayers();
                verifyNext();
            }
            step();
            drawCharts(pacer.stepsThisTick() > 0);
            followAhead();
        } catch (RuntimeException e) {
            stop(e);
        }
    }

    /**
     * Takes the steps the pacer asks for in this tick, under one sky: one every few ticks at normal speed, none
     * while paused, and as many as fit into the budget while running faster or sent ahead.
     */
    private void step() {
        pacer.beginTick();
        long start = System.nanoTime();
        double spent = 0.0;
        while (pacer.wantsStep(spent)) {
            if (pacer.stepsThisTick() == 0) {
                followSky();
            }
            long stepStart = System.nanoTime();
            HostedWorld.TickResult result = hosted.tick();
            record();
            toShow.addAll(result.phaseChanges());
            lastStepMillis = (System.nanoTime() - stepStart) / 1e6;
            averageStepMillis = averageStepMillis == 0.0 ? lastStepMillis
                    : 0.95 * averageStepMillis + 0.05 * lastStepMillis;
            pacer.stepped(lastStepMillis);
            spent = (System.nanoTime() - start) / 1e6;
        }
        pacer.endTick();
        if (pacer.stepsThisTick() > 0) {
            show();
        }
    }

    /**
     * Notes that a block changed, to be taken in on the next tick.
     *
     * @param pos the block
     */
    void blockChanged(BlockPos pos) {
        if (failure == null && hosted.isImported(sectionKey(pos))) {
            changed.add(pos.asLong());
        }
    }

    /**
     * Writes the sections of a chunk the level is unloading into the chunk, before it is saved, and lets go of
     * them.
     *
     * @param chunk the chunk
     */
    void chunkUnloaded(LevelChunk chunk) {
        if (failure != null) {
            return;
        }
        try {
            ChunkPos pos = chunk.getPos();
            List<Long> gone = new ArrayList<>();
            for (long key : hosted.importedSections()) {
                if (SectionPos.x(key) == pos.x() && SectionPos.z(key) == pos.z()) {
                    gone.add(key);
                }
            }
            for (long key : gone) {
                save(chunk, key);
                forget(key);
            }
        } catch (RuntimeException e) {
            stop(e);
        }
    }

    /** Writes every simulated section that changed since it was last written into its chunk, for the next save. */
    void save() {
        if (failure != null) {
            return;
        }
        try {
            saveAll();
        } catch (RuntimeException e) {
            stop(e);
        }
    }

    /**
     * Writes the section around a block into its chunk, lets it go and brings it in again from what was
     * written, as unloading and loading its chunk would.
     *
     * @param pos the block
     * @return {@code true} if the section was simulated and came back
     */
    boolean reloadSection(BlockPos pos) {
        long key = sectionKey(pos);
        if (failure != null || !hosted.isImported(key)) {
            return false;
        }
        try {
            LevelChunk chunk = chunk(key);
            if (chunk == null) {
                return false;
            }
            save(chunk, key);
            forget(key);
            LevelChunkSection section = section(chunk, key);
            if (section == null) {
                return false;
            }
            bringIn(chunk, key, section);
            return true;
        } catch (RuntimeException e) {
            stop(e);
            return false;
        }
    }

    /**
     * Keeps the area around a block simulated even with no player near, as if a player stood there. Each call
     * needs a matching {@link #release}.
     *
     * @param pos the block
     */
    void keepSimulated(BlockPos pos) {
        pinned.merge(sectionKey(pos), 1, Integer::sum);
    }

    /**
     * Undoes one {@link #keepSimulated} call for a block.
     *
     * @param pos the block
     */
    void release(BlockPos pos) {
        pinned.computeIfPresent(sectionKey(pos), (key, count) -> count > 1 ? count - 1 : null);
    }

    /**
     * Sets the temperature of a block and keeps its matter, as an experiment's starting condition.
     *
     * @param pos the block
     * @param kelvin the temperature in kelvin
     * @return {@code true} if it was set; {@code false} if the block is not simulated, holds nothing or heat
     *     has stopped
     */
    boolean setTemperature(BlockPos pos, double kelvin) {
        if (failure != null) {
            return false;
        }
        try {
            takeInNow(pos);
            return hosted.setTemperature(grid(pos), kelvin);
        } catch (RuntimeException e) {
            stop(e);
            return false;
        }
    }

    /**
     * Holds the sky over the simulation still, in place of the level's own sun and weather, as for an experiment
     * that needs the noon sun, or lets it follow the level again. A level without a sun, or with the sun and sky
     * switched off, stays without one.
     *
     * @param sky the sky to hold, or {@code null} to follow the level's own
     */
    void holdSky(Sky sky) {
        heldSky = sky;
    }

    /**
     * Returns how many blocks have been changed to show melting, freezing or boiling.
     *
     * @return the count
     */
    long shownPhaseChanges() {
        return shown;
    }

    /** Forgets how blocks were described, for when data packs are reloaded. */
    void appearancesChanged() {
        mapper.reloaded();
        hosted.appearancesChanged();
        replacements.clear();
    }

    /**
     * Returns a block's temperature as the simulation has it, without taking in changes to the block first. It
     * is quick enough to read many blocks a tick, as the thermal camera does.
     *
     * @param pos the block
     * @return the temperature in kelvin, or {@link Double#NaN} if the block is empty or not simulated, or heat
     *     has stopped
     */
    double temperature(BlockPos pos) {
        return failure == null ? hosted.temperature(grid(pos)) : Double.NaN;
    }

    /**
     * Returns the temperature at a point of a block as the simulation has it: the block's or, where the block
     * is refined into smaller cells, that of its cell there, so a point on a face reads that face. Like
     * {@link #temperature}, it takes in no changes first.
     *
     * @param pos the block
     * @param at the point; one outside the block, such as a point on the outline of a block larger than a
     *     cube, is moved to the block's nearest side
     * @return the temperature in kelvin, or {@link Double#NaN} if the matter there is empty or not simulated, or
     *     heat has stopped
     */
    double temperatureAt(BlockPos pos, Vec3 at) {
        return failure == null ? hosted.temperatureAt(within(at.x(), pos.getX()), within(at.y(), pos.getY()),
                within(at.z(), pos.getZ())) : Double.NaN;
    }

    /** Moves a coordinate into the block that starts at {@code start} along its axis. */
    private static double within(double coordinate, int start) {
        return Math.max(start, Math.min(Math.nextDown(start + 1.0), coordinate));
    }

    /**
     * Returns what the simulation knows about a block.
     *
     * @param pos the block
     * @return the inspection, or empty if the block is not simulated or heat has stopped
     */
    Optional<HostedWorld.Inspection> inspect(BlockPos pos) {
        if (failure != null) {
            return Optional.empty();
        }
        try {
            takeInNow(pos);
            return hosted.inspect(grid(pos));
        } catch (RuntimeException e) {
            stop(e);
            return Optional.empty();
        }
    }

    /**
     * Returns how the simulation is doing.
     *
     * @return the report
     */
    HeatReport report() {
        return new HeatReport(hosted.status(), hosted.settings().tickSeconds(), lastStepMillis, averageStepMillis,
                shown, restoredBlocks, restoredSections, failure, pacer.status(), ticksPerStep);
    }

    /**
     * Returns how the simulation is paced.
     *
     * @return where its pacer stands
     */
    Pacer.Status pace() {
        return pacer.status();
    }

    /**
     * Returns how long a step covers.
     *
     * @return simulated seconds per step
     */
    double stepSeconds() {
        return hosted.settings().tickSeconds();
    }

    /**
     * Tells whether the simulation follows a sun and a sky here.
     *
     * @return {@code true} if the level has a sun and the sun and sky are switched on
     */
    boolean hasSky() {
        return hasSun && AnchorConfig.get(AnchorConfig.SUN_AND_SKY);
    }

    /**
     * Returns how many game ticks apart steps come at normal speed.
     *
     * @return the ticks per step
     */
    int ticksPerStep() {
        return ticksPerStep;
    }

    /** Pauses heat in this level, dropping any steps still waiting, and remembers it with the level. */
    void pause() {
        pacer.pause();
        pace.set(true, pacer.speed());
        hideAhead(false);
    }

    /** Lets heat in this level run at its speed again, and remembers it with the level. */
    void resume() {
        pacer.resume();
        pace.set(false, pacer.speed());
    }

    /**
     * Sets how fast heat runs in this level, and remembers it with the level.
     *
     * @param hundredths the speed in hundredths of normal, from {@link Pacer#MIN_SPEED} to {@link Pacer#MAX_SPEED}
     */
    void setSpeed(int hundredths) {
        pacer.setSpeed(hundredths);
        pace.set(pacer.paused(), hundredths);
    }

    /**
     * Asks for steps to be taken as fast as the budget allows, paused or not, adding to any still waiting.
     *
     * @param steps how many
     * @throws IllegalArgumentException if that would leave more than {@link Pacer#MAX_REQUESTED} waiting
     */
    void request(long steps) {
        pacer.request(steps);
    }

    /**
     * Drops the steps still waiting to be taken.
     *
     * @return how many were dropped
     */
    long cancel() {
        long dropped = pacer.cancel();
        hideAhead(false);
        return dropped;
    }

    /** Lets go of what the level shows players, for when the level is unloaded. */
    void close() {
        hideAhead(false);
    }

    /**
     * Takes in a block now instead of at the next tick, so that what is read or set is the block that is there.
     * A block placed this tick would otherwise still be the old one to the simulation.
     */
    private void takeInNow(BlockPos pos) {
        changed.remove(pos.asLong());
        if (hosted.isImported(sectionKey(pos)) && level.isLoaded(pos)) {
            hosted.reconcile(grid(pos), Block.getId(level.getBlockState(pos)), Double.NaN);
            followSkyHeight(pos);
        }
    }

    /** Takes in the blocks that changed since the last tick. */
    private void takeInChanges() {
        for (int n = 0; n < CHANGES_PER_TICK && !changed.isEmpty(); n++) {
            BlockPos pos = BlockPos.of(changed.pollFirst());
            if (level.isLoaded(pos)) {
                hosted.reconcile(grid(pos), Block.getId(level.getBlockState(pos)), Double.NaN);
                followSkyHeight(pos);
            }
        }
    }

    /**
     * Gives each of the level's probes a reading of the step just simulated. A probe whose block is not simulated
     * records a missing reading.
     */
    private void record() {
        LevelProbes probes = level.getData(AnchorAttachments.PROBES);
        probes.set().record(hosted.settings().tickSeconds(), p -> hosted.temperatureAt(p.x(), p.y(), p.z()));
    }

    /** Draws the level's charts again when they are due. */
    private void drawCharts(boolean stepped) {
        LevelProbes probes = level.getData(AnchorAttachments.PROBES);
        if (probes.chartsDue(stepped, ProbeCharts.DRAW_EVERY_TICKS) && !probes.charts().isEmpty()) {
            ProbeCharts.drawAll(level, probes);
        }
    }

    /**
     * Shows the players in the level how far heat sent ahead has come, on a bar like a boss's, and tells them when it
     * arrives. A few steps, or steps all taken in the tick they were asked for, show no bar.
     */
    private void followAhead() {
        long waiting = pacer.requested();
        if (waiting == 0) {
            hideAhead(true);
            return;
        }
        long total = pacer.requestTotal();
        if (aheadBar == null) {
            if (total <= AHEAD_BAR_STEPS) {
                return;
            }
            UUID id = UUID.nameUUIDFromBytes(("anchor:ahead:" + level.dimension().identifier())
                    .getBytes(StandardCharsets.UTF_8));
            aheadBar = new ServerBossEvent(id, Component.literal("Heat going ahead"), BossEvent.BossBarColor.YELLOW,
                    BossEvent.BossBarOverlay.PROGRESS);
            aheadSteps = 0;
            aheadTicks = 0;
            aheadStartNanos = System.nanoTime();
        }
        aheadSteps = Math.max(aheadSteps, total);
        if (aheadTicks++ % AHEAD_BAR_TICKS != 0) {
            return;
        }
        double stepSeconds = hosted.settings().tickSeconds();
        aheadBar.setName(Component.literal("Heat going ahead: " + HeatText.duration((total - waiting) * stepSeconds)
                + " of " + HeatText.duration(total * stepSeconds)));
        aheadBar.setProgress((float) ((double) (total - waiting) / total));
        List<ServerPlayer> players = level.players();
        for (ServerPlayer player : players) {
            aheadBar.addPlayer(player);
        }
        for (ServerPlayer player : new ArrayList<>(aheadBar.getPlayers())) {
            if (!players.contains(player)) {
                aheadBar.removePlayer(player);
            }
        }
    }

    /** Takes the bar of how far heat has gone ahead away, telling its players if heat arrived. */
    private void hideAhead(boolean arrived) {
        if (aheadBar == null) {
            return;
        }
        if (arrived) {
            Component message = Component.literal("Heat in " + level.dimension().identifier() + " went "
                    + HeatText.duration(aheadSteps * hosted.settings().tickSeconds()) + " ahead in "
                    + HeatText.duration((System.nanoTime() - aheadStartNanos) / 1e9) + ".");
            for (ServerPlayer player : aheadBar.getPlayers()) {
                player.sendSystemMessage(message);
            }
        }
        aheadBar.removeAllPlayers();
        aheadBar = null;
    }

    /**
     * Sets the sky for the next step from the level's sun and weather, or none if the level has no sun. Minecraft
     * lets the sun's angle differ from place to place, though its own biomes keep it the same everywhere; the one
     * sky over the simulation takes it at the first simulated section.
     */
    private void followSky() {
        SortedSet<Long> sections = hosted.importedSections();
        if (!hasSun || !AnchorConfig.get(AnchorConfig.SUN_AND_SKY)) {
            hosted.setSky(null);
        } else if (heldSky != null) {
            hosted.setSky(heldSky);
        } else if (!sections.isEmpty()) {
            BlockPos at = centre(sections.first());
            hosted.setSky(Climate.sky(level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, at),
                    level.getRainLevel(1.0f), level.getThunderLevel(1.0f)));
        }
    }

    /** Tells the simulation where the open sky begins in each column of a chunk. */
    private void followSkyHeights(LevelChunk chunk) {
        if (!hasSun) {
            return;
        }
        int baseX = chunk.getPos().x() << 4;
        int baseZ = chunk.getPos().z() << 4;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                hosted.setSkyHeight(baseX + x, baseZ + z, firstOpenY(chunk, x, z));
            }
        }
    }

    /** Tells the simulation where the open sky begins in the column of a block that changed. */
    private void followSkyHeight(BlockPos pos) {
        if (hasSun) {
            hosted.setSkyHeight(pos.getX(), pos.getZ(), firstOpenY(level.getChunkAt(pos), pos.getX(), pos.getZ()));
        }
    }

    /**
     * Returns the lowest height open to the sky in a column of a chunk: just above its highest block that is not
     * air. Whether the blocks below let light through, as glass, water and flowers do, is the simulation's to judge.
     */
    private static int firstOpenY(LevelChunk chunk, int x, int z) {
        return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) + 1;
    }

    /** Brings in sections near players and lets go of those no player is near. */
    private void followPlayers() {
        TreeSet<Long> anchors = new TreeSet<>(pinned.keySet());
        for (ServerPlayer player : level.players()) {
            anchors.add(SectionPos.pack(player.getBlockX() >> 4, player.getBlockY() >> 4, player.getBlockZ() >> 4));
        }
        ImportPlanner.Plan plan = planner.plan(anchors, hosted.importedSections(), key -> section(key) != null,
                sectionsPerStep);
        for (long key : plan.removals()) {
            LevelChunk chunk = chunk(key);
            if (chunk != null) {
                save(chunk, key);
            }
            forget(key);
        }
        for (long key : plan.imports()) {
            LevelChunk chunk = chunk(key);
            LevelChunkSection section = chunk == null ? null : section(chunk, key);
            if (section != null) {
                bringIn(chunk, key, section);
            }
        }
    }

    /** Brings a section in with the state saved in its chunk, if any. */
    private void bringIn(LevelChunk chunk, long key, LevelChunkSection section) {
        ChunkHeat saved = chunk.getExistingDataOrNull(AnchorAttachments.CHUNK_HEAT);
        SectionSnapshot snapshot = saved == null ? null : saved.section(SectionPos.y(key));
        int restored = hosted.importSection(key, ids(section), climate(key), humidity(key), snapshot);
        followSkyHeights(chunk);
        if (restored > 0) {
            restoredBlocks += restored;
            restoredSections++;
        }
        // A section that came back from its chunk is written again at the next save, which drops any saved
        // blocks that no longer fit the blocks there.
        savedVersions.put(key, snapshot == null ? hosted.sectionVersion(key) : Long.MIN_VALUE);
    }

    /** Writes every simulated section that changed since it was last written into its chunk. */
    private void saveAll() {
        for (long key : hosted.importedSections()) {
            LevelChunk chunk = chunk(key);
            if (chunk != null) {
                save(chunk, key);
            }
        }
    }

    /**
     * Writes a simulated section into its chunk if it changed since it was last written, and marks the chunk
     * for saving if that changed what the chunk holds.
     */
    private void save(LevelChunk chunk, long key) {
        long version = hosted.sectionVersion(key);
        Long saved = savedVersions.get(key);
        if (saved != null && saved == version) {
            return;
        }
        Optional<SectionSnapshot> snapshot = hosted.snapshot(key);
        ChunkHeat heat = snapshot.isPresent() ? chunk.getData(AnchorAttachments.CHUNK_HEAT)
                : chunk.getExistingDataOrNull(AnchorAttachments.CHUNK_HEAT);
        if (heat != null && heat.put(SectionPos.y(key), snapshot.orElse(null))) {
            chunk.markUnsaved();
        }
        savedVersions.put(key, version);
    }

    /** Lets go of a section without saving it. */
    private void forget(long key) {
        hosted.removeSection(key);
        savedVersions.remove(key);
    }

    /** Compares the next imported section with the level, taking in any change nobody reported. */
    private void verifyNext() {
        SortedSet<Long> imported = hosted.importedSections();
        if (imported.isEmpty()) {
            return;
        }
        SortedSet<Long> after = lastVerified == Long.MAX_VALUE ? Collections.emptySortedSet()
                : imported.tailSet(lastVerified + 1);
        long key = after.isEmpty() ? imported.first() : after.first();
        lastVerified = key;
        LevelChunk chunk = chunk(key);
        LevelChunkSection section = chunk == null ? null : section(chunk, key);
        if (section == null) {
            forget(key);
        } else {
            hosted.verifySection(key, ids(section));
            followSkyHeights(chunk);
        }
    }

    /** Shows waiting phase changes by changing blocks, a limited number per tick. */
    private void show() {
        if (!AnchorConfig.get(AnchorConfig.SHOW_PHASE_CHANGES)) {
            toShow.clear();
            return;
        }
        for (int n = 0; n < SHOWN_PER_TICK && !toShow.isEmpty(); n++) {
            show(toShow.poll());
        }
    }

    private void show(PhaseChange change) {
        BlockPos pos = new BlockPos(change.pos().x(), change.pos().y(), change.pos().z());
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockAppearance look = mapper.appearance(level.getBlockState(pos));
        Optional<HostedWorld.Inspection> now = hosted.inspect(change.pos());
        if (look.phase() != change.shown() || now.isEmpty() || now.get().phase() != change.now()) {
            return; // the block or its matter changed again since the step
        }
        Optional<BlockState> replacement = replacement(change.hostBlock());
        if (replacement.isEmpty()) {
            return;
        }
        level.setBlock(pos, replacement.get(), Block.UPDATE_ALL);
        hosted.reconcile(change.pos(), Block.getId(level.getBlockState(pos)), change.temperatureK());
        followSkyHeight(pos);
        shown++;
        if (change.now() == Phase.GAS) {
            level.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3,
                    0.3, 0.3, 0.02);
        }
    }

    private Optional<BlockState> replacement(String id) {
        return replacements.computeIfAbsent(id, key -> {
            Identifier parsed = Identifier.tryParse(key);
            Optional<Block> block = parsed == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(parsed);
            if (block.isEmpty()) {
                LOGGER.warn("Anchor: cannot show a phase change as {}, which is not a block", key);
            }
            return block.map(Block::defaultBlockState);
        });
    }

    /** Returns the loaded chunk a section is in, or {@code null} if it is not loaded. */
    private LevelChunk chunk(long key) {
        return level.getChunkSource().getChunkNow(SectionPos.x(key), SectionPos.z(key));
    }

    /** Returns a loaded section of the level, or {@code null} if it is not loaded or outside the world. */
    private LevelChunkSection section(long key) {
        LevelChunk chunk = chunk(key);
        return chunk == null ? null : section(chunk, key);
    }

    /** Returns a section of a chunk, or {@code null} if it is outside the world. */
    private LevelChunkSection section(LevelChunk chunk, long key) {
        int index = level.getSectionIndexFromSectionY(SectionPos.y(key));
        LevelChunkSection[] sections = chunk.getSections();
        return index >= 0 && index < sections.length ? sections[index] : null;
    }

    /** Reads the block state ids of a section in the order the simulation numbers its blocks. */
    private static IntUnaryOperator ids(LevelChunkSection section) {
        return i -> Block.getId(section.getBlockState(i & 15, (i >> 8) & 15, (i >> 4) & 15));
    }

    /** Returns the temperature of a section's surroundings, from the biome at its centre. */
    private double climate(long key) {
        BlockPos centre = centre(key);
        return Climate.kelvin(level.getBiome(centre).value().getBaseTemperature(), centre.getY());
    }

    /** Returns the relative humidity of a section's surroundings, from the biome at its centre. */
    private double humidity(long key) {
        return Climate.relativeHumidity(level.getBiome(centre(key)).value().getModifiedClimateSettings().downfall());
    }

    private static BlockPos centre(long key) {
        return new BlockPos((SectionPos.x(key) << 4) + 8, (SectionPos.y(key) << 4) + 8, (SectionPos.z(key) << 4) + 8);
    }

    private void stop(RuntimeException e) {
        failure = e.toString();
        changed.clear();
        toShow.clear();
        LOGGER.error("Anchor: heat in {} stopped after an error; it starts again when the world is next loaded",
                level.dimension().identifier(), e);
        hideAhead(false);
    }

    private static GridPos grid(BlockPos pos) {
        return new GridPos(pos.getX(), pos.getY(), pos.getZ());
    }

    private static long sectionKey(BlockPos pos) {
        return SectionPos.pack(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }
}
