package io.github.osir933.anchor.core.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class GlowingBlocksTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int IRON = 2;

    private static final BlockAppearance[] LOOKS = {
        BlockAppearance.of("anchor:air"),
        BlockAppearance.of("anchor:granite"),
        BlockAppearance.of("anchor:iron"),
    };

    private static final long ORIGIN = SectionPos.pack(0, 0, 0);
    private static final IntPredicate SOLID = id -> id != AIR;
    private static final IntPredicate NOTHING = id -> false;

    /** The host's world: stone below y = 8, air above, and the blocks placed in it. */
    private static final class Host {
        private final TreeMap<GridPos, Integer> placed = new TreeMap<>();

        Host place(GridPos pos, int id) {
            placed.put(pos, id);
            return this;
        }

        int at(GridPos pos) {
            return placed.getOrDefault(pos, pos.y() < 8 ? STONE : AIR);
        }

        HostedWorld simulate() {
            HostedWorld h = new HostedWorld(new PhysicalWorld(WorldSettings.airAt20C(3),
                    MaterialRegistry.withLibrary()), id -> LOOKS[id], HostedWorld.Settings.defaults());
            h.importSection(ORIGIN, i -> at(GridPos.of(ORIGIN, i)), 290.0);
            return h;
        }
    }

    private static void tick(HostedWorld h, int steps) {
        for (int i = 0; i < steps; i++) {
            h.tick();
        }
    }

    @Test
    void coolBlocksAndMissingSectionsShowNothing() {
        Host host = new Host().place(new GridPos(8, 10, 8), IRON);
        HostedWorld h = host.simulate();
        assertEquals(List.of(), h.glowingBlocks(ORIGIN, SOLID, NOTHING));
        assertEquals(List.of(), h.glowingBlocks(SectionPos.pack(5, 0, 0), SOLID, NOTHING));
        assertTrue(h.setTemperature(new GridPos(8, 10, 8), 700.0));
        assertEquals(List.of(), h.glowingBlocks(ORIGIN, SOLID, NOTHING), "700 K is below the Draper point");
    }

    @Test
    void aHotBlockGlowsAllOverTheFacesItShows() {
        GridPos iron = new GridPos(8, 10, 8);
        Host host = new Host().place(iron, IRON).place(iron.offset(Direction.EAST), STONE);
        HostedWorld h = host.simulate();
        assertTrue(h.setTemperature(iron, 1300.0));

        List<GlowingBlock> glowing = h.glowingBlocks(ORIGIN, SOLID, NOTHING);
        assertEquals(1, glowing.size(), glowing::toString);
        GlowingBlock block = glowing.get(0);
        assertEquals(iron.indexInSection(), block.index());
        assertEquals(0.70, block.emissivity(), 1e-12, "oxidised iron");
        for (Direction d : Direction.values()) {
            assertEquals(d != Direction.EAST, block.shows(d), d::toString);
            for (int v = 0; v < GlowingBlock.GRID; v++) {
                for (int u = 0; u < GlowingBlock.GRID; u++) {
                    double t = block.temperature(d, u, v);
                    if (d == Direction.EAST) {
                        assertTrue(Double.isNaN(t), "the stone hides the east face");
                    } else {
                        assertEquals(1300.0, t, 1e-6);
                    }
                }
            }
        }
        assertEquals(1300.0, block.hottest(), 1e-6);
        assertEquals(List.of(), h.glowingBlocks(ORIGIN, SOLID, id -> id == IRON), "a block that shines is left out");
    }

    @Test
    void aBuriedBlockAndHotGasShowNothing() {
        GridPos buried = new GridPos(8, 4, 8);
        GridPos air = new GridPos(3, 12, 3);
        HostedWorld h = new Host().simulate();
        assertTrue(h.setTemperature(buried, 1400.0));
        assertTrue(h.setTemperature(air, 2000.0));
        assertEquals(List.of(), h.glowingBlocks(ORIGIN, SOLID, NOTHING),
                "stone all round hides the buried block, and air has no surface to glow from");
        List<GlowingBlock> seenThroughGlass = h.glowingBlocks(ORIGIN, NOTHING, NOTHING);
        assertEquals(1, seenThroughGlass.size());
        assertEquals(buried.indexInSection(), seenThroughGlass.get(0).index());
        assertEquals(0b111111, seenThroughGlass.get(0).faces());
    }

    @Test
    void aRefinedBlockShowsWhereItIsHotter() {
        GridPos hot = new GridPos(8, 8, 8);
        Host host = new Host().place(hot, STONE);
        HostedWorld h = host.simulate();
        assertTrue(h.setTemperature(hot, 1300.0));
        tick(h, 20);
        assertTrue(h.inspect(hot).orElseThrow().refined(), "the stone it stands on cools its foot");

        GlowingBlock block = h.glowingBlocks(ORIGIN, SOLID, NOTHING).stream()
                .filter(b -> b.index() == hot.indexInSection()).findFirst().orElseThrow();
        assertFalse(block.shows(Direction.DOWN));
        assertTrue(block.shows(Direction.UP) && block.shows(Direction.WEST));
        TreeSet<Double> spots = new TreeSet<>();
        for (int v = 0; v < GlowingBlock.GRID; v++) {
            for (int u = 0; u < GlowingBlock.GRID; u++) {
                spots.add(block.temperature(Direction.WEST, u, v));
            }
        }
        assertTrue(spots.last() - spots.first() > 1.0, () -> "the west face is not all as hot: " + spots);
        assertTrue(block.hottest() >= spots.last());
    }

    @Test
    void spotsAreLaidOutAlongTheFaceAxes() {
        assertArrayEquals(new double[] {0.125, 1.0 - GlowingBlock.INSET, 0.875},
                GlowingBlock.point(Direction.UP, 0, 3), 1e-12);
        assertArrayEquals(new double[] {GlowingBlock.INSET, 0.375, 0.625},
                GlowingBlock.point(Direction.WEST, 2, 1), 1e-12);
        assertArrayEquals(new double[] {0.875, 0.125, 1.0 - GlowingBlock.INSET},
                GlowingBlock.point(Direction.SOUTH, 3, 0), 1e-12);
        TreeSet<Integer> indices = new TreeSet<>();
        for (Direction d : Direction.values()) {
            assertTrue(GlowingBlock.uAxis(d) != d.axis() && GlowingBlock.vAxis(d) != d.axis()
                    && GlowingBlock.uAxis(d) != GlowingBlock.vAxis(d), d::toString);
            for (int v = 0; v < GlowingBlock.GRID; v++) {
                for (int u = 0; u < GlowingBlock.GRID; u++) {
                    indices.add(GlowingBlock.sample(d, u, v));
                }
            }
        }
        assertEquals(6 * GlowingBlock.SAMPLES, indices.size());
        assertEquals(0, indices.first());
        assertEquals(6 * GlowingBlock.SAMPLES - 1, indices.last());
    }

    @Test
    void blocksCompareByValue() {
        double[] t = new double[6 * GlowingBlock.SAMPLES];
        Arrays.fill(t, 1000.0);
        GlowingBlock a = new GlowingBlock(7, 0.5, 0b111111, t.clone());
        GlowingBlock b = new GlowingBlock(7, 0.5, 0b111111, t.clone());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertThrows(IllegalArgumentException.class, () -> new GlowingBlock(7, 0.5, 0b111111, new double[3]));
        assertThrows(IllegalArgumentException.class, () -> new GlowingBlock(7, 0.5, 64, t));
    }
}
