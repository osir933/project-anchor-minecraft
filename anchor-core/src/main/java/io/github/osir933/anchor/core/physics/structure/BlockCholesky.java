package io.github.osir933.anchor.core.physics.structure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool; // determinism-ok: each number is worked out by one thread, in one order
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.function.IntFunction;

/**
 * Solves the stiffness equations of a frame exactly: a sparse Cholesky factorization of a matrix whose entries
 * are 6 by 6 blocks, one per node and one per pair of joined nodes.
 *
 * <p>Nodes are eliminated in nested-dissection order, worked out from their grid positions: the structure is cut
 * in half by a plane of nodes through its longest side, each half is ordered the same way, and the plane comes
 * last. Cutting keeps the factor sparse, close to the best possible for grid-shaped structures, and depends only
 * on positions, so every machine eliminates in the same order and gets the same bits.
 *
 * <p>The factorization is multifrontal: nodes whose columns of the factor share one pattern, such as the nodes of
 * a cutting plane, are eliminated together in one dense matrix, their front, which then hands what it leaves to
 * the front above it. Nearly all the work is in dense loops over memory laid out in a row, which is fast.
 *
 * <p>A direct solver is used rather than an iterative one because long, slender structures, which blocks often
 * make, are exactly where iterative solvers converge slowly.
 *
 * <p>A large factorization can be shared among threads: fronts in separate branches of the elimination tree are
 * worked on at once, and a large front's update is split into bands of rows. Solves are shared the same way, each
 * front taking in what the fronts below it pass on rather than those fronts handing it out. Every number is still
 * worked out by the same operations in the same order, so the factor and the solution are the same to the bit
 * however many threads share them.
 */
final class BlockCholesky {

    /** Degrees of freedom per node. */
    static final int B = 6;

    private static final int BB = B * B;

    /** Groups this small are ordered as they are rather than cut further. */
    private static final int LEAF = 8;

    /** Columns a dense front eliminates at a time before it updates the rest. */
    private static final int PANEL = 48;

    /** Fronts of up to this many nodes take in the next step whatever zeros that costs. */
    private static final int SMALL_FRONT = 8;

    /** The largest fraction of zeros a larger front may hold. */
    private static final double ZEROS = 0.1;

    /** A factorization that takes fewer multiplications than this is done on one thread, sharing costing more. */
    static final double SHARED_WORK = 2e7;

    /** A branch of the elimination tree that takes fewer multiplications than this is not split among threads. */
    private static final double BRANCH_WORK = 1e6;

    /** A front's update is split into bands of rows for several threads once it takes this many multiplications. */
    private static final double BAND_WORK = 1e6;

    /** Half a solve that takes fewer multiplications than this is done on one thread. */
    static final double SHARED_SOLVE = 2e5;

    /** A branch whose part of half a solve takes fewer multiplications than this is not split among threads. */
    private static final double BRANCH_SOLVE = 5e4;

    /** A front's part of half a solve is split into bands for several threads once it takes this many. */
    private static final double BAND_SOLVE = 5e4;

    /**
     * Pools of threads to share factorizations and solves among, one for each number of threads asked for, made the
     * first time so many are asked for and kept from then on.
     */
    private static final ConcurrentHashMap<Integer, ForkJoinPool> POOLS = // determinism-ok: as the import says
            new ConcurrentHashMap<>();

    private final int n;
    private final int[] perm;
    /** Front s eliminates the steps from start[s] up to, not including, start[s + 1]. */
    private final int[] start;
    /** The steps below each front that its columns reach, ascending. */
    private final int[][] below;
    /**
     * Each front's columns of the factor, row-major: a row per degree of freedom of its own steps and then of the
     * steps below it, a column per degree of freedom of its own steps; the row stride is in {@link #stride}.
     */
    private final double[][] factor;
    private final int[] stride;
    private final long fill;
    /** How many multiplications the factorization took. */
    private final double work;
    /** The tree of fronts: each front's first child and each front's next sibling, or -1 for none. */
    private final int[] childHead;
    private final int[] childNext;
    /**
     * The fronts that pass numbers on to each step in the first half of a solve: for step p, entries
     * {@code incomingStart[p]} up to {@code incomingStart[p + 1]} of {@link #incomingFront}, the fronts in elimination
     * order, and of {@link #incomingRow}, where the step lies among each one's steps below.
     */
    private final int[] incomingStart;
    private final int[] incomingFront;
    private final int[] incomingRow;
    /** How many multiplications each front takes in, in the first half of a solve. */
    private final double[] takeInWork;
    /** How many multiplications each front's branch takes in half a solve: the front and every front below it. */
    private final double[] solveBranch;
    private final double solveWork;
    /** The threads that share solves, or {@code null} to solve on the calling thread. */
    private final ForkJoinPool pool; // determinism-ok: as the import says

    private BlockCholesky(int n, int[] perm, int[] start, int[][] below, double[][] factor, int[] stride,
            long fill, double work, int[] frontOf, int[] childHead, int[] childNext, int threads) {
        this.n = n;
        this.perm = perm;
        this.start = start;
        this.below = below;
        this.factor = factor;
        this.stride = stride;
        this.fill = fill;
        this.work = work;
        this.childHead = childHead;
        this.childNext = childNext;
        this.pool = pool(threads);
        int fronts = below.length;
        incomingStart = new int[n + 1];
        for (int[] rows : below) {
            for (int step : rows) {
                incomingStart[step + 1]++;
            }
        }
        for (int p = 0; p < n; p++) {
            incomingStart[p + 1] += incomingStart[p];
        }
        incomingFront = new int[incomingStart[n]];
        incomingRow = new int[incomingStart[n]];
        int[] next = Arrays.copyOf(incomingStart, n);
        takeInWork = new double[fronts];
        solveBranch = new double[fronts];
        double half = 0;
        for (int s = 0; s < fronts; s++) {
            double m = B * (start[s + 1] - start[s]);
            for (int r = 0; r < below[s].length; r++) {
                int step = below[s][r];
                incomingFront[next[step]] = s;
                incomingRow[next[step]] = r;
                next[step]++;
                takeInWork[frontOf[step]] += B * m;
            }
            solveBranch[s] = m * (m / 2 + B * below[s].length);
            half += solveBranch[s];
        }
        solveWork = half;
        for (int s = 0; s < fronts; s++) {
            if (below[s].length > 0) {
                solveBranch[frontOf[below[s][0]]] += solveBranch[s];
            }
        }
    }

    /** Thrown when the matrix is not positive definite: something in the frame can move freely. */
    static final class SingularException extends Exception {
        private static final long serialVersionUID = 1L;

        /** The node, in the caller's numbering, where elimination broke down. */
        final int node;

        SingularException(int node) {
            super("the stiffness matrix is singular at node " + node);
            this.node = node;
        }
    }

    /**
     * A symmetric matrix of 6 by 6 blocks: one diagonal block per node and one block per pair of joined nodes.
     *
     * @param diagonal the diagonal blocks, 36 numbers each in row-major order
     * @param pairs the joined pairs as two node indices each, the first smaller than the second
     * @param offDiagonal for each pair (a, b), the block in row a and column b, row-major; the block in row b and
     *     column a is its transpose
     */
    record Matrix(double[][] diagonal, int[] pairs, double[][] offDiagonal) {
    }

    /** Returns the pool for so many threads, or {@code null} for one, which works alone. */
    private static ForkJoinPool pool(int threads) { // determinism-ok: as the import says
        return threads <= 1 ? null : POOLS.computeIfAbsent(threads, ForkJoinPool::new); // determinism-ok: as above
    }

    /**
     * Factorizes a matrix on the calling thread.
     *
     * @param matrix the matrix
     * @param x the x position of each node
     * @param y the y position of each node
     * @param z the z position of each node
     * @return the factorization
     * @throws SingularException if the matrix is not positive definite
     */
    static BlockCholesky factor(Matrix matrix, int[] x, int[] y, int[] z) throws SingularException {
        return factor(matrix, x, y, z, 1);
    }

    /**
     * Factorizes a matrix, sharing the work among threads if it is large enough to gain from that, as do the
     * factorization's solves. The factor and the solutions are the same to the bit however many threads share them.
     *
     * @param matrix the matrix
     * @param x the x position of each node
     * @param y the y position of each node
     * @param z the z position of each node
     * @param threads how many threads may share the work; with one, it is done on the calling thread
     * @return the factorization
     * @throws SingularException if the matrix is not positive definite
     */
    static BlockCholesky factor(Matrix matrix, int[] x, int[] y, int[] z, int threads) throws SingularException {
        int n = matrix.diagonal().length;
        int[] perm = dissect(x, y, z);
        int[] inverse = new int[n];
        for (int p = 0; p < n; p++) {
            inverse[perm[p]] = p;
        }
        // The lower triangle in elimination order: for each column, the rows below it and their blocks.
        int[] count = new int[n];
        int[] pairs = matrix.pairs();
        int m = pairs.length / 2;
        for (int e = 0; e < m; e++) {
            int p = inverse[pairs[2 * e]];
            int q = inverse[pairs[2 * e + 1]];
            count[Math.min(p, q)]++;
        }
        int[][] lowerRows = new int[n][];
        double[][][] lowerBlocks = new double[n][][];
        for (int c = 0; c < n; c++) {
            lowerRows[c] = new int[count[c]];
            lowerBlocks[c] = new double[count[c]][];
        }
        Arrays.fill(count, 0);
        for (int e = 0; e < m; e++) {
            int p = inverse[pairs[2 * e]];
            int q = inverse[pairs[2 * e + 1]];
            double[] block = matrix.offDiagonal()[e];
            int col = Math.min(p, q);
            int row = Math.max(p, q);
            // The stored block sits in row a, column b; the lower triangle wants row "row", column "col".
            double[] lower = p > q ? block : transpose(block);
            lowerRows[col][count[col]] = row;
            lowerBlocks[col][count[col]] = lower;
            count[col]++;
        }
        int[][] pattern = symbolic(n, lowerRows);
        long fill = 0;
        for (int[] rows : pattern) {
            fill += rows.length;
        }

        // Fronts: a step may join the front of the step before it when it is that step's parent in the
        // elimination tree. The front then holds the last step's pattern below it for all its columns, which
        // may be zeros for the earlier ones. Small fronts always join; larger ones while zeros stay few.
        int[] frontOf = new int[n];
        int[] starts = new int[n + 1];
        int fronts = 0;
        long zeros = 0;
        int size = 0;
        for (int j = 0; j < n; j++) {
            boolean join = false;
            long joinedZeros = 0;
            if (j > 0 && pattern[j - 1].length > 0 && pattern[j - 1][0] == j) {
                // Every earlier column of the front gains the rows this step brings that the last one lacked.
                joinedZeros = zeros + (long) size * (pattern[j].length - pattern[j - 1].length + 1);
                long entries = (long) (size + 1) * (size + 2) / 2 + (long) (size + 1) * pattern[j].length;
                join = size + 1 <= SMALL_FRONT || joinedZeros <= ZEROS * entries;
            }
            if (join) {
                zeros = joinedZeros;
                size++;
            } else {
                starts[fronts++] = j;
                zeros = 0;
                size = 1;
            }
            frontOf[j] = fronts - 1;
        }
        starts[fronts] = n;
        int[] start = Arrays.copyOf(starts, fronts + 1);
        int[][] below = new int[fronts][];
        for (int s = 0; s < fronts; s++) {
            below[s] = pattern[start[s + 1] - 1];
        }
        int[] childHead = new int[fronts];
        int[] childNext = new int[fronts];
        Arrays.fill(childHead, -1);
        for (int s = fronts - 1; s >= 0; s--) {
            if (below[s].length > 0) {
                int parent = frontOf[below[s][0]];
                childNext[s] = childHead[parent];
                childHead[parent] = s;
            }
        }

        Elimination job = new Elimination(matrix, perm, start, below, lowerRows, lowerBlocks, childHead, childNext);
        double total = 0;
        for (int s = 0; s < fronts; s++) {
            total += job.branch[s];
        }
        // Children come before their parents, so each front's branch is whole by the time it is added to its parent.
        for (int s = 0; s < fronts; s++) {
            if (below[s].length > 0) {
                job.branch[frontOf[below[s][0]]] += job.branch[s];
            }
        }
        if (threads <= 1 || total < SHARED_WORK) {
            for (int s = 0; s < fronts; s++) {
                if (!job.eliminate(s, false)) {
                    break;
                }
            }
        } else {
            List<Elimination.Branch> roots = new ArrayList<>();
            for (int s = 0; s < fronts; s++) {
                if (below[s].length == 0) {
                    roots.add(job.new Branch(s));
                }
            }
            pool(threads).invoke(new RecursiveAction() {
                private static final long serialVersionUID = 1L;

                @Override
                protected void compute() {
                    ForkJoinTask.invokeAll(roots);
                }
            });
        }
        if (job.failedFront < fronts) {
            throw new SingularException(perm[start[job.failedFront] + job.failedColumn / B]);
        }
        return new BlockCholesky(n, perm, start, below, job.factor, job.stride, fill, total, frontOf, childHead,
                childNext, threads);
    }

    /**
     * The numeric work of a factorization, front by front, each front taking in its children's updates in the same
     * order however the fronts are shared among threads.
     */
    private static final class Elimination {
        private final Matrix matrix;
        private final int[] perm;
        private final int[] start;
        private final int[][] below;
        private final int[][] lowerRows;
        private final double[][][] lowerBlocks;
        private final int[] childHead;
        private final int[] childNext;
        private final double[][] factor;
        private final int[] stride;
        private final double[][] update;
        /** How many multiplications each front takes, then each front's branch: it and every front below it. */
        private final double[] branch;
        /** Whether each front was eliminated; a front whose children were not is not either. */
        private final boolean[] done;
        /** The first front, in elimination order, whose pivot was not positive, or more than any if none. */
        private int failedFront = Integer.MAX_VALUE;
        /** The column of that front where elimination broke down. */
        private int failedColumn = -1;

        Elimination(Matrix matrix, int[] perm, int[] start, int[][] below, int[][] lowerRows,
                double[][][] lowerBlocks, int[] childHead, int[] childNext) {
            this.matrix = matrix;
            this.perm = perm;
            this.start = start;
            this.below = below;
            this.lowerRows = lowerRows;
            this.lowerBlocks = lowerBlocks;
            this.childHead = childHead;
            this.childNext = childNext;
            int fronts = below.length;
            factor = new double[fronts][];
            stride = new int[fronts];
            update = new double[fronts][];
            done = new boolean[fronts];
            branch = new double[fronts];
            for (int s = 0; s < fronts; s++) {
                double m = B * (start[s + 1] - start[s]);
                double d = m + B * below[s].length;
                branch[s] = m * (d * d - m * d + m * m / 3);
            }
        }

        /** Returns where an elimination step lies in a front: among its own steps, then among the steps below it. */
        private int local(int s, int step) {
            int columns = start[s + 1] - start[s];
            return step < start[s + 1] ? step - start[s] : columns + Arrays.binarySearch(below[s], step);
        }

        /**
         * Eliminates one front once its children are: assembles it from the matrix and their updates, factorizes its
         * own columns and keeps what that leaves for the front above. Returns whether it was eliminated.
         *
         * @param shared whether this runs in a pool, which may then share a large update among its threads
         */
        boolean eliminate(int s, boolean shared) {
            for (int child = childHead[s]; child != -1; child = childNext[child]) {
                if (!done[child]) {
                    return false;
                }
            }
            int first = start[s];
            int columns = start[s + 1] - first;
            int[] rows = below[s];
            int d = B * (columns + rows.length);
            int eliminated = B * columns;
            double[] f = new double[d * d];
            for (int t = 0; t < columns; t++) {
                int c = first + t;
                addLower(f, d, t, t, matrix.diagonal()[perm[c]]);
                for (int e = 0; e < lowerRows[c].length; e++) {
                    addLower(f, d, local(s, lowerRows[c][e]), t, lowerBlocks[c][e]);
                }
            }
            for (int child = childHead[s]; child != -1; child = childNext[child]) {
                int[] at = new int[below[child].length];
                for (int p = 0; p < at.length; p++) {
                    at[p] = B * local(s, below[child][p]);
                }
                extendAdd(f, d, update[child], at);
                update[child] = null;
            }
            int failed = partialCholesky(f, d, eliminated, shared);
            if (failed >= 0) {
                fail(s, failed);
                return false;
            }
            if (rows.length == 0) {
                factor[s] = f;
                stride[s] = d;
            } else {
                double[] l = new double[d * eliminated];
                for (int i = 0; i < d; i++) {
                    System.arraycopy(f, i * d, l, i * eliminated, Math.min(eliminated, i + 1));
                }
                factor[s] = l;
                stride[s] = eliminated;
                int du = d - eliminated;
                double[] u = new double[du * du];
                for (int i = 0; i < du; i++) {
                    System.arraycopy(f, (eliminated + i) * d + eliminated, u, i * du, i + 1);
                }
                update[s] = u;
            }
            done[s] = true;
            return true;
        }

        /** Notes that a front broke down, keeping the first in elimination order, as working alone would find. */
        private synchronized void fail(int s, int column) {
            if (s < failedFront) {
                failedFront = s;
                failedColumn = column;
            }
        }

        /** Eliminates a whole branch on the calling thread, front after front in elimination order. */
        private void eliminateBranch(int root) {
            for (int s : frontsOf(root, childHead, childNext)) {
                eliminate(s, true);
            }
        }

        /** A branch of the elimination tree as a task: its large child branches are forked, the rest done here. */
        private final class Branch extends RecursiveAction {
            private static final long serialVersionUID = 1L;
            private final int root;

            Branch(int root) {
                this.root = root;
            }

            @Override
            protected void compute() {
                List<Branch> forked = new ArrayList<>();
                for (int child = childHead[root]; child != -1; child = childNext[child]) {
                    if (branch[child] >= BRANCH_WORK) {
                        Branch task = new Branch(child);
                        task.fork();
                        forked.add(task);
                    }
                }
                for (int child = childHead[root]; child != -1; child = childNext[child]) {
                    if (branch[child] < BRANCH_WORK) {
                        eliminateBranch(child);
                    }
                }
                for (int k = forked.size() - 1; k >= 0; k--) {
                    forked.get(k).join();
                }
                eliminate(root, true);
            }
        }
    }

    /** Adds a 6 by 6 block's lower triangle, or all of it if it lies below the diagonal, at node (row, col). */
    private static void addLower(double[] f, int d, int row, int col, double[] block) {
        for (int a = 0; a < B; a++) {
            int target = (B * row + a) * d + B * col;
            int last = row == col ? a : B - 1;
            for (int b = 0; b <= last; b++) {
                f[target + b] += block[a * B + b];
            }
        }
    }

    /**
     * Adds what a front left over, over the steps below it, into the front above it.
     *
     * @param at where each step below the front lies in the front above, in degrees of freedom
     */
    private static void extendAdd(double[] f, int d, double[] update, int[] at) {
        int du = B * at.length;
        for (int p = 0; p < at.length; p++) {
            for (int a = 0; a < B; a++) {
                int source = (B * p + a) * du;
                int target = (at[p] + a) * d;
                for (int q = 0; q < p; q++) {
                    int from = source + B * q;
                    int to = target + at[q];
                    for (int b = 0; b < B; b++) {
                        f[to + b] += update[from + b];
                    }
                }
                int from = source + B * p;
                int to = target + at[p];
                for (int b = 0; b <= a; b++) {
                    f[to + b] += update[from + b];
                }
            }
        }
    }

    /**
     * Eliminates the first {@code m} columns of a symmetric matrix kept in its lower triangle, row-major with
     * {@code d} numbers to a row: those columns become the Cholesky factor's, and the rest of the matrix what
     * elimination leaves of it. Works a panel of columns at a time, so the bulk of the work is one dense update.
     *
     * @return -1, or the column whose pivot was not positive
     */
    static int partialCholesky(double[] f, int d, int m) {
        return partialCholesky(f, d, m, false);
    }

    /**
     * Eliminates columns as {@link #partialCholesky(double[], int, int)} does, sharing each large update among the
     * threads of the pool this runs in, if it is shared.
     */
    private static int partialCholesky(double[] f, int d, int m, boolean shared) {
        double[] panel = new double[d * Math.min(PANEL, Math.max(m, 1))];
        for (int p = 0; p < m; p += PANEL) {
            int q = Math.min(p + PANEL, m);
            int w = q - p;
            int first = p;
            // The panel's own rows, column by column.
            for (int k = p; k < q; k++) {
                int rk = k * d;
                double s = f[rk + k];
                for (int c = p; c < k; c++) {
                    s -= f[rk + c] * f[rk + c];
                }
                if (!(s > 0)) {
                    return k;
                }
                double root = Math.sqrt(s);
                f[rk + k] = root;
                for (int i = k + 1; i < q; i++) {
                    int ri = i * d;
                    double t = f[ri + k];
                    for (int c = p; c < k; c++) {
                        t -= f[ri + c] * f[rk + c];
                    }
                    f[ri + k] = t / root;
                }
            }
            // The rows below it, each against the panel's own rows; every row on its own, so they may be shared.
            if (shared && (double) (d - q) * w * w >= BAND_WORK) {
                inBands(d - q, false, (lo, hi) -> panelRows(f, d, first, q, q + lo, q + hi));
            } else {
                panelRows(f, d, p, q, q, d);
            }
            int rest = d - q;
            if (rest == 0) {
                continue;
            }
            for (int i = 0; i < rest; i++) {
                System.arraycopy(f, (q + i) * d + p, panel, i * w, w);
            }
            if (shared && 0.5 * rest * rest * w >= BAND_WORK) {
                inBands(rest, true, (lo, hi) -> update(f, d, q, panel, w, lo, hi));
            } else {
                update(f, d, q, panel, w, 0, rest);
            }
        }
        return -1;
    }

    /**
     * Works out the panel's columns from {@code p} up to {@code q} in rows {@code from} up to {@code to}, all below
     * those columns' diagonal, given the panel's own rows: each row's entry in a column is what is left of it once its
     * entries in the panel's earlier columns, times the column's row, are taken off, over the column's diagonal. Four
     * rows at a time, in four independent sums, each adding its products in the same order.
     */
    private static void panelRows(double[] f, int d, int p, int q, int from, int to) {
        int i = from;
        for (; i + 3 < to; i += 4) {
            int r0 = i * d;
            int r1 = r0 + d;
            int r2 = r1 + d;
            int r3 = r2 + d;
            for (int k = p; k < q; k++) {
                int rk = k * d;
                double t0 = f[r0 + k];
                double t1 = f[r1 + k];
                double t2 = f[r2 + k];
                double t3 = f[r3 + k];
                for (int c = p; c < k; c++) {
                    double v = f[rk + c];
                    t0 -= f[r0 + c] * v;
                    t1 -= f[r1 + c] * v;
                    t2 -= f[r2 + c] * v;
                    t3 -= f[r3 + c] * v;
                }
                double root = f[rk + k];
                f[r0 + k] = t0 / root;
                f[r1 + k] = t1 / root;
                f[r2 + k] = t2 / root;
                f[r3 + k] = t3 / root;
            }
        }
        for (; i < to; i++) {
            int ri = i * d;
            for (int k = p; k < q; k++) {
                int rk = k * d;
                double t = f[ri + k];
                for (int c = p; c < k; c++) {
                    t -= f[ri + c] * f[rk + c];
                }
                f[ri + k] = t / f[rk + k];
            }
        }
    }

    /** Work on a band of rows, from {@code lo} up to {@code hi}. */
    private interface Band {
        void work(int lo, int hi);
    }

    /**
     * Does work on rows 0 up to {@code rows} in bands shared among the threads of the pool this runs in. Each row is
     * worked out as it would be in one go.
     *
     * @param triangle whether a row's work grows with its number, as in a triangle, rather than staying the same;
     *     the bands then get about equal shares of the triangle
     */
    private static void inBands(int rows, boolean triangle, Band band) {
        int parts = Math.max(2, 2 * ForkJoinTask.getPool().getParallelism());
        List<RecursiveAction> bands = new ArrayList<>();
        int from = 0;
        for (int k = 1; k <= parts && from < rows; k++) {
            double share = triangle ? Math.sqrt((double) k / parts) : (double) k / parts;
            int to = k == parts ? rows : Math.min(rows, (int) (rows * share));
            if (to > from) {
                int lo = from;
                int hi = to;
                bands.add(new RecursiveAction() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    protected void compute() {
                        band.work(lo, hi);
                    }
                });
                from = to;
            }
        }
        ForkJoinTask.invokeAll(bands);
    }

    /**
     * Subtracts the product of the panel with its own transpose from the lower triangle of the matrix's trailing
     * block, which starts at row and column {@code q}, in its rows from {@code from} up to {@code to}. Four rows by
     * two columns at a time, in eight independent sums, which keeps the processor busy; each entry still adds its
     * products in one fixed order, from the panel's first column, however the rows are grouped.
     */
    private static void update(double[] f, int d, int q, double[] panel, int w, int from, int to) {
        int i = from;
        for (; i + 3 < to; i += 4) {
            int a0 = i * w;
            int a1 = a0 + w;
            int a2 = a1 + w;
            int a3 = a2 + w;
            int j = 0;
            for (; j + 1 <= i; j += 2) {
                int b0 = j * w;
                int b1 = b0 + w;
                double s00 = 0;
                double s01 = 0;
                double s10 = 0;
                double s11 = 0;
                double s20 = 0;
                double s21 = 0;
                double s30 = 0;
                double s31 = 0;
                for (int c = 0; c < w; c++) {
                    double x0 = panel[b0 + c];
                    double x1 = panel[b1 + c];
                    double y0 = panel[a0 + c];
                    double y1 = panel[a1 + c];
                    double y2 = panel[a2 + c];
                    double y3 = panel[a3 + c];
                    s00 += y0 * x0;
                    s01 += y0 * x1;
                    s10 += y1 * x0;
                    s11 += y1 * x1;
                    s20 += y2 * x0;
                    s21 += y2 * x1;
                    s30 += y3 * x0;
                    s31 += y3 * x1;
                }
                int r = (q + i) * d + q + j;
                f[r] -= s00;
                f[r + 1] -= s01;
                r += d;
                f[r] -= s10;
                f[r + 1] -= s11;
                r += d;
                f[r] -= s20;
                f[r + 1] -= s21;
                r += d;
                f[r] -= s30;
                f[r + 1] -= s31;
            }
            for (int row = i; row < i + 4; row++) {
                for (int col = j; col <= row; col++) {
                    f[(q + row) * d + q + col] -= dot(panel, row * w, col * w, w);
                }
            }
        }
        for (; i < to; i++) {
            for (int col = 0; col <= i; col++) {
                f[(q + i) * d + q + col] -= dot(panel, i * w, col * w, w);
            }
        }
    }

    private static double dot(double[] v, int a, int b, int length) {
        double s = 0;
        for (int c = 0; c < length; c++) {
            s += v[a + c] * v[b + c];
        }
        return s;
    }

    /**
     * Solves the factorized system in place.
     *
     * @param b the right-hand side, six numbers per node in the caller's numbering; replaced by the solution
     */
    void solve(double[] b) {
        double[] y = permuted(b);
        forwardInPlace(y);
        backwardInPlace(y);
        unpermute(y, b);
    }

    /**
     * Applies the first half of a solve: with the matrix factorized as Pᵀ L Lᵀ P, returns L⁻¹ P b. The two halves
     * turn the generalized eigenproblems of a frame into ordinary symmetric ones.
     *
     * @param b six numbers per node in the caller's numbering, left as they are
     * @return six numbers per elimination step
     */
    double[] forward(double[] b) {
        double[] y = permuted(b);
        forwardInPlace(y);
        return y;
    }

    /**
     * Applies the second half of a solve: returns Pᵀ L⁻ᵀ z.
     *
     * @param z six numbers per elimination step, left as they are
     * @return six numbers per node in the caller's numbering
     */
    double[] backward(double[] z) {
        double[] y = z.clone();
        backwardInPlace(y);
        double[] x = new double[B * n];
        unpermute(y, x);
        return x;
    }

    private double[] permuted(double[] b) {
        double[] y = new double[B * n];
        for (int p = 0; p < n; p++) {
            System.arraycopy(b, B * perm[p], y, B * p, B);
        }
        return y;
    }

    private void unpermute(double[] y, double[] x) {
        for (int p = 0; p < n; p++) {
            System.arraycopy(y, B * p, x, B * perm[p], B);
        }
    }

    /**
     * Solves L z = y in place, front by front: each takes in what the fronts below it pass on, then solves its own
     * triangle. Each number takes in what it is passed in elimination order, however the fronts are shared among
     * threads.
     */
    private void forwardInPlace(double[] y) {
        if (pool == null || solveWork < SHARED_SOLVE) {
            for (int s = 0; s < factor.length; s++) {
                forwardFront(y, s, false);
            }
        } else {
            shareFromRoots(s -> new Forward(y, s));
        }
    }

    /** Solves Lᵀ x = z in place, fronts in reverse: each takes in the steps below it, then solves its own triangle. */
    private void backwardInPlace(double[] y) {
        if (pool == null || solveWork < SHARED_SOLVE) {
            for (int s = factor.length - 1; s >= 0; s--) {
                backwardFront(y, s, false);
            }
        } else {
            shareFromRoots(s -> new Backward(y, s));
        }
    }

    /** Runs a task for each root of the tree of fronts in the pool, and waits for them all. */
    private void shareFromRoots(IntFunction<RecursiveAction> task) {
        List<RecursiveAction> roots = new ArrayList<>();
        for (int s = 0; s < factor.length; s++) {
            if (below[s].length == 0) {
                roots.add(task.apply(s));
            }
        }
        pool.invoke(new RecursiveAction() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void compute() {
                ForkJoinTask.invokeAll(roots);
            }
        });
    }

    /**
     * Does a front's part of the first half of a solve once the fronts below it have done theirs.
     *
     * @param shared whether this runs in a pool, which may then share the front's steps among its threads
     */
    private void forwardFront(double[] y, int s, boolean shared) {
        int first = start[s];
        int last = start[s + 1];
        if (shared && takeInWork[s] >= BAND_SOLVE) {
            inBands(last - first, false, (lo, hi) -> takeIn(y, first + lo, first + hi));
        } else {
            takeIn(y, first, last);
        }
        double[] l = factor[s];
        int w = stride[s];
        int base = B * first;
        int m = B * (last - first);
        for (int i = 0; i < m; i++) {
            int ri = i * w;
            double t = y[base + i];
            for (int c = 0; c < i; c++) {
                t -= l[ri + c] * y[base + c];
            }
            y[base + i] = t / l[ri + i];
        }
    }

    /**
     * Takes off the steps from {@code from} up to {@code to} what each front below them passes on, the front's own
     * solved numbers times its rows of the factor for the step, front after front in elimination order. A step's
     * six rows at a time, in six independent sums, each adding its products in the same order.
     */
    private void takeIn(double[] y, int from, int to) {
        for (int p = from; p < to; p++) {
            int target = B * p;
            for (int k = incomingStart[p]; k < incomingStart[p + 1]; k++) {
                int s = incomingFront[k];
                double[] l = factor[s];
                int w = stride[s];
                int base = B * start[s];
                int m = B * (start[s + 1] - start[s]);
                int r0 = (m + B * incomingRow[k]) * w;
                int r1 = r0 + w;
                int r2 = r1 + w;
                int r3 = r2 + w;
                int r4 = r3 + w;
                int r5 = r4 + w;
                double t0 = 0;
                double t1 = 0;
                double t2 = 0;
                double t3 = 0;
                double t4 = 0;
                double t5 = 0;
                for (int c = 0; c < m; c++) {
                    double v = y[base + c];
                    t0 += l[r0 + c] * v;
                    t1 += l[r1 + c] * v;
                    t2 += l[r2 + c] * v;
                    t3 += l[r3 + c] * v;
                    t4 += l[r4 + c] * v;
                    t5 += l[r5 + c] * v;
                }
                y[target] -= t0;
                y[target + 1] -= t1;
                y[target + 2] -= t2;
                y[target + 3] -= t3;
                y[target + 4] -= t4;
                y[target + 5] -= t5;
            }
        }
    }

    /**
     * Does a front's part of the second half of a solve once the fronts above it have done theirs.
     *
     * @param shared whether this runs in a pool, which may then share the front's columns among its threads
     */
    private void backwardFront(double[] y, int s, boolean shared) {
        int m = B * (start[s + 1] - start[s]);
        if (shared && (double) m * B * below[s].length >= BAND_SOLVE) {
            inBands(m, false, (lo, hi) -> takeOut(y, s, lo, hi));
        } else {
            takeOut(y, s, 0, m);
        }
        double[] l = factor[s];
        int w = stride[s];
        int base = B * start[s];
        for (int i = m - 1; i >= 0; i--) {
            double t = y[base + i];
            for (int c = i + 1; c < m; c++) {
                t -= l[c * w + i] * y[base + c];
            }
            y[base + i] = t / l[i * w + i];
        }
    }

    /**
     * Takes off a front's own numbers, from {@code from} up to {@code to}, what the solved steps below it give
     * through its rows of the factor. A step's six rows at once: each number still takes them off one after
     * another, in the same order.
     */
    private void takeOut(double[] y, int s, int from, int to) {
        double[] l = factor[s];
        int w = stride[s];
        int base = B * start[s];
        int m = B * (start[s + 1] - start[s]);
        int[] rows = below[s];
        for (int r = 0; r < rows.length; r++) {
            int source = B * rows[r];
            int r0 = (m + B * r) * w;
            int r1 = r0 + w;
            int r2 = r1 + w;
            int r3 = r2 + w;
            int r4 = r3 + w;
            int r5 = r4 + w;
            double v0 = y[source];
            double v1 = y[source + 1];
            double v2 = y[source + 2];
            double v3 = y[source + 3];
            double v4 = y[source + 4];
            double v5 = y[source + 5];
            for (int c = from; c < to; c++) {
                double t = y[base + c];
                t -= l[r0 + c] * v0;
                t -= l[r1 + c] * v1;
                t -= l[r2 + c] * v2;
                t -= l[r3 + c] * v3;
                t -= l[r4 + c] * v4;
                t -= l[r5 + c] * v5;
                y[base + c] = t;
            }
        }
    }

    /**
     * Returns the fronts of a branch of the tree of fronts in elimination order, which puts every front after the
     * fronts below it.
     */
    private static int[] frontsOf(int root, int[] childHead, int[] childNext) {
        int[] fronts = new int[64];
        int size = 0;
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            int s = stack.pop();
            if (size == fronts.length) {
                fronts = Arrays.copyOf(fronts, 2 * size);
            }
            fronts[size++] = s;
            for (int child = childHead[s]; child != -1; child = childNext[child]) {
                stack.push(child);
            }
        }
        fronts = Arrays.copyOf(fronts, size);
        Arrays.sort(fronts);
        return fronts;
    }

    /** A branch's part of the first half of a solve: its large child branches are forked, the rest done here. */
    private final class Forward extends RecursiveAction {
        private static final long serialVersionUID = 1L;
        private final double[] y;
        private final int root;

        Forward(double[] y, int root) {
            this.y = y;
            this.root = root;
        }

        @Override
        protected void compute() {
            List<Forward> forked = new ArrayList<>();
            for (int child = childHead[root]; child != -1; child = childNext[child]) {
                if (solveBranch[child] >= BRANCH_SOLVE) {
                    Forward task = new Forward(y, child);
                    task.fork();
                    forked.add(task);
                }
            }
            for (int child = childHead[root]; child != -1; child = childNext[child]) {
                if (solveBranch[child] < BRANCH_SOLVE) {
                    for (int s : frontsOf(child, childHead, childNext)) {
                        forwardFront(y, s, false);
                    }
                }
            }
            for (int k = forked.size() - 1; k >= 0; k--) {
                forked.get(k).join();
            }
            forwardFront(y, root, true);
        }
    }

    /** A branch's part of the second half of a solve: its root first, then its child branches, the large forked. */
    private final class Backward extends RecursiveAction {
        private static final long serialVersionUID = 1L;
        private final double[] y;
        private final int root;

        Backward(double[] y, int root) {
            this.y = y;
            this.root = root;
        }

        @Override
        protected void compute() {
            backwardFront(y, root, true);
            List<Backward> forked = new ArrayList<>();
            for (int child = childHead[root]; child != -1; child = childNext[child]) {
                if (solveBranch[child] >= BRANCH_SOLVE) {
                    Backward task = new Backward(y, child);
                    task.fork();
                    forked.add(task);
                }
            }
            for (int child = childHead[root]; child != -1; child = childNext[child]) {
                if (solveBranch[child] < BRANCH_SOLVE) {
                    int[] fronts = frontsOf(child, childHead, childNext);
                    for (int k = fronts.length - 1; k >= 0; k--) {
                        backwardFront(y, fronts[k], false);
                    }
                }
            }
            for (int k = forked.size() - 1; k >= 0; k--) {
                forked.get(k).join();
            }
        }
    }

    /**
     * Multiplies a vector by a block matrix.
     *
     * @param matrix the matrix
     * @param x six numbers per node
     * @return the product, six numbers per node
     */
    static double[] multiply(Matrix matrix, double[] x) {
        double[] y = new double[x.length];
        double[][] diagonal = matrix.diagonal();
        for (int a = 0; a < diagonal.length; a++) {
            addProduct(diagonal[a], false, x, B * a, y, B * a);
        }
        int[] pairs = matrix.pairs();
        double[][] off = matrix.offDiagonal();
        for (int e = 0; e < off.length; e++) {
            int a = pairs[2 * e];
            int b = pairs[2 * e + 1];
            addProduct(off[e], false, x, B * b, y, B * a);
            addProduct(off[e], true, x, B * a, y, B * b);
        }
        return y;
    }

    /** Adds a 6 by 6 block, or its transpose, times six numbers of x to six numbers of y. */
    private static void addProduct(double[] block, boolean transposed, double[] x, int from, double[] y, int to) {
        for (int r = 0; r < B; r++) {
            double sum = 0;
            for (int c = 0; c < B; c++) {
                sum += (transposed ? block[c * B + r] : block[r * B + c]) * x[from + c];
            }
            y[to + r] += sum;
        }
    }

    /**
     * Returns how many off-diagonal blocks the factor holds, a measure of its cost.
     *
     * @return the number of blocks
     */
    long fill() {
        return fill;
    }

    /**
     * Returns how many multiplications the factorization took.
     *
     * @return the number of multiplications
     */
    double work() {
        return work;
    }

    /**
     * Returns how many multiplications half a solve takes.
     *
     * @return the number of multiplications
     */
    double solveWork() {
        return solveWork;
    }

    /**
     * Works out which blocks of the factor are not zero: the rows of each column are its rows in the matrix plus
     * those of the columns it absorbs as their parent in the elimination tree.
     */
    private static int[][] symbolic(int n, int[][] lowerRows) {
        int[][] pattern = new int[n][];
        int[] childHead = new int[n];
        int[] childNext = new int[n];
        Arrays.fill(childHead, -1);
        int[] mark = new int[n];
        Arrays.fill(mark, -1);
        int[] scratch = new int[n];
        for (int j = 0; j < n; j++) {
            int size = 0;
            mark[j] = j;
            for (int r : lowerRows[j]) {
                if (mark[r] != j) {
                    mark[r] = j;
                    scratch[size++] = r;
                }
            }
            for (int c = childHead[j]; c != -1; c = childNext[c]) {
                for (int r : pattern[c]) {
                    if (mark[r] != j) {
                        mark[r] = j;
                        scratch[size++] = r;
                    }
                }
            }
            int[] rows = Arrays.copyOf(scratch, size);
            Arrays.sort(rows);
            pattern[j] = rows;
            if (size > 0) {
                int parent = rows[0];
                childNext[j] = childHead[parent];
                childHead[parent] = j;
            }
        }
        return pattern;
    }

    /** Orders nodes by nested dissection of their positions; returns, for each elimination step, the node. */
    static int[] dissect(int[] x, int[] y, int[] z) {
        int n = x.length;
        int[] index = new int[n];
        for (int i = 0; i < n; i++) {
            index[i] = i;
        }
        int[] order = new int[n];
        int[] filled = {0};
        dissect(index, 0, n, new int[][] {x, y, z}, order, filled, new int[n]);
        return order;
    }

    private static void dissect(int[] index, int from, int to, int[][] coords, int[] order, int[] filled,
            int[] scratch) {
        int size = to - from;
        if (size <= LEAF) {
            appendSorted(index, from, to, order, filled);
            return;
        }
        int axis = -1;
        int extent = 0;
        for (int a = 0; a < 3; a++) {
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (int i = from; i < to; i++) {
                int v = coords[a][index[i]];
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            if (hi - lo > extent) {
                extent = hi - lo;
                axis = a;
            }
        }
        if (axis < 0) {
            appendSorted(index, from, to, order, filled);
            return;
        }
        int[] values = new int[size];
        for (int i = 0; i < size; i++) {
            values[i] = coords[axis][index[from + i]];
        }
        Arrays.sort(values);
        int cut = values[size / 2];
        int left = from;
        for (int i = from; i < to; i++) {
            if (coords[axis][index[i]] < cut) {
                scratch[left++] = index[i];
            }
        }
        int right = left;
        for (int i = from; i < to; i++) {
            if (coords[axis][index[i]] > cut) {
                scratch[right++] = index[i];
            }
        }
        int plane = right;
        for (int i = from; i < to; i++) {
            if (coords[axis][index[i]] == cut) {
                scratch[plane++] = index[i];
            }
        }
        System.arraycopy(scratch, from, index, from, size);
        dissect(index, from, left, coords, order, filled, scratch);
        dissect(index, left, right, coords, order, filled, scratch);
        appendSorted(index, right, to, order, filled);
    }

    private static void appendSorted(int[] index, int from, int to, int[] order, int[] filled) {
        Arrays.sort(index, from, to);
        for (int i = from; i < to; i++) {
            order[filled[0]++] = index[i];
        }
    }

    private static double[] transpose(double[] m) {
        double[] t = new double[BB];
        for (int a = 0; a < B; a++) {
            for (int c = 0; c < B; c++) {
                t[c * B + a] = m[a * B + c];
            }
        }
        return t;
    }
}
