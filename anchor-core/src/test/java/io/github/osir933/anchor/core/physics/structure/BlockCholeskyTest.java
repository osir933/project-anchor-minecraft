package io.github.osir933.anchor.core.physics.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.osir933.anchor.core.math.DeterministicRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockCholeskyTest {

    @Test
    void solvesAGridAsDenseEliminationDoes() throws Exception {
        // A 5 x 4 x 3 grid of nodes joined to their neighbours, with random symmetric positive definite blocks.
        int nx = 5;
        int ny = 4;
        int nz = 3;
        int n = nx * ny * nz;
        int[] x = new int[n];
        int[] y = new int[n];
        int[] z = new int[n];
        for (int i = 0; i < n; i++) {
            x[i] = i % nx;
            y[i] = (i / nx) % ny;
            z[i] = i / (nx * ny);
        }
        DeterministicRandom random = new DeterministicRandom(42);
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int d = Math.abs(x[i] - x[j]) + Math.abs(y[i] - y[j]) + Math.abs(z[i] - z[j]);
                if (d == 1) {
                    pairs.add(new int[] {i, j});
                }
            }
        }
        int m = pairs.size();
        double[][] off = new double[m][];
        double[] dense = new double[36 * n * n];
        int size = 6 * n;
        int[] flat = new int[2 * m];
        for (int p = 0; p < m; p++) {
            flat[2 * p] = pairs.get(p)[0];
            flat[2 * p + 1] = pairs.get(p)[1];
            double[] block = new double[36];
            for (int k = 0; k < 36; k++) {
                block[k] = random.nextDouble() - 0.5;
            }
            off[p] = block;
            int a = pairs.get(p)[0];
            int b = pairs.get(p)[1];
            for (int r = 0; r < 6; r++) {
                for (int c = 0; c < 6; c++) {
                    dense[(6 * a + r) * size + 6 * b + c] = block[r * 6 + c];
                    dense[(6 * b + c) * size + 6 * a + r] = block[r * 6 + c];
                }
            }
        }
        double[][] diagonal = new double[n][];
        for (int i = 0; i < n; i++) {
            double[] block = new double[36];
            for (int r = 0; r < 6; r++) {
                for (int c = 0; c <= r; c++) {
                    double v = random.nextDouble() - 0.5;
                    block[r * 6 + c] = v;
                    block[c * 6 + r] = v;
                }
                // Diagonally dominant, so positive definite.
                block[r * 6 + r] = 60.0;
            }
            diagonal[i] = block;
            for (int r = 0; r < 6; r++) {
                for (int c = 0; c < 6; c++) {
                    dense[(6 * i + r) * size + 6 * i + c] = block[r * 6 + c];
                }
            }
        }
        double[] rhs = new double[size];
        for (int i = 0; i < size; i++) {
            rhs[i] = random.nextDouble() * 2 - 1;
        }
        double[] expected = gauss(dense, rhs.clone(), size);
        BlockCholesky factor = BlockCholesky.factor(new BlockCholesky.Matrix(diagonal, flat, off), x, y, z);
        double[] solution = rhs.clone();
        factor.solve(solution);
        for (int i = 0; i < size; i++) {
            assertEquals(expected[i], solution[i], 1e-10);
        }
        // The two halves of a solve make the whole; the first alone has bᵀ K⁻¹ b for its square length.
        assertArrayEquals(solution, factor.backward(factor.forward(rhs)));
        double[] half = factor.forward(rhs);
        double square = 0;
        double energy = 0;
        for (int i = 0; i < size; i++) {
            square += half[i] * half[i];
            energy += rhs[i] * solution[i];
        }
        assertEquals(energy, square, 1e-12 * energy);
        // The matrix times the solution gives the right-hand side back.
        double[] product = BlockCholesky.multiply(new BlockCholesky.Matrix(diagonal, flat, off), solution);
        for (int i = 0; i < size; i++) {
            assertEquals(rhs[i], product[i], 1e-10);
        }
    }

    @Test
    void nestedDissectionVisitsEveryNodeOnce() {
        int n = 300;
        int[] x = new int[n];
        int[] y = new int[n];
        int[] z = new int[n];
        for (int i = 0; i < n; i++) {
            x[i] = i % 30;
            y[i] = i / 30;
            z[i] = 0;
        }
        int[] order = BlockCholesky.dissect(x, y, z);
        int[] sorted = order.clone();
        Arrays.sort(sorted);
        for (int i = 0; i < n; i++) {
            assertEquals(i, sorted[i]);
        }
        assertArrayEquals(order, BlockCholesky.dissect(x, y, z));
    }

    @Test
    void reportsAMatrixThatIsNotPositiveDefinite() {
        double[][] diagonal = {new double[36], new double[36]};
        for (int r = 0; r < 6; r++) {
            diagonal[0][r * 6 + r] = 1;
        }
        assertThrows(BlockCholesky.SingularException.class, () -> BlockCholesky.factor(
                new BlockCholesky.Matrix(diagonal, new int[0], new double[0][]), new int[] {0, 1}, new int[2],
                new int[2]));
    }

    /** Solves a dense system by Gaussian elimination with partial pivoting. */
    private static double[] gauss(double[] a, double[] b, int n) {
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r * n + col]) > Math.abs(a[pivot * n + col])) {
                    pivot = r;
                }
            }
            if (pivot != col) {
                for (int c = 0; c < n; c++) {
                    double t = a[col * n + c];
                    a[col * n + c] = a[pivot * n + c];
                    a[pivot * n + c] = t;
                }
                double t = b[col];
                b[col] = b[pivot];
                b[pivot] = t;
            }
            for (int r = col + 1; r < n; r++) {
                double f = a[r * n + col] / a[col * n + col];
                if (f == 0) {
                    continue;
                }
                for (int c = col; c < n; c++) {
                    a[r * n + c] -= f * a[col * n + c];
                }
                b[r] -= f * b[col];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int c = r + 1; c < n; c++) {
                s -= a[r * n + c] * x[c];
            }
            x[r] = s / a[r * n + r];
        }
        return x;
    }
}
