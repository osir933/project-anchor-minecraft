package io.github.osir933.anchor.core.world;

import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A block subdivided into an octree of smaller cells.
 *
 * <p>The leaves hold the authoritative state. The block's coarse view, which the rest of the world and the
 * Minecraft side read, is an aggregate of the leaves that is computed on demand and cached until a leaf
 * changes. Leaves are visited depth first in octant order, the same order as {@link CellId#compareTo}.
 *
 * <p>Instances are owned by a {@link Section}; everything that changes them goes through the section so
 * that its cached totals stay correct.
 */
public final class RefinedBlock {

    private final GridPos pos;
    private final Node root;
    private int leafCount;
    private CellState aggregate;

    /** A node of the octree: a leaf with a state, or an inner node with eight children. */
    private static final class Node {
        private CellState state;
        private Node[] children;

        private Node(CellState state) {
            this.state = state;
        }

        private boolean isLeaf() {
            return children == null;
        }

        private Node copy() {
            Node c = new Node(state == null ? null : state.copy());
            if (children != null) {
                c.children = new Node[8];
                for (int i = 0; i < 8; i++) {
                    c.children[i] = children[i].copy();
                }
            }
            return c;
        }
    }

    /** The node found while walking towards a cell, and the level at which the walk stopped. */
    private record Located(Node node, int level) {
    }

    RefinedBlock(GridPos pos, CellState blockState) {
        this.pos = Objects.requireNonNull(pos, "pos");
        this.root = new Node(blockState.copy());
        this.leafCount = 1;
    }

    private RefinedBlock(RefinedBlock other) {
        this.pos = other.pos;
        this.root = other.root.copy();
        this.leafCount = other.leafCount;
        this.aggregate = other.aggregate == null ? null : other.aggregate.copy();
    }

    RefinedBlock copy() {
        return new RefinedBlock(this);
    }

    /**
     * Returns the block's position.
     *
     * @return the block
     */
    public GridPos pos() {
        return pos;
    }

    /**
     * Returns the number of leaves.
     *
     * @return the leaf count, at least 1
     */
    public int leafCount() {
        return leafCount;
    }

    /**
     * Returns the level of the deepest leaf.
     *
     * @return the depth of the octree
     */
    public int depth() {
        return depth(root);
    }

    private static int depth(Node node) {
        if (node.isLeaf()) {
            return 0;
        }
        int max = 0;
        for (Node child : node.children) {
            max = Math.max(max, depth(child));
        }
        return max + 1;
    }

    /**
     * Returns the leaf that contains a cell: the cell itself if it is a leaf, or the coarser leaf around it.
     *
     * @param cell a cell of this block
     * @return the covering leaf, or {@code null} if the cell is subdivided into smaller leaves
     */
    public CellId leafCovering(CellId cell) {
        Located at = locate(cell);
        if (!at.node.isLeaf()) {
            return null;
        }
        return ancestor(cell, at.level);
    }

    /**
     * Tells whether a cell is one of the leaves.
     *
     * @param cell a cell of this block
     * @return {@code true} if the cell exists and is not subdivided
     */
    public boolean isLeaf(CellId cell) {
        Located at = locate(cell);
        return at.level == cell.level() && at.node.isLeaf();
    }

    /**
     * Returns a leaf's enthalpy, for readers that need nothing else and should not copy its state.
     *
     * @param cell a cell of this block
     * @return the enthalpy in joules, or {@link Double#NaN} if the cell is not a leaf
     */
    public double leafEnthalpy(CellId cell) {
        Located at = locate(cell);
        return at.level == cell.level() && at.node.isLeaf() ? at.node.state.enthalpy() : Double.NaN;
    }

    /**
     * Visits every leaf, depth first in octant order.
     *
     * @param visitor receives each leaf and a view of its state that is only valid during the call
     */
    public void forEachLeaf(LeafVisitor visitor) {
        CellState scratch = CellState.vacuum(Provenance.INITIAL);
        visit(root, CellId.of(pos), (cell, state) -> visitor.visit(cell, scratch.set(state)));
    }

    /**
     * Visits the leaves under a cell, depth first in octant order. If a coarser leaf covers the cell, that
     * leaf is the only one visited.
     *
     * @param cell a cell of this block
     * @param visitor receives each leaf and a view of its state that is only valid during the call
     */
    public void forEachLeaf(CellId cell, LeafVisitor visitor) {
        Located at = locate(cell);
        CellState scratch = CellState.vacuum(Provenance.INITIAL);
        visit(at.node, ancestor(cell, at.level), (c, state) -> visitor.visit(c, scratch.set(state)));
    }

    /**
     * Returns the block's coarse view: total mass and enthalpy, and the material, owner and provenance that
     * account for the most mass. Ties go to the lowest material index, owner id or provenance.
     *
     * @return a copy of the aggregate state
     */
    public CellState aggregate() {
        if (aggregate == null) {
            aggregate = computeAggregate();
        }
        return aggregate.copy();
    }

    // ---- package-private operations, called through Section ----

    /** Visits leaves with their live state objects. Callers must not keep or modify them. */
    void visitLive(LeafVisitor visitor) {
        visit(root, CellId.of(pos), visitor);
    }

    /** Returns the live state of a leaf. */
    CellState liveLeaf(CellId leaf) {
        Located at = locate(leaf);
        if (at.level != leaf.level() || !at.node.isLeaf()) {
            throw new IllegalArgumentException(leaf + " is not a leaf");
        }
        return at.node.state;
    }

    /** Replaces the state of a leaf, given its live state from {@link #liveLeaf}. */
    void writeLive(CellState live, CellState state) {
        live.set(state);
        aggregate = null;
    }

    /**
     * Subdivides leaves until the target cell is a leaf or an inner node.
     *
     * @return the number of leaves added
     */
    int refineTo(CellId target) {
        Located at = locate(target);
        Node node = at.node;
        int level = at.level;
        int added = 0;
        while (level < target.level()) {
            subdivide(node);
            added += 7;
            int shift = target.level() - level - 1;
            node = node.children[octantAt(target, shift)];
            level++;
        }
        return added;
    }

    /** Returns the number of leaves {@link #refineTo} would add. */
    int leavesToRefine(CellId target) {
        Located at = locate(target);
        return at.node.isLeaf() ? 7 * (target.level() - at.level) : 0;
    }

    /** Returns live states of the leaves under a node, in visiting order. */
    List<CellId> leavesUnder(CellId node, List<CellState> statesOut) {
        Located at = locate(node);
        if (at.level != node.level()) {
            throw new IllegalArgumentException(node + " lies inside a coarser leaf");
        }
        List<CellId> ids = new ArrayList<>();
        visit(at.node, node, (cell, state) -> {
            ids.add(cell);
            statesOut.add(state);
        });
        return ids;
    }

    /**
     * Returns the mass and enthalpy under a node, summed pairwise down the tree so that a subtree that was
     * only ever refined adds back up to exactly what it was split from.
     */
    double[] subtreeTotals(CellId node) {
        Located at = locate(node);
        if (at.level != node.level()) {
            throw new IllegalArgumentException(node + " lies inside a coarser leaf");
        }
        return new double[] {sumMass(at.node), sumEnthalpy(at.node)};
    }

    /**
     * Collapses the subtree at a node into one leaf with the given state.
     *
     * @return the number of leaves removed
     */
    int collapse(CellId node, CellState merged) {
        Located at = locate(node);
        if (at.level != node.level()) {
            throw new IllegalArgumentException(node + " lies inside a coarser leaf");
        }
        int before = countLeaves(at.node);
        at.node.children = null;
        at.node.state = merged.copy();
        leafCount -= before - 1;
        aggregate = null;
        return before - 1;
    }

    /** Returns the root leaf's state when the block is no longer subdivided. */
    CellState rootState() {
        if (!root.isLeaf()) {
            throw new IllegalStateException("the block is still subdivided");
        }
        return root.state.copy();
    }

    // ---- internals ----

    private void subdivide(Node leaf) {
        CellState s = leaf.state;
        // Dividing by eight is exact in binary floating point, so refinement conserves to the last bit.
        double mass = s.mass() / 8;
        double enthalpy = s.enthalpy() / 8;
        leaf.children = new Node[8];
        for (int i = 0; i < 8; i++) {
            leaf.children[i] = new Node(new CellState(s.material(), mass, enthalpy, s.owner(),
                    Provenance.RECONSTRUCTED));
        }
        leaf.state = null;
        leafCount += 7;
        aggregate = null;
    }

    private Located locate(CellId cell) {
        if (!cell.block().equals(pos)) {
            throw new IllegalArgumentException(cell + " is not in block " + pos);
        }
        Node node = root;
        int level = 0;
        while (level < cell.level() && !node.isLeaf()) {
            int shift = cell.level() - level - 1;
            node = node.children[octantAt(cell, shift)];
            level++;
        }
        return new Located(node, level);
    }

    private static int octantAt(CellId cell, int shift) {
        return ((cell.subX() >> shift) & 1) | (((cell.subY() >> shift) & 1) << 1)
                | (((cell.subZ() >> shift) & 1) << 2);
    }

    private static CellId ancestor(CellId cell, int level) {
        int shift = cell.level() - level;
        return new CellId(cell.block(), level, cell.subX() >> shift, cell.subY() >> shift, cell.subZ() >> shift);
    }

    private static void visit(Node node, CellId id, LeafVisitor visitor) {
        if (node.isLeaf()) {
            visitor.visit(id, node.state);
            return;
        }
        for (int i = 0; i < 8; i++) {
            visit(node.children[i], id.child(i), visitor);
        }
    }

    private static int countLeaves(Node node) {
        if (node.isLeaf()) {
            return 1;
        }
        int n = 0;
        for (Node child : node.children) {
            n += countLeaves(child);
        }
        return n;
    }

    /** Sums mass pairwise down the tree: exact whenever the children are equal, as after refinement. */
    private static double sumMass(Node node) {
        if (node.isLeaf()) {
            return node.state.mass();
        }
        Node[] c = node.children;
        return ((sumMass(c[0]) + sumMass(c[1])) + (sumMass(c[2]) + sumMass(c[3])))
                + ((sumMass(c[4]) + sumMass(c[5])) + (sumMass(c[6]) + sumMass(c[7])));
    }

    private static double sumEnthalpy(Node node) {
        if (node.isLeaf()) {
            return node.state.enthalpy();
        }
        Node[] c = node.children;
        return ((sumEnthalpy(c[0]) + sumEnthalpy(c[1])) + (sumEnthalpy(c[2]) + sumEnthalpy(c[3])))
                + ((sumEnthalpy(c[4]) + sumEnthalpy(c[5])) + (sumEnthalpy(c[6]) + sumEnthalpy(c[7])));
    }

    private CellState computeAggregate() {
        Map<Integer, Double> byMaterial = new TreeMap<>();
        Map<Long, Double> byOwner = new TreeMap<>();
        Map<Provenance, Double> byProvenance = new EnumMap<>(Provenance.class);
        visit(root, CellId.of(pos), (cell, state) -> {
            byMaterial.merge(state.material(), state.mass(), Double::sum);
            byOwner.merge(state.owner(), state.mass(), Double::sum);
            byProvenance.merge(state.provenance(), state.mass(), Double::sum);
        });
        // With no mass at all every candidate ties at zero and the lowest index, vacuum if present, wins.
        return new CellState(heaviest(byMaterial), sumMass(root), sumEnthalpy(root), heaviest(byOwner),
                heaviest(byProvenance));
    }

    /** Returns the key with the largest mass; maps iterate in key order, so ties go to the smallest key. */
    private static <K> K heaviest(Map<K, Double> massByKey) {
        K best = null;
        double bestMass = -1;
        for (Map.Entry<K, Double> e : massByKey.entrySet()) {
            if (e.getValue() > bestMass) {
                best = e.getKey();
                bestMass = e.getValue();
            }
        }
        return best;
    }
}
