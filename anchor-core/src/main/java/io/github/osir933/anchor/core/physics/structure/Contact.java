package io.github.osir933.anchor.core.physics.structure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The patch where two blocks touch, through which they carry loads: a union of rectangles on their shared face.
 *
 * <p>Coordinates on the face run from 0 to 1 metre along the two axes that cross the face, taken in cyclic order
 * after the axis the face looks along: for a face that looks along x they are (y, z), along y (z, x) and along z
 * (x, y). Two whole faces touch over a square metre; a slab beside a full block only over its lower half.
 *
 * <p>The patch is the narrowest cross-section between the two block centres, so the joint is taken to be that
 * section all the way: the stiffness and strength of a slab beside a block are those of half a face. Where the
 * patch sits off the line between the block centres the joint is still taken to run along that line, which
 * ignores the small extra bending an off-centre joint causes.
 */
public final class Contact {

    /** Shear stress in a rectangle peaks at the neutral axis at 1.5 times its average. */
    private static final double SHEAR_PEAK = 1.5;

    /** Two whole faces. */
    public static final Contact FULL = rectangle(0, 0, 1, 1);

    private final double[] rectangles;
    private final double area;
    private final double centreS;
    private final double centreT;
    private final double inertiaS;
    private final double inertiaT;
    private final double torsion;
    private final double[] cornersS;
    private final double[] cornersT;
    private final double halfS;
    private final double halfT;
    private final double reachS;
    private final double reachT;
    private final double plasticTorque;

    private Contact(double[] rectangles) {
        this.rectangles = rectangles;
        int n = rectangles.length / 4;
        double a = 0;
        double ms = 0;
        double mt = 0;
        for (int i = 0; i < n; i++) {
            double w = rectangles[4 * i + 2] - rectangles[4 * i];
            double h = rectangles[4 * i + 3] - rectangles[4 * i + 1];
            double ai = w * h;
            a += ai;
            ms += ai * (rectangles[4 * i] + rectangles[4 * i + 2]) * 0.5;
            mt += ai * (rectangles[4 * i + 1] + rectangles[4 * i + 3]) * 0.5;
        }
        this.area = a;
        this.centreS = a > 0 ? ms / a : 0.5;
        this.centreT = a > 0 ? mt / a : 0.5;
        double is = 0;
        double it = 0;
        double j = 0;
        double tp = 0;
        double minS = Double.POSITIVE_INFINITY;
        double maxS = Double.NEGATIVE_INFINITY;
        double minT = Double.POSITIVE_INFINITY;
        double maxT = Double.NEGATIVE_INFINITY;
        double[] cs = new double[4 * n];
        double[] ct = new double[4 * n];
        for (int i = 0; i < n; i++) {
            double s0 = rectangles[4 * i] - centreS;
            double t0 = rectangles[4 * i + 1] - centreT;
            double s1 = rectangles[4 * i + 2] - centreS;
            double t1 = rectangles[4 * i + 3] - centreT;
            double w = s1 - s0;
            double h = t1 - t0;
            // Second moments about the patch's centroid: the rectangle's own plus the parallel-axis term.
            is += h * (s1 * s1 * s1 - s0 * s0 * s0) / 3.0;
            it += w * (t1 * t1 * t1 - t0 * t0 * t0) / 3.0;
            j += torsionConstant(w, h);
            double longer = Math.max(w, h);
            double shorter = Math.min(w, h);
            // The torque that yields a whole rectangle, per unit of shear yield stress.
            tp += shorter * shorter * (3 * longer - shorter) / 6.0;
            minS = Math.min(minS, s0);
            maxS = Math.max(maxS, s1);
            minT = Math.min(minT, t0);
            maxT = Math.max(maxT, t1);
            cs[4 * i] = s0;
            ct[4 * i] = t0;
            cs[4 * i + 1] = s1;
            ct[4 * i + 1] = t0;
            cs[4 * i + 2] = s0;
            ct[4 * i + 2] = t1;
            cs[4 * i + 3] = s1;
            ct[4 * i + 3] = t1;
        }
        this.inertiaS = is;
        this.inertiaT = it;
        this.torsion = j;
        this.cornersS = cs;
        this.cornersT = ct;
        this.halfS = n > 0 ? Math.min(-minS, maxS) : 0;
        this.halfT = n > 0 ? Math.min(-minT, maxT) : 0;
        this.reachS = n > 0 ? Math.max(-minS, maxS) : 0;
        this.reachT = n > 0 ? Math.max(-minT, maxT) : 0;
        this.plasticTorque = tp;
    }

    /**
     * Returns a patch of one rectangle.
     *
     * @param s0 the lower edge along the first face axis, from 0 to 1
     * @param t0 the lower edge along the second face axis
     * @param s1 the upper edge along the first face axis, above {@code s0}
     * @param t1 the upper edge along the second face axis, above {@code t0}
     * @return the patch
     */
    public static Contact rectangle(double s0, double t0, double s1, double t1) {
        return of(new double[] {s0, t0, s1, t1});
    }

    /**
     * Returns a patch made of rectangles that do not overlap.
     *
     * @param rectangles four numbers per rectangle: lower s, lower t, upper s, upper t, each from 0 to 1
     * @return the patch
     * @throws IllegalArgumentException if a rectangle is empty, lies outside the face, or overlaps another
     */
    public static Contact of(double[] rectangles) {
        if (rectangles.length == 0 || rectangles.length % 4 != 0) {
            throw new IllegalArgumentException("a patch needs four numbers per rectangle");
        }
        for (int i = 0; i < rectangles.length; i += 4) {
            double s0 = rectangles[i];
            double t0 = rectangles[i + 1];
            double s1 = rectangles[i + 2];
            double t1 = rectangles[i + 3];
            if (!(s0 >= 0 && t0 >= 0 && s1 <= 1 && t1 <= 1 && s1 > s0 && t1 > t0)) {
                throw new IllegalArgumentException("rectangle " + i / 4 + " is empty or leaves the face: "
                        + Arrays.toString(Arrays.copyOfRange(rectangles, i, i + 4)));
            }
            for (int k = 0; k < i; k += 4) {
                if (Math.min(s1, rectangles[k + 2]) > Math.max(s0, rectangles[k])
                        && Math.min(t1, rectangles[k + 3]) > Math.max(t0, rectangles[k + 1])) {
                    throw new IllegalArgumentException("rectangles " + k / 4 + " and " + i / 4 + " overlap");
                }
            }
        }
        return new Contact(rectangles.clone());
    }

    /**
     * Returns where two block faces that look at each other touch: the overlap of their covered parts.
     *
     * @param a the rectangles one face covers, as for {@link #of}
     * @param b the rectangles the other face covers, in the same coordinates
     * @return the patch, or {@code null} if the faces do not touch
     */
    public static Contact between(double[] a, double[] b) {
        List<double[]> parts = new ArrayList<>();
        for (int i = 0; i + 3 < a.length; i += 4) {
            for (int k = 0; k + 3 < b.length; k += 4) {
                double s0 = Math.max(a[i], b[k]);
                double t0 = Math.max(a[i + 1], b[k + 1]);
                double s1 = Math.min(a[i + 2], b[k + 2]);
                double t1 = Math.min(a[i + 3], b[k + 3]);
                if (s1 - s0 > 1e-9 && t1 - t0 > 1e-9) {
                    parts.add(new double[] {s0, t0, s1, t1});
                }
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        double[] flat = new double[parts.size() * 4];
        for (int i = 0; i < parts.size(); i++) {
            System.arraycopy(parts.get(i), 0, flat, 4 * i, 4);
        }
        return new Contact(flat);
    }

    /**
     * Returns the rectangles of the patch.
     *
     * @return four numbers per rectangle, a copy
     */
    public double[] rectangles() {
        return rectangles.clone();
    }

    /**
     * Returns the area.
     *
     * @return the area in square metres
     */
    public double area() {
        return area;
    }

    /**
     * Returns the shear area, the part of the area that resists shear in Timoshenko beam theory.
     *
     * @return the area times 5/6, in square metres
     */
    public double shearArea() {
        return area * 5.0 / 6.0;
    }

    /**
     * Returns the second moment of area for bending in the plane of the bond and the first face axis, about the
     * centroid.
     *
     * @return the second moment in m⁴
     */
    public double inertiaS() {
        return inertiaS;
    }

    /**
     * Returns the second moment of area for bending in the plane of the bond and the second face axis.
     *
     * @return the second moment in m⁴
     */
    public double inertiaT() {
        return inertiaT;
    }

    /**
     * Returns the torsion constant: for one rectangle Saint-Venant's, for several the sum of theirs, which is
     * a little less than the true value of the whole.
     *
     * @return the constant in m⁴
     */
    public double torsionConstant() {
        return torsion;
    }

    /**
     * Returns how far the patch reaches from its centroid along the first face axis, on its shorter side.
     *
     * @return the distance in metres
     */
    public double halfS() {
        return halfS;
    }

    /**
     * Returns how far the patch reaches from its centroid along the second face axis, on its shorter side.
     *
     * @return the distance in metres
     */
    public double halfT() {
        return halfT;
    }

    /**
     * Returns the plastic section modulus for bending in the plane of the bond and the first face axis: the
     * bending moment that yields the whole section, per unit of yield stress. Taken as 1.5 times the elastic
     * modulus, exact for a rectangle.
     *
     * @return the modulus in m³
     */
    double plasticModulusS() {
        return 1.5 * inertiaS / reachS;
    }

    /**
     * Returns the plastic section modulus for bending in the plane of the bond and the second face axis.
     *
     * @return the modulus in m³
     */
    double plasticModulusT() {
        return 1.5 * inertiaT / reachT;
    }

    /**
     * Returns the torque that yields the whole patch, per unit of shear yield stress: the sum over its rectangles.
     *
     * @return the factor in m³
     */
    double plasticTorque() {
        return plasticTorque;
    }

    /**
     * Returns the number of corner points where bending stress is largest.
     *
     * @return four per rectangle
     */
    int corners() {
        return cornersS.length;
    }

    /** Returns a corner's offset from the centroid along the first face axis, in metres. */
    double cornerS(int i) {
        return cornersS[i];
    }

    /** Returns a corner's offset from the centroid along the second face axis, in metres. */
    double cornerT(int i) {
        return cornersT[i];
    }

    /**
     * Returns the largest shear stress a shear force and a torque cause together, adding the peaks of each,
     * which is safe even where they do not coincide.
     *
     * @param shear the shear force across the patch, in newtons
     * @param torque the torque about the bond's axis, in newton metres
     * @return the stress in pascals
     */
    double peakShearStress(double shear, double torque) {
        double tau = SHEAR_PEAK * Math.abs(shear) / area;
        if (torque == 0) {
            return tau;
        }
        double peak = 0;
        int n = rectangles.length / 4;
        for (int i = 0; i < n; i++) {
            double w = rectangles[4 * i + 2] - rectangles[4 * i];
            double h = rectangles[4 * i + 3] - rectangles[4 * i + 1];
            double share = Math.abs(torque) * torsionConstant(w, h) / torsion;
            double longer = Math.max(w, h);
            double shorter = Math.min(w, h);
            // Roark's formula for the largest shear stress in a twisted rectangle, at the middle of its long side.
            peak = Math.max(peak, share * (3 * longer + 1.8 * shorter) / (longer * longer * shorter * shorter));
        }
        return tau + peak;
    }

    /**
     * Returns the radius at which friction spread evenly over the patch resists twisting, so that the patch can
     * take a torque of friction times pressing force times this radius before it turns.
     *
     * @return the radius in metres
     */
    double frictionRadius() {
        // A square of side a under even pressure resists turning as if all its friction acted 0.383 a out.
        return 0.383 * Math.sqrt(area);
    }

    /** Saint-Venant's torsion constant of a solid rectangle, after Roark's series. */
    private static double torsionConstant(double w, double h) {
        double a = Math.max(w, h);
        double b = Math.min(w, h);
        double r = b / a;
        double r2 = r * r;
        return a * b * b * b * (1.0 / 3.0 - 0.21 * r * (1.0 - r2 * r2 / 12.0));
    }

    @Override
    public String toString() {
        return "Contact[area=" + area + "]";
    }
}
