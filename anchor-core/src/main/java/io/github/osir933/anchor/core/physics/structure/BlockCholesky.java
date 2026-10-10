package io.github.osir933.anchor.core.physics.structure;

import java.util.Arrays;

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

    private BlockCholesky(int n, int[] perm, int[] start, int[][] below, double[][] factor, int[] stride,
            long fill) {
        this.n = n;
        this.perm = perm;
        this.start = start;
        this.below = below;
        this.factor = factor;
        this.stride = stride;
        this.fill = fill;
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

    /**
     * Factorizes a matrix.
     *
     * @param matrix the matrix
     * @param x the x position of each node
     * @param y the y position of each node
     * @param z the z position of each node
     * @return the factorization
     * @throws SingularException if the matrix is not positive definite
     */
    static BlockCholesky factor(Matrix matrix, int[] x, int[] y, int[] z) throws SingularException {
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

        double[][] factor = new double[fronts][];
        int[] stride = new int[fronts];
        double[][] update = new double[fronts][];
        int[] local = new int[n];
        for (int s = 0; s < fronts; s++) {
            int first = start[s];
            int columns = start[s + 1] - first;
            int[] rows = below[s];
            int d = B * (columns + rows.length);
            int eliminated = B * columns;
            double[] f = new double[d * d];
            for (int t = 0; t < columns; t++) {
                local[first + t] = t;
            }
            for (int t = 0; t < rows.length; t++) {
                local[rows[t]] = columns + t;
            }
            for (int t = 0; t < columns; t++) {
                int c = first + t;
                addLower(f, d, t, t, matrix.diagonal()[perm[c]]);
                for (int e = 0; e < lowerRows[c].length; e++) {
                    addLower(f, d, local[lowerRows[c][e]], t, lowerBlocks[c][e]);
                }
            }
            for (int child = childHead[s]; child != -1; child = childNext[child]) {
                extendAdd(f, d, update[child], below[child], local);
                update[child] = null;
            }
            int failed = partialCholesky(f, d, eliminated);
            if (failed >= 0) {
                throw new SingularException(perm[first + failed / B]);
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
        }
        return new BlockCholesky(n, perm, start, below, factor, stride, fill);
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

    /** Adds what a front left over, over the steps below it, into the front above it. */
    private static void extendAdd(double[] f, int d, double[] update, int[] rows, int[] local) {
        int du = B * rows.length;
        int[] at = new int[rows.length];
        for (int p = 0; p < rows.length; p++) {
            at[p] = B * local[rows[p]];
        }
        for (int p = 0; p < rows.length; p++) {
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
        double[] panel = new double[d * Math.min(PANEL, Math.max(m, 1))];
        for (int p = 0; p < m; p += PANEL) {
            int q = Math.min(p + PANEL, m);
            int w = q - p;
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
                for (int i = k + 1; i < d; i++) {
                    int ri = i * d;
                    double t = f[ri + k];
                    for (int c = p; c < k; c++) {
                        t -= f[ri + c] * f[rk + c];
                    }
                    f[ri + k] = t / root;
                }
            }
            int rest = d - q;
            if (rest == 0) {
                continue;
            }
            for (int i = 0; i < rest; i++) {
                System.arraycopy(f, (q + i) * d + p, panel, i * w, w);
            }
            update(f, d, q, panel, w, rest);
        }
        return -1;
    }

    /**
     * Subtracts the product of the panel with its own transpose from the lower triangle of the matrix's trailing
     * block, which starts at row and column {@code q}. Four rows by two columns at a time, in eight independent
     * sums, which keeps the processor busy; each entry still adds its products in one fixed order.
     */
    private static void update(double[] f, int d, int q, double[] panel, int w, int rows) {
        int i = 0;
        for (; i + 3 < rows; i += 4) {
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
        for (; i < rows; i++) {
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

    /** Solves L z = y in place, front by front: first its own triangle, then pass the result to the steps below. */
    private void forwardInPlace(double[] y) {
        int fronts = factor.length;
        for (int s = 0; s < fronts; s++) {
            double[] l = factor[s];
            int w = stride[s];
            int base = B * start[s];
            int m = B * (start[s + 1] - start[s]);
            for (int i = 0; i < m; i++) {
                int ri = i * w;
                double t = y[base + i];
                for (int c = 0; c < i; c++) {
                    t -= l[ri + c] * y[base + c];
                }
                y[base + i] = t / l[ri + i];
            }
            int[] rows = below[s];
            for (int r = 0; r < rows.length; r++) {
                int target = B * rows[r];
                for (int a = 0; a < B; a++) {
                    int ri = (m + B * r + a) * w;
                    double t = 0;
                    for (int c = 0; c < m; c++) {
                        t += l[ri + c] * y[base + c];
                    }
                    y[target + a] -= t;
                }
            }
        }
    }

    /** Solves Lᵀ x = z in place, fronts in reverse: first take in the steps below, then the front's own triangle. */
    private void backwardInPlace(double[] y) {
        for (int s = factor.length - 1; s >= 0; s--) {
            double[] l = factor[s];
            int w = stride[s];
            int base = B * start[s];
            int m = B * (start[s + 1] - start[s]);
            int[] rows = below[s];
            for (int r = 0; r < rows.length; r++) {
                int source = B * rows[r];
                for (int a = 0; a < B; a++) {
                    int ri = (m + B * r + a) * w;
                    double v = y[source + a];
                    for (int c = 0; c < m; c++) {
                        y[base + c] -= l[ri + c] * v;
                    }
                }
            }
            for (int i = m - 1; i >= 0; i--) {
                double t = y[base + i];
                for (int c = i + 1; c < m; c++) {
                    t -= l[c * w + i] * y[base + c];
                }
                y[base + i] = t / l[i * w + i];
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
