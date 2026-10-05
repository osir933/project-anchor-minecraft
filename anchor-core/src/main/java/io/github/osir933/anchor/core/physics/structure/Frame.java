package io.github.osir933.anchor.core.physics.structure;

import io.github.osir933.anchor.core.matter.Mechanics;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A structure as the structural model sees it: blocks that are free to move, blocks of the ground that hold
 * still, and the joints where they touch.
 *
 * <p>Each free block is a rigid node with six degrees of freedom at its centre. Each joint between two blocks
 * that share a face is a beam from one centre to the other, as wide as the patch where they touch, half its
 * length in each block's material. A joint to the ground is the half of that beam inside the free block,
 * clamped at the face. A joint is either intact, joining the blocks as if they were one piece of material, or
 * cracked: then it only carries what pressing and friction can, as do all joints of granular materials such as
 * sand.
 *
 * <p>Blocks and joints are kept in position order, so a frame built from the same blocks in any order is the
 * same frame.
 */
public final class Frame {

    /** The state of a joint between two blocks. */
    public enum Joint {
        /** The blocks are joined as if they were one piece of material. */
        INTACT,
        /** The joint has cracked: it carries pressing force, and shear up to friction, but no pull. */
        CRACKED
    }

    /**
     * A block: free, with the weight of its matter, or part of the ground, holding still.
     *
     * @param pos where it is
     * @param mechanics how its matter carries loads, or {@code null} for ground that cannot break, such as
     *     bedrock
     * @param temperatureK its temperature, which decides its stiffness and strength
     * @param solidFraction the fraction of its matter that is solid, from above 0 to 1; partly molten blocks are
     *     proportionally weaker
     * @param massKg the mass of its matter in kilograms; zero for ground
     * @param ground whether it is part of the ground
     */
    public record Block(GridPos pos, Mechanics mechanics, double temperatureK, double solidFraction, double massKg,
            boolean ground) {

        /**
         * Validates the block.
         *
         * @param pos the position
         * @param mechanics the mechanics, or {@code null} only for unbreakable ground
         * @param temperatureK the temperature
         * @param solidFraction the solid fraction
         * @param massKg the mass
         * @param ground whether it is ground
         */
        public Block {
            Objects.requireNonNull(pos, "pos");
            if (mechanics == null && !ground) {
                throw new IllegalArgumentException("a free block needs mechanics: " + pos);
            }
            if (!(temperatureK > 0 && Double.isFinite(temperatureK))) {
                throw new IllegalArgumentException("temperature must be positive: " + temperatureK);
            }
            if (!(solidFraction > 0 && solidFraction <= 1)) {
                throw new IllegalArgumentException("solid fraction must lie in (0, 1]: " + solidFraction);
            }
            if (!(massKg >= 0 && Double.isFinite(massKg))) {
                throw new IllegalArgumentException("mass must be finite and non-negative: " + massKg);
            }
        }
    }

    /**
     * A joint where two blocks touch.
     *
     * @param pos the block on the negative side
     * @param axis the axis the joint runs along: 0 for x, 1 for y, 2 for z; the other block is one step along it
     * @param contact where the blocks touch
     * @param state whether the joint is intact or cracked
     */
    public record Bond(GridPos pos, int axis, Contact contact, Joint state) {

        /**
         * Validates the joint.
         *
         * @param pos the block on the negative side
         * @param axis the axis
         * @param contact the patch
         * @param state the state
         */
        public Bond {
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(contact, "contact");
            Objects.requireNonNull(state, "state");
            if (axis < 0 || axis > 2) {
                throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
            }
        }

        /**
         * Returns the block on the positive side.
         *
         * @return its position
         */
        public GridPos other() {
            return pos.offset(Direction.POSITIVE.get(axis));
        }
    }

    private final List<Block> blocks;
    private final List<Bond> bonds;

    private Frame(List<Block> blocks, List<Bond> bonds) {
        this.blocks = Collections.unmodifiableList(blocks);
        this.bonds = Collections.unmodifiableList(bonds);
    }

    /**
     * Starts building a frame.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the blocks, free ones and ground, in position order.
     *
     * @return the blocks
     */
    public List<Block> blocks() {
        return blocks;
    }

    /**
     * Returns the joints in order of their negative block's position, then axis.
     *
     * @return the joints
     */
    public List<Bond> bonds() {
        return bonds;
    }

    /** Builds a {@link Frame}. */
    public static final class Builder {

        private final TreeMap<GridPos, Block> blocks = new TreeMap<>();
        private final TreeMap<GridPos, Bond[]> bonds = new TreeMap<>();

        private Builder() {
        }

        /**
         * Adds a free block.
         *
         * @param pos where it is
         * @param mechanics how its matter carries loads
         * @param temperatureK its temperature in kelvin
         * @param solidFraction the solid fraction of its matter, above 0 and at most 1
         * @param massKg its mass in kilograms
         * @return this builder
         * @throws IllegalArgumentException if a block is already there
         */
        public Builder block(GridPos pos, Mechanics mechanics, double temperatureK, double solidFraction,
                double massKg) {
            return add(new Block(pos, Objects.requireNonNull(mechanics, "mechanics"), temperatureK, solidFraction,
                    massKg, false));
        }

        /**
         * Adds a block of ground, which holds still and holds up what is joined to it.
         *
         * @param pos where it is
         * @param mechanics how its matter carries loads, so that joints to it can break on its side; {@code null}
         *     for ground that cannot break
         * @param temperatureK its temperature in kelvin
         * @param solidFraction the solid fraction of its matter
         * @return this builder
         * @throws IllegalArgumentException if a block is already there
         */
        public Builder ground(GridPos pos, Mechanics mechanics, double temperatureK, double solidFraction) {
            return add(new Block(pos, mechanics, temperatureK, solidFraction, 0.0, true));
        }

        private Builder add(Block block) {
            if (blocks.putIfAbsent(block.pos(), block) != null) {
                throw new IllegalArgumentException("two blocks at " + block.pos());
            }
            return this;
        }

        /**
         * Joins a block to its neighbour. Both must be added, before or after, and at least one must be free.
         *
         * @param pos one block
         * @param direction where the neighbour is
         * @param contact where they touch
         * @param state whether the joint is intact or cracked
         * @return this builder
         * @throws IllegalArgumentException if the two are already joined
         */
        public Builder bond(GridPos pos, Direction direction, Contact contact, Joint state) {
            GridPos negative = direction.isPositive() ? pos : pos.offset(direction);
            Bond[] axes = bonds.computeIfAbsent(negative, p -> new Bond[3]);
            int axis = direction.axis();
            if (axes[axis] != null) {
                throw new IllegalArgumentException("the joint at " + negative + " along axis " + axis
                        + " is already there");
            }
            axes[axis] = new Bond(negative, axis, contact, state);
            return this;
        }

        /**
         * Builds the frame. Joints to positions without a block, and joints between two blocks of ground, are
         * left out.
         *
         * @return the frame
         */
        public Frame build() {
            List<Block> list = new ArrayList<>(blocks.values());
            List<Bond> joints = new ArrayList<>();
            for (Bond[] axes : bonds.values()) {
                for (Bond b : axes) {
                    if (b == null) {
                        continue;
                    }
                    Block a = blocks.get(b.pos());
                    Block c = blocks.get(b.other());
                    if (a == null || c == null || (a.ground() && c.ground())) {
                        continue;
                    }
                    joints.add(b);
                }
            }
            return new Frame(list, joints);
        }
    }
}
