package io.github.osir933.anchor.core.physics.thermal;

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
 * every face two of those leaves share. The arrays are reused from step to step, so a steady simulation
 * allocates almost nothing; they may be longer than {@link #leafCount} and {@link #faceCount}.
 *
 * <p>A section in scope is skipped when all its matter sits at one temperature, to within
 * {@link Isotherms#TOLERANCE_K}, and so does the matter of each neighbour in scope where it touches the
 * section: nothing can flow there. Faces to skipped sections and to sections outside the scope are left out,
 * which makes them insulating. A skipped section joins in the step after a neighbour's temperature at their
 * shared face moves, so heat reaches it one step late but none is lost.
 *
 * <p>Each face is found once, from the leaf on its negative side, whatever the sizes of the two leaves; a
 * coarse leaf facing finer ones gets one face per finer leaf. Whole blocks facing whole blocks, by far the
 * most common case, are paired by index arithmetic without building cell ids.
 */
final class ThermalGraph {

    /** How far apart neighbouring blocks are in a section's index, along x, y and z. */
    private static final int[] STRIDE = {1, 256, 16};

    /** Where the local x, y and z coordinates sit in a block's index. */
    private static final int[] SHIFT = {0, 8, 4};

    /** Arrays never shrink below this many entries. */
    private static final int MIN_CAPACITY = SectionPos.BLOCKS;

    int leafCount;
    /** The sections the leaves lie in, in scope order. */
    Section[] sections = new Section[0];
    /** For each leaf, its section, as an index into {@link #sections}. */
    int[] leafSection = new int[0];
    /** For each leaf, the index of its block in the section. */
    int[] leafBlock = new int[0];
    /** For each leaf of a refined block, its cell id; {@code null} where the leaf is a whole block. */
    CellId[] cells = new CellId[0];
    int[] material = new int[0];
    double[] mass = new double[0];
    double[] enthalpy = new double[0];
    long[] owner = new long[0];
    double[] edge = new double[0];
    int faceCount;
    int[] faceA = new int[0];
    int[] faceB = new int[0];
    double[] faceArea = new double[0];
    /** The axis each face is normal to: 0 for x, 1 for y (so {@code faceA} is below), 2 for z. */
    byte[] faceAxis = new byte[0];

    private final TreeMap<Long, SectionIndex> index = new TreeMap<>();

    /** A section in the graph, with where each of its blocks' leaves start; the last entry is the end. */
    private record SectionIndex(Section section, int[] firstLeaf) {
    }

    /** Builds a graph from scratch. */
    static ThermalGraph build(PhysicalWorld world, SortedSet<Long> scope, Isotherms isotherms) {
        ThermalGraph g = new ThermalGraph();
        g.rebuild(world, scope, isotherms);
        return g;
    }

    /** Returns the id of a leaf. */
    CellId cell(int leaf) {
        CellId id = cells[leaf];
        return id != null ? id : CellId.of(sections[leafSection[leaf]].blockPos(leafBlock[leaf]));
    }

    /** Rebuilds the graph for a new step, reusing the arrays of the last one. */
    void rebuild(PhysicalWorld world, SortedSet<Long> scope, Isotherms isotherms) {
        List<Section> included = new ArrayList<>();
        int leaves = 0;
        for (long key : scope) {
            Section s = world.section(key);
            if (s != null && !isotherms.isQuiet(world, scope, s)) {
                included.add(s);
                leaves += SectionPos.BLOCKS - s.refinedBlocks().size() + s.leafCount();
            }
        }
        sections = included.toArray(new Section[0]);
        resizeLeaves(leaves);
        int faceCapacity = capacity(faceA.length, 3 * leaves);
        if (faceCapacity != faceA.length) {
            faceA = new int[faceCapacity];
            faceB = new int[faceCapacity];
            faceArea = new double[faceCapacity];
            faceAxis = new byte[faceCapacity];
        }
        index.clear();
        leafCount = 0;
        for (int number = 0; number < sections.length; number++) {
            Section s = sections[number];
            int[] first = new int[SectionPos.BLOCKS + 1];
            if (s.leafCount() == 0) {
                addWholeSection(number, s);
                for (int b = 0; b <= SectionPos.BLOCKS; b++) {
                    first[b] = leafCount - SectionPos.BLOCKS + b;
                }
            } else {
                for (int b = 0; b < SectionPos.BLOCKS; b++) {
                    first[b] = leafCount;
                    RefinedBlock block = s.refinedBlock(b);
                    if (block == null) {
                        addLeaf(number, b, null, s.material(b), s.mass(b), s.enthalpy(b), s.owner(b), 1.0);
                    } else {
                        int sectionNumber = number;
                        int blockIndex = b;
                        block.forEachLeaf((cell, state) -> addLeaf(sectionNumber, blockIndex, cell,
                                state.material(), state.mass(), state.enthalpy(), state.owner(), cell.edgeLength()));
                    }
                }
                first[SectionPos.BLOCKS] = leafCount;
            }
            index.put(s.key(), new SectionIndex(s, first));
        }
        faceCount = 0;
        for (Section s : sections) {
            SectionIndex self = index.get(s.key());
            SectionIndex[] next = {
                index.get(SectionPos.offset(s.key(), 1, 0, 0)),
                index.get(SectionPos.offset(s.key(), 0, 1, 0)),
                index.get(SectionPos.offset(s.key(), 0, 0, 1))};
            int[] first = self.firstLeaf;
            boolean whole = s.leafCount() == 0;
            for (int b = 0; b < SectionPos.BLOCKS; b++) {
                if (!whole && s.isRefined(b)) {
                    for (int leaf = first[b]; leaf < first[b + 1]; leaf++) {
                        for (Direction d : Direction.POSITIVE) {
                            facesFrom(leaf, d);
                        }
                    }
                    continue;
                }
                for (int axis = 0; axis < 3; axis++) {
                    boolean inside = ((b >> SHIFT[axis]) & 15) < 15;
                    SectionIndex target = inside ? self : next[axis];
                    if (target == null) {
                        continue;
                    }
                    int neighbour = inside ? b + STRIDE[axis] : b - 15 * STRIDE[axis];
                    if (target.section.leafCount() != 0 && target.section.isRefined(neighbour)) {
                        facesFrom(first[b], Direction.POSITIVE.get(axis));
                    } else {
                        addFace(first[b], target.firstLeaf[neighbour], 1.0, axis);
                    }
                }
            }
        }
    }

    /** Returns the temperature of a state, or NaN for matter that cannot conduct (vacuum or no mass). */
    static double temperature(MaterialRegistry materials, CellState state) {
        return temperature(materials, state.material(), state.mass(), state.enthalpy());
    }

    /** Returns the temperature of matter, or NaN for matter that cannot conduct (vacuum or no mass). */
    static double temperature(MaterialRegistry materials, int material, double mass, double enthalpy) {
        if (material == MaterialRegistry.VACUUM || mass == 0) {
            return Double.NaN;
        }
        return materials.get(material).temperatureFor(enthalpy / mass);
    }

    /**
     * Returns how long an array should be to hold a number of entries: the current length if it is enough
     * and not wastefully large, otherwise the number plus a quarter.
     */
    static int capacity(int current, int needed) {
        if (needed <= current && (current <= MIN_CAPACITY || current <= 4L * needed)) {
            return current;
        }
        return Math.max(MIN_CAPACITY, needed + needed / 4);
    }

    /** Adds the faces on one positive side of a leaf, finding its neighbours by cell id. */
    private void facesFrom(int leaf, Direction d) {
        CellId cell = cell(leaf);
        CellId adjacent = cell.neighbor(d);
        SectionIndex si = index.get(adjacent.block().sectionKey());
        if (si == null) {
            return;
        }
        int b = adjacent.block().indexInSection();
        int from = si.firstLeaf[b];
        int axis = d.axis();
        double area = cell.edgeLength() * cell.edgeLength();
        if (!si.section.isRefined(b)) {
            addFace(leaf, from, area, axis);
            return;
        }
        int to = si.firstLeaf[b + 1];
        int found = Arrays.binarySearch(cells, from, to, adjacent);
        if (found >= 0) {
            addFace(leaf, found, area, axis);
            return;
        }
        int insertion = -found - 1;
        if (insertion > from && cells[insertion - 1].contains(adjacent)) {
            addFace(leaf, insertion - 1, area, axis);
            return;
        }
        for (int k = insertion; k < to && adjacent.contains(cells[k]); k++) {
            CellId fine = cells[k];
            int shift = fine.level() - adjacent.level();
            if (fine.sub(axis) - (adjacent.sub(axis) << shift) == 0) {
                addFace(leaf, k, fine.edgeLength() * fine.edgeLength(), axis);
            }
        }
    }

    private void resizeLeaves(int needed) {
        int size = capacity(leafSection.length, needed);
        if (size == leafSection.length) {
            return;
        }
        leafSection = new int[size];
        leafBlock = new int[size];
        cells = new CellId[size];
        material = new int[size];
        mass = new double[size];
        enthalpy = new double[size];
        owner = new long[size];
        edge = new double[size];
    }

    private void addLeaf(int sectionNumber, int blockIndex, CellId id, int materialIndex, double leafMass,
            double leafEnthalpy, long leafOwner, double leafEdge) {
        int i = leafCount++;
        leafSection[i] = sectionNumber;
        leafBlock[i] = blockIndex;
        cells[i] = id;
        material[i] = materialIndex;
        mass[i] = leafMass;
        enthalpy[i] = leafEnthalpy;
        owner[i] = leafOwner;
        edge[i] = leafEdge;
    }

    /** Adds every block of a section that has no refined blocks. */
    private void addWholeSection(int sectionNumber, Section s) {
        int start = leafCount;
        int end = start + SectionPos.BLOCKS;
        s.copyBlocks(material, mass, enthalpy, owner, start);
        Arrays.fill(leafSection, start, end, sectionNumber);
        for (int b = 0; b < SectionPos.BLOCKS; b++) {
            leafBlock[start + b] = b;
        }
        Arrays.fill(cells, start, end, null);
        Arrays.fill(edge, start, end, 1.0);
        leafCount = end;
    }

    private void addFace(int from, int to, double area, int axis) {
        if (faceCount == faceA.length) {
            int grown = Math.max(MIN_CAPACITY, faceCount * 2);
            faceA = Arrays.copyOf(faceA, grown);
            faceB = Arrays.copyOf(faceB, grown);
            faceArea = Arrays.copyOf(faceArea, grown);
            faceAxis = Arrays.copyOf(faceAxis, grown);
        }
        faceA[faceCount] = from;
        faceB[faceCount] = to;
        faceArea[faceCount] = area;
        faceAxis[faceCount] = (byte) axis;
        faceCount++;
    }
}
