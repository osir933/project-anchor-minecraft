package io.github.osir933.anchor.neoforge;

import com.mojang.logging.LogUtils;
import io.github.osir933.anchor.core.host.HostedWorld;
import io.github.osir933.anchor.core.host.StructureSurvey;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.slf4j.Logger;

/**
 * Whether what players build in one level stands, by the strength of its blocks. The simulation marks built blocks
 * to be checked when something around them changes, or heat weakens them; each game tick this takes them in turn,
 * reads the structure each belongs to and {@linkplain StructuralAnalysis analyses} it. Joints loaded beyond what they
 * can take crack, with the sound of the block breaking and a puff of its dust, and blocks left with nothing to hold
 * them up fall as falling blocks do, or break where they stand if they cannot fall whole, such as chests, doors and
 * beds. Blocks the game itself would break for want of support, such as torches and bamboo, are left to the game.
 *
 * <p>Small structures are analysed in the tick that asks, up to a few milliseconds of each tick. A larger one is
 * analysed on a thread of its own and settled a fixed number of ticks later, waiting for the analysis if it is late,
 * so a structure falls at the same tick whatever the machine; nothing else is analysed in the meantime.
 */
final class LevelStructures {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Structures of at most this many blocks are analysed in the tick that asks; larger ones in the background. */
    static final int AT_ONCE = 256;

    /** Milliseconds of each game tick spent analysing structures in the tick; one analysis always runs. */
    private static final double MILLIS_PER_TICK = 5.0;

    /** A structure analysed in the background is settled no sooner than this many game ticks after its survey... */
    private static final int MIN_SETTLE_TICKS = 10;

    /** ...and one game tick later for every this many blocks in it. */
    private static final int BLOCKS_PER_SETTLE_TICK = 64;

    /** The most blocks let fall per game tick; the rest fall in the ticks after. */
    static final int FALLS_PER_TICK = 256;

    /** The most cracks shown per settled analysis. */
    private static final int CRACKS_SHOWN = 32;

    /** A falling block hurts what it lands on by this much for each block it fell, as a pointed dripstone does... */
    private static final float DAMAGE_PER_BLOCK = 1.0f;

    /** ...up to this much. */
    private static final int MAX_DAMAGE = 20;

    /** Analyses of large structures run one at a time, on one thread shared by every level. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Anchor structures");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * What the structures of a level have done since it was loaded.
     *
     * @param enabled whether structures stand or fall by their strength
     * @param analyses how many structures were analysed
     * @param inBackground how many of them were large enough to be analysed in the background
     * @param cracks how many joints cracked
     * @param fallen how many blocks fell, or broke for want of support
     * @param waiting how many built blocks wait to have their structure checked
     * @param lastMillis how long the last analysis took, in milliseconds
     * @param largest the most built blocks analysed together
     * @param analysing whether a large structure is being analysed now
     * @param failure the error that stopped structures in this level, or {@code null}
     */
    record Report(boolean enabled, long analyses, long inBackground, long cracks, long fallen, int waiting,
            double lastMillis, int largest, boolean analysing, String failure) {
    }

    /**
     * What a look at one block's structure found.
     *
     * @param built whether the block is built; ground holds still and the rest of the record says nothing
     * @param crackedToward the directions in which its joints have cracked
     * @param blocks how many built blocks its structure was analysed with
     * @param edge how many built blocks beyond them were held still
     * @param falls whether the block would fall
     * @param falling how many blocks of the structure would fall
     * @param load how loaded the block's most loaded joint is, as a fraction of what it can take
     * @param worst the structure's most loaded joint that holds, or {@code null} if none does
     * @param settled whether the analysis found a state where nothing more gives way
     */
    record Look(boolean built, List<Direction> crackedToward, int blocks, int edge, boolean falls, int falling,
            double load, StructuralAnalysis.BondResult worst, boolean settled) {
    }

    /** An analysis running in the background, and the game tick it is settled at. */
    private record Pending(StructureSurvey survey, CompletableFuture<StructuralAnalysis.Result> result, long due) {
    }

    /** A block to let fall, as long as it is still the block that was found unsupported. */
    private record Fall(BlockPos pos, int stateId) {
    }

    private final ServerLevel level;
    private final HostedWorld hosted;
    private final ArrayDeque<Fall> falls = new ArrayDeque<>();
    private Pending pending;
    private long analyses;
    private long inBackground;
    private long cracks;
    private long fallen;
    /** Written by the thread that analysed last. */
    private volatile double lastMillis;
    private int largest;
    private String failure;

    /**
     * Starts structures in a level.
     *
     * @param level the level
     * @param hosted the level's simulation
     */
    LevelStructures(ServerLevel level, HostedWorld hosted) {
        this.level = level;
        this.hosted = hosted;
    }

    /** Runs one game tick: lets blocks fall, settles what is due and analyses what waits, within the tick's time. */
    void tick() {
        if (failure != null) {
            return;
        }
        try {
            if (!AnchorConfig.get(AnchorConfig.STRUCTURES_ENABLED)) {
                drop();
                return;
            }
            fallSome();
            long now = level.getGameTime();
            if (pending != null) {
                if (now < pending.due()) {
                    return;
                }
                settle(pending.survey(), pending.result().get());
                pending = null;
            }
            analyseWaiting(now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(new IllegalStateException("interrupted while waiting for a structure's analysis", e));
        } catch (ExecutionException e) {
            fail(e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause()));
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /** Analyses waiting structures in turn, small ones now and the first large one in the background. */
    private void analyseWaiting(long now) {
        int limit = AnchorConfig.get(AnchorConfig.STRUCTURE_BLOCKS);
        long start = System.nanoTime();
        do {
            Optional<StructureSurvey> next = hosted.nextStructure(limit);
            if (next.isEmpty()) {
                return;
            }
            StructureSurvey survey = next.get();
            largest = Math.max(largest, survey.blocks());
            if (survey.blocks() > AT_ONCE) {
                inBackground++;
                pending = new Pending(survey, CompletableFuture.supplyAsync(() -> timed(survey), WORKER),
                        now + Math.max(MIN_SETTLE_TICKS, survey.blocks() / BLOCKS_PER_SETTLE_TICK));
                return;
            }
            settle(survey, timed(survey));
        } while ((System.nanoTime() - start) / 1e6 < MILLIS_PER_TICK);
    }

    /** Analyses a structure, noting how long it took. */
    private StructuralAnalysis.Result timed(StructureSurvey survey) {
        long start = System.nanoTime();
        StructuralAnalysis.Result result = StructuralAnalysis.analyse(survey.frame());
        lastMillis = (System.nanoTime() - start) / 1e6;
        return result;
    }

    /** Settles an analysis into the world: shows its cracks and lines up the blocks it left unsupported to fall. */
    private void settle(StructureSurvey survey, StructuralAnalysis.Result result) {
        analyses++;
        HostedWorld.Settled settled = hosted.settle(survey, result);
        if (settled.stale()) {
            return;
        }
        cracks += settled.cracks().size();
        for (int k = 0; k < Math.min(CRACKS_SHOWN, settled.cracks().size()); k++) {
            showCrack(settled.cracks().get(k));
        }
        List<GridPos> falling = new ArrayList<>(settled.falling());
        falling.sort(Comparator.comparingInt(GridPos::y).thenComparing(Comparator.<GridPos>naturalOrder()));
        for (GridPos p : falling) {
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            falls.add(new Fall(pos, Block.getId(level.getBlockState(pos))));
        }
    }

    /** Lets the blocks lined up to fall go, a limited number per tick, lowest first. */
    private void fallSome() {
        for (int n = 0; n < FALLS_PER_TICK && !falls.isEmpty(); n++) {
            Fall f = falls.poll();
            BlockPos pos = f.pos();
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (Block.getId(state) != f.stateId() || !hosted.isBuilt(new GridPos(pos.getX(), pos.getY(), pos.getZ()))
                    || !state.canSurvive(level, pos)) {
                // Changed since, made ground, or about to be broken by the game for want of the support it needs.
                continue;
            }
            if (state.hasBlockEntity() || state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    || state.hasProperty(BlockStateProperties.BED_PART)) {
                level.destroyBlock(pos, true);
            } else {
                FallingBlockEntity.fall(level, pos, state).setHurtsEntities(DAMAGE_PER_BLOCK, MAX_DAMAGE);
            }
            fallen++;
        }
    }

    /** Shows a joint cracking: the sound of the block breaking, lower, and its dust where the two blocks meet. */
    // The sound without a position is deprecated for the sound at one; the block's own sound is the one wanted.
    @SuppressWarnings("deprecation")
    private void showCrack(StructuralAnalysis.Crack crack) {
        Direction toward = StructuralAnalysis.direction(crack.axis());
        BlockPos pos = new BlockPos(crack.pos().x(), crack.pos().y(), crack.pos().z());
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            state = level.getBlockState(pos.offset(toward.dx(), toward.dy(), toward.dz()));
        }
        if (state.isAir()) {
            return;
        }
        double x = pos.getX() + 0.5 + 0.5 * toward.dx();
        double y = pos.getY() + 0.5 + 0.5 * toward.dy();
        double z = pos.getZ() + 0.5 + 0.5 * toward.dz();
        SoundType sound = state.getSoundType();
        level.playSound(null, x, y, z, sound.getBreakSound(), SoundSource.BLOCKS, sound.getVolume(),
                sound.getPitch() * 0.6f);
        double spread = 0.3;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), x, y, z, 16,
                toward.dx() == 0 ? spread : 0.02, toward.dy() == 0 ? spread : 0.02, toward.dz() == 0 ? spread : 0.02,
                0.05);
    }

    /**
     * Looks at the structure of one block: whether it is built, which of its joints have cracked and, for a built
     * block, how its structure is loaded now. The structure is analysed on the spot, which may take a moment for a
     * large one.
     *
     * @param pos the block
     * @return the look, or empty if the block is not simulated
     */
    Optional<Look> look(BlockPos pos) {
        GridPos g = new GridPos(pos.getX(), pos.getY(), pos.getZ());
        if (!hosted.isImported(g.sectionKey())) {
            return Optional.empty();
        }
        List<Direction> cracked = new ArrayList<>();
        for (Direction d : Direction.values()) {
            if (hosted.isCracked(g, d)) {
                cracked.add(d);
            }
        }
        Optional<StructureSurvey> survey = hosted.survey(g, AnchorConfig.get(AnchorConfig.STRUCTURE_BLOCKS));
        if (survey.isEmpty()) {
            return Optional.of(new Look(false, cracked, 0, 0, false, 0, 0.0, null, true));
        }
        StructuralAnalysis.Result result = StructuralAnalysis.analyse(survey.get().frame());
        StructuralAnalysis.BlockResult block = result.block(g);
        StructuralAnalysis.BondResult worst = null;
        for (StructuralAnalysis.BondResult b : result.bonds()) {
            if (b.holds() && (worst == null || b.load() > worst.load())) {
                worst = b;
            }
        }
        return Optional.of(new Look(true, cracked, survey.get().blocks(), survey.get().edge(),
                block == null || block.fell(), result.falling().size(), block == null ? 0.0 : block.load(), worst,
                result.settled()));
    }

    /**
     * Returns what the structures of the level have done.
     *
     * @return the report
     */
    Report report() {
        return new Report(AnchorConfig.get(AnchorConfig.STRUCTURES_ENABLED), analyses, inBackground, cracks, fallen,
                hosted.uncheckedStructures(), lastMillis, largest, pending != null, failure);
    }

    /** Drops what is in hand, for when structures are switched off or the level stops; what was dropped waits again. */
    void drop() {
        if (pending != null) {
            pending.result().cancel(false);
            hosted.checkLater(pending.survey());
            pending = null;
        }
        falls.clear();
    }

    private void fail(RuntimeException e) {
        failure = e.toString();
        pending = null;
        falls.clear();
        LOGGER.error("Anchor: structures in {} stopped after an error; heat carries on, and structures start again "
                + "when the world is next loaded", level.dimension().identifier(), e);
    }
}
