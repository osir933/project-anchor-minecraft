package io.github.osir933.anchor.core.physics.structure;

import io.github.osir933.anchor.core.math.DeterministicRandom;
import java.util.Arrays;

/**
 * Finds how many times its loads a frame can carry before it buckles, and the shape it buckles into.
 *
 * <p>Forces along a frame's beams stiffen it where they pull and soften it where they press: tilted as the beams
 * turn and bow, they push them further aside. That softening is linear in the forces, a geometric stiffness K_G,
 * so under λ times its loads the frame's stiffness is K + λ K_G, and it buckles where that first stops resisting
 * some movement: (K + λ K_G) φ = 0 for the smallest positive λ, the critical load factor, with φ the shape it bows
 * into.
 *
 * <p>With K already factorized as Pᵀ L Lᵀ P for the frame's linear solution, this is the ordinary symmetric
 * eigenproblem A y = μ y for A = L⁻¹ P (-K_G) Pᵀ L⁻ᵀ, where μ = 1/λ and φ = Pᵀ L⁻ᵀ y. The Lanczos method finds its
 * largest eigenvalue in a few dozen solves with the factor, and no second factorization; every vector it builds is
 * kept orthogonal to all those before it, which is cheap for so few. It starts from a fixed pseudo-random vector,
 * so that every machine finds the same answer, and so that it leans a little toward every way the frame could
 * buckle, which a guess at the shape would not.
 */
final class Buckling {

    /** The Lanczos method takes at most this many steps... */
    static final int MAX_STEPS = 30;

    /** ...and at least this many, unless it has found every way the frame can buckle sooner... */
    static final int MIN_STEPS = 8;

    /** ...and stops once its estimate of the largest eigenvalue is known to this fraction of itself... */
    static final double ACCURACY = 1e-4;

    /**
     * ...or once, after at least {@link #MIN_STEPS} steps, the factor it finds is more than this many times the
     * factor of interest. Its estimate of the largest eigenvalue only grows toward the true one, but after that many
     * steps from a random start it is all but certain to be within a factor of ten: Kuczyński and Woźniakowski
     * (1992) bound the chance of missing by that much at under 1e-4 for frames of tens of thousands of blocks.
     */
    static final double CLEARLY = 10.0;

    /** The seed of the start vector. */
    private static final long SEED = 0x6275636B6C65L;

    private Buckling() {
    }

    /**
     * How a frame buckles.
     *
     * @param factor how many times its loads the frame can carry before it buckles: the critical load factor, or
     *     infinity if no multiple of its loads buckles it
     * @param shape the shape it buckles into, six numbers per node, scaled so that its largest movement is one
     *     metre; {@code null} if it was not asked for
     * @param converged whether the factor is known to {@link #ACCURACY}; if not, it is somewhat too large
     */
    record Mode(double factor, double[] shape, boolean converged) {
    }

    /**
     * Finds the critical load factor of a frame and, if it is low enough to matter, the shape it buckles into.
     *
     * @param stiffness the factorized stiffness matrix of the frame, K
     * @param geometric its geometric stiffness under its loads, K_G
     * @param dofs the number of degrees of freedom, six per node
     * @param interest the factor of interest: the shape is worked out only if the factor is at most this, and the
     *     search stops once the factor is clearly above it
     * @return how it buckles
     */
    static Mode critical(BlockCholesky stiffness, BlockCholesky.Matrix geometric, int dofs, double interest) {
        int steps = Math.min(MAX_STEPS, dofs);
        double[][] q = new double[steps][];
        double[] alpha = new double[steps];
        double[] beta = new double[steps];
        DeterministicRandom random = new DeterministicRandom(SEED);
        double[] start = new double[dofs];
        for (int i = 0; i < dofs; i++) {
            start[i] = 2 * random.nextDouble() - 1;
        }
        scale(start, 1 / norm(start));
        q[0] = start;
        double top = 0;
        double[] ritz = null;
        boolean converged = false;
        int size = 0;
        for (int j = 0; j < steps; j++) {
            double[] w = apply(stiffness, geometric, q[j]);
            alpha[j] = dot(q[j], w);
            add(w, -alpha[j], q[j]);
            if (j > 0) {
                add(w, -beta[j - 1], q[j - 1]);
            }
            // Rounding lets the vectors drift out of orthogonality; taking out what is left of each twice restores it.
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i <= j; i++) {
                    add(w, -dot(q[i], w), q[i]);
                }
            }
            beta[j] = norm(w);
            size = j + 1;
            double[][] vectors = new double[size][size];
            double[] values = eigen(tridiagonal(alpha, beta, size), vectors);
            int k = 0;
            double spread = 0;
            for (int i = 0; i < size; i++) {
                if (values[i] > values[k]) {
                    k = i;
                }
                spread = Math.max(spread, Math.abs(values[i]));
            }
            top = values[k];
            ritz = new double[size];
            for (int i = 0; i < size; i++) {
                ritz[i] = vectors[i][k];
            }
            // A step that adds nothing new means the vectors span every shape the frame can buckle into.
            boolean exhausted = beta[j] <= 1e-12 * spread || size == dofs;
            boolean enough = size >= Math.min(MIN_STEPS, dofs);
            converged = exhausted || enough && Math.abs(beta[j] * ritz[size - 1]) <= ACCURACY * Math.abs(top);
            if (converged || size == steps || enough && top * interest * CLEARLY < 1) {
                break;
            }
            scale(w, 1 / beta[j]);
            q[j + 1] = w;
        }
        double factor = top > 0 ? 1 / top : Double.POSITIVE_INFINITY;
        if (!(factor <= interest)) {
            return new Mode(factor, null, converged);
        }
        double[] y = new double[dofs];
        for (int i = 0; i < size; i++) {
            add(y, ritz[i], q[i]);
        }
        double[] shape = stiffness.backward(y);
        double largest = 0;
        double largestTurn = 0;
        for (int i = 0; i < shape.length; i++) {
            if (i % BlockCholesky.B < 3) {
                largest = Math.max(largest, Math.abs(shape[i]));
            } else {
                largestTurn = Math.max(largestTurn, Math.abs(shape[i]));
            }
        }
        scale(shape, 1 / (largest > 0 ? largest : largestTurn));
        return new Mode(factor, shape, converged);
    }

    /** Applies A = L⁻¹ P (-K_G) Pᵀ L⁻ᵀ to a vector. */
    private static double[] apply(BlockCholesky stiffness, BlockCholesky.Matrix geometric, double[] v) {
        double[] g = BlockCholesky.multiply(geometric, stiffness.backward(v));
        scale(g, -1);
        return stiffness.forward(g);
    }

    /** Returns the tridiagonal matrix of the Lanczos method's first steps, dense. */
    private static double[][] tridiagonal(double[] alpha, double[] beta, int size) {
        double[][] t = new double[size][size];
        for (int i = 0; i < size; i++) {
            t[i][i] = alpha[i];
            if (i + 1 < size) {
                t[i][i + 1] = beta[i];
                t[i + 1][i] = beta[i];
            }
        }
        return t;
    }

    /**
     * Finds the eigenvalues and eigenvectors of a small symmetric matrix by Jacobi's method, which makes the same
     * rotations in the same order on every machine.
     *
     * @param a the matrix, which is used up
     * @param vectors filled with the eigenvectors, one per column
     * @return the eigenvalues, in the order of the columns
     */
    static double[] eigen(double[][] a, double[][] vectors) {
        int n = a.length;
        for (int i = 0; i < n; i++) {
            Arrays.fill(vectors[i], 0);
            vectors[i][i] = 1;
        }
        for (int sweep = 0; sweep < 64; sweep++) {
            double off = 0;
            double all = 0;
            for (int p = 0; p < n; p++) {
                for (int r = 0; r < n; r++) {
                    double v = a[p][r] * a[p][r];
                    all += v;
                    if (p != r) {
                        off += v;
                    }
                }
            }
            if (off <= 1e-30 * all) {
                break;
            }
            for (int p = 0; p < n - 1; p++) {
                for (int r = p + 1; r < n; r++) {
                    if (a[p][r] == 0) {
                        continue;
                    }
                    double theta = (a[r][r] - a[p][p]) / (2 * a[p][r]);
                    double t = (theta >= 0 ? 1 : -1) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    double c = 1 / Math.sqrt(t * t + 1);
                    double s = t * c;
                    for (int k = 0; k < n; k++) {
                        double kp = a[k][p];
                        double kr = a[k][r];
                        a[k][p] = c * kp - s * kr;
                        a[k][r] = s * kp + c * kr;
                    }
                    for (int k = 0; k < n; k++) {
                        double pk = a[p][k];
                        double rk = a[r][k];
                        a[p][k] = c * pk - s * rk;
                        a[r][k] = s * pk + c * rk;
                    }
                    for (int k = 0; k < n; k++) {
                        double kp = vectors[k][p];
                        double kr = vectors[k][r];
                        vectors[k][p] = c * kp - s * kr;
                        vectors[k][r] = s * kp + c * kr;
                    }
                }
            }
        }
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = a[i][i];
        }
        return values;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private static double norm(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    /** Adds c times b to a. */
    private static void add(double[] a, double c, double[] b) {
        for (int i = 0; i < a.length; i++) {
            a[i] += c * b[i];
        }
    }

    private static void scale(double[] a, double c) {
        for (int i = 0; i < a.length; i++) {
            a[i] *= c;
        }
    }
}
