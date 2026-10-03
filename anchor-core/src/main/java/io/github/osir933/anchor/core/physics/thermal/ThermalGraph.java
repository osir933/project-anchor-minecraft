package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.RefinedBlock;
import io.github.osir933.anchor.core.world.Section;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeMap;

/**
 * The cells and faces conduction works on in one step: every leaf of the sections that need simulating, and
 * every face two of those leaves share.
 *
 * <p>A section in scope is skipped when it is uniform and every neighbour in scope is uniform at exactly
 * the same temperature: nothing can flow there. Faces to skipped sections and to sections outside the scope
 * are left out, which makes them insulating. A skipped section joins in the step after a neighbour's
 * temperature moves, so heat reaches it one step late but none is lost.
 *
 * <p>Each face is found once, from the leaf on its negative side, whatever the sizes of the two leaves; a
 * coarse leaf facing finer ones gets one face per finer leaf.
 */
final class ThermalGraph {

    final int leafCount;
    final CellId[] cells;
    final int[] material;
    final double[] mass;
    final double[] enthalpy;
    final long[] owner;
    final double[] edge;
    final int faceCount;
    final int[] faceA;
    final int[] faceB;
    final double[] faceArea;

    /** Where a section's leaves start in the leaf arrays, block by block. */
    private record SectionIndex(Section section, int[] firstLeaf) {
    }

    private ThermalGraph(List<CellId> cells, List<CellState> states, FaceList faces) {
        this.leafCount = cells.size();
        this.cells = cells.toArray(new CellId[0]);
        this.material = new int[leafCount];
        this.mass = new double[leafCount];
        this.enthalpy = new double[leafCount];
        this.owner = new long[leafCount];
        this.edge = new double[leafCount];
        for (int i = 0; i < leafCount; i++) {
            CellState s = states.get(i);
            material[i] = s.material();
            mass[i] = s.mass();
            enthalpy[i] = s.enthalpy();
            owner[i] = s.owner();
            edge[i] = this.cells[i].edgeLength();
        }
        this.faceCount = faces.size;
        this.faceA = Arrays.copyOf(faces.a, faces.size);
        this.faceB = Arrays.copyOf(faces.b, faces.size);
        this.faceArea = Arrays.copyOf(faces.area, faces.size);
    }

    static ThermalGraph build(PhysicalWorld world, SortedSet<Long> scope) {
        List<CellId> cells = new ArrayList<>();
        List<CellState> states = new ArrayList<>();
        TreeMap<Long, SectionIndex> index = new TreeMap<>();
        for (long key : scope) {
            Section s = world.section(key);
            if (s == null || isQuiet(world, scope, s)) {
                continue;
            }
            int[] first = new int[SectionPos.BLOCKS];
            for (int b = 0; b < SectionPos.BLOCKS; b++) {
                first[b] = cells.size();
                RefinedBlock block = s.refinedBlock(b);
                if (block == null) {
                    cells.add(CellId.of(s.blockPos(b)));
                    states.add(s.blockState(b));
                } else {
                    block.forEachLeaf((cell, state) -> {
                        cells.add(cell);
                        states.add(state.copy());
                    });
                }
            }
            index.put(key, new SectionIndex(s, first));
        }
        CellId[] all = cells.toArray(new CellId[0]);
        FaceList faces = new FaceList();
        for (int i = 0; i < all.length; i++) {
            CellId cell = all[i];
            for (Direction d : Direction.POSITIVE) {
                CellId adjacent = cell.neighbor(d);
                SectionIndex si = index.get(adjacent.block().sectionKey());
                if (si == null) {
                    continue;
                }
                int b = adjacent.block().indexInSection();
                int from = si.firstLeaf[b];
                if (!si.section.isRefined(b)) {
                    faces.add(i, from, cell.edgeLength() * cell.edgeLength());
                    continue;
                }
                int to = from + si.section.refinedBlock(b).leafCount();
                int found = Arrays.binarySearch(all, from, to, adjacent);
                if (found >= 0) {
                    faces.add(i, found, cell.edgeLength() * cell.edgeLength());
                    continue;
                }
                int insertion = -found - 1;
                if (insertion > from && all[insertion - 1].contains(adjacent)) {
                    faces.add(i, insertion - 1, cell.edgeLength() * cell.edgeLength());
                    continue;
                }
                int axis = d.axis();
                for (int k = insertion; k < to && adjacent.contains(all[k]); k++) {
                    CellId fine = all[k];
                    int shift = fine.level() - adjacent.level();
                    if (fine.sub(axis) - (adjacent.sub(axis) << shift) == 0) {
                        faces.add(i, k, fine.edgeLength() * fine.edgeLength());
                    }
                }
            }
        }
        return new ThermalGraph(cells, states, faces);
    }

    /** Tells whether nothing can flow into, out of or within a section this step. */
    static boolean isQuiet(PhysicalWorld world, SortedSet<Long> scope, Section s) {
        if (!s.isUniform()) {
            return false;
        }
        double t = temperature(world.materials(), s.blockState(0));
        if (Double.isNaN(t)) {
            return true;
        }
        for (Direction d : Direction.values()) {
            long key = SectionPos.offset(s.key(), d.dx(), d.dy(), d.dz());
            Section n = world.section(key);
            if (n == null || !scope.contains(key)) {
                continue;
            }
            if (!n.isUniform()) {
                return false;
            }
            double tn = temperature(world.materials(), n.blockState(0));
            if (!Double.isNaN(tn) && Double.doubleToRawLongBits(tn) != Double.doubleToRawLongBits(t)) {
                return false;
            }
        }
        return true;
    }

    /** Returns the temperature of a state, or NaN for matter that cannot conduct (vacuum or no mass). */
    static double temperature(MaterialRegistry materials, CellState state) {
        if (state.material() == MaterialRegistry.VACUUM || state.mass() == 0) {
            return Double.NaN;
        }
        Material m = materials.get(state.material());
        return m.stateFor(state.specificEnthalpy()).temperatureK();
    }

    /** A growable list of faces. */
    private static final class FaceList {
        private int[] a = new int[1024];
        private int[] b = new int[1024];
        private double[] area = new double[1024];
        private int size;

        private void add(int from, int to, double faceArea) {
            if (size == a.length) {
                a = Arrays.copyOf(a, size * 2);
                b = Arrays.copyOf(b, size * 2);
                area = Arrays.copyOf(area, size * 2);
            }
            a[size] = from;
            b[size] = to;
            area[size] = faceArea;
            size++;
        }
    }
}
