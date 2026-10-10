package io.github.osir933.anchor.core.physics.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.space.Direction;
import org.junit.jupiter.api.Test;

class ShapeTest {

    private static final Shape BOTTOM_SLAB = Shape.bottom(0.5);
    private static final Shape TOP_SLAB = Shape.of(new double[] {0, 0.5, 0, 1, 1, 1});
    /** Stairs climbing toward +x: a bottom slab with a step on its +x half. */
    private static final Shape STAIRS = Shape.of(new double[] {0, 0, 0, 1, 0.5, 1, 0.5, 0.5, 0, 1, 1, 1});

    @Test
    void aFullBlockCoversEveryFace() {
        for (Direction d : Direction.values()) {
            assertArrayEquals(new double[] {0, 0, 1, 1}, Shape.FULL.face(d), d.toString());
        }
        assertEquals(1.0, Shape.FULL.volume());
        assertSame(Shape.FULL, Shape.bottom(1.0));
    }

    @Test
    void facesUseTheCoordinatesContactsDo() {
        // Across x the face's coordinates are y then z; across y, z then x; across z, x then y.
        assertArrayEquals(new double[] {0, 0, 0.5, 1}, BOTTOM_SLAB.face(Direction.EAST));
        assertArrayEquals(new double[] {0, 0, 1, 0.5}, BOTTOM_SLAB.face(Direction.NORTH));
        assertArrayEquals(new double[] {0, 0, 1, 1}, BOTTOM_SLAB.face(Direction.DOWN));
        assertEquals(0, BOTTOM_SLAB.face(Direction.UP).length, "a bottom slab does not reach its top");
        assertArrayEquals(new double[] {0, 0.5, 1, 1}, STAIRS.face(Direction.UP), "only the step reaches the top");
        assertArrayEquals(new double[] {0, 0, 0.5, 1, 0.5, 0, 1, 1}, STAIRS.face(Direction.EAST));
        assertEquals(0.75, STAIRS.volume(), 1e-12);
    }

    @Test
    void neighboursTouchWhereBothReachTheirSharedFace() {
        assertSame(Contact.FULL, Shape.contact(Shape.FULL, Shape.FULL, 1));
        assertNull(Shape.contact(BOTTOM_SLAB, Shape.FULL, 1), "nothing rests on a bottom slab's top");
        assertEquals(1.0, Shape.contact(TOP_SLAB, Shape.FULL, 1).area(), 1e-12);
        assertNull(Shape.contact(Shape.FULL, TOP_SLAB, 1), "a top slab hangs clear of the block below");
        assertEquals(0.5, Shape.contact(BOTTOM_SLAB, Shape.FULL, 0).area(), 1e-12, "side by side, half a face");
        assertNull(Shape.contact(BOTTOM_SLAB, TOP_SLAB, 0), "a bottom and a top slab side by side miss each other");
        assertEquals(0.5, Shape.contact(STAIRS, Shape.FULL, 1).area(), 1e-12);
        assertNull(Shape.contact(Shape.NONE, Shape.FULL, 2));
        assertThrows(IllegalArgumentException.class, () -> Shape.contact(Shape.FULL, Shape.FULL, 3));
    }

    @Test
    void boxesAreClippedToTheBlock() {
        // A fence post is a block and a half tall to the game; only the part inside the block counts.
        Shape post = Shape.of(new double[] {0.375, 0, 0.375, 0.625, 1.5, 0.625});
        assertArrayEquals(new double[] {0.375, 0, 0.375, 0.625, 1, 0.625}, post.boxes());
        assertEquals(0.0625, post.volume(), 1e-12);
        // Boxes that clipping leaves empty are dropped.
        Shape kept = Shape.of(new double[] {0, 0, 0, 1, 0.5, 1, 0, 1, 0, 1, 1.5, 1});
        assertEquals(BOTTOM_SLAB, kept);
        assertEquals(BOTTOM_SLAB.hashCode(), kept.hashCode());
    }

    @Test
    void onlyShapesReachingAFaceCanTouchAnything() {
        assertTrue(Shape.FULL.reachesAFace());
        assertTrue(BOTTOM_SLAB.reachesAFace());
        assertFalse(Shape.NONE.reachesAFace());
        // A lantern hanging from a chain floats inside its block.
        Shape hanging = Shape.of(new double[] {0.3125, 0.0625, 0.3125, 0.6875, 0.5, 0.6875,
            0.375, 0.5, 0.375, 0.625, 0.625, 0.625});
        assertFalse(hanging.reachesAFace());
        for (Direction d : Direction.values()) {
            assertEquals(0, hanging.face(d).length);
        }
    }

    @Test
    void nonsenseIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Shape.of(new double[0]));
        assertThrows(IllegalArgumentException.class, () -> Shape.of(new double[] {0, 0, 0, 1, 1}));
        assertThrows(IllegalArgumentException.class, () -> Shape.of(new double[] {0, 0, 0, 1, Double.NaN, 1}));
        assertThrows(IllegalArgumentException.class, () -> Shape.of(new double[] {0, 1, 0, 1, 0, 1}),
                "inside out");
        assertThrows(IllegalArgumentException.class, () -> Shape.of(new double[] {2, 0, 0, 3, 1, 1}),
                "outside the block");
        assertThrows(IllegalArgumentException.class, () -> Shape.bottom(0.0));
        assertThrows(IllegalArgumentException.class, () -> Shape.bottom(1.5));
    }
}
