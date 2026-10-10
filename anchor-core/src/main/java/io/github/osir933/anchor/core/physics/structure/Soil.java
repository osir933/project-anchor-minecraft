package io.github.osir933.anchor.core.physics.structure;

/**
 * Soil mechanics for the ground a structure stands on: how stiffly the ground resists a footing that presses, slides,
 * rocks or twists on it, and how hard a footing can press before the ground gives way under it.
 *
 * <p>The ground's stiffness is that of a uniform elastic half-space under a rigid footing, as Gazetas fitted it to
 * rigorous solutions for footings of any shape (G. Gazetas, "Formulas and charts for impedances of surface and
 * embedded foundations", Journal of Geotechnical Engineering 117 (1991) 1363-1381). A footing's shape enters through
 * its area, the second moments of its area and the rectangle that circumscribes it, 2L by 2B. For rocking, Gazetas
 * gives one formula about the long axis and another about the short one; this takes the second for both, which keeps
 * a square footing as stiff one way as the other and lies within a tenth of the first for footings up to ten times as
 * long as they are wide.
 *
 * <p>How hard a footing can press is its bearing resistance, as Eurocode 7 works it out for ground that drains (EN
 * 1997-1:2004 Annex D.4). The ground gives way by shearing along a wedge under the footing that heaves the ground
 * beside it. The soil's cohesion resists it, and so do the weight of the ground beside the footing, which the wedge
 * must lift, and the weight of the soil the wedge shears through, each grown by a factor that the soil's angle of
 * friction sets, for the footing's shape, and shrunk the more the load leans. A footing pressed off centre bears on the
 * part of it centred where the load acts, as Meyerhof took it. A soil's angle of friction is taken from its friction
 * coefficient, and its cohesion from its unconfined compressive strength, by Mohr and Coulomb; loose grains have none.
 */
final class Soil {

    /** Below this tangent of its angle of friction, a soil is taken to have no friction at all. */
    private static final double FRICTIONLESS = 1e-9;

    private Soil() {
    }

    /**
     * Returns a footing's stiffness against pressing straight into the ground.
     *
     * @param shear the ground's shear modulus in pascals
     * @param poisson its Poisson's ratio
     * @param area the footing's area in square metres
     * @param halfLong half the long side of the rectangle that circumscribes it, in metres
     * @return the force per metre it sinks, in newtons per metre
     */
    static double pressing(double shear, double poisson, double area, double halfLong) {
        double chi = area / (4 * halfLong * halfLong);
        return 2 * shear * halfLong / (1 - poisson) * (0.73 + 1.54 * StrictMath.pow(chi, 0.75));
    }

    /**
     * Returns a footing's stiffness against sliding sideways on the ground.
     *
     * @param shear the ground's shear modulus in pascals
     * @param poisson its Poisson's ratio
     * @param area the footing's area in square metres
     * @param halfLong half the long side of the rectangle that circumscribes it, in metres
     * @param halfShort half its short side, in metres
     * @param alongLong whether it slides along the long side; otherwise across it
     * @return the force per metre it slides, in newtons per metre
     */
    static double sliding(double shear, double poisson, double area, double halfLong, double halfShort,
            boolean alongLong) {
        double chi = area / (4 * halfLong * halfLong);
        double across = 2 * shear * halfLong / (2 - poisson) * (2 + 2.5 * StrictMath.pow(chi, 0.85));
        if (!alongLong) {
            return across;
        }
        return across - 0.2 / (0.75 - poisson) * shear * halfLong * (1 - halfShort / halfLong);
    }

    /**
     * Returns a footing's stiffness against rocking about an axis in its plane, through its centroid.
     *
     * @param shear the ground's shear modulus in pascals
     * @param poisson its Poisson's ratio
     * @param inertia the second moment of the footing's area about the axis, in m⁴
     * @param halfLong half the long side of the rectangle that circumscribes it, in metres
     * @param halfShort half its short side, in metres
     * @return the moment per radian it turns, in newton metres
     */
    static double rocking(double shear, double poisson, double inertia, double halfLong, double halfShort) {
        return 3 * shear / (1 - poisson) * StrictMath.pow(inertia, 0.75)
                * StrictMath.pow(halfLong / halfShort, 0.15);
    }

    /**
     * Returns a footing's stiffness against twisting on the ground, about the axis through its centroid across its
     * plane.
     *
     * @param shear the ground's shear modulus in pascals
     * @param polar the polar second moment of the footing's area about that axis, in m⁴
     * @param halfLong half the long side of the rectangle that circumscribes it, in metres
     * @param halfShort half its short side, in metres
     * @return the moment per radian it turns, in newton metres
     */
    static double twisting(double shear, double polar, double halfLong, double halfShort) {
        double b4 = halfShort * halfShort * halfShort * halfShort;
        return 3.5 * shear * StrictMath.pow(polar, 0.75) * StrictMath.pow(halfShort / halfLong, 0.4)
                * StrictMath.pow(polar / b4, 0.2);
    }

    /**
     * Returns a soil's cohesion, from its unconfined compressive strength and its angle of friction, as Mohr and
     * Coulomb relate them: σc = 2c cos φ / (1 - sin φ).
     *
     * @param compressive the unconfined compressive strength in pascals
     * @param tanPhi the tangent of the angle of friction
     * @return the cohesion in pascals
     */
    static double cohesion(double compressive, double tanPhi) {
        return compressive * (Math.sqrt(1 + tanPhi * tanPhi) - tanPhi) / 2;
    }

    /**
     * Returns Eurocode 7's bearing capacity factor for the weight of the ground beside a footing, N_q = e^(π tan φ)
     * tan²(45° + φ/2).
     *
     * @param tanPhi the tangent of the soil's angle of friction
     * @return the factor
     */
    static double surchargeFactor(double tanPhi) {
        double root = Math.sqrt(1 + tanPhi * tanPhi) + tanPhi;
        return StrictMath.exp(Math.PI * tanPhi) * root * root;
    }

    /**
     * Returns Eurocode 7's bearing capacity factor for cohesion, N_c = (N_q - 1) cot φ, which is π + 2 without
     * friction.
     *
     * @param tanPhi the tangent of the soil's angle of friction
     * @return the factor
     */
    static double cohesionFactor(double tanPhi) {
        if (tanPhi < FRICTIONLESS) {
            return Math.PI + 2;
        }
        return (surchargeFactor(tanPhi) - 1) / tanPhi;
    }

    /**
     * Returns Eurocode 7's bearing capacity factor for the soil's own weight, N_γ = 2 (N_q - 1) tan φ, for a base
     * rough enough to grip the soil.
     *
     * @param tanPhi the tangent of the soil's angle of friction
     * @return the factor
     */
    static double weightFactor(double tanPhi) {
        return 2 * (surchargeFactor(tanPhi) - 1) * tanPhi;
    }

    /**
     * Returns the pressure at which the ground gives way under a footing, spread over the footing's effective area,
     * as Eurocode 7 works it out for drained ground (EN 1997-1:2004 D.4), with a level base and no water. Without
     * friction it is worked out as for undrained ground (D.3).
     *
     * @param cohesion the soil's cohesion in pascals
     * @param tanPhi the tangent of its angle of friction
     * @param unitWeight its unit weight, in newtons per cubic metre
     * @param surcharge the pressure of the ground beside the footing at the level of its base, in pascals
     * @param width the effective width B' of the footing, in metres
     * @param length its effective length L', in metres, at least the width
     * @param area its effective area A', in square metres
     * @param pressing the force pressing it into the ground, V, in newtons, positive
     * @param sideways the force along the ground, H, in newtons
     * @param toLength the square of the cosine of the angle between that force and the long side
     * @return the pressure in pascals; zero if the force along the ground would slide the footing
     */
    static double bearing(double cohesion, double tanPhi, double unitWeight, double surcharge, double width,
            double length, double area, double pressing, double sideways, double toLength) {
        double ratio = width / length;
        if (tanPhi < FRICTIONLESS) {
            double shape = 1 + 0.2 * ratio;
            if (sideways > 0 && !(sideways < area * cohesion)) {
                return 0;
            }
            double lean = sideways > 0 ? 0.5 * (1 + Math.sqrt(1 - sideways / (area * cohesion))) : 1;
            return (Math.PI + 2) * cohesion * shape * lean + surcharge;
        }
        double nq = surchargeFactor(tanPhi);
        double nc = (nq - 1) / tanPhi;
        double ng = 2 * (nq - 1) * tanPhi;
        double sin = tanPhi / Math.sqrt(1 + tanPhi * tanPhi);
        double sq = 1 + ratio * sin;
        double sg = 1 - 0.3 * ratio;
        double sc = (sq * nq - 1) / (nq - 1);
        double iq = 1;
        double ig = 1;
        double ic = 1;
        if (sideways > 0) {
            double base = 1 - sideways / (pressing + area * cohesion / tanPhi);
            if (!(base > 0)) {
                return 0;
            }
            double mWide = (2 + ratio) / (1 + ratio);
            double mLong = (2 + 1 / ratio) / (1 + 1 / ratio);
            double m = mLong * toLength + mWide * (1 - toLength);
            iq = StrictMath.pow(base, m);
            ig = iq * base;
            ic = Math.max(0, iq - (1 - iq) / (nc * tanPhi));
        }
        return cohesion * nc * sc * ic + surcharge * nq * sq * iq + 0.5 * unitWeight * width * ng * sg * ig;
    }
}
