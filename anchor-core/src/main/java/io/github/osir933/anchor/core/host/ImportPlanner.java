package io.github.osir933.anchor.core.host;

import io.github.osir933.anchor.core.space.SectionPos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongPredicate;

/**
 * Chooses which sections of the host's world to simulate around a set of anchors, such as players: every
 * section within a horizontal and a vertical radius of an anchor. Imports come nearest first and a few at a
 * time, so loading spreads over many ticks; sections are let go only once they are a margin further away
 * than the radius, so walking back and forth across the edge does not reload them.
 */
public final class ImportPlanner {

    private final int radius;
    private final int verticalRadius;
    private final int margin;

    /**
     * What to do this tick.
     *
     * @param imports sections to import, nearest first
     * @param removals sections to let go, in ascending key order
     */
    public record Plan(List<Long> imports, List<Long> removals) {

        /**
         * Takes unmodifiable copies of the lists.
         *
         * @param imports the imports
         * @param removals the removals
         */
        public Plan {
            imports = List.copyOf(imports);
            removals = List.copyOf(removals);
        }
    }

    /**
     * Creates a planner.
     *
     * @param radius how many sections to each side of an anchor's section to simulate, horizontally
     * @param verticalRadius how many sections above and below an anchor's section to simulate
     * @param margin how many sections further away a section must be before it is let go
     */
    public ImportPlanner(int radius, int verticalRadius, int margin) {
        if (radius < 0 || verticalRadius < 0 || margin < 0) {
            throw new IllegalArgumentException("radii and margin must not be negative");
        }
        this.radius = radius;
        this.verticalRadius = verticalRadius;
        this.margin = margin;
    }

    /**
     * Plans one tick.
     *
     * @param anchors the packed sections the anchors are in
     * @param imported the sections already imported
     * @param available tells whether the host can provide a section now, for example because its chunk is
     *     loaded and it lies within the world's height
     * @param limit the most sections to import this tick
     * @return the plan
     */
    public Plan plan(Collection<Long> anchors, SortedSet<Long> imported, LongPredicate available, int limit) {
        TreeSet<Long> centres = new TreeSet<>(anchors);
        TreeMap<Long, Long> distance = new TreeMap<>();
        for (long anchor : centres) {
            int ax = SectionPos.x(anchor);
            int ay = SectionPos.y(anchor);
            int az = SectionPos.z(anchor);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                        long key = SectionPos.pack(ax + dx, ay + dy, az + dz);
                        if (!imported.contains(key)) {
                            distance.merge(key, (long) dx * dx + (long) dy * dy + (long) dz * dz, Math::min);
                        }
                    }
                }
            }
        }
        List<Map.Entry<Long, Long>> ranked = new ArrayList<>(distance.entrySet());
        ranked.sort(Map.Entry.<Long, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey(
                Comparator.naturalOrder())));
        List<Long> imports = new ArrayList<>();
        for (Map.Entry<Long, Long> e : ranked) {
            if (imports.size() >= limit) {
                break;
            }
            if (available.test(e.getKey())) {
                imports.add(e.getKey());
            }
        }
        List<Long> removals = new ArrayList<>();
        for (long key : imported) {
            if (!near(key, centres)) {
                removals.add(key);
            }
        }
        return new Plan(imports, removals);
    }

    /** Tells whether a section is close enough to an anchor to keep. */
    private boolean near(long key, Collection<Long> centres) {
        int x = SectionPos.x(key);
        int y = SectionPos.y(key);
        int z = SectionPos.z(key);
        for (long anchor : centres) {
            if (Math.abs(x - SectionPos.x(anchor)) <= radius + margin
                    && Math.abs(z - SectionPos.z(anchor)) <= radius + margin
                    && Math.abs(y - SectionPos.y(anchor)) <= verticalRadius + margin) {
                return true;
            }
        }
        return false;
    }
}
