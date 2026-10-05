package io.github.osir933.anchor.core.physics.structure;

/**
 * The stiffness of a straight beam made of pieces of different materials, from Timoshenko beam theory.
 *
 * <p>The beam runs along its local x axis from end i to end j. Each end has six degrees of freedom in the order
 * u, v, w (moves along local x, y, z) and rx, ry, rz (turns about them). The stiffness comes from the flexibility
 * of the beam clamped at i and loaded at j, integrated piece by piece, so it is exact for beams whose pieces
 * each have one material, shear deformation included. That matters here: a block is as deep as it is long, and
 * shear makes a fair part of its deflection.
 */
final class BeamElement {

    /** Degrees of freedom of one beam, six at each end. */
    static final int DOFS = 12;

    private BeamElement() {
    }

    /**
     * Returns the stiffness matrix of a beam.
     *
     * @param lengths the length of each piece in metres, from end i to end j
     * @param youngs each piece's Young's modulus in pascals
     * @param shear each piece's shear modulus in pascals
     * @param contact the cross-section, the same throughout; its first face axis is local y, its second local z
     * @return the 12 by 12 matrix in row-major order, mapping end movements to the forces the beam needs at its
     *     ends
     */
    static double[] stiffness(double[] lengths, double[] youngs, double[] shear, Contact contact) {
        double area = contact.area();
        double shearArea = contact.shearArea();
        // Bending in the x-y plane curves about z and works against the spread of the section along y.
        double iz = contact.inertiaS();
        double iy = contact.inertiaT();
        double j = contact.torsionConstant();
        double length = 0;
        for (double l : lengths) {
            length += l;
        }
        double axial = 0;
        double twist = 0;
        double yy = 0;
        double yt = 0;
        double tt = 0;
        double zz = 0;
        double zt = 0;
        double zr = 0;
        double s = 0;
        for (int k = 0; k < lengths.length; k++) {
            double l = lengths[k];
            double a = length - s;
            double b = length - s - l;
            double cube = (a * a * a - b * b * b) / 3.0;
            double square = (a * a - b * b) / 2.0;
            axial += l / (youngs[k] * area);
            twist += l / (shear[k] * j);
            yy += cube / (youngs[k] * iz) + l / (shear[k] * shearArea);
            yt += square / (youngs[k] * iz);
            tt += l / (youngs[k] * iz);
            zz += cube / (youngs[k] * iy) + l / (shear[k] * shearArea);
            zt += square / (youngs[k] * iy);
            zr += l / (youngs[k] * iy);
            s += l;
        }
        double[] k = new double[DOFS * DOFS];
        addSpring(k, 0, 6, 1.0 / axial);
        addSpring(k, 3, 9, 1.0 / twist);
        // A force along +y at the free end lifts it and turns it the positive way about z: both couplings positive.
        addBending(k, new int[] {1, 5, 7, 11}, invert(yy, yt, tt), -length);
        // A force along +z lifts the free end but turns it the negative way about y.
        addBending(k, new int[] {2, 4, 8, 10}, invert(zz, -zt, zr), length);
        return k;
    }

    /** Adds a spring between two degrees of freedom. */
    private static void addSpring(double[] k, int a, int b, double stiffness) {
        k[a * DOFS + a] += stiffness;
        k[b * DOFS + b] += stiffness;
        k[a * DOFS + b] -= stiffness;
        k[b * DOFS + a] -= stiffness;
    }

    /**
     * Adds bending in one plane: Bᵀ K B, where K is the stiffness of the free end of the clamped beam and B turns
     * the four end movements into the free end's movement relative to the clamped end carried along rigidly.
     *
     * @param k the matrix to add to
     * @param dofs the movement at i, the turn at i, the movement at j and the turn at j
     * @param free the 2 by 2 stiffness of the free end, row-major
     * @param arm how far a turn at i moves end j: minus the length when a positive turn lifts it, plus when it lowers
     *     it
     */
    private static void addBending(double[] k, int[] dofs, double[] free, double arm) {
        double[][] b = {{-1, arm, 1, 0}, {0, -1, 0, 1}};
        for (int p = 0; p < 4; p++) {
            for (int q = 0; q < 4; q++) {
                double sum = 0;
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        sum += b[r][p] * free[r * 2 + c] * b[c][q];
                    }
                }
                k[dofs[p] * DOFS + dofs[q]] += sum;
            }
        }
    }

    /** Inverts the symmetric 2 by 2 matrix [[a, b], [b, c]]. */
    private static double[] invert(double a, double b, double c) {
        double det = a * c - b * b;
        return new double[] {c / det, -b / det, -b / det, a / det};
    }
}
