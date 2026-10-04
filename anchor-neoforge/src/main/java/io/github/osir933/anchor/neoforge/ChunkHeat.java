package io.github.osir933.anchor.neoforge;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.osir933.anchor.core.host.SectionSnapshot;
import io.github.osir933.anchor.core.world.Provenance;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import org.slf4j.Logger;

/**
 * The heat Anchor saves with a chunk: for each of its sections where heat changed something, the
 * {@link SectionSnapshot} of the blocks that differ from what the section's blocks would start as. It is
 * attached to the chunk, so it is written and read with it. {@link LevelHeat} hands a section's snapshot to the
 * simulation when it brings the section in, and puts a new one here while the section is simulated.
 *
 * <p>The snapshots are replaced, never changed, so a save that reads them while the server writes sees either
 * the old ones or the new ones.
 */
final class ChunkHeat {

    /** The version of the saved layout, written next to the data. */
    static final int FORMAT = 1;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Saved blocks are packed as their local index in the low 12 bits and their palette index above. */
    private static final int INDEX_BITS = 12;
    private static final int INDEX_MASK = (1 << INDEX_BITS) - 1;

    private static final Codec<Provenance> PROVENANCE = Codec.STRING.comapFlatMap(ChunkHeat::provenanceNamed,
            p -> p.name().toLowerCase(Locale.ROOT));

    /** Reads and writes a palette entry; snapshot files use it too. */
    static final Codec<SectionSnapshot.Entry> ENTRY = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("material").forGetter(SectionSnapshot.Entry::material),
            Codec.LONG.optionalFieldOf("owner", 0L).forGetter(SectionSnapshot.Entry::owner),
            PROVENANCE.fieldOf("provenance").forGetter(SectionSnapshot.Entry::provenance))
            .apply(i, SectionSnapshot.Entry::new));

    /**
     * One section as it is written: its height, the palette, each saved block packed with its palette index,
     * and masses and enthalpies as the raw bits of their doubles, so they come back exactly.
     */
    private record Stored(int y, List<SectionSnapshot.Entry> palette, int[] blocks, long[] mass, long[] enthalpy) {
    }

    private static final Codec<Stored> STORED = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("y").forGetter(Stored::y),
            ENTRY.listOf().fieldOf("palette").forGetter(Stored::palette),
            Codec.INT_STREAM.fieldOf("blocks").forGetter(s -> Arrays.stream(s.blocks())),
            Codec.LONG_STREAM.fieldOf("mass").forGetter(s -> Arrays.stream(s.mass())),
            Codec.LONG_STREAM.fieldOf("enthalpy").forGetter(s -> Arrays.stream(s.enthalpy())))
            .apply(i, (y, palette, blocks, mass, enthalpy) -> new Stored(y, palette, blocks.toArray(),
                    mass.toArray(), enthalpy.toArray())));

    /** Reads and writes a chunk's saved heat as a list of sections. */
    static final Codec<ChunkHeat> CODEC = STORED.listOf().comapFlatMap(ChunkHeat::fromStored, ChunkHeat::toStored);

    /**
     * Saves a chunk's heat with the chunk: nothing for a chunk with nothing saved, otherwise the layout's
     * version and the sections. Heat saved in a layout this version does not read is dropped, and those blocks
     * start afresh.
     */
    static final IAttachmentSerializer<ChunkHeat> SERIALIZER = new IAttachmentSerializer<>() {
        @Override
        public ChunkHeat read(IAttachmentHolder holder, ValueInput input) {
            int format = input.getIntOr("format", 0);
            if (format != FORMAT) {
                LOGGER.warn("Anchor: dropping heat saved in layout {}; this version reads layout {}", format,
                        FORMAT);
                return new ChunkHeat();
            }
            return input.read("sections", CODEC).orElseGet(() -> {
                LOGGER.warn("Anchor: dropping heat saved with a chunk that could not be read");
                return new ChunkHeat();
            });
        }

        @Override
        public boolean write(ChunkHeat heat, ValueOutput output) {
            if (heat.isEmpty()) {
                return false;
            }
            output.putInt("format", FORMAT);
            output.store("sections", CODEC, heat);
            return true;
        }
    };

    private volatile SortedMap<Integer, SectionSnapshot> sections;

    /** Creates a chunk's heat with nothing saved. */
    ChunkHeat() {
        this(new TreeMap<>());
    }

    private ChunkHeat(TreeMap<Integer, SectionSnapshot> sections) {
        this.sections = Collections.unmodifiableSortedMap(sections);
    }

    /**
     * Returns a section's snapshot.
     *
     * @param sectionY the section's height in sections
     * @return the snapshot, or {@code null} if nothing is saved for the section
     */
    SectionSnapshot section(int sectionY) {
        return sections.get(sectionY);
    }

    /**
     * Saves a section's snapshot in place of the one saved before.
     *
     * @param sectionY the section's height in sections
     * @param snapshot the new snapshot, or {@code null} if nothing needs saving
     * @return {@code true} if that changed what is saved
     */
    boolean put(int sectionY, SectionSnapshot snapshot) {
        SortedMap<Integer, SectionSnapshot> current = sections;
        SectionSnapshot old = current.get(sectionY);
        if (old == null ? snapshot == null : old.equals(snapshot)) {
            return false;
        }
        TreeMap<Integer, SectionSnapshot> changed = new TreeMap<>(current);
        if (snapshot == null) {
            changed.remove(sectionY);
        } else {
            changed.put(sectionY, snapshot);
        }
        sections = Collections.unmodifiableSortedMap(changed);
        return true;
    }

    /**
     * Returns the saved sections.
     *
     * @return the snapshots by section height, unmodifiable
     */
    SortedMap<Integer, SectionSnapshot> sections() {
        return sections;
    }

    /**
     * Tells whether nothing is saved.
     *
     * @return {@code true} if no section has a snapshot
     */
    boolean isEmpty() {
        return sections.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ChunkHeat other && sections.equals(other.sections);
    }

    @Override
    public int hashCode() {
        return sections.hashCode();
    }

    @Override
    public String toString() {
        return "ChunkHeat" + sections;
    }

    private static DataResult<ChunkHeat> fromStored(List<Stored> stored) {
        TreeMap<Integer, SectionSnapshot> sections = new TreeMap<>();
        for (Stored s : stored) {
            int n = s.blocks().length;
            if (s.mass().length != n || s.enthalpy().length != n) {
                return DataResult.error(() -> "section " + s.y() + " saves " + n + " blocks but " + s.mass().length
                        + " masses and " + s.enthalpy().length + " enthalpies");
            }
            int[] blocks = new int[n];
            int[] entries = new int[n];
            double[] mass = new double[n];
            double[] enthalpy = new double[n];
            for (int k = 0; k < n; k++) {
                blocks[k] = s.blocks()[k] & INDEX_MASK;
                entries[k] = s.blocks()[k] >>> INDEX_BITS;
                mass[k] = Double.longBitsToDouble(s.mass()[k]);
                enthalpy[k] = Double.longBitsToDouble(s.enthalpy()[k]);
            }
            try {
                sections.put(s.y(), new SectionSnapshot(s.palette(), blocks, entries, mass, enthalpy));
            } catch (IllegalArgumentException e) {
                return DataResult.error(() -> "section " + s.y() + ": " + e.getMessage());
            }
        }
        return DataResult.success(new ChunkHeat(sections));
    }

    private static List<Stored> toStored(ChunkHeat heat) {
        List<Stored> stored = new ArrayList<>();
        for (var e : heat.sections().entrySet()) {
            SectionSnapshot s = e.getValue();
            int n = s.size();
            int[] blocks = new int[n];
            long[] mass = new long[n];
            long[] enthalpy = new long[n];
            for (int k = 0; k < n; k++) {
                blocks[k] = s.block(k) | s.paletteIndex(k) << INDEX_BITS;
                mass[k] = Double.doubleToRawLongBits(s.mass(k));
                enthalpy[k] = Double.doubleToRawLongBits(s.enthalpy(k));
            }
            stored.add(new Stored(e.getKey(), s.palette(), blocks, mass, enthalpy));
        }
        return stored;
    }

    private static DataResult<Provenance> provenanceNamed(String name) {
        for (Provenance p : Provenance.values()) {
            if (p.name().equalsIgnoreCase(name)) {
                return DataResult.success(p);
            }
        }
        return DataResult.error(() -> "unknown provenance '" + name + "'");
    }
}
