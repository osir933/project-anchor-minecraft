package io.github.osir933.anchor.neoforge;

import com.mojang.logging.LogUtils;
import io.github.osir933.anchor.core.host.BlockAppearance;
import io.github.osir933.anchor.core.host.HostedWorld;
import io.github.osir933.anchor.core.host.ImportPlanner;
import io.github.osir933.anchor.core.host.PhaseChange;
import io.github.osir933.anchor.core.host.SectionSnapshot;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.thermal.AtmosphereModel;
import io.github.osir933.anchor.core.physics.thermal.ThermalActivity;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntUnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;

/**
 * Heat in one Minecraft level. It keeps the sections around players in a {@link HostedWorld}, follows every
 * block change in them, runs a simulation step every few game ticks and shows melting, freezing and boiling
 * by changing blocks.
 *
 * <p>Each game tick it takes in the blocks that changed since the last tick. Every
 * {@linkplain AnchorConfig#GAME_TICKS_PER_STEP few ticks} it brings in sections that players have come near
 * and lets go of those they have left, compares one section with the level to catch changes nobody reported,
 * and steps the simulation. An error stops heat in this level and is logged; the game carries on.
 *
 * <p>The state of simulated sections is saved with their chunks as {@link ChunkHeat}: when a section is let
 * go or its chunk unloads, when the level is saved, and every minute in between. A section brought in again
 * takes its saved state back.
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

    private final ServerLevel level;
    private final BlockMapper mapper;
    private final HostedWorld hosted;
    private final ImportPlanner planner;
    private final int ticksPerStep;
    private final int sectionsPerStep;
    private final TreeSet<Long> changed = new TreeSet<>();
    private final TreeMap<Long, Integer> pinned = new TreeMap<>();
    private final ArrayDeque<PhaseChange> toShow = new ArrayDeque<>();
    private final TreeMap<String, Optional<BlockState>> replacements = new TreeMap<>();
    /** The version of each simulated section when it was last written into its chunk or brought in. */
    private final TreeMap<Long, Long> savedVersions = new TreeMap<>();
    private long shown;
    private long restoredBlocks;
    private int restoredSections;
    private int ticksUntilStep = 1;
    private int ticksUntilSave = SAVE_INTERVAL_TICKS;
    private long lastVerified = Long.MIN_VALUE;
    private double lastStepMillis;
    private double averageStepMillis;
    private String failure;

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
        HostedWorld.Settings settings = new HostedWorld.Settings(stepSeconds, WORK_PER_STEP,
                AtmosphereModel.DEFAULT_RELAXATION_SECONDS, Climate.kelvin(0.8, 64), calmRate,
                ThermalActivity.DEFAULT_CALM_STEPS, AUDIT_INTERVAL);
        this.hosted = new HostedWorld(world, mapper::forStateId, settings);
        this.planner = new ImportPlanner(AnchorConfig.get(AnchorConfig.RADIUS),
                AnchorConfig.get(AnchorConfig.VERTICAL_RADIUS), MARGIN);
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
            if (--ticksUntilStep > 0) {
                return;
            }
            ticksUntilStep = ticksPerStep;
            long start = System.nanoTime();
            followPlayers();
            verifyNext();
            HostedWorld.TickResult result = hosted.tick();
            toShow.addAll(result.phaseChanges());
            show();
            lastStepMillis = (System.nanoTime() - start) / 1e6;
            averageStepMillis = averageStepMillis == 0.0 ? lastStepMillis
                    : 0.95 * averageStepMillis + 0.05 * lastStepMillis;
        } catch (RuntimeException e) {
            stop(e);
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
                shown, restoredBlocks, restoredSections, failure);
    }

    /**
     * Takes in a block now instead of at the next tick, so that what is read or set is the block that is there.
     * A block placed this tick would otherwise still be the old one to the simulation.
     */
    private void takeInNow(BlockPos pos) {
        changed.remove(pos.asLong());
        if (hosted.isImported(sectionKey(pos)) && level.isLoaded(pos)) {
            hosted.reconcile(grid(pos), Block.getId(level.getBlockState(pos)), Double.NaN);
        }
    }

    /** Takes in the blocks that changed since the last tick. */
    private void takeInChanges() {
        for (int n = 0; n < CHANGES_PER_TICK && !changed.isEmpty(); n++) {
            BlockPos pos = BlockPos.of(changed.pollFirst());
            if (level.isLoaded(pos)) {
                hosted.reconcile(grid(pos), Block.getId(level.getBlockState(pos)), Double.NaN);
            }
        }
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
        int restored = hosted.importSection(key, ids(section), climate(key), snapshot);
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
        LevelChunkSection section = section(key);
        if (section == null) {
            forget(key);
        } else {
            hosted.verifySection(key, ids(section));
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
        BlockPos centre = new BlockPos((SectionPos.x(key) << 4) + 8, (SectionPos.y(key) << 4) + 8,
                (SectionPos.z(key) << 4) + 8);
        return Climate.kelvin(level.getBiome(centre).value().getBaseTemperature(), centre.getY());
    }

    private void stop(RuntimeException e) {
        failure = e.toString();
        changed.clear();
        toShow.clear();
        LOGGER.error("Anchor: heat in {} stopped after an error; it starts again when the world is next loaded",
                level.dimension().identifier(), e);
    }

    private static GridPos grid(BlockPos pos) {
        return new GridPos(pos.getX(), pos.getY(), pos.getZ());
    }

    private static long sectionKey(BlockPos pos) {
        return SectionPos.pack(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }
}
