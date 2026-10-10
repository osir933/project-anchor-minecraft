package io.github.osir933.anchor.core.physics.structure;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Where a footing touches the ground: rectangles on the plane of the ground's face that do not overlap, each
 * belonging to one of the footing's joints.
 *
 * <p>Coordinates run along the face's two axes, s and t, in metres, as {@link Contact} takes them on one face, carried
 * across the plane: the patch of a joint whose block lies at cell (S, T) of the plane runs from S to S + 1 along s and
 * from T to T + 1 along t, the cells counted from any cell near the footing.
 */
final class Footprint {

    /** Four numbers per rectangle: lower s, lower t, upper s, upper t. */
    private final double[] rects;
    /** The joint each rectangle belongs to, counted from 0. */
    private final int[] owner;
    private final int owners;
    /** Each joint's rectangles, by the cell they lie in. */
    private final TreeMap<Long, List<Integer>> byCell = new TreeMap<>();
    private final double area;
    private final double centreS;
    private final double centreT;
    private final double spreadS;
    private final double spreadT;
    private final double lowS;
    private final double lowT;
    private final double highS;
    private final double highT;

    /**
     * Makes a footprint.
     *
     * @param cellS the cell of each joint's block along s
     * @param cellT the cell of each joint's block along t
     * @param patches each joint's patch, four numbers per rectangle as {@link Contact#rectangles} gives them
     */
    Footprint(int[] cellS, int[] cellT, double[][] patches) {
        owners = patches.length;
        int count = 0;
        for (double[] p : patches) {
            count += p.length / 4;
        }
        rects = new double[4 * count];
        owner = new int[count];
        int r = 0;
        double a = 0;
        double ms = 0;
        double mt = 0;
        double s0 = Double.POSITIVE_INFINITY;
        double t0 = Double.POSITIVE_INFINITY;
        double s1 = Double.NEGATIVE_INFINITY;
        double t1 = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < owners; k++) {
            List<Integer> cell = byCell.computeIfAbsent(key(cellS[k], cellT[k]), c -> new ArrayList<>());
            double[] p = patches[k];
            for (int i = 0; i < p.length; i += 4) {
                rects[4 * r] = cellS[k] + p[i];
                rects[4 * r + 1] = cellT[k] + p[i + 1];
                rects[4 * r + 2] = cellS[k] + p[i + 2];
                rects[4 * r + 3] = cellT[k] + p[i + 3];
                owner[r] = k;
                cell.add(r);
                double ai = (rects[4 * r + 2] - rects[4 * r]) * (rects[4 * r + 3] - rects[4 * r + 1]);
                a += ai;
                ms += ai * (rects[4 * r] + rects[4 * r + 2]) * 0.5;
                mt += ai * (rects[4 * r + 1] + rects[4 * r + 3]) * 0.5;
                s0 = Math.min(s0, rects[4 * r]);
                t0 = Math.min(t0, rects[4 * r + 1]);
                s1 = Math.max(s1, rects[4 * r + 2]);
                t1 = Math.max(t1, rects[4 * r + 3]);
                r++;
            }
        }
        area = a;
        centreS = ms / a;
        centreT = mt / a;
        double ss = 0;
        double tt = 0;
        for (int i = 0; i < count; i++) {
            ss += secondMoment(rects[4 * i] - centreS, rects[4 * i + 2] - centreS)
                    * (rects[4 * i + 3] - rects[4 * i + 1]);
            tt += secondMoment(rects[4 * i + 1] - centreT, rects[4 * i + 3] - centreT)
                    * (rects[4 * i + 2] - rects[4 * i]);
        }
        spreadS = ss;
        spreadT = tt;
        lowS = s0;
        lowT = t0;
        highS = s1;
        highT = t1;
    }

    /** Returns the integral of x² from a to b. */
    private static double secondMoment(double a, double b) {
        return (b * b * b - a * a * a) / 3;
    }

    private static long key(int s, int t) {
        return ((long) s << 32) ^ (t & 0xffffffffL);
    }

    /**
     * Returns the area.
     *
     * @return the area in square metres
     */
    double area() {
        return area;
    }

    /**
     * Returns where the centroid lies along s.
     *
     * @return the coordinate in metres
     */
    double centroidS() {
        return centreS;
    }

    /**
     * Returns where the centroid lies along t.
     *
     * @return the coordinate in metres
     */
    double centroidT() {
        return centreT;
    }

    /**
     * Returns the second moment of the area about the axis along t through its centroid: how far it spreads along s.
     *
     * @return the integral of (s - s̄)² over the area, in m⁴
     */
    double spreadS() {
        return spreadS;
    }

    /**
     * Returns the second moment of the area about the axis along s through its centroid: how far it spreads along t.
     *
     * @return the integral of (t - t̄)² over the area, in m⁴
     */
    double spreadT() {
        return spreadT;
    }

    /**
     * Returns how far the footprint reaches along s, from its lowest edge to its highest.
     *
     * @return the width in metres
     */
    double widthS() {
        return highS - lowS;
    }

    /**
     * Returns how far the footprint reaches along t.
     *
     * @return the width in metres
     */
    double widthT() {
        return highT - lowT;
    }

    /**
     * The part of a footprint centred on a point: where it overlaps its own mirror image through the point. That is
     * how Meyerhof took a footing pressed off centre to bear, as {@link Contact#effectiveArea} takes a joint: the
     * part shrinks as the point nears the footing's edge and is gone beyond it.
     *
     * <p>Its width and length are those of the rectangle with the same area and the same outline, which are a
     * rectangle's own, and for a footing of another shape keep what matters for how the ground beneath gives way:
     * a long wall bears as a strip as wide as the wall, whether it runs straight or round a house.
     *
     * @param area the part's area in square metres
     * @param perimeter the length of its outline, in metres
     * @param width the short side B' of the rectangle with the same area and outline, in metres
     * @param length that rectangle's long side L', in metres
     * @param axisS the part's long axis along s, a unit vector with {@code axisT}: the direction in which its area
     *     spreads farthest
     * @param axisT its long axis along t
     * @param overlap how much of each joint's patch the part covers, in square metres
     */
    record Effective(double area, double perimeter, double width, double length, double axisS, double axisT,
            double[] overlap) {

        /**
         * Returns the square of the cosine of the angle between a direction and the long axis.
         *
         * @param s the direction along s
         * @param t the direction along t
         * @return the square of the cosine, 1 along the long axis, 0 across it; 1 for no direction at all
         */
        double toLength(double s, double t) {
            double size = s * s + t * t;
            if (!(size > 0)) {
                return 1;
            }
            double along = s * axisS + t * axisT;
            return along * along / size;
        }
    }

    /**
     * Returns the part of the footprint centred on a point.
     *
     * @param ps the point along s, in the footprint's coordinates
     * @param pt the point along t
     * @return the part; of no area if the point lies beyond the footprint
     */
    Effective effective(double ps, double pt) {
        List<double[]> parts = new ArrayList<>();
        double[] overlap = new double[owners];
        double a = 0;
        double ss = 0;
        double tt = 0;
        double st = 0;
        for (int i = 0; i < owner.length; i++) {
            double s0 = rects[4 * i];
            double t0 = rects[4 * i + 1];
            double s1 = rects[4 * i + 2];
            double t1 = rects[4 * i + 3];
            // The mirror image of this rectangle covers these cells; the rectangles there mirror back onto it.
            int fromS = (int) Math.floor(2 * ps - s1);
            int toS = (int) Math.floor(2 * ps - s0);
            int fromT = (int) Math.floor(2 * pt - t1);
            int toT = (int) Math.floor(2 * pt - t0);
            for (int cs = fromS; cs <= toS; cs++) {
                for (int ct = fromT; ct <= toT; ct++) {
                    List<Integer> there = byCell.get(key(cs, ct));
                    if (there == null) {
                        continue;
                    }
                    for (int k : there) {
                        double a0 = Math.max(s0, 2 * ps - rects[4 * k + 2]);
                        double a1 = Math.min(s1, 2 * ps - rects[4 * k]);
                        double b0 = Math.max(t0, 2 * pt - rects[4 * k + 3]);
                        double b1 = Math.min(t1, 2 * pt - rects[4 * k + 1]);
                        if (!(a1 > a0 && b1 > b0)) {
                            continue;
                        }
                        parts.add(new double[] {a0, b0, a1, b1});
                        double w = a1 - a0;
                        double h = b1 - b0;
                        overlap[owner[i]] += w * h;
                        a += w * h;
                        // The part is symmetric about the point, so its centroid is the point itself.
                        ss += secondMoment(a0 - ps, a1 - ps) * h;
                        tt += secondMoment(b0 - pt, b1 - pt) * w;
                        st += (a1 - ps + a0 - ps) * 0.5 * (b1 - pt + b0 - pt) * 0.5 * w * h;
                    }
                }
            }
        }
        if (!(a > 0)) {
            return new Effective(0, 0, 0, 0, 1, 0, overlap);
        }
        double perimeter = perimeter(parts);
        double half = perimeter / 2;
        double width = 2 * a / (half + Math.sqrt(Math.max(0, half * half - 4 * a)));
        // The long axis is the eigenvector of the larger eigenvalue of the spread [[ss, st], [st, tt]].
        double mean = (ss + tt) / 2;
        double larger = mean + Math.sqrt((ss - tt) * (ss - tt) / 4 + st * st);
        double axisS;
        double axisT;
        if (st != 0) {
            axisS = st;
            axisT = larger - ss;
        } else if (ss >= tt) {
            axisS = 1;
            axisT = 0;
        } else {
            axisS = 0;
            axisT = 1;
        }
        double size = Math.sqrt(axisS * axisS + axisT * axisT);
        return new Effective(a, perimeter, width, a / width, axisS / size, axisT / size, overlap);
    }

    /**
     * Returns the length of the outline of rectangles that do not overlap: their own outlines, less twice every
     * length along which two of them touch. Rectangles that touch share the very same coordinate there, as they come
     * from the same edges of the footprint or its mirror image.
     */
    private static double perimeter(List<double[]> parts) {
        double total = 0;
        TreeMap<Double, List<double[]>> right = new TreeMap<>();
        TreeMap<Double, List<double[]>> top = new TreeMap<>();
        for (double[] p : parts) {
            total += 2 * (p[2] - p[0] + p[3] - p[1]);
            right.computeIfAbsent(p[2], c -> new ArrayList<>()).add(p);
            top.computeIfAbsent(p[3], c -> new ArrayList<>()).add(p);
        }
        for (double[] p : parts) {
            List<double[]> left = right.get(p[0]);
            if (left != null) {
                for (double[] q : left) {
                    total -= 2 * Math.max(0, Math.min(p[3], q[3]) - Math.max(p[1], q[1]));
                }
            }
            List<double[]> below = top.get(p[1]);
            if (below != null) {
                for (double[] q : below) {
                    total -= 2 * Math.max(0, Math.min(p[2], q[2]) - Math.max(p[0], q[0]));
                }
            }
        }
        return total;
    }
}
