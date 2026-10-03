package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.RefinedBlock;
import io.github.osir933.anchor.core.world.Section;
import java.util.Arrays;
import java.util.SortedSet;
import java.util.TreeMap;

/**
 * Remembers the range of temperatures in each section, overall and on each of its six faces, so that
 * sections where no heat can flow cost almost nothing to skip. An entry belongs to one section object at
 * one version, so any change to the section, a snapshot restore or a reload makes it recompute; the cache
 * never changes results, only how fast they come.
 */
final class Isotherms {

    /** Temperature differences up to this are treated as equilibrium when deciding what to simulate, in K. */
    static final double TOLERANCE_K = 1e-6;

    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * The temperatures in one section. Ranges with no matter able to conduct are empty: their minimum is
     * positive infinity and their maximum negative infinity.
     */
    private record Entry(Section section, long version, double min, double max, double[] faceMin,
            double[] faceMax) {
    }

    private final TreeMap<Long, Entry> entries = new TreeMap<>();
    private final int[] material = new int[SectionPos.BLOCKS];
    private final double[] mass = new double[SectionPos.BLOCKS];
    private final double[] enthalpy = new double[SectionPos.BLOCKS];

    /**
     * Tells whether all the matter of a section sits at one temperature, to within {@link #TOLERANCE_K}.
     * A section with no matter able to conduct counts as isothermal.
     */
    boolean isIsothermal(PhysicalWorld world, Section section) {
        Entry e = entry(world, section);
        return !(e.max - e.min > TOLERANCE_K);
    }

    /**
     * Tells whether no heat can flow into, out of or within a section this step: its matter sits at one
     * temperature, and so does the matter of every neighbour in scope that touches it.
     */
    boolean isQuiet(PhysicalWorld world, SortedSet<Long> scope, Section section) {
        Entry e = entry(world, section);
        if (e.max - e.min > TOLERANCE_K) {
            return false;
        }
        if (e.min > e.max) {
            return true;
        }
        for (Direction d : DIRECTIONS) {
            long key = SectionPos.offset(section.key(), d.dx(), d.dy(), d.dz());
            if (!scope.contains(key)) {
                continue;
            }
            Section neighbour = world.section(key);
            if (neighbour == null) {
                continue;
            }
            Entry n = entry(world, neighbour);
            int face = d.opposite().ordinal();
            if (Math.max(e.max, n.faceMax[face]) - Math.min(e.min, n.faceMin[face]) > TOLERANCE_K) {
                return false;
            }
        }
        return true;
    }

    /** Forgets sections that are no longer part of the world. */
    void prune(PhysicalWorld world) {
        entries.entrySet().removeIf(e -> world.section(e.getKey()) != e.getValue().section);
    }

    private Entry entry(PhysicalWorld world, Section section) {
        Entry e = entries.get(section.key());
        if (e != null && e.section == section && e.version == section.version()) {
            return e;
        }
        e = compute(world.materials(), section);
        entries.put(section.key(), e);
        return e;
    }

    private Entry compute(MaterialRegistry materials, Section section) {
        double[] faceMin = new double[DIRECTIONS.length];
        double[] faceMax = new double[DIRECTIONS.length];
        if (section.isUniform()) {
            double t = ThermalGraph.temperature(materials, section.blockState(0));
            double min = Double.isNaN(t) ? Double.POSITIVE_INFINITY : t;
            double max = Double.isNaN(t) ? Double.NEGATIVE_INFINITY : t;
            Arrays.fill(faceMin, min);
            Arrays.fill(faceMax, max);
            return new Entry(section, section.version(), min, max, faceMin, faceMax);
        }
        Arrays.fill(faceMin, Double.POSITIVE_INFINITY);
        Arrays.fill(faceMax, Double.NEGATIVE_INFINITY);
        section.copyBlocks(material, mass, enthalpy, null, 0);
        boolean whole = section.leafCount() == 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double[] block = new double[2];
        int lastMaterial = -1;
        double lastSpecific = Double.NaN;
        double lastT = Double.NaN;
        for (int i = 0; i < SectionPos.BLOCKS; i++) {
            if (!whole && section.isRefined(i)) {
                leafRange(materials, section.refinedBlock(i), block);
                if (block[0] > block[1]) {
                    continue;
                }
            } else {
                int m = material[i];
                if (m == MaterialRegistry.VACUUM || mass[i] == 0) {
                    continue;
                }
                double specific = enthalpy[i] / mass[i];
                long bits = Double.doubleToRawLongBits(specific);
                if (m != lastMaterial || bits != Double.doubleToRawLongBits(lastSpecific)) {
                    lastMaterial = m;
                    lastSpecific = specific;
                    lastT = materials.get(m).temperatureFor(specific);
                }
                block[0] = lastT;
                block[1] = lastT;
            }
            min = Math.min(min, block[0]);
            max = Math.max(max, block[1]);
            int x = i & 15;
            int z = (i >> 4) & 15;
            int y = i >> 8;
            if (x == 0) {
                widen(faceMin, faceMax, Direction.WEST, block);
            } else if (x == 15) {
                widen(faceMin, faceMax, Direction.EAST, block);
            }
            if (y == 0) {
                widen(faceMin, faceMax, Direction.DOWN, block);
            } else if (y == 15) {
                widen(faceMin, faceMax, Direction.UP, block);
            }
            if (z == 0) {
                widen(faceMin, faceMax, Direction.NORTH, block);
            } else if (z == 15) {
                widen(faceMin, faceMax, Direction.SOUTH, block);
            }
        }
        return new Entry(section, section.version(), min, max, faceMin, faceMax);
    }

    /** Puts the coldest and hottest temperature among a refined block's leaves into {@code out}. */
    private static void leafRange(MaterialRegistry materials, RefinedBlock refined, double[] out) {
        out[0] = Double.POSITIVE_INFINITY;
        out[1] = Double.NEGATIVE_INFINITY;
        refined.forEachLeaf((cell, state) -> {
            double t = ThermalGraph.temperature(materials, state);
            if (!Double.isNaN(t)) {
                out[0] = Math.min(out[0], t);
                out[1] = Math.max(out[1], t);
            }
        });
    }

    private static void widen(double[] faceMin, double[] faceMax, Direction face, double[] block) {
        int f = face.ordinal();
        faceMin[f] = Math.min(faceMin[f], block[0]);
        faceMax[f] = Math.max(faceMax[f], block[1]);
    }
}
