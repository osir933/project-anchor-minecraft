package io.github.osir933.anchor.core.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis;
import io.github.osir933.anchor.core.physics.structure.ThermalShock;
import io.github.osir933.anchor.core.physics.thermal.HeatSourceModel;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/** Built blocks that thermal stress cracks through, in a hosted world. */
class HostedThermalShockTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int LAVA = 2;
    private static final int COBBLESTONE = 3;
    private static final int IRON = 4;

    private static final BlockAppearance[] LOOKS = {
        BlockAppearance.of("anchor:air"),
        BlockAppearance.of("anchor:granite").fracturingInto("test:cobblestone"),
        BlockAppearance.of("anchor:basalt").shownAs(Phase.LIQUID).startingAt(1450.0)
                .heatedBy(new HeatSourceModel.Source(1450.0, 1_500_000.0)),
        BlockAppearance.of("anchor:granite"),
        BlockAppearance.of("anchor:iron"),
    };

    private static final long ORIGIN = SectionPos.pack(0, 0, 0);
    private static final double CLIMATE = 293.15;
    private static final GridPos LAVA_AT = new GridPos(4, 8, 4);
    private static final GridPos BESIDE = new GridPos(5, 8, 4);
    /** Stone beside lava cracks after about 115 steps of 14.4 s; this leaves room to spare. */
    private static final int STEPS = 300;

    private static HostedWorld empty() {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(3), MaterialRegistry.withLibrary());
        return new HostedWorld(world, id -> LOOKS[id], HostedWorld.Settings.defaults().withTickSeconds(14.4));
    }

    /** Stone below y = 8 and air above, with some blocks as the world was found. */
    private static IntUnaryOperator ground(Map<GridPos, Integer> found) {
        TreeMap<Integer, Integer> byIndex = new TreeMap<>();
        found.forEach((pos, id) -> byIndex.put(pos.indexInSection(), id));
        return i -> byIndex.getOrDefault(i, SectionPos.localY(i) < 8 ? STONE : AIR);
    }

    /** Lava poured on the ground with a block placed beside it. */
    private static HostedWorld besideLava(int placed) {
        HostedWorld h = empty();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        h.reconcile(LAVA_AT, LAVA, Double.NaN);
        h.reconcile(BESIDE, placed, Double.NaN);
        return h;
    }

    /** Ticks until thermal stress cracks something and returns what it cracked. */
    private static List<Fracture> tickUntilCracked(HostedWorld h) {
        for (int s = 0; s < STEPS; s++) {
            List<Fracture> cracked = h.tick().fractures();
            if (!cracked.isEmpty()) {
                return cracked;
            }
        }
        return fail("nothing cracked in " + STEPS + " steps");
    }

    /** Checks every structure that waits to be checked and returns the blocks that fall. */
    private static List<GridPos> settleAll(HostedWorld h) {
        List<GridPos> falling = new ArrayList<>();
        for (Optional<StructureSurvey> s = h.nextStructure(4096); s.isPresent(); s = h.nextStructure(4096)) {
            falling.addAll(h.settle(s.get(), StructuralAnalysis.analyse(s.get().frame())).falling());
        }
        return falling;
    }

    private static void assertEveryJoint(boolean cracked, HostedWorld h, GridPos pos) {
        for (Direction d : Direction.values()) {
            assertEquals(cracked, h.isCracked(pos, d), "the joint toward " + d);
        }
    }

    @Test
    void stonePlacedBesideLavaCracksThroughOnce() {
        HostedWorld h = besideLava(STONE);
        assertTrue(h.isBuilt(BESIDE));
        List<Fracture> cracked = tickUntilCracked(h);
        assertEquals(1, cracked.size(), cracked::toString);
        Fracture f = cracked.get(0);
        assertEquals(BESIDE, f.pos());
        assertEquals("test:cobblestone", f.hostBlock());
        assertTrue(f.load() >= 1.0, f::toString);
        assertTrue(f.tension(), "the cold back of the block, held by its hot face, is pulled apart: " + f);
        assertTrue(f.temperatureK() > CLIMATE);
        assertEquals(h.temperature(BESIDE), f.temperatureK(), 1e-9);
        assertTrue(h.isFractured(BESIDE));
        assertEveryJoint(true, h, BESIDE);
        HostedWorld.Inspection seen = h.inspect(BESIDE).orElseThrow();
        assertTrue(seen.built());
        assertTrue(seen.fractured());
        assertTrue(seen.thermalStress().load() >= 1.0);
        assertEquals(1, h.status().fractures());
        // Shown as rubble of the same rock, it keeps its heat and its cracks, and it does not crack again.
        double temperature = h.temperature(BESIDE);
        h.reconcile(BESIDE, COBBLESTONE, Double.NaN);
        assertTrue(h.isBuilt(BESIDE));
        assertTrue(h.isFractured(BESIDE));
        assertEveryJoint(true, h, BESIDE);
        assertEquals(temperature, h.temperature(BESIDE), 1e-9);
        for (int s = 0; s < 50; s++) {
            assertEquals(List.of(), h.tick().fractures());
        }
        assertEquals(1, h.status().fractures());
    }

    @Test
    void theWorldAsFoundDoesNotCrack() {
        // A lava pool and the rock around it only start to exchange heat when they are first simulated, all at once,
        // which would crack the lining of every pool brought in; so the world as it was found holds.
        HostedWorld h = empty();
        h.importSection(ORIGIN, ground(Map.of(LAVA_AT, LAVA, BESIDE, STONE)), CLIMATE);
        assertFalse(h.isBuilt(BESIDE));
        assertFalse(h.inspect(BESIDE).orElseThrow().built());
        double peak = 0.0;
        for (int s = 0; s < 150; s++) {
            assertEquals(List.of(), h.tick().fractures());
            ThermalShock.Result stress = h.inspect(BESIDE).orElseThrow().thermalStress();
            peak = Math.max(peak, stress == null ? 0.0 : stress.load());
        }
        assertTrue(peak >= 1.0, "stressed enough to crack, had it been built: " + peak);
        assertFalse(h.isFractured(BESIDE));
        assertEquals(0, h.status().fractures());
    }

    @Test
    void crackingCanBeSwitchedOff() {
        HostedWorld h = besideLava(STONE);
        h.setThermalShock(false);
        for (int s = 0; s < 150; s++) {
            assertEquals(List.of(), h.tick().fractures());
        }
        assertTrue(h.inspect(BESIDE).orElseThrow().thermalStress().load() >= 1.0, "stressed past its strength");
        assertFalse(h.isFractured(BESIDE));
        h.setThermalShock(true);
        List<Fracture> cracked = h.tick().fractures();
        assertEquals(List.of(BESIDE), cracked.stream().map(Fracture::pos).toList());
    }

    @Test
    void metalsDoNotCrackFromHeat() {
        // Iron yields where it is strained past its strength, so it bends rather than cracks.
        HostedWorld h = besideLava(IRON);
        boolean refined = false;
        for (int s = 0; s < 150; s++) {
            assertEquals(List.of(), h.tick().fractures());
            HostedWorld.Inspection seen = h.inspect(BESIDE).orElseThrow();
            refined |= seen.refined();
            assertNull(seen.thermalStress());
        }
        assertTrue(refined, "the iron was refined, so its cells had temperatures of their own to check");
    }

    @Test
    void aBlockHangingFromOneThatCracksFalls() {
        // A block held out over a hole only by its joint with the stone beside the lava.
        HostedWorld h = empty();
        GridPos hanging = BESIDE.offset(Direction.EAST);
        h.importSection(ORIGIN, ground(Map.of(hanging.offset(Direction.DOWN), AIR)), CLIMATE);
        h.reconcile(LAVA_AT, LAVA, Double.NaN);
        h.reconcile(BESIDE, STONE, Double.NaN);
        h.reconcile(hanging, STONE, Double.NaN);
        assertEquals(List.of(), settleAll(h), "a block of stone held out from another holds");
        List<Fracture> cracked = tickUntilCracked(h);
        assertEquals(List.of(BESIDE), cracked.stream().map(Fracture::pos).toList());
        // The cracked stone rests on the ground by pressing and friction; nothing holds the block beside it up.
        assertEquals(List.of(hanging), settleAll(h));
    }

    @Test
    void aCrackedBlockStaysCrackedWhenItsSectionIsSaved() {
        HostedWorld h = besideLava(STONE);
        tickUntilCracked(h);
        SectionSnapshot saved = h.snapshot(ORIGIN).orElseThrow();
        HostedWorld again = empty();
        again.importSection(ORIGIN, ground(Map.of(LAVA_AT, LAVA, BESIDE, STONE)), CLIMATE, saved);
        assertTrue(again.isBuilt(BESIDE));
        assertTrue(again.isFractured(BESIDE));
        assertEveryJoint(true, again, BESIDE);
        assertTrue(again.inspect(BESIDE).orElseThrow().fractured());
        assertEquals(saved, again.snapshot(ORIGIN).orElseThrow());
        for (int s = 0; s < 20; s++) {
            assertEquals(List.of(), again.tick().fractures());
        }
    }

    @Test
    void newMatterInACrackedBlockIsWhole() {
        HostedWorld h = besideLava(STONE);
        tickUntilCracked(h);
        h.reconcile(BESIDE, AIR, Double.NaN);
        assertFalse(h.isFractured(BESIDE));
        h.reconcile(BESIDE, STONE, Double.NaN);
        assertTrue(h.isBuilt(BESIDE));
        assertFalse(h.isFractured(BESIDE));
        assertEveryJoint(false, h, BESIDE);
        // Fresh stone starts cool beside the lava again, and cracks again in time.
        assertEquals(List.of(BESIDE), tickUntilCracked(h).stream().map(Fracture::pos).toList());
        assertEquals(2, h.status().fractures());
    }
}
