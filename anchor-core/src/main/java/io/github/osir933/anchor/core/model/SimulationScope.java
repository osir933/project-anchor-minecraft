package io.github.osir933.anchor.core.model;

import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Section;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Where each branch of physics is simulated during a tick. Sections outside a domain's scope keep their
 * state for that domain: time stands still there until a request brings them in. Faces between a section
 * in scope and one outside are treated as closed, so nothing leaks across the edge.
 */
@FunctionalInterface
public interface SimulationScope {

    /**
     * Returns the sections where a domain is simulated.
     *
     * @param world the world
     * @param domain the domain
     * @return section keys in ascending order; every key names a section that exists in the world
     */
    SortedSet<Long> sections(PhysicalWorld world, Domain domain);

    /**
     * Returns a scope that covers every section for every domain, for laboratory worlds and tests.
     *
     * @return the scope
     */
    static SimulationScope everywhere() {
        return (world, domain) -> {
            TreeSet<Long> keys = new TreeSet<>();
            for (Section s : world.sections()) {
                keys.add(s.key());
            }
            return Collections.unmodifiableSortedSet(keys);
        };
    }

    /**
     * Returns a scope with fixed sections per domain. Keys of sections that do not exist are skipped.
     *
     * @param sections the section keys per domain
     * @return the scope
     */
    static SimulationScope of(Map<Domain, ? extends Collection<Long>> sections) {
        Map<Domain, TreeSet<Long>> copy = new EnumMap<>(Domain.class);
        for (Map.Entry<Domain, ? extends Collection<Long>> e : sections.entrySet()) {
            copy.put(e.getKey(), new TreeSet<>(e.getValue()));
        }
        return (world, domain) -> {
            TreeSet<Long> keys = new TreeSet<>();
            for (long key : copy.getOrDefault(domain, new TreeSet<>())) {
                if (world.section(key) != null) {
                    keys.add(key);
                }
            }
            return Collections.unmodifiableSortedSet(keys);
        };
    }
}
