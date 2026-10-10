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

    /**
     * Returns the geometric stiffness of a beam that carries a force along it: how that force, tilted as the beam
     * turns and bows, pushes its ends further aside. Pulling stiffens a beam and pressing softens it, and a frame
     * buckles where the softening cancels its own stiffness. This is the standard matrix from cubic bending shapes,
     * with the Wagner term for the twist a force along a twisted beam adds.
     *
     * @param length the beam's length in metres
     * @param axial the force along it in newtons, positive when it pulls
     * @param contact the cross-section, the same throughout
     * @return the 12 by 12 matrix in row-major order, which adds to the beam's stiffness in second-order theory
     */
    static double[] geometric(double length, double axial, Contact contact) {
        double[] k = new double[DOFS * DOFS];
        double c = axial / length;
        // A slope toward +y turns the beam the positive way about z; one toward +z turns it the negative way about y.
        addTilt(k, new int[] {1, 5, 7, 11}, c, length);
        addTilt(k, new int[] {2, 4, 8, 10}, c, -length);
        addSpring(k, 3, 9, axial * (contact.inertiaS() + contact.inertiaT()) / (contact.area() * length));
        return k;
    }

    /**
     * Returns how a beam's ends move when the part of it beyond a hinge turns about that hinge by one radian, end i
     * held still: end j turns about the hinge's axis and swings around the line it lies on. The hinge lies across the
     * beam's section at some point along it, and its axis in the section's plane.
     *
     * @param length the beam's length in metres
     * @param at how far from end i the hinge is, in metres, from 0 to the length
     * @param y where the hinge line crosses the section, along local y, in metres from the beam's axis
     * @param z where it crosses the section along local z
     * @param ay the hinge's axis along local y, a unit vector with {@code az}
     * @param az its axis along local z
     * @return the movements of both ends, twelve numbers, zero at end i
     */
    static double[] hingeMode(double length, double at, double y, double z, double ay, double az) {
        double[] g = new double[DOFS];
        double beyond = length - at;
        // End j turns with the part beyond the hinge, and its centre swings about the hinge line.
        g[6] = az * y - ay * z;
        g[7] = az * beyond;
        g[8] = -ay * beyond;
        g[10] = ay;
        g[11] = az;
        return g;
    }

    /**
     * Returns the stiffness of a beam with a hinge across it, which turns freely but for a small fraction of the
     * stiffness the beam had against that turn: the beam's matrix with the hinge's turn condensed out, K - K g gᵀ K /
     * ((1 + softness) gᵀ K g). The beam still carries every force through the hinge, and every moment but the one
     * about it.
     *
     * @param stiffness the beam's stiffness without the hinge, 12 by 12, row-major
     * @param mode how the ends move as the hinge turns, from {@link #hingeMode}
     * @param softness the fraction of its stiffness against turning the hinge keeps, small and positive
     * @return the 12 by 12 matrix in row-major order
     */
    static double[] released(double[] stiffness, double[] mode, double softness) {
        double[] kg = times(stiffness, mode);
        double turn = dot(mode, kg) * (1 + softness);
        double[] k = stiffness.clone();
        for (int r = 0; r < DOFS; r++) {
            for (int c = 0; c < DOFS; c++) {
                k[r * DOFS + c] -= kg[r] * kg[c] / turn;
            }
        }
        return k;
    }

    /**
     * Returns how far a hinge across a beam turns, opening, for given movements of the beam's ends: the turn that
     * leaves the beam's matter least strained, from {@link #released}.
     *
     * @param stiffness the beam's stiffness without the hinge
     * @param mode how the ends move as the hinge turns
     * @param softness the fraction of its stiffness the hinge keeps
     * @param moves the movements of the beam's ends, twelve numbers, beyond those that strain nothing
     * @return the turn in radians, in the sense of the hinge's axis
     */
    static double hingeTurn(double[] stiffness, double[] mode, double softness, double[] moves) {
        double[] kg = times(stiffness, mode);
        return dot(kg, moves) / (dot(mode, kg) * (1 + softness));
    }

    /**
     * Returns the geometric stiffness of a beam with a hinge across it. The beam bows as cubic shapes from its ends'
     * movements less the hinge's turn, and the part beyond the hinge tilts besides by that turn, so a force along the
     * beam pushes the hinge aside as it does a short strut turning about it: over the length beyond the hinge, N times
     * the turn squared, where cubic shapes alone would smooth the kink away.
     *
     * @param geometric the beam's geometric stiffness without the hinge, from {@link #geometric}
     * @param stiffness its stiffness without the hinge
     * @param mode how the ends move as the hinge turns, from {@link #hingeMode}
     * @param softness the fraction of its stiffness the hinge keeps
     * @param length the beam's length in metres
     * @param at how far from end i the hinge is
     * @param axial the force along the beam in newtons, positive when it pulls
     * @param ay the hinge's axis along local y, as for {@link #hingeMode}
     * @param az its axis along local z
     * @return the 12 by 12 matrix in row-major order
     */
    static double[] releasedGeometric(double[] geometric, double[] stiffness, double[] mode, double softness,
            double length, double at, double axial, double ay, double az) {
        double[] kg = times(stiffness, mode);
        double turn = dot(mode, kg) * (1 + softness);
        // The hinge turns by k · d for end movements d, and the beam bows as cubic shapes of d - g (k · d).
        double[] k = new double[DOFS];
        for (int r = 0; r < DOFS; r++) {
            k[r] = kg[r] / turn;
        }
        double[] gg = times(geometric, mode);
        double ggg = dot(mode, gg);
        // How much a turn beyond the hinge tilts against the cubic shapes there: q · d is the change in the bow, in
        // the plane the hinge turns in, between the hinge and end j.
        double xi = at / length;
        double h1 = 1 - 3 * xi * xi + 2 * xi * xi * xi;
        double h2 = length * (xi - 2 * xi * xi + xi * xi * xi);
        double h3 = 3 * xi * xi - 2 * xi * xi * xi;
        double h4 = length * (xi * xi * xi - xi * xi);
        double[] q = new double[DOFS];
        q[1] = -az * h1;
        q[5] = -az * h2;
        q[7] = az * (1 - h3);
        q[11] = -az * h4;
        q[2] = ay * h1;
        q[4] = -ay * h2;
        q[8] = -ay * (1 - h3);
        q[10] = -ay * h4;
        double gq = dot(mode, q);
        double strut = axial * (length - at) + ggg - 2 * axial * gq;
        double[] out = geometric.clone();
        for (int r = 0; r < DOFS; r++) {
            for (int c = 0; c < DOFS; c++) {
                out[r * DOFS + c] += -gg[r] * k[c] - k[r] * gg[c] + axial * (q[r] * k[c] + k[r] * q[c])
                        + strut * k[r] * k[c];
            }
        }
        return out;
    }

    /** Returns a 12 by 12 matrix times a vector. */
    private static double[] times(double[] m, double[] v) {
        double[] out = new double[DOFS];
        for (int r = 0; r < DOFS; r++) {
            double sum = 0;
            for (int c = 0; c < DOFS; c++) {
                sum += m[r * DOFS + c] * v[c];
            }
            out[r] = sum;
        }
        return out;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
     * Adds the geometric stiffness of bending in one plane, (N / 30 L) times [[36, 3L, -36, 3L], [3L, 4L², -3L,
     * -L²], [-36, -3L, 36, -3L], [3L, -L², -3L, 4L²]] for the movement and turn at each end, with the sign of the
     * couplings between movement and turn as the plane's turns go.
     */
    private static void addTilt(double[] k, int[] dofs, double perLength, double arm) {
        double l2 = arm * arm;
        double[] g = {
            1.2, arm / 10, -1.2, arm / 10,
            arm / 10, 2 * l2 / 15, -arm / 10, -l2 / 30,
            -1.2, -arm / 10, 1.2, -arm / 10,
            arm / 10, -l2 / 30, -arm / 10, 2 * l2 / 15,
        };
        for (int p = 0; p < 4; p++) {
            for (int q = 0; q < 4; q++) {
                k[dofs[p] * DOFS + dofs[q]] += perLength * g[p * 4 + q];
            }
        }
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
