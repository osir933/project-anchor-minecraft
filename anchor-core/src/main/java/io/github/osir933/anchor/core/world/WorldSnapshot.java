package io.github.osir933.anchor.core.world;

import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A frozen copy of a world's physical state, taken by {@link PhysicalWorld#snapshot()}.
 *
 * <p>Restoring a snapshot is the only way to go back in time. Coarsening never does it: once refinement has
 * changed an outcome, only an explicit rewind can undo that.
 */
public final class WorldSnapshot {

    private final WorldSettings settings;
    private final List<String> palette;
    private final long tick;
    private final TreeMap<Long, Section> sections;
    private final int leafCount;
    private final String stateHash;

    WorldSnapshot(WorldSettings settings, List<String> palette, long tick, TreeMap<Long, Section> sections,
            int leafCount, String stateHash) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.palette = List.copyOf(palette);
        this.tick = tick;
        this.sections = Objects.requireNonNull(sections, "sections");
        this.leafCount = leafCount;
        this.stateHash = Objects.requireNonNull(stateHash, "stateHash");
    }

    /**
     * Returns the tick at which the snapshot was taken.
     *
     * @return the tick
     */
    public long tick() {
        return tick;
    }

    /**
     * Returns the number of sections captured.
     *
     * @return the section count
     */
    public int sectionCount() {
        return sections.size();
    }

    /**
     * Returns the hash of the captured state, equal to {@link PhysicalWorld#stateHash()} at capture time.
     *
     * @return the hash as 64 hexadecimal digits
     */
    public String stateHash() {
        return stateHash;
    }

    WorldSettings settings() {
        return settings;
    }

    List<String> palette() {
        return palette;
    }

    TreeMap<Long, Section> sections() {
        return sections;
    }

    int leafCount() {
        return leafCount;
    }
}
