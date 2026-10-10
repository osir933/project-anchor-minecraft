package io.github.osir933.anchor.neoforge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.osir933.anchor.core.host.HostedWorld;
import io.github.osir933.anchor.core.host.RegionSnapshot;
import io.github.osir933.anchor.core.host.SectionSnapshot;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Experiments saved to be rewound: a box of a level's blocks, kept as a structure template so that every block comes
 * back as it was, contents and all, together with the heat of every cell in it, kept as a {@link RegionSnapshot}.
 * Each snapshot is a compressed NBT file in the world's folder, under {@value #FOLDER} and the dimension's id, so it
 * can be kept, copied into another world or shared.
 *
 * <p>Saving needs heat to run in every section the box touches, and so does restoring. A restore puts the blocks back
 * first, as they were and without disturbing their neighbours or dropping anything, then gives every cell its heat.
 * Things that move, such as dropped items, animals and players, are no part of a snapshot and stay where they are.
 */
final class Snapshots {

    /** The folder in the world's folder that snapshots are kept in, with a folder for each dimension below it. */
    static final String FOLDER = "anchor/snapshots";

    /** The version of the file layout, written into each file. */
    static final int FORMAT = 1;

    /** The longest side of a snapshot's box, in blocks. */
    static final int MAX_EDGE = 64;

    /** How far the box saved around a player reaches to each side, in blocks. */
    static final int REACH = 16;

    /** The longest name a snapshot can have. */
    static final int MAX_NAME_LENGTH = 32;

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1," + MAX_NAME_LENGTH + "}");

    private static final String EXTENSION = ".nbt";

    /** The most NBT a snapshot file may hold, in bytes; a box of 64 blocks a side stays well inside it. */
    private static final long MAX_FILE_BYTES = 256L << 20;

    /** Blocks are placed back as they were, without updating their neighbours, dropping items or reacting. */
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    /**
     * A snapshot's heat as it is written: the box's size, the palette, the saved blocks, their packed cells with their
     * palette indices, masses and enthalpies as the raw bits of their doubles, so they come back exactly, and, if any
     * block was built or has a cracked joint, each such block's index in the box packed with its structural flags.
     */
    private record StoredHeat(int[] size, List<SectionSnapshot.Entry> palette, int[] blocks, long[] cells,
            int[] entries, long[] mass, long[] enthalpy, Optional<long[]> structure) {
    }

    /** Structural flags are packed in the low byte of a saved block, below its index in the box. */
    private static final int FLAG_BITS = 8;

    /** Reads and writes a snapshot's heat. */
    static final Codec<RegionSnapshot> HEAT = RecordCodecBuilder.<StoredHeat>create(i -> i.group(
            Codec.INT_STREAM.fieldOf("size").forGetter(s -> Arrays.stream(s.size())),
            ChunkHeat.ENTRY.listOf().fieldOf("palette").forGetter(StoredHeat::palette),
            Codec.INT_STREAM.fieldOf("blocks").forGetter(s -> Arrays.stream(s.blocks())),
            Codec.LONG_STREAM.fieldOf("cells").forGetter(s -> Arrays.stream(s.cells())),
            Codec.INT_STREAM.fieldOf("entries").forGetter(s -> Arrays.stream(s.entries())),
            Codec.LONG_STREAM.fieldOf("mass").forGetter(s -> Arrays.stream(s.mass())),
            Codec.LONG_STREAM.fieldOf("enthalpy").forGetter(s -> Arrays.stream(s.enthalpy())),
            Codec.LONG_STREAM.xmap(LongStream::toArray, Arrays::stream).optionalFieldOf("structure")
                    .forGetter(StoredHeat::structure))
            .apply(i, (size, palette, blocks, cells, entries, mass, enthalpy, structure) -> new StoredHeat(
                    size.toArray(), palette, blocks.toArray(), cells.toArray(), entries.toArray(), mass.toArray(),
                    enthalpy.toArray(), structure)))
            .comapFlatMap(Snapshots::fromStored, Snapshots::toStored);

    /**
     * A snapshot as it is kept.
     *
     * @param dimension the id of the dimension it was saved in
     * @param origin the lowest corner of the box it was saved from
     * @param blocks the box's blocks, as a structure template marked with the game's data version
     * @param heat the heat of the box
     * @param savedAtMillis when it was saved, in milliseconds since the start of 1970
     * @param simulatedSeconds how long heat had been simulated in the dimension when it was saved, in seconds
     */
    record Saved(String dimension, BlockPos origin, CompoundTag blocks, RegionSnapshot heat, long savedAtMillis,
            long simulatedSeconds) {

        /**
         * Returns the size of the box.
         *
         * @return its length, height and width in blocks
         */
        Vec3i size() {
            return new Vec3i(heat.sizeX(), heat.sizeY(), heat.sizeZ());
        }
    }

    private Snapshots() {
    }

    /**
     * Checks a snapshot's name.
     *
     * @param name the name as given
     * @return the name in lower case
     * @throws IllegalArgumentException if it is not a name a snapshot can have
     */
    static String checkName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!NAME.matcher(lower).matches()) {
            throw new IllegalArgumentException("A snapshot's name is up to " + MAX_NAME_LENGTH + " letters, digits, "
                    + "- and _, not " + name + ".");
        }
        return lower;
    }

    /**
     * Returns the folder a level's snapshots are kept in.
     *
     * @param level the level
     * @return the folder in the world's folder, which may not exist yet
     */
    static Path folder(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve(relativeFolder(level)).normalize();
    }

    /**
     * Returns where in the world's folder a level's snapshots are kept, for telling players.
     *
     * @param level the level
     * @return the folder, relative to the world's folder
     */
    static Path relativeFolder(ServerLevel level) {
        Identifier dimension = level.dimension().identifier();
        return Path.of(FOLDER, dimension.getNamespace(), dimension.getPath());
    }

    /**
     * Returns the file a snapshot is kept in.
     *
     * @param level the level it belongs to
     * @param name its name, {@linkplain #checkName as checked}
     * @return the file, which may not exist
     */
    static Path file(ServerLevel level, String name) {
        return folder(level).resolve(name + EXTENSION);
    }

    /**
     * Returns the names of a level's snapshots.
     *
     * @param level the level
     * @return the names, in alphabetical order; none if the folder cannot be read
     */
    static List<String> names(ServerLevel level) {
        List<String> names = new ArrayList<>();
        Path folder = folder(level);
        if (!Files.isDirectory(folder)) {
            return names;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + EXTENSION)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                name = name.substring(0, name.length() - EXTENSION.length());
                if (NAME.matcher(name).matches()) {
                    names.add(name);
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        names.sort(null);
        return names;
    }

    /**
     * Saves a box of a level's blocks and their heat, replacing any snapshot of the same name.
     *
     * @param level the level
     * @param heat the level's heat
     * @param name the snapshot's name, {@linkplain #checkName as checked}
     * @param min the box's lowest corner
     * @param max the box's highest corner
     * @return the snapshot, or empty if heat does not run in all of the box or stopped after an error
     * @throws IllegalArgumentException if the box is longer than {@link #MAX_EDGE} blocks along an axis, or its
     *     corners are the wrong way round
     * @throws IOException if the file cannot be written
     */
    static Optional<Saved> save(ServerLevel level, LevelHeat heat, String name, BlockPos min, BlockPos max)
            throws IOException {
        for (int axis = 0; axis < 3; axis++) {
            int length = edge(min, max, axis);
            if (length < 1 || length > MAX_EDGE) {
                throw new IllegalArgumentException("a snapshot's box runs 1 to " + MAX_EDGE + " blocks along each "
                        + "axis, not " + length);
            }
        }
        Optional<RegionSnapshot> captured = heat.capture(min, max);
        if (captured.isEmpty()) {
            return Optional.empty();
        }
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, min, new Vec3i(edge(min, max, 0), edge(min, max, 1), edge(min, max, 2)), false,
                List.of());
        CompoundTag blocks = NbtUtils.addCurrentDataVersion(template.save(new CompoundTag()));
        Saved saved = new Saved(level.dimension().identifier().toString(), min, blocks, captured.get(),
                System.currentTimeMillis(), Math.round(heat.report().world().simulatedSeconds()));
        write(file(level, name), saved);
        return Optional.of(saved);
    }

    /** Returns how many blocks a box runs along an axis: 0 for x, 1 for y and 2 for z. */
    private static int edge(BlockPos min, BlockPos max, int axis) {
        return switch (axis) {
            case 0 -> max.getX() - min.getX() + 1;
            case 1 -> max.getY() - min.getY() + 1;
            default -> max.getZ() - min.getZ() + 1;
        };
    }

    /**
     * Puts a snapshot's blocks back and then their heat, with the box's lowest corner at a given place.
     *
     * @param level the level
     * @param heat the level's heat
     * @param saved the snapshot
     * @param corner where the box's lowest corner goes: where it was saved from, or elsewhere
     * @return what was restored, or empty if heat does not run in all of the box or stopped after an error
     * @throws IllegalArgumentException if the snapshot's blocks cannot be read or do not fill its box
     */
    static Optional<HostedWorld.Restored> restore(ServerLevel level, LevelHeat heat, Saved saved, BlockPos corner) {
        Vec3i size = saved.size();
        if (!heat.simulates(corner, corner.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
            return Optional.empty();
        }
        CompoundTag blocks = saved.blocks().copy();
        blocks = DataFixTypes.STRUCTURE.updateToCurrentVersion(level.getServer().getFixerUpper(), blocks,
                NbtUtils.getDataVersion(blocks, 500));
        StructureTemplate template = new StructureTemplate();
        template.load(BuiltInRegistries.BLOCK, blocks);
        if (!template.getSize().equals(size)) {
            throw new IllegalArgumentException("its blocks fill " + template.getSize().toShortString()
                    + " but its heat " + size.toShortString());
        }
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true).setKnownShape(true)
                .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING);
        template.placeInWorld(level, corner, corner, settings, RandomSource.create(0L), PLACE_FLAGS);
        return heat.restore(saved.heat(), corner);
    }

    /**
     * Writes a snapshot file, replacing any file there only once the new one is complete.
     *
     * @param file the file
     * @param saved the snapshot
     * @throws IOException if it cannot be written
     */
    static void write(Path file, Saved saved) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("format", FORMAT);
        root.putString("dimension", saved.dimension());
        root.putIntArray("origin", new int[] {saved.origin().getX(), saved.origin().getY(), saved.origin().getZ()});
        root.putLong("saved_at", saved.savedAtMillis());
        root.putLong("simulated_seconds", saved.simulatedSeconds());
        root.put("blocks", saved.blocks());
        root.put("heat", HEAT.encodeStart(NbtOps.INSTANCE, saved.heat()).getOrThrow());
        Files.createDirectories(file.getParent());
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        NbtIo.writeCompressed(root, partial);
        try {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reads a snapshot file.
     *
     * @param file the file
     * @return the snapshot
     * @throws NoSuchFileException if there is no such file
     * @throws IOException if it cannot be read, or holds no snapshot this version of Anchor reads
     */
    static Saved read(Path file) throws IOException {
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.create(MAX_FILE_BYTES));
        } catch (RuntimeException e) {
            // The game reports NBT that is too big or makes no sense with exceptions of its own.
            throw new IOException("it is damaged: " + e.getMessage(), e);
        }
        int format = root.getIntOr("format", 0);
        if (format != FORMAT) {
            throw new IOException("it is in layout " + format + ", and this version of Anchor reads layout "
                    + FORMAT);
        }
        int[] origin = root.getIntArray("origin").filter(a -> a.length == 3)
                .orElseThrow(() -> new IOException("it does not say where it was saved"));
        CompoundTag blocks = root.getCompound("blocks").orElseThrow(() -> new IOException("it holds no blocks"));
        CompoundTag stored = root.getCompound("heat").orElseThrow(() -> new IOException("it holds no heat"));
        DataResult<RegionSnapshot> heat = HEAT.parse(NbtOps.INSTANCE, stored);
        if (heat.result().isEmpty()) {
            throw new IOException("its heat cannot be read: " + heat.error().map(DataResult.Error::message)
                    .orElse("no reason given"));
        }
        return new Saved(root.getStringOr("dimension", ""), new BlockPos(origin[0], origin[1], origin[2]), blocks,
                heat.result().get(), root.getLongOr("saved_at", 0L), root.getLongOr("simulated_seconds", 0L));
    }

    private static DataResult<RegionSnapshot> fromStored(StoredHeat s) {
        if (s.size().length != 3) {
            return DataResult.error(() -> "a box has three sizes, not " + s.size().length);
        }
        double[] mass = new double[s.mass().length];
        double[] enthalpy = new double[s.enthalpy().length];
        for (int c = 0; c < mass.length; c++) {
            mass[c] = Double.longBitsToDouble(s.mass()[c]);
        }
        for (int c = 0; c < enthalpy.length; c++) {
            enthalpy[c] = Double.longBitsToDouble(s.enthalpy()[c]);
        }
        long[] structure = s.structure().orElse(new long[0]);
        int[] built = new int[structure.length];
        byte[] flags = new byte[structure.length];
        for (int k = 0; k < structure.length; k++) {
            long index = structure[k] >>> FLAG_BITS;
            if (index > Integer.MAX_VALUE) {
                return DataResult.error(() -> "a structural flag lies outside any box");
            }
            built[k] = (int) index;
            flags[k] = (byte) structure[k];
        }
        try {
            return DataResult.success(new RegionSnapshot(s.size()[0], s.size()[1], s.size()[2], s.palette(),
                    s.blocks(), s.cells(), s.entries(), mass, enthalpy, built, flags));
        } catch (IllegalArgumentException e) {
            return DataResult.error(e::getMessage);
        }
    }

    private static StoredHeat toStored(RegionSnapshot heat) {
        double[] mass = heat.masses();
        double[] enthalpy = heat.enthalpies();
        long[] massBits = new long[mass.length];
        long[] enthalpyBits = new long[enthalpy.length];
        for (int c = 0; c < mass.length; c++) {
            massBits[c] = Double.doubleToRawLongBits(mass[c]);
            enthalpyBits[c] = Double.doubleToRawLongBits(enthalpy[c]);
        }
        int[] built = heat.structureBlocks();
        byte[] flags = heat.structureFlags();
        long[] structure = new long[built.length];
        for (int k = 0; k < built.length; k++) {
            structure[k] = (long) built[k] << FLAG_BITS | (flags[k] & 0xFF);
        }
        return new StoredHeat(new int[] {heat.sizeX(), heat.sizeY(), heat.sizeZ()}, heat.palette(), heat.blocks(),
                heat.cells(), heat.paletteIndices(), massBits, enthalpyBits,
                structure.length == 0 ? Optional.empty() : Optional.of(structure));
    }
}
