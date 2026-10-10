package io.github.osir933.anchor.core.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.host.HostedWorld.Settled;
import io.github.osir933.anchor.core.physics.structure.Frame;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis;
import io.github.osir933.anchor.core.physics.thermal.HeatSourceModel;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Built blocks that heat stretches, and the structures that take it, in a hosted world. */
class HostedExpansionTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int LAVA = 2;
    private static final int STOVE = 3;

    private static final BlockAppearance[] LOOKS = {
        BlockAppearance.of("anchor:air"),
        BlockAppearance.of("anchor:granite"),
        BlockAppearance.of("anchor:basalt").shownAs(Phase.LIQUID).startingAt(1450.0)
                .heatedBy(new HeatSourceModel.Source(1450.0, 1_500_000.0)),
        BlockAppearance.of("anchor:iron").startingAt(600.0).heatedBy(new HeatSourceModel.Source(600.0, 3000.0)),
    };

    private static final long ORIGIN = SectionPos.pack(0, 0, 0);
    private static final double CLIMATE = 293.15;
    private static final GridPos LAVA_AT = new GridPos(4, 8, 4);
    private static final GridPos BESIDE = new GridPos(5, 8, 4);

    private static HostedWorld empty() {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(3), MaterialRegistry.withLibrary());
        return new HostedWorld(world, id -> LOOKS[id], HostedWorld.Settings.defaults().withTickSeconds(14.4));
    }

    /** Stone below y = 8 and air above. */
    private static HostedWorld hosted() {
        HostedWorld h = empty();
        h.importSection(ORIGIN, i -> SectionPos.localY(i) < 8 ? STONE : AIR, CLIMATE);
        return h;
    }

    /** Checks every structure that waits to be checked and gathers what cracked and fell. */
    private static Settled settleAll(HostedWorld h) {
        List<StructuralAnalysis.Crack> cracks = new ArrayList<>();
        List<GridPos> falling = new ArrayList<>();
        List<GridPos> sunk = new ArrayList<>();
        for (Optional<StructureSurvey> s = h.nextStructure(4096); s.isPresent(); s = h.nextStructure(4096)) {
            Settled settled = h.settle(s.get(), StructuralAnalysis.analyse(s.get().frame()));
            cracks.addAll(settled.cracks());
            falling.addAll(settled.falling());
            sunk.addAll(settled.sunk());
        }
        return new Settled(false, cracks, falling, sunk);
    }

    /** Takes every structure that waits to be checked off the queue. */
    private static void checkAll(HostedWorld h) {
        for (Optional<StructureSurvey> s = h.nextStructure(4096); s.isPresent(); s = h.nextStructure(4096)) {
            assertTrue(s.get().frame().blocks().size() > 0);
        }
    }

    /** Returns the block at a position in the structure surveyed from it. */
    private static Frame.Block surveyed(HostedWorld h, GridPos pos) {
        StructureSurvey survey = h.survey(pos, 4096).orElseThrow();
        return survey.frame().blocks().stream().filter(b -> b.pos().equals(pos)).findFirst().orElseThrow();
    }

    @Test
    void heatStretchesABuiltBlockAndMostOnItsHotSide() {
        HostedWorld h = hosted();
        h.setThermalShock(false);
        h.reconcile(LAVA_AT, LAVA, Double.NaN);
        h.reconcile(BESIDE, STONE, Double.NaN);
        for (int s = 0; s < 60; s++) {
            h.tick();
        }
        assertEquals(CLIMATE, h.unstrainedTemperature(BESIDE), 1e-9);
        double warmer = h.temperature(BESIDE) - CLIMATE;
        assertTrue(warmer > 2, "the lava has warmed it: " + warmer);
        Frame.Expansion e = surveyed(h, BESIDE).expansion();
        // Granite grows by about 8e-6 per kelvin; the lava's side, toward -x, grows most, so the block bends away.
        double strain = MaterialLibrary.GRANITE.mechanics().thermalStrain(CLIMATE, h.temperature(BESIDE));
        assertEquals(strain, e.strain(), strain * 0.5, e::toString);
        assertTrue(e.gradientX() < 0, e::toString);
        assertTrue(e.stretchX() < 0, e::toString);
        assertEquals(e, h.inspect(BESIDE).orElseThrow().expansion());
        // The ground it stands on, warmed by the lava too, is held where it is.
        StructureSurvey survey = h.survey(BESIDE, 4096).orElseThrow();
        for (Frame.Block b : survey.frame().blocks()) {
            assertTrue(b.pos().equals(BESIDE) || (b.ground() && !b.expansion().any()), b::toString);
        }
        assertNull(h.inspect(BESIDE.offset(Direction.DOWN)).orElseThrow().expansion(),
                "the world as it was found is not stretched");

        // Switched off, every structure waits to be checked again, and heat stretches nothing.
        checkAll(h);
        assertEquals(0, h.uncheckedStructures());
        h.setThermalExpansion(false);
        assertTrue(h.uncheckedStructures() > 0);
        assertFalse(surveyed(h, BESIDE).expansion().any());
        assertNull(h.inspect(BESIDE).orElseThrow().expansion());
    }

    @Test
    void aSpanThatTheColdShrinksCracksAtBothEndsAndFalls() {
        // Three blocks of granite between two pillars of the world as it was found. Cooled by 60 K they would be
        // 0.48 mm shorter; held to their length, they are pulled by 24 MPa, more than the 10 MPa that cracks them.
        HostedWorld h = empty();
        h.importSection(ORIGIN, i -> {
            int x = SectionPos.localX(i);
            int y = SectionPos.localY(i);
            return y < 8 || ((x == 4 || x == 8) && y <= 10 && SectionPos.localZ(i) == 4) ? STONE : AIR;
        }, CLIMATE);
        List<GridPos> span = List.of(new GridPos(5, 10, 4), new GridPos(6, 10, 4), new GridPos(7, 10, 4));
        for (GridPos p : span) {
            h.reconcile(p, STONE, Double.NaN);
        }
        Settled warm = settleAll(h);
        assertTrue(warm.cracks().isEmpty() && warm.falling().isEmpty(), "it holds as built: " + warm);
        for (GridPos p : span) {
            h.setTemperature(p, CLIMATE - 60.0);
        }
        for (int i = 0; i < HostedWorld.STRENGTH_CHECK_STEPS; i++) {
            h.tick();
        }
        // Pulled alike all along, every joint cracks at once, and nothing holds the pieces up.
        Settled cold = settleAll(h);
        assertEquals(List.of(new GridPos(4, 10, 4), new GridPos(5, 10, 4), new GridPos(6, 10, 4),
                new GridPos(7, 10, 4)), cold.cracks().stream().map(StructuralAnalysis.Crack::pos).sorted().toList());
        for (StructuralAnalysis.Crack c : cold.cracks()) {
            assertTrue(c.heat(), "its weight alone would have left it whole: " + c);
            assertEquals(StructuralAnalysis.Mode.TENSION, c.mode());
        }
        assertEquals(span, cold.falling().stream().sorted().toList());
        assertTrue(h.temperature(span.get(1)) < CLIMATE - 50.0, "it has not warmed much meanwhile");
    }

    @Test
    void aBlockAHeatSourceHoldsAtItsTemperatureIsNotStretched() {
        // The source, not the physics of heat, sets how warm a stove is, so how it grows is left out.
        HostedWorld h = hosted();
        GridPos stove = new GridPos(6, 8, 6);
        h.reconcile(stove, STOVE, Double.NaN);
        for (int s = 0; s < 5; s++) {
            h.tick();
        }
        assertTrue(h.isBuilt(stove));
        assertTrue(h.temperature(stove) > CLIMATE + 100);
        assertFalse(surveyed(h, stove).expansion().any());
        HostedWorld.Inspection seen = h.inspect(stove).orElseThrow();
        assertNotNull(seen.expansion());
        assertFalse(seen.expansion().any());
    }

    @Test
    void aBuiltBlockThatHeatStretchesWaitsToBeCheckedAgain() {
        HostedWorld h = hosted();
        GridPos placed = new GridPos(4, 8, 4);
        h.reconcile(placed, STONE, Double.NaN);
        assertTrue(h.nextStructure(100).isPresent());
        assertEquals(0, h.uncheckedStructures());
        // A kelvin stretches granite by too little to matter: 0.4 MPa held back, against 10 MPa that cracks it.
        h.setTemperature(placed, CLIMATE + 1.0);
        for (int i = 0; i < HostedWorld.STRENGTH_CHECK_STEPS; i++) {
            h.tick();
        }
        assertEquals(0, h.uncheckedStructures());
        // Ten more, held back, would press it by over a tenth of that, so its structure is checked again.
        h.setTemperature(placed, CLIMATE + 11.0);
        for (int i = 0; i < HostedWorld.STRENGTH_CHECK_STEPS; i++) {
            h.tick();
        }
        assertEquals(1, h.uncheckedStructures());
        assertTrue(h.nextStructure(100).isPresent());
        // Not while heat stretches nothing.
        h.setThermalExpansion(false);
        assertTrue(h.nextStructure(100).isPresent());
        h.setTemperature(placed, CLIMATE + 30.0);
        for (int i = 0; i < HostedWorld.STRENGTH_CHECK_STEPS; i++) {
            h.tick();
        }
        assertEquals(0, h.uncheckedStructures());
    }
}
