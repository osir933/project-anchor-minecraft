package io.github.osir933.anchor.core.physics.thermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.space.Direction;
import org.junit.jupiter.api.Test;

class RayPatternTest {

    @Test
    void raysStartOnTheFaceAndLeaveThroughIt() {
        RayPattern pattern = new RayPattern(64);
        for (Direction d : Direction.values()) {
            for (int ray = 0; ray < pattern.rays(); ray++) {
                double[] origin = {pattern.originX(d, ray), pattern.originY(d, ray), pattern.originZ(d, ray)};
                double[] dir = {pattern.dirX(d, ray), pattern.dirY(d, ray), pattern.dirZ(d, ray)};
                double[] normal = {d.dx(), d.dy(), d.dz()};
                assertEquals(1.0, dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2], 1e-12, "unit length");
                double out = dir[0] * normal[0] + dir[1] * normal[1] + dir[2] * normal[2];
                assertTrue(out > 0, d + " ray " + ray + " points back into its block");
                for (int axis = 0; axis < 3; axis++) {
                    if (axis == d.axis()) {
                        assertEquals(d.isPositive() ? 1.0 : 0.0, origin[axis], d + " ray " + ray + " is off its face");
                    } else {
                        assertTrue(origin[axis] > 0 && origin[axis] < 1, d + " ray " + ray + " starts off the face");
                    }
                }
            }
        }
    }

    @Test
    void directionsAreCosineWeighted() {
        // With directions spread in proportion to cos θ, the mean of cos θ is 2/3, the mean of cos² θ is 1/2,
        // and the sideways components average out.
        RayPattern pattern = new RayPattern(4096);
        double cos = 0;
        double cos2 = 0;
        double sideways = 0;
        for (int ray = 0; ray < pattern.rays(); ray++) {
            double c = pattern.dirY(Direction.UP, ray);
            cos += c;
            cos2 += c * c;
            sideways += pattern.dirX(Direction.UP, ray);
        }
        assertEquals(2.0 / 3.0, cos / pattern.rays(), 1e-4);
        assertEquals(0.5, cos2 / pattern.rays(), 1e-4);
        assertEquals(0.0, sideways / pattern.rays(), 1e-3);
    }

    @Test
    void oppositeFacesMirrorEachOther() {
        RayPattern pattern = new RayPattern(32);
        for (int ray = 0; ray < pattern.rays(); ray++) {
            assertEquals(pattern.dirX(Direction.EAST, ray), -pattern.dirX(Direction.WEST, ray));
            assertEquals(pattern.dirY(Direction.EAST, ray), pattern.dirY(Direction.WEST, ray));
            assertEquals(pattern.dirZ(Direction.EAST, ray), pattern.dirZ(Direction.WEST, ray));
            assertEquals(pattern.originY(Direction.EAST, ray), pattern.originY(Direction.WEST, ray));
        }
    }

    @Test
    void theDefaultPatternSharesANearbyViewAsTheFormulasDo() {
        // Exact view factors from a unit square to the square facing it 1, 2 and 3 blocks away (aligned
        // parallel squares) and to a square standing on one of its edges (perpendicular squares sharing an
        // edge), from Incropera, Fundamentals of Heat and Mass Transfer, table 13.2.
        RayPattern pattern = new RayPattern(RadiationModel.DEFAULT_RAYS_PER_FACE);
        int n = pattern.rays();
        double[] facing = {0.19982489569838732, 0.06858958881855266, 0.03297139721949724};
        for (int distance = 1; distance <= 3; distance++) {
            assertEquals(Math.round(n * facing[distance - 1]), facing(pattern, distance),
                    "rays reaching the square " + distance + " blocks away");
        }
        double standing = 0.20004377607540316;
        for (int side = 0; side < 4; side++) {
            int hits = standing(pattern, side);
            assertTrue(Math.abs(hits - n * standing) < 1, hits + " rays reach the square on edge " + side);
        }
    }

    /** Counts the rays of a south face that cross the square facing it a distance away. */
    private static int facing(RayPattern pattern, int distance) {
        int hits = 0;
        for (int ray = 0; ray < pattern.rays(); ray++) {
            double t = distance / pattern.dirZ(Direction.SOUTH, ray);
            double x = pattern.originX(Direction.SOUTH, ray) + pattern.dirX(Direction.SOUTH, ray) * t;
            double y = pattern.originY(Direction.SOUTH, ray) + pattern.dirY(Direction.SOUTH, ray) * t;
            if (x >= 0 && x < 1 && y >= 0 && y < 1) {
                hits++;
            }
        }
        return hits;
    }

    /** Counts the rays of a south face that cross the square standing on one of its edges: east, north... */
    private static int standing(RayPattern pattern, int side) {
        int hits = 0;
        for (int ray = 0; ray < pattern.rays(); ray++) {
            // Turn the ray so that the edge in question is the east one, at x = 1.
            double ox = pattern.originX(Direction.SOUTH, ray);
            double oy = pattern.originY(Direction.SOUTH, ray);
            double dx = pattern.dirX(Direction.SOUTH, ray);
            double dy = pattern.dirY(Direction.SOUTH, ray);
            for (int turn = 0; turn < side; turn++) {
                double x = ox;
                ox = oy;
                oy = 1 - x;
                double d = dx;
                dx = dy;
                dy = -d;
            }
            if (dx <= 0) {
                continue;
            }
            double t = (1 - ox) / dx;
            double y = oy + dy * t;
            double height = pattern.dirZ(Direction.SOUTH, ray) * t;
            if (y >= 0 && y < 1 && height < 1) {
                hits++;
            }
        }
        return hits;
    }

    @Test
    void radicalInverseMirrorsTheDigits() {
        assertEquals(0.0, RayPattern.radicalInverse(0, 2));
        assertEquals(0.5, RayPattern.radicalInverse(1, 2));
        assertEquals(0.25, RayPattern.radicalInverse(2, 2));
        assertEquals(0.75, RayPattern.radicalInverse(3, 2));
        assertEquals(1.0 / 3.0, RayPattern.radicalInverse(1, 3), 1e-15);
        assertEquals(1.0 / 25.0, RayPattern.radicalInverse(5, 5), 1e-15);
    }

    @Test
    void latticePointsAreCentredInTheirShareOfTheInterval() {
        assertEquals(0.5 / 32, RayPattern.latticePoint(0, 6, 32));
        assertEquals(6.5 / 32, RayPattern.latticePoint(1, 6, 32));
        assertEquals((6 * 31 % 32 + 0.5) / 32, RayPattern.latticePoint(31, 6, 32));
        for (int[] lattice : RayPattern.LATTICES) {
            assertEquals(1, lattice[1], "the polar angles are stratified, one ray to each band");
        }
    }

    @Test
    void aFaceNeedsRays() {
        assertThrows(IllegalArgumentException.class, () -> new RayPattern(0));
    }
}
