package io.github.osir933.anchor.core.physics.structure;

import io.github.osir933.anchor.core.space.Direction;
import java.util.Arrays;

/**
 * The solid part of a block: boxes inside its cube, in metres from its lower corner, so from 0 to 1 along each
 * axis. A full block is one box filling the cube, a bottom slab one box half as tall, stairs two boxes.
 *
 * <p>Where a block touches its neighbour decides how much load passes between them, so a shape gives, for each
 * face of the cube, the parts of the face its boxes reach, in the coordinates {@link Contact} uses for that face.
 */
public final class Shape {

    /** Boxes closer to a face than this, in metres, count as touching it. */
    private static final double TOUCH = 1e-6;

    /** A block filling its whole cube. */
    public static final Shape FULL = new Shape(new double[] {0, 0, 0, 1, 1, 1});

    /** A block with no solid part, such as a torch or a flower, which touches nothing. */
    public static final Shape NONE = new Shape(new double[0]);

    private final double[] boxes;

    private Shape(double[] boxes) {
        this.boxes = boxes;
    }

    /**
     * Returns a shape made of boxes, each clipped to the cube. Boxes left empty by clipping are dropped. The boxes
     * should not overlap, as the boxes the game gives for a block's shape do not.
     *
     * @param boxes six numbers per box: its lower x, y and z, then its upper x, y and z, in metres from the cube's
     *     lower corner
     * @return the shape
     * @throws IllegalArgumentException if the numbers do not come in sixes, a number is not finite, a box is
     *     inside out, or no box is left inside the cube
     */
    public static Shape of(double[] boxes) {
        if (boxes.length == 0 || boxes.length % 6 != 0) {
            throw new IllegalArgumentException("a shape needs six numbers per box, not " + boxes.length);
        }
        double[] kept = new double[boxes.length];
        int n = 0;
        for (int i = 0; i < boxes.length; i += 6) {
            for (int a = 0; a < 3; a++) {
                double lo = boxes[i + a];
                double hi = boxes[i + 3 + a];
                if (!Double.isFinite(lo) || !Double.isFinite(hi) || hi < lo) {
                    throw new IllegalArgumentException("box " + i / 6 + " is not a box: "
                            + Arrays.toString(Arrays.copyOfRange(boxes, i, i + 6)));
                }
            }
            boolean empty = false;
            for (int a = 0; a < 6; a++) {
                kept[n + a] = Math.min(1.0, Math.max(0.0, boxes[i + a]));
            }
            for (int a = 0; a < 3; a++) {
                empty |= kept[n + 3 + a] - kept[n + a] <= TOUCH;
            }
            if (!empty) {
                n += 6;
            }
        }
        if (n == 0) {
            throw new IllegalArgumentException("no box of the shape is inside the block");
        }
        return new Shape(Arrays.copyOf(kept, n));
    }

    /**
     * Returns a block filled from the bottom up to a height, as a layer of snow or a bottom slab is.
     *
     * @param height the height in metres, more than 0 and at most 1
     * @return the shape
     */
    public static Shape bottom(double height) {
        if (!(height > 0 && height <= 1)) {
            throw new IllegalArgumentException("height must be more than 0 and at most 1: " + height);
        }
        return height == 1 ? FULL : new Shape(new double[] {0, 0, 0, 1, height, 1});
    }

    /**
     * Returns the boxes.
     *
     * @return six numbers per box, a copy
     */
    public double[] boxes() {
        return boxes.clone();
    }

    /**
     * Returns the volume the boxes fill, counting any overlap twice.
     *
     * @return the volume in cubic metres
     */
    public double volume() {
        double v = 0;
        for (int i = 0; i < boxes.length; i += 6) {
            v += (boxes[i + 3] - boxes[i]) * (boxes[i + 4] - boxes[i + 1]) * (boxes[i + 5] - boxes[i + 2]);
        }
        return v;
    }

    /**
     * Tells whether the shape reaches any face of its cube, so that it can touch a neighbour. A lantern hanging
     * from a chain does not, nor does a block with no solid part.
     *
     * @return {@code true} if some box touches a face
     */
    public boolean reachesAFace() {
        for (int i = 0; i < boxes.length; i += 6) {
            for (int a = 0; a < 3; a++) {
                if (boxes[i + a] <= TOUCH || boxes[i + 3 + a] >= 1 - TOUCH) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns where the shape reaches a face of its cube.
     *
     * @param face the face
     * @return four numbers per rectangle, in the face's coordinates as {@link Contact} takes them; empty if the
     *     shape does not reach the face
     */
    public double[] face(Direction face) {
        int axis = face.axis();
        int s = (axis + 1) % 3;
        int t = (axis + 2) % 3;
        double[] out = new double[boxes.length / 6 * 4];
        int n = 0;
        for (int i = 0; i < boxes.length; i += 6) {
            boolean touches = face.isPositive() ? boxes[i + 3 + axis] >= 1 - TOUCH : boxes[i + axis] <= TOUCH;
            if (touches) {
                out[n] = boxes[i + s];
                out[n + 1] = boxes[i + t];
                out[n + 2] = boxes[i + 3 + s];
                out[n + 3] = boxes[i + 3 + t];
                n += 4;
            }
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * Returns where two blocks that are neighbours touch.
     *
     * @param negative the shape of the block on the negative side
     * @param positive the shape of the block one step along the axis
     * @param axis the axis from one to the other: 0 for x, 1 for y, 2 for z
     * @return the patch where they touch, or {@code null} if they do not
     */
    public static Contact contact(Shape negative, Shape positive, int axis) {
        if (axis < 0 || axis > 2) {
            throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
        }
        if (negative == FULL && positive == FULL) {
            return Contact.FULL;
        }
        Direction up = Direction.POSITIVE.get(axis);
        return Contact.between(negative.face(up), positive.face(up.opposite()));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Shape other && Arrays.equals(boxes, other.boxes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(boxes);
    }

    @Override
    public String toString() {
        return "Shape" + Arrays.toString(boxes);
    }
}
