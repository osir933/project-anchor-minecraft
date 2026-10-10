package io.github.osir933.anchor.neoforge;

import com.mojang.logging.LogUtils;
import io.github.osir933.anchor.core.host.BlockAppearance;
import io.github.osir933.anchor.core.matter.Mechanics;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.structure.Shape;
import io.github.osir933.anchor.core.physics.thermal.HeatSourceModel;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

/**
 * Describes Minecraft's blocks to the heat simulation: what each block state is made of, how much of its
 * space it fills, the phase it is shown in and any heat it gives off.
 *
 * <p>The {@linkplain AnchorDataMaps#MATERIALS material data map} comes first, so data packs can describe any
 * block. Then come built-in rules for the blocks whose heat or phase matters: water, ice and snow, lava and
 * magma, fires, torches, lanterns, candles, campfires and furnaces. Everything else is guessed from its name
 * (see {@link MaterialGuess}) or its sound, and its fill from its collision shape. Blocks filling less than a
 * fifth of their space, such as torches, flowers, rails and panes, count as the air around them; heat sources
 * among them heat that air.
 *
 * <p>Where a guessed block's look says more about the sunlight it takes in than its material does, its appearance
 * carries an albedo: the grass on a grass block, and the colour of wool, concrete and terracotta, from the colour
 * the block has on maps.
 *
 * <p>Every block's {@linkplain Shape shape}, where it touches its neighbours and so passes loads to them, is its
 * collision shape, with gaps of up to an eighth of a block at its faces closed, so that a chest's lid or soul sand
 * holds up what stands on it. Blocks without one, such as water, flowers and a single layer of snow, carry nothing.
 * Thin blocks that count as the air around them carry loads in a frame of their guessed material, as a fence's wood
 * or a pane's glass does, and blocks the game makes unbreakable, such as bedrock and barriers, hold still wherever
 * they are put.
 *
 * <p>A built block of brittle matter that thermal stress cracks through turns into a cracked or broken form of
 * itself where there is one: cobblestone for stone, cobbled deepslate for deepslate and cracked stone bricks for stone
 * bricks, and glass shatters. Other blocks stay as they are, with every joint cracked.
 */
final class BlockMapper {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Blocks filling less than this fraction of their space count as the air or water around them. */
    static final double THIN = 0.2;

    /** Collision boxes this close to a face of their block, as a fraction of a block, count as reaching it. */
    static final double REACH = 2.0 / 16.0;

    /** How far a collision box's side may be off a sixteenth of a block from rounding. */
    private static final double ROUNDING = 1e-6;

    /** A collision shape of more boxes than this counts as the box around them all. */
    private static final int MAX_BOXES = 32;

    /** The share of sunlight the grass on a grass block reflects, that of a short green crop. */
    static final double GRASS_ALBEDO = 0.23;

    static final HeatSourceModel.Source TORCH = new HeatSourceModel.Source(1300.0, 1500.0);
    static final HeatSourceModel.Source LANTERN = new HeatSourceModel.Source(1000.0, 300.0);
    static final HeatSourceModel.Source CANDLE = new HeatSourceModel.Source(1200.0, 80.0);
    static final HeatSourceModel.Source CAMPFIRE = new HeatSourceModel.Source(1100.0, 15_000.0);
    static final HeatSourceModel.Source FIRE = new HeatSourceModel.Source(1200.0, 20_000.0);
    static final HeatSourceModel.Source FURNACE = new HeatSourceModel.Source(600.0, 3000.0);
    // Minecraft's lava and magma blocks never cool, so their sources must outrun what they radiate: about 240 kW
    // from each face open to the air at 1450 K, and 50 kW at 1000 K. These keep even a block open on all six
    // sides at its temperature.
    static final HeatSourceModel.Source MAGMA = new HeatSourceModel.Source(1000.0, 320_000.0);
    static final HeatSourceModel.Source LAVA = new HeatSourceModel.Source(1450.0, 1_500_000.0);

    static final BlockAppearance AIR = BlockAppearance.of("anchor:air");
    static final BlockAppearance WATER = BlockAppearance.of("anchor:water").shownAs(Phase.LIQUID)
            .becoming(Phase.SOLID, "minecraft:ice").becoming(Phase.GAS, "minecraft:air");
    static final BlockAppearance ICE = BlockAppearance.of("anchor:water").shownAs(Phase.SOLID)
            .becoming(Phase.LIQUID, "minecraft:water").becoming(Phase.GAS, "minecraft:air");
    static final BlockAppearance SNOW_LAYER = BlockAppearance.of("anchor:powder_snow").shownAs(Phase.SOLID)
            .becoming(Phase.LIQUID, "minecraft:air").becoming(Phase.GAS, "minecraft:air");
    static final BlockAppearance SNOW_BLOCK = BlockAppearance.of("anchor:snow").shownAs(Phase.SOLID)
            .becoming(Phase.LIQUID, "minecraft:air").becoming(Phase.GAS, "minecraft:air");
    static final BlockAppearance LAVA_LOOK = BlockAppearance.of("anchor:basalt").shownAs(Phase.LIQUID)
            .startingAt(1450.0).heatedBy(LAVA);
    static final BlockAppearance MAGMA_LOOK = BlockAppearance.of("anchor:basalt").startingAt(1000.0)
            .heatedBy(MAGMA);

    private static final MaterialGuess.Guess GRANITE = new MaterialGuess.Guess("anchor:granite", null);
    private static final MaterialGuess.Guess IRON = new MaterialGuess.Guess("anchor:iron", null);
    private static final MaterialGuess.Guess GLASS = new MaterialGuess.Guess("anchor:glass", null);
    private static final MaterialGuess.Guess GRAVEL = new MaterialGuess.Guess("anchor:gravel", null);
    private static final MaterialGuess.Guess SAND = new MaterialGuess.Guess("anchor:sand", null);
    private static final MaterialGuess.Guess WOOL = new MaterialGuess.Guess("anchor:wool", null);
    private static final MaterialGuess.Guess FOLIAGE = new MaterialGuess.Guess("anchor:foliage", null);
    private static final MaterialGuess.Guess SNOW = new MaterialGuess.Guess("anchor:snow", Phase.SOLID);

    private final MaterialRegistry materials;
    private final TreeSet<String> warned = new TreeSet<>();

    /**
     * Creates a mapper.
     *
     * @param materials the materials the simulation knows; blocks are only described with these
     */
    BlockMapper(MaterialRegistry materials) {
        this.materials = materials;
    }

    /**
     * Describes the block state with a given id. Never fails: a block that cannot be described counts as
     * air, with a warning in the log.
     *
     * @param stateId the block state's id, as {@link Block#getId} gives it
     * @return the description
     */
    BlockAppearance forStateId(int stateId) {
        BlockState state = Block.stateById(stateId);
        if (state == null) {
            return AIR;
        }
        try {
            BlockAppearance a = appearance(state);
            if (materials.indexOf(a.material()) < 0) {
                warnOnce(state, "is made of " + a.material() + ", which is not an Anchor material; it counts as air");
                return AIR;
            }
            return a;
        } catch (RuntimeException e) {
            warnOnce(state, "could not be described, so it counts as air: " + e);
            return AIR;
        }
    }

    /**
     * Describes a block state.
     *
     * @param state the block state
     * @return the description
     */
    BlockAppearance appearance(BlockState state) {
        if (state.isAir()) {
            return AIR;
        }
        MaterialEntry entry = state.typeHolder().getData(AnchorDataMaps.MATERIALS);
        BlockAppearance a = entry == null ? null : listed(state, entry);
        if (a == null) {
            a = fracturing(builtIn(state), state);
        }
        return unbreakable(state) ? a.asImmovable() : a;
    }

    /** Forgets which blocks have been warned about, for when data packs are reloaded. */
    void reloaded() {
        warned.clear();
    }

    /** Describes a block from its data map entry, or returns {@code null} if the entry cannot be used. */
    private BlockAppearance listed(BlockState state, MaterialEntry e) {
        if (materials.indexOf(e.material()) < 0) {
            warnOnce(state, "is listed as " + e.material() + ", which is not an Anchor material; Anchor guesses "
                    + "its material instead");
            return null;
        }
        try {
            double fill = e.fill().isPresent() ? e.fill().get() * layers(state) : fill(state);
            HeatSourceModel.Source source = e.source().orElse(null);
            if (fill <= 0.0) {
                return lit(state, AIR, source);
            }
            BlockAppearance a = BlockAppearance.of(e.material()).withFill(Math.min(1.0, fill)).withShape(shape(state));
            if (e.phase().isPresent()) {
                a = a.shownAs(e.phase().get());
            }
            if (e.temperature().isPresent()) {
                a = a.startingAt(e.temperature().get());
            }
            for (Phase p : Phase.values()) {
                String replacement = e.becomes().get(p);
                if (replacement != null) {
                    a = a.becoming(p, replacement);
                }
            }
            a = e.fractured().isPresent() ? a.fracturingInto(e.fractured().get()) : fracturing(a, state);
            return lit(state, a, source);
        } catch (IllegalArgumentException mistake) {
            warnOnce(state, "has a material entry Anchor cannot use (" + mistake.getMessage() + "); Anchor "
                    + "guesses its material instead");
            return null;
        }
    }

    /** Describes a block the data map does not list. */
    private BlockAppearance builtIn(BlockState state) {
        Block block = state.getBlock();
        FluidState fluid = state.getFluidState();
        Shape shape = shape(state);
        if (isFluidBlock(state) && !fluid.isEmpty()) {
            return fluid(fluid).withShape(shape);
        }
        if (block == Blocks.ICE || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE
                || block == Blocks.FROSTED_ICE) {
            return ICE;
        }
        if (block == Blocks.SNOW) {
            return SNOW_LAYER.withFill(layers(state)).withShape(shape);
        }
        if (block == Blocks.SNOW_BLOCK) {
            return SNOW_BLOCK;
        }
        if (block == Blocks.POWDER_SNOW) {
            return SNOW_LAYER.withShape(shape);
        }
        if (block == Blocks.MAGMA_BLOCK) {
            return MAGMA_LOOK;
        }
        Holder<Block> holder = state.typeHolder();
        if (holder.is(BlockTags.FIRE)) {
            return AIR.heatedBy(FIRE).withShape(shape);
        }
        if (holder.is(BlockTags.CAMPFIRES)) {
            return lit(state, AIR, CAMPFIRE).withShape(shape);
        }
        if (holder.is(BlockTags.CANDLES)) {
            return lit(state, AIR, CANDLE).withShape(shape);
        }
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        List<String> words = List.of(path.split("_"));
        double fill = fill(state);
        if (fill < THIN) {
            // Kelp, seagrass and waterlogged fences are the water they stand in, and other thin blocks the air.
            BlockAppearance around = fluid.isEmpty() ? lit(state, AIR, thinSource(words))
                    : fluid(fluid).withoutReplacements();
            return framed(around.withShape(shape), state, path);
        }
        MaterialGuess.Guess guess = MaterialGuess.fromName(path).orElseGet(() -> bySound(state, path));
        BlockAppearance a = BlockAppearance.of(guess.material()).withFill(fill).withShape(shape);
        if (guess.phase() != null) {
            a = a.shownAs(guess.phase());
        }
        if (MaterialGuess.isDyed(path)) {
            a = a.withAlbedo(MaterialGuess.albedoOfColour(
                    state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col));
        } else if (words.contains("grass") && guess.material().equals("anchor:soil")) {
            a = a.withAlbedo(GRASS_ALBEDO);
        }
        boolean furnace = words.contains("furnace") || words.contains("smoker") || words.contains("kiln")
                || words.contains("oven");
        return furnace && state.hasProperty(BlockStateProperties.LIT) ? lit(state, a, FURNACE) : a;
    }

    /**
     * Gives a thin block that counts as its surroundings a frame of its guessed material to carry loads in, if it
     * touches anything and that material has mechanics.
     */
    private BlockAppearance framed(BlockAppearance around, BlockState state, String path) {
        if (!around.shape().reachesAFace()) {
            return around;
        }
        String material = MaterialGuess.fromName(path).orElseGet(() -> bySound(state, path)).material();
        int index = materials.indexOf(material);
        return index >= 0 && materials.get(index).mechanics() != null ? around.framedIn(material) : around;
    }

    /**
     * Gives a block that thermal stress can crack the block to show once it has cracked through: a cracked or broken
     * form of it if there is one, or air for glass, which shatters. Thin blocks count as the air around them and do
     * not crack, and blocks that only sound like glass, such as glowstone and beacons, stay whole.
     */
    private BlockAppearance fracturing(BlockAppearance a, BlockState state) {
        if (a.frame() != null || !cracks(a.material())) {
            return a;
        }
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String into = fracturedForm(id);
        if (into == null && a.material().equals(GLASS.material())
                && List.of(id.getPath().split("_")).contains("glass")) {
            into = "minecraft:air";
        }
        return into == null ? a : a.fracturingInto(into);
    }

    /** Tells whether thermal stress can crack a material through: brittle matter with a fracture toughness. */
    private boolean cracks(String material) {
        int index = materials.indexOf(material);
        Mechanics m = index < 0 ? null : materials.get(index).mechanics();
        return m != null && m.failure() == Mechanics.Failure.BRITTLE && !Double.isNaN(m.toughness());
    }

    /**
     * Returns the cracked or broken form of a block: the vanilla rubble of stone and deepslate, or the block named
     * {@code cracked_} and the block's own name, as cracked stone bricks are.
     *
     * @param id the block's registry name
     * @return the form's registry name, or {@code null} if the block has none
     */
    static String fracturedForm(Identifier id) {
        if (id.getNamespace().equals("minecraft")) {
            String vanilla = switch (id.getPath()) {
                case "stone" -> "minecraft:cobblestone";
                case "deepslate" -> "minecraft:cobbled_deepslate";
                case "infested_stone" -> "minecraft:infested_cobblestone";
                case "infested_stone_bricks" -> "minecraft:infested_cracked_stone_bricks";
                default -> null;
            };
            if (vanilla != null) {
                return vanilla;
            }
        }
        Identifier cracked = Identifier.fromNamespaceAndPath(id.getNamespace(), "cracked_" + id.getPath());
        return BuiltInRegistries.BLOCK.getOptional(cracked).isPresent() ? cracked.toString() : null;
    }

    /** Tells whether the game lets nobody break a block, as with bedrock and barriers. */
    private static boolean unbreakable(BlockState state) {
        try {
            return state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0;
        } catch (RuntimeException needsTheWorld) {
            return false;
        }
    }

    /**
     * Returns the solid part of a block: its collision shape inside its own space, with gaps of up to {@link #REACH}
     * at its faces closed, or no shape at all for a block nothing collides with.
     */
    static Shape shape(BlockState state) {
        try {
            if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                return Shape.FULL;
            }
            VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (shape.isEmpty()) {
                return Shape.NONE;
            }
            List<double[]> boxes = new ArrayList<>();
            double[] around = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE};
            shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> {
                double[] box = {x1, y1, z1, x2, y2, z2};
                boxes.add(box);
                for (int a = 0; a < 3; a++) {
                    around[a] = Math.min(around[a], box[a]);
                    around[a + 3] = Math.max(around[a + 3], box[a + 3]);
                }
            });
            List<double[]> kept = boxes.size() > MAX_BOXES ? List.of(around) : boxes;
            double[] packed = new double[6 * kept.size()];
            for (int b = 0; b < kept.size(); b++) {
                for (int a = 0; a < 3; a++) {
                    double low = kept.get(b)[a];
                    double high = kept.get(b)[a + 3];
                    packed[6 * b + a] = low <= REACH + ROUNDING ? Math.min(low, 0.0) : low;
                    packed[6 * b + a + 3] = high >= 1.0 - REACH - ROUNDING ? Math.max(high, 1.0) : high;
                }
            }
            return Shape.of(packed);
        } catch (IllegalArgumentException outside) {
            // Every box lies outside the block, as for a block that only reaches into its neighbours.
            return Shape.NONE;
        } catch (RuntimeException needsTheWorld) {
            // Some blocks work out their shape from the blocks around them; count them as whole.
            return Shape.FULL;
        }
    }

    /**
     * Returns whether a block is a fluid itself, as water, lava and bubble columns are, rather than a block
     * standing in one, such as a waterlogged stair.
     */
    private static boolean isFluidBlock(BlockState state) {
        return state.getBlock() instanceof LiquidBlock || state.is(Blocks.BUBBLE_COLUMN);
    }

    /** Describes a fluid: its own block, or the water a thin block stands in. */
    private static BlockAppearance fluid(FluidState fluid) {
        double fill = Math.max(1, Math.min(8, fluid.getAmount())) / 8.0;
        if (fluid.is(FluidTags.LAVA)) {
            return LAVA_LOOK.withFill(fill);
        }
        // Only still water freezes into ice or boils away; flowing water and other fluids keep their look.
        BlockAppearance water = fluid.is(FluidTags.WATER) && fluid.isSource() ? WATER : WATER.withoutReplacements();
        return water.withFill(fill);
    }

    /** Returns the heat a thin block gives off, judging by its name. */
    private static HeatSourceModel.Source thinSource(List<String> words) {
        if (words.contains("torch")) {
            return words.contains("redstone") ? null : TORCH;
        }
        if (words.contains("lantern")) {
            return LANTERN;
        }
        if (words.contains("candle")) {
            return CANDLE;
        }
        if (words.contains("campfire")) {
            return CAMPFIRE;
        }
        return words.contains("fire") ? FIRE : null;
    }

    /** Guesses a material from the sound a block makes, for names that say nothing. */
    // The sound without a position is deprecated for the sound at one, but a block is described once for
    // everywhere it stands, so its own sound is the one wanted.
    @SuppressWarnings("deprecation")
    private static MaterialGuess.Guess bySound(BlockState state, String path) {
        SoundType sound = state.getSoundType();
        if (sound == SoundType.WOOD) {
            return MaterialGuess.wood(path);
        }
        if (sound == SoundType.METAL) {
            return IRON;
        }
        if (sound == SoundType.GLASS) {
            return GLASS;
        }
        if (sound == SoundType.GRAVEL) {
            return GRAVEL;
        }
        if (sound == SoundType.SAND) {
            return SAND;
        }
        if (sound == SoundType.WOOL) {
            return WOOL;
        }
        if (sound == SoundType.GRASS) {
            return FOLIAGE;
        }
        if (sound == SoundType.SNOW) {
            return SNOW;
        }
        return GRANITE;
    }

    /** Adds a heat source, unless the block can be unlit and is not lit now. */
    private static BlockAppearance lit(BlockState state, BlockAppearance a, HeatSourceModel.Source source) {
        if (source == null) {
            return a;
        }
        if (state.hasProperty(BlockStateProperties.LIT) && !state.getValue(BlockStateProperties.LIT)) {
            return a;
        }
        return a.heatedBy(source);
    }

    /** Returns the fraction of its space a block fills, from its snow layers, its fluid level or its shape. */
    static double fill(BlockState state) {
        if (state.hasProperty(BlockStateProperties.LAYERS)) {
            return layers(state);
        }
        FluidState fluid = state.getFluidState();
        if (isFluidBlock(state) && !fluid.isEmpty()) {
            return Math.max(1, Math.min(8, fluid.getAmount())) / 8.0;
        }
        return shapeFill(state);
    }

    /** Returns the fraction of a full block that a block's snow layers make up, or one without layers. */
    private static double layers(BlockState state) {
        return state.hasProperty(BlockStateProperties.LAYERS) ? state.getValue(BlockStateProperties.LAYERS) / 8.0
                : 1.0;
    }

    /** Returns the volume of a block's collision shape inside its own space. */
    private static double shapeFill(BlockState state) {
        try {
            if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                return 1.0;
            }
            VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (shape.isEmpty()) {
                return 0.0;
            }
            double[] volume = new double[1];
            shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> volume[0] += span(x1, x2) * span(y1, y2) * span(z1, z2));
            return Math.min(1.0, volume[0]);
        } catch (RuntimeException needsTheWorld) {
            // Some blocks work out their shape from the blocks around them; count them as whole.
            return 1.0;
        }
    }

    /** Returns how much of the unit interval lies between two coordinates. */
    private static double span(double from, double to) {
        return Math.max(0.0, Math.min(1.0, to) - Math.max(0.0, from));
    }

    private void warnOnce(BlockState state, String problem) {
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (warned.add(name)) {
            LOGGER.warn("Anchor: the block {} {}", name, problem);
        }
    }
}
