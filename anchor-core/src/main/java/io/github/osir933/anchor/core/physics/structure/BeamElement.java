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

    /**
     * Returns the stiffness of a beam whose one end rests on springs at a face, as a block rests on soft ground: the
     * springs stand for the ground, which gives a little under the face. The beam and the springs carry the same
     * forces one after the other, so their flexibilities add at the beam's free end, K' = (F + R S⁻¹ Rᵀ)⁻¹, where F
     * is the beam's flexibility there with the face held, S the springs' stiffness and R carries a movement of the
     * face to the free end. The result is a beam between the free end and the springs' far side, which holds still;
     * the force at that side is the force through the face.
     *
     * @param stiffness the beam's stiffness, 12 by 12, row-major
     * @param springs the springs' stiffness against moving along and turning about the beam's local axes at the face,
     *     six positive numbers in the order of the degrees of freedom
     * @param faceAtI whether the face is at end i; otherwise it is at end j
     * @param length the beam's length in metres
     * @return the 12 by 12 matrix in row-major order
     */
    static double[] onSprings(double[] stiffness, double[] springs, boolean faceAtI, double length) {
        int f = faceAtI ? 6 : 0;
        int b = faceAtI ? 0 : 6;
        double[] carry = carry(faceAtI ? length : -length);
        double[] flex = invert6(block(stiffness, f, f));
        for (int p = 0; p < 6; p++) {
            for (int q = 0; q < 6; q++) {
                double sum = 0;
                for (int m = 0; m < 6; m++) {
                    sum += carry[p * 6 + m] * carry[q * 6 + m] / springs[m];
                }
                flex[p * 6 + q] += sum;
            }
        }
        double[] kff = invert6(flex);
        // The ends are held by equal and opposite forces, carried along the beam: K' = Aᵀ K'ff A with A = [-R, I].
        double[] kr = multiply6(kff, carry);
        double[] rtk = multiply6(transpose6(carry), kff);
        double[] rtkr = multiply6(rtk, carry);
        double[] k = new double[DOFS * DOFS];
        for (int p = 0; p < 6; p++) {
            for (int q = 0; q < 6; q++) {
                k[(f + p) * DOFS + f + q] = kff[p * 6 + q];
                k[(f + p) * DOFS + b + q] = -kr[p * 6 + q];
                k[(b + p) * DOFS + f + q] = -rtk[p * 6 + q];
                k[(b + p) * DOFS + b + q] = rtkr[p * 6 + q];
            }
        }
        return k;
    }

    /**
     * Returns the geometric stiffness of a beam that rests on springs at a face, from {@link #onSprings}, with a
     * hinge at the face if it pivots there. The face moves with the springs, and the beam beyond it with the hinge's
     * turn; both follow from how far the free end moves, as the springs and the hinge leave it, so the beam's
     * geometric stiffness, taken over the movements of both its ends, is carried to the free end alone: Pᵀ G P, where
     * P gives both ends' movements from the free end's. That is the first-order part of condensing the face out of
     * the stiffness and the geometric stiffness together, and exact where the force along the beam is small next to
     * what the beam and springs resist, as it is short of buckling a single block.
     *
     * @param geometric the beam's geometric stiffness, from {@link #geometric}
     * @param onSprings its stiffness on the springs without the hinge, from {@link #onSprings}
     * @param springs the springs' stiffness, as for {@link #onSprings}
     * @param faceAtI whether the face is at end i
     * @param length the beam's length in metres
     * @param hinge if the beam pivots at the face, a point on its hinge line, y and z in metres from the beam's axis,
     *     and the line's direction, y and z; {@code null} if it does not
     * @param softness the fraction of its stiffness against turning a hinge keeps, as for {@link #released}
     * @return the 12 by 12 matrix in row-major order, nonzero only for the free end
     */
    static double[] onSpringsGeometric(double[] geometric, double[] onSprings, double[] springs, boolean faceAtI,
            double length, double[] hinge, double softness) {
        int f = faceAtI ? 6 : 0;
        int b = faceAtI ? 0 : 6;
        double[] carry = carry(faceAtI ? length : -length);
        double[] kff = block(onSprings, f, f);
        // The face moves as the springs give under the force through it: S⁻¹ Rᵀ K'ff times the free end's movement.
        double[] face = multiply6(transpose6(carry), kff);
        for (int p = 0; p < 6; p++) {
            for (int q = 0; q < 6; q++) {
                face[p * 6 + q] /= springs[p];
            }
        }
        if (hinge != null) {
            double y = hinge[0];
            double z = hinge[1];
            double ay = hinge[2];
            double az = hinge[3];
            // How the face's section moves as the hinge turns, and how that carries to the free end.
            double[] h = {az * y - ay * z, 0, 0, 0, ay, az};
            double[] g = new double[6];
            for (int p = 0; p < 6; p++) {
                for (int m = 0; m < 6; m++) {
                    g[p] += carry[p * 6 + m] * h[m];
                }
            }
            double[] kg = new double[6];
            double turn = 0;
            for (int p = 0; p < 6; p++) {
                for (int m = 0; m < 6; m++) {
                    kg[p] += kff[p * 6 + m] * g[m];
                }
                turn += g[p] * kg[p];
            }
            turn *= 1 + softness;
            // The hinge turns by kg · d / turn; the springs then take the free end's movement less the turn's.
            double[] fg = new double[6];
            for (int p = 0; p < 6; p++) {
                for (int m = 0; m < 6; m++) {
                    fg[p] += face[p * 6 + m] * g[m];
                }
            }
            for (int p = 0; p < 6; p++) {
                for (int q = 0; q < 6; q++) {
                    face[p * 6 + q] -= (fg[p] - h[p]) * kg[q] / turn;
                }
            }
        }
        double[] gbb = block(geometric, b, b);
        double[] gbf = block(geometric, b, f);
        double[] gff = block(geometric, f, f);
        double[] cross = multiply6(transpose6(face), gbf);
        double[] inner = multiply6(transpose6(face), multiply6(gbb, face));
        double[] k = new double[DOFS * DOFS];
        for (int p = 0; p < 6; p++) {
            for (int q = 0; q < 6; q++) {
                k[(f + p) * DOFS + f + q] = gff[p * 6 + q] + cross[p * 6 + q] + cross[q * 6 + p]
                        + inner[p * 6 + q];
            }
        }
        return k;
    }

    /**
     * Returns the 6 by 6 matrix that carries a rigid movement of a beam's section to another section a distance
     * along the beam's axis: the turn about y and z moves the other section across the beam.
     */
    private static double[] carry(double lever) {
        double[] r = new double[36];
        for (int p = 0; p < 6; p++) {
            r[p * 6 + p] = 1;
        }
        // A turn about z moves a section farther along +x toward +y; one about y moves it toward -z.
        r[1 * 6 + 5] = lever;
        r[2 * 6 + 4] = -lever;
        return r;
    }

    /** Returns the 6 by 6 block of a 12 by 12 matrix at (row, col). */
    private static double[] block(double[] k, int row, int col) {
        double[] out = new double[36];
        for (int p = 0; p < 6; p++) {
            for (int q = 0; q < 6; q++) {
                out[p * 6 + q] = k[(row + p) * DOFS + col + q];
            }
        }
        return out;
    }

    private static double[] multiply6(double[] a, double[] b) {
        double[] out = new double[36];
        for (int p = 0; p < 6; p++) {
            for (int q = 0; q < 6; q++) {
                double sum = 0;
                for (int m = 0; m < 6; m++) {
                    sum += a[p * 6 + m] * b[m * 6 + q];
                }
                out[p * 6 + q] = sum;
            }
        }
        return out;
    }

    private static double[] transpose6(double[] a) {
        double[] out = new double[36];
        for (int p = 0; p < 6; p++) {
            for (int q = 0; q < 6; q++) {
                out[q * 6 + p] = a[p * 6 + q];
            }
        }
        return out;
    }

    /** Inverts a symmetric positive definite 6 by 6 matrix by Gauss-Jordan elimination with partial pivoting. */
    private static double[] invert6(double[] m) {
        double[] a = m.clone();
        double[] inv = new double[36];
        for (int p = 0; p < 6; p++) {
            inv[p * 6 + p] = 1;
        }
        for (int c = 0; c < 6; c++) {
            int pivot = c;
            for (int r = c + 1; r < 6; r++) {
                if (Math.abs(a[r * 6 + c]) > Math.abs(a[pivot * 6 + c])) {
                    pivot = r;
                }
            }
            if (pivot != c) {
                for (int q = 0; q < 6; q++) {
                    double t = a[c * 6 + q];
                    a[c * 6 + q] = a[pivot * 6 + q];
                    a[pivot * 6 + q] = t;
                    t = inv[c * 6 + q];
                    inv[c * 6 + q] = inv[pivot * 6 + q];
                    inv[pivot * 6 + q] = t;
                }
            }
            double d = a[c * 6 + c];
            for (int q = 0; q < 6; q++) {
                a[c * 6 + q] /= d;
                inv[c * 6 + q] /= d;
            }
            for (int r = 0; r < 6; r++) {
                if (r == c) {
                    continue;
                }
                double factor = a[r * 6 + c];
                if (factor == 0) {
                    continue;
                }
                for (int q = 0; q < 6; q++) {
                    a[r * 6 + q] -= factor * a[c * 6 + q];
                    inv[r * 6 + q] -= factor * inv[c * 6 + q];
                }
            }
        }
        return inv;
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
