package io.github.osir933.anchor.core.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.thermal.HeatSourceModel;
import io.github.osir933.anchor.core.physics.thermal.ThermalRefinement;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

class HostedWorldTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int WATER = 2;
    private static final int ICE = 3;
    private static final int TORCH = 4;
    private static final int LAVA = 5;
    private static final int SNOW = 6;
    private static final int SLAB = 7;
    private static final int DOUBLE_SLAB = 8;
    private static final int HEATED_SNOW = 9;
    private static final int DUST = 10;

    private static final BlockAppearance SNOW_LOOK = BlockAppearance.of("anchor:powder_snow").shownAs(Phase.SOLID)
            .becoming(Phase.LIQUID, "air").becoming(Phase.GAS, "air");

    private static final BlockAppearance[] LOOKS = {
        BlockAppearance.of("anchor:air"),
        BlockAppearance.of("anchor:granite"),
        BlockAppearance.of("anchor:water").shownAs(Phase.LIQUID).becoming(Phase.SOLID, "ice")
                .becoming(Phase.GAS, "air"),
        BlockAppearance.of("anchor:water").shownAs(Phase.SOLID).becoming(Phase.LIQUID, "water")
                .becoming(Phase.GAS, "air"),
        BlockAppearance.of("anchor:air").heatedBy(new HeatSourceModel.Source(1300.0, 1500.0)),
        BlockAppearance.of("anchor:basalt").shownAs(Phase.LIQUID).startingAt(1450.0)
                .heatedBy(new HeatSourceModel.Source(1450.0, 1.5e6)),
        SNOW_LOOK,
        BlockAppearance.of("anchor:granite").withFill(0.5),
        BlockAppearance.of("anchor:granite"),
        SNOW_LOOK.heatedBy(new HeatSourceModel.Source(400.0, 1e5)),
        BlockAppearance.of("anchor:air"),
    };

    private static final long ORIGIN = SectionPos.pack(0, 0, 0);

    private static HostedWorld hosted(HostedWorld.Settings settings) {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(3), MaterialRegistry.withLibrary());
        return new HostedWorld(world, id -> LOOKS[id], settings);
    }

    private static HostedWorld hosted() {
        return hosted(HostedWorld.Settings.defaults());
    }

    /** Stone below y = 8 and air above, with some blocks of the origin section replaced. */
    private static IntUnaryOperator terrain(Map<GridPos, Integer> blocks) {
        TreeMap<Integer, Integer> byIndex = new TreeMap<>();
        blocks.forEach((pos, id) -> byIndex.put(pos.indexInSection(), id));
        return i -> byIndex.getOrDefault(i, SectionPos.localY(i) < 8 ? STONE : AIR);
    }

    private static double densityAt(Material m, double temperatureK) {
        return m.density(m.stateFor(m.specificEnthalpy(temperatureK)));
    }

    @Test
    void importedBlocksStartAtTheTemperatureOfTheirSurroundingsAndSleep() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, terrain(Map.of()), 290.0);
        GridPos stone = new GridPos(3, 2, 3);
        assertEquals(290.0, h.temperature(stone), 1e-9);
        assertEquals(290.0, h.temperature(new GridPos(3, 12, 3)), 1e-9);
        assertEquals(densityAt(MaterialLibrary.GRANITE, 290.0), h.world().readBlock(stone).mass(), 1e-9);
        assertTrue(h.isImported(ORIGIN));
        assertEquals(0, h.status().awakeSections(), "a section in balance starts asleep");
        assertTrue(Double.isNaN(h.temperature(new GridPos(40, 2, 3))), "sections not imported are unknown");
        assertTrue(h.world().audit().balanced());
    }

    @Test
    void iceInAWarmClimateStartsAtItsMeltingPointAndWakesItsSection() {
        HostedWorld h = hosted();
        GridPos ice = new GridPos(5, 9, 5);
        h.importSection(ORIGIN, terrain(Map.of(ice, ICE)), 300.0);
        assertEquals(273.15, h.temperature(ice), 1e-9);
        HostedWorld.Inspection inspection = h.inspect(ice).orElseThrow();
        assertEquals(Phase.SOLID, inspection.phase());
        assertEquals("anchor:water", inspection.material());
        assertEquals(LOOKS[ICE], inspection.appearance());
        assertTrue(inspection.awake());
        assertEquals(1, h.status().awakeSections());
    }

    @Test
    void lavaKeepsItsOwnTemperatureAndHeatsItself() {
        HostedWorld h = hosted();
        GridPos lava = new GridPos(5, 7, 5);
        h.importSection(ORIGIN, terrain(Map.of(lava, LAVA)), 290.0);
        HostedWorld.Inspection inspection = h.inspect(lava).orElseThrow();
        assertEquals(1450.0, inspection.temperatureK(), 1e-9);
        assertEquals(Phase.LIQUID, inspection.phase());
        assertEquals(new HeatSourceModel.Source(1450.0, 1.5e6), inspection.source());
        assertEquals(1, h.status().sources());
        for (int i = 0; i < 10; i++) {
            h.tick();
        }
        // Radiation from the open top takes about 0.2 K in each step, after the source has topped the lava up.
        assertEquals(1450.0, h.temperature(lava), 0.5, "the source holds the lava near its temperature");
        assertTrue(h.temperature(lava.offset(0, -1, 0)) > 290.0, "the rock under the lava warms");
        assertTrue(h.temperature(lava.offset(0, 1, 0)) > 290.0, "and so does the air above it");
        assertTrue(h.world().audit().balanced(), () -> h.world().audit().toString());
    }

    /** Lava set into the ground, ticked long enough for the stone around it to start warming. */
    private static HostedWorld lavaInTheGround(HostedWorld.Settings settings, GridPos lava) {
        HostedWorld h = hosted(settings);
        h.importSection(ORIGIN, terrain(Map.of(lava, LAVA)), 290.0);
        for (int i = 0; i < 20; i++) {
            h.tick();
        }
        return h;
    }

    @Test
    void stoneBesideLavaIsRefinedSoItsFaceShowsTheHeatSoakingIn() {
        GridPos lava = new GridPos(5, 7, 5);
        HostedWorld h = lavaInTheGround(HostedWorld.Settings.defaults(), lava);
        List<GridPos> touching = List.of(lava.offset(1, 0, 0), lava.offset(-1, 0, 0), lava.offset(0, 0, 1),
                lava.offset(0, 0, -1), lava.offset(0, -1, 0));
        for (GridPos stone : touching) {
            assertTrue(h.inspect(stone).orElseThrow().refined(), () -> stone + " touches the lava");
        }
        assertFalse(h.inspect(lava).orElseThrow().refined(), "the source keeps the lava whole");
        assertFalse(h.inspect(lava.offset(0, 1, 0)).orElseThrow().refined(), "air is never refined");
        HostedWorld.Status status = h.status();
        assertEquals(touching.size(), status.refinedBlocks());
        assertTrue(status.refinedCells() > 8 * touching.size(),
                () -> status.refinedCells() + " cells: those against the lava are split again");

        GridPos stone = lava.offset(1, 0, 0);
        HostedWorld.Inspection inspection = h.inspect(stone).orElseThrow();
        assertTrue(inspection.coolestK() < inspection.temperatureK()
                && inspection.temperatureK() < inspection.hottestK(), () -> inspection.toString());
        double face = h.temperatureAt(stone.x() + 0.1, stone.y() + 0.5, stone.z() + 0.5);
        double back = h.temperatureAt(stone.x() + 0.9, stone.y() + 0.5, stone.z() + 0.5);
        assertTrue(back < inspection.temperatureK() && inspection.temperatureK() < face,
                () -> face + " K at the lava, " + back + " K at the back");
        assertTrue(h.world().audit().balanced(), () -> h.world().audit().toString());
    }

    @Test
    void withoutRefinementEveryBlockStaysWholeAndTakesInHeatMoreSlowly() {
        GridPos lava = new GridPos(5, 7, 5);
        GridPos stone = lava.offset(1, 0, 0);
        HostedWorld h = lavaInTheGround(HostedWorld.Settings.defaults()
                .withRefinement(ThermalRefinement.Settings.OFF), lava);
        assertEquals(0, h.status().refinedBlocks());
        assertEquals(0, h.status().refinedCells());
        HostedWorld.Inspection inspection = h.inspect(stone).orElseThrow();
        assertFalse(inspection.refined());
        assertEquals(inspection.temperatureK(), inspection.coolestK());
        assertEquals(inspection.temperatureK(), inspection.hottestK());
        assertEquals(h.temperature(stone), h.temperatureAt(stone.x() + 0.1, stone.y() + 0.5, stone.z() + 0.5));

        double refined = lavaInTheGround(HostedWorld.Settings.defaults(), lava).temperature(stone);
        assertTrue(inspection.temperatureK() > 290.0, "a whole block still warms");
        // A whole block's heat has half a block of stone to cross; cells against the lava take it in nearer the face.
        assertTrue(refined > inspection.temperatureK(),
                () -> "refined stone " + refined + " K, whole stone " + inspection.temperatureK() + " K");
    }

    @Test
    void aNeighbourWithAnotherClimateWakesANewSection() {
        HostedWorld h = hosted();
        long east = SectionPos.pack(1, 0, 0);
        long north = SectionPos.pack(0, 0, -1);
        h.importSection(ORIGIN, terrain(Map.of()), 290.0);
        h.importSection(east, terrain(Map.of()), 290.0);
        assertEquals(0, h.status().awakeSections());
        h.importSection(north, terrain(Map.of()), 300.0);
        assertEquals(1, h.status().awakeSections());
        assertTrue(h.inspect(GridPos.of(north, 0)).orElseThrow().awake());
    }

    @Test
    void changedMatterIsReplacedAndDeclared() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, terrain(Map.of()), 290.0);
        GridPos stone = new GridPos(4, 7, 4);
        assertTrue(h.reconcile(stone, AIR, Double.NaN));
        assertEquals("anchor:air", h.inspect(stone).orElseThrow().material());
        assertEquals(290.0, h.temperature(stone), 1e-9);
        assertFalse(h.reconcile(stone, AIR, Double.NaN), "the same id again changes nothing");
        assertEquals(1, h.status().awakeSections(), "a change wakes its section");

        GridPos lava = new GridPos(4, 6, 4);
        assertTrue(h.reconcile(lava, LAVA, 300.0));
        assertEquals(1450.0, h.temperature(lava), 1e-9, "lava brings its own temperature");
        assertNotNull(h.inspect(lava).orElseThrow().source());
        assertTrue(h.reconcile(lava, STONE, Double.NaN));
        assertNull(h.inspect(lava).orElseThrow().source(), "removing the lava removes its source");

        assertFalse(h.reconcile(new GridPos(40, 0, 0), STONE, Double.NaN), "unimported sections are ignored");
        assertTrue(h.world().audit().balanced(), () -> h.world().audit().toString());
        assertEquals(3, h.status().reconciled());
    }

    @Test
    void theSameMatterKeepsItsStateUnderANewLook() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, terrain(Map.of(new GridPos(1, 7, 1), SLAB)), 290.0);
        GridPos stone = new GridPos(2, 7, 2);
        CellState before = h.world().readBlock(stone);
        assertTrue(h.reconcile(stone, DOUBLE_SLAB, 500.0));
        assertEquals(before, h.world().readBlock(stone), "a full granite block stays the same granite");

        GridPos slab = new GridPos(1, 7, 1);
        assertEquals(0.5 * densityAt(MaterialLibrary.GRANITE, 290.0), h.world().readBlock(slab).mass(), 1e-9);
        assertTrue(h.reconcile(slab, DOUBLE_SLAB, 400.0));
        assertEquals(densityAt(MaterialLibrary.GRANITE, 400.0), h.world().readBlock(slab).mass(), 1e-9,
                "a second slab is new matter");
        assertEquals(400.0, h.temperature(slab), 1e-9);

        GridPos water = new GridPos(3, 9, 3);
        assertTrue(h.reconcile(water, WATER, Double.NaN));
        assertEquals(Phase.LIQUID, h.inspect(water).orElseThrow().phase());
        assertTrue(h.reconcile(water, ICE, Double.NaN));
        assertEquals(273.15, h.temperature(water), 1e-9, "liquid water cannot be shown as ice, so it is replaced");
        assertEquals(Phase.SOLID, h.inspect(water).orElseThrow().phase());
        assertTrue(h.world().audit().balanced(), () -> h.world().audit().toString());
    }

    @Test
    void waterFreezesInTheColdAndIsThenShownAsIceWithoutLosingItsState() {
        // Freezing through takes weeks, slower than the calm rate, so this world never lets sections sleep.
        HostedWorld h = hosted(new HostedWorld.Settings(3600.0, 50_000_000L, 300.0, 288.15, 0.0, 20, 20,
                ThermalRefinement.Settings.DEFAULT));
        GridPos water = new GridPos(8, 9, 8);
        h.importSection(ORIGIN, terrain(Map.of(water, WATER)), 250.0);
        assertEquals(273.15, h.temperature(water), 1e-9, "water in the cold starts at its freezing point");
        PhaseChange change = null;
        for (int i = 0; i < 2000 && change == null; i++) {
            HostedWorld.TickResult result = h.tick();
            if (!result.phaseChanges().isEmpty()) {
                change = result.phaseChanges().get(0);
            }
        }
        assertNotNull(change, "the water froze through");
        assertEquals(water, change.pos());
        assertEquals(Phase.LIQUID, change.shown());
        assertEquals(Phase.SOLID, change.now());
        assertEquals("ice", change.hostBlock());
        CellState frozen = h.world().readBlock(water);
        assertTrue(h.reconcile(water, ICE, change.temperatureK()));
        assertEquals(frozen, h.world().readBlock(water), "showing the ice changes nothing physical");
        assertTrue(h.world().audit().balanced(), () -> h.world().audit().toString());
        assertTrue(h.tick().phaseChanges().isEmpty());
    }

    @Test
    void meltedSnowGivesWayToAirAtItsTemperature() {
        HostedWorld h = hosted(HostedWorld.Settings.defaults().withTickSeconds(60.0));
        GridPos snow = new GridPos(8, 8, 8);
        h.importSection(ORIGIN, terrain(Map.of(snow, HEATED_SNOW)), 268.0);
        PhaseChange change = null;
        for (int i = 0; i < 200 && change == null; i++) {
            var changes = h.tick().phaseChanges();
            change = changes.isEmpty() ? null : changes.get(0);
        }
        assertNotNull(change, "the snow melted");
        assertEquals(Phase.SOLID, change.shown());
        assertTrue(change.now() != Phase.SOLID);
        assertEquals("air", change.hostBlock());
        assertTrue(change.temperatureK() >= 273.15);
        assertTrue(h.reconcile(snow, AIR, change.temperatureK()));
        HostedWorld.Inspection air = h.inspect(snow).orElseThrow();
        assertEquals("anchor:air", air.material());
        assertEquals(change.temperatureK(), air.temperatureK(), 1e-9);
        assertNull(air.source());
        assertTrue(h.world().audit().balanced(), () -> h.world().audit().toString());
    }

    @Test
    void aTorchWarmsTheAirAboveItAndTheRoomSettlesIntoSleep() {
        HostedWorld h = hosted();
        GridPos torch = new GridPos(8, 8, 8);
        h.importSection(ORIGIN, terrain(Map.of(torch, TORCH)), 290.0);
        assertEquals(1, h.status().awakeSections(), "a heat source wakes its section");
        int ticks = 0;
        while (h.status().awakeSections() > 0 && ticks < 5000) {
            h.tick();
            ticks++;
        }
        assertTrue(ticks < 5000, "the room reached a steady state and fell asleep");
        assertTrue(ticks > 20);
        double flame = h.temperature(torch);
        double above = h.temperature(torch.offset(0, 3, 0));
        double beside = h.temperature(torch.offset(3, 0, 0));
        assertTrue(flame > above && above > 290.0, flame + " K at the torch, " + above + " K above it");
        assertTrue(above > beside, "heat rises: " + above + " K above, " + beside + " K beside");
        assertTrue(h.world().audit().balanced(), () -> h.world().audit().toString());
    }

    @Test
    void aChangeBetweenIdsThatLookTheSameLeavesThePhysicsAlone() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, terrain(Map.of()), 290.0);
        GridPos dust = new GridPos(4, 8, 4);
        CellState before = h.world().readBlock(dust);
        assertTrue(h.reconcile(dust, DUST, 500.0), "the id changed");
        assertEquals(before, h.world().readBlock(dust));
        assertEquals(0, h.status().awakeSections(), "nothing physical happened, so the section sleeps on");
        assertEquals(0, h.status().reconciled());
        assertFalse(h.reconcile(dust, DUST, Double.NaN));
        assertEquals(0, h.verifySection(ORIGIN, terrain(Map.of(dust, DUST))), "the new id was recorded");
        assertTrue(h.reconcile(dust, STONE, Double.NaN));
        assertEquals(1, h.status().awakeSections());
    }

    @Test
    void anExperimentCanSetATemperatureAndTheNextTickShowsWhatItDid() {
        HostedWorld h = hosted();
        GridPos ice = new GridPos(6, 8, 6);
        h.importSection(ORIGIN, terrain(Map.of(ice, ICE)), 260.0);
        assertEquals(0, h.status().awakeSections());
        double mass = h.world().readBlock(ice).mass();
        assertTrue(h.setTemperature(ice, 290.0));
        assertEquals(290.0, h.temperature(ice), 1e-9);
        assertEquals(mass, h.world().readBlock(ice).mass(), "the matter stays");
        assertEquals(1, h.status().awakeSections(), "the change wakes its section");
        PhaseChange melted = h.tick().phaseChanges().get(0);
        assertEquals(ice, melted.pos());
        assertEquals(Phase.LIQUID, melted.now());
        assertEquals("water", melted.hostBlock());
        assertTrue(h.world().audit().balanced(), () -> h.world().audit().toString());
        assertFalse(h.setTemperature(new GridPos(6, 8, 40), 290.0), "the block is not imported");
        assertThrows(IllegalArgumentException.class, () -> h.setTemperature(ice, -1.0));
    }

    @Test
    void verifyingASectionCatchesChangesTheHostDidNotReport() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, terrain(Map.of()), 290.0);
        GridPos hole = new GridPos(9, 3, 9);
        assertEquals(1, h.verifySection(ORIGIN, terrain(Map.of(hole, AIR))));
        assertEquals("anchor:air", h.inspect(hole).orElseThrow().material());
        assertEquals(0, h.verifySection(ORIGIN, terrain(Map.of(hole, AIR))));
        assertEquals(0, h.verifySection(SectionPos.pack(5, 5, 5), terrain(Map.of())));
    }

    @Test
    void removingASectionForgetsEverythingInIt() {
        HostedWorld h = hosted();
        GridPos lava = new GridPos(5, 7, 5);
        h.importSection(ORIGIN, terrain(Map.of(lava, LAVA)), 290.0);
        h.tick();
        assertTrue(h.removeSection(ORIGIN));
        assertFalse(h.removeSection(ORIGIN));
        assertFalse(h.isImported(ORIGIN));
        assertEquals(0, h.status().sources());
        assertEquals(0, h.status().awakeSections());
        assertEquals(0, h.world().sectionCount());
        assertTrue(h.inspect(lava).isEmpty());
        assertTrue(h.world().audit().balanced(), () -> h.world().audit().toString());
    }

    @Test
    void importingAgainReplacesTheSection() {
        HostedWorld h = hosted();
        GridPos lava = new GridPos(5, 7, 5);
        h.importSection(ORIGIN, terrain(Map.of(lava, LAVA)), 290.0);
        h.importSection(ORIGIN, terrain(Map.of()), 295.0);
        assertEquals(0, h.status().sources());
        assertEquals(295.0, h.temperature(lava), 1e-9);
        assertEquals(1, h.status().sections());
        assertTrue(h.world().audit().balanced(), () -> h.world().audit().toString());
    }

    @Test
    void hostMistakesAreReportedClearly() {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(3), MaterialRegistry.withLibrary());
        HostedWorld unknown = new HostedWorld(world, id -> BlockAppearance.of("anchor:unobtainium"),
                HostedWorld.Settings.defaults());
        assertThrows(IllegalStateException.class, () -> unknown.importSection(ORIGIN, i -> 0, 290.0));
        HostedWorld missing = new HostedWorld(new PhysicalWorld(WorldSettings.airAt20C(3),
                MaterialRegistry.withLibrary()), id -> null, HostedWorld.Settings.defaults());
        assertThrows(IllegalStateException.class, () -> missing.importSection(ORIGIN, i -> 0, 290.0));
        assertThrows(IllegalArgumentException.class, () -> hosted().importSection(ORIGIN, i -> 0, -1.0));
    }

    @Test
    void aFreshSectionNeedsNoSnapshotUntilSomethingInItChanges() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, terrain(Map.of()), 290.0);
        assertTrue(h.snapshot(ORIGIN).isEmpty(), "a fresh import rebuilds itself from the host's blocks");
        long before = h.sectionVersion(ORIGIN);
        GridPos stone = new GridPos(3, 2, 3);
        assertTrue(h.setTemperature(stone, 400.0));
        assertTrue(h.sectionVersion(ORIGIN) > before);
        SectionSnapshot saved = h.snapshot(ORIGIN).orElseThrow();
        assertEquals(1, saved.size(), "only the block that differs from a fresh import is saved");
        assertEquals(stone.indexInSection(), saved.block(0));
        assertEquals("anchor:granite", saved.entry(0).material());
        assertEquals(h.world().readBlock(stone).enthalpy(), saved.enthalpy(0));

        assertTrue(h.setTemperature(stone, 290.0));
        assertTrue(h.snapshot(ORIGIN).isEmpty(), "a block set back to its surroundings' temperature is as new");
        long elsewhere = SectionPos.pack(4, 4, 4);
        assertTrue(h.snapshot(elsewhere).isEmpty());
        assertEquals(Long.MIN_VALUE, h.sectionVersion(elsewhere));
    }

    @Test
    void placingAndBreakingBlocksSavesNoHeat() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, terrain(Map.of()), 290.0);
        GridPos placed = new GridPos(4, 10, 4);
        assertTrue(h.reconcile(placed, STONE, Double.NaN));
        assertTrue(h.reconcile(new GridPos(4, 3, 4), AIR, Double.NaN));
        SectionSnapshot saved = h.snapshot(ORIGIN).orElseThrow();
        assertEquals(0, saved.size(), "blocks placed at the climate come back from the host's blocks");
        assertArrayEquals(new int[] {placed.indexInSection()}, saved.structureBlocks(), "only that one was built");
        assertTrue(h.reconcile(placed, AIR, Double.NaN));
        assertTrue(h.snapshot(ORIGIN).isEmpty());
    }

    @Test
    void aSavedSectionComesBackExactly() {
        HostedWorld h = hosted();
        GridPos ice = new GridPos(5, 9, 5);
        IntUnaryOperator blocks = terrain(Map.of(ice, ICE));
        h.importSection(ORIGIN, blocks, 290.0);
        assertTrue(h.setTemperature(new GridPos(3, 2, 3), 600.0));
        for (int i = 0; i < 20; i++) {
            h.tick();
        }
        assertTrue(h.inspect(new GridPos(3, 2, 3)).orElseThrow().refined(), "the hot stone is split into cells");
        SectionSnapshot saved = h.snapshot(ORIGIN).orElseThrow();
        assertTrue(saved.size() > 1 && saved.size() < SectionPos.BLOCKS, () -> saved.size() + " blocks saved");

        HostedWorld again = hosted();
        assertEquals(saved.size(), again.importSection(ORIGIN, blocks, 290.0, saved));
        assertFalse(again.inspect(new GridPos(3, 2, 3)).orElseThrow().refined(), "cells are saved as block totals");
        for (int i = 0; i < SectionPos.BLOCKS; i++) {
            GridPos p = GridPos.of(ORIGIN, i);
            CellState was = h.world().readBlock(p);
            CellState is = again.world().readBlock(p);
            assertEquals(was.material(), is.material(), () -> "material of " + p);
            assertEquals(was.mass(), is.mass(), () -> "mass of " + p);
            assertEquals(was.enthalpy(), is.enthalpy(), () -> "enthalpy of " + p);
        }
        assertEquals(Provenance.SIMULATED, again.inspect(new GridPos(3, 2, 3)).orElseThrow().provenance());
        assertEquals(saved, again.snapshot(ORIGIN).orElseThrow(), "a restored section is saved as it was");
        assertTrue(again.status().awakeSections() > 0, "the warm stone keeps the restored section awake");
        assertTrue(again.world().audit().balanced(), () -> again.world().audit().toString());
    }

    @Test
    void blocksThatChangedWhileNotSimulatedStartAfresh() {
        HostedWorld h = hosted();
        GridPos water = new GridPos(5, 9, 5);
        GridPos stone = new GridPos(3, 2, 3);
        h.importSection(ORIGIN, terrain(Map.of(water, WATER)), 290.0);
        assertTrue(h.setTemperature(water, 330.0));
        assertTrue(h.setTemperature(stone, 350.0));
        SectionSnapshot saved = h.snapshot(ORIGIN).orElseThrow();
        assertEquals(2, saved.size());

        HostedWorld again = hosted();
        assertEquals(1, again.importSection(ORIGIN, terrain(Map.of(water, STONE)), 290.0, saved));
        assertEquals("anchor:granite", again.inspect(water).orElseThrow().material());
        assertEquals(290.0, again.temperature(water), 1e-9);
        assertEquals(350.0, again.temperature(stone), 1e-9, "the stone that is still there keeps its heat");
    }

    @Test
    void blocksOfMaterialsNoLongerRegisteredStartAfresh() {
        HostedWorld h = hosted();
        GridPos stone = new GridPos(1, 1, 1);
        h.importSection(ORIGIN, terrain(Map.of()), 290.0);
        assertTrue(h.setTemperature(stone, 350.0));
        SectionSnapshot saved = h.snapshot(ORIGIN).orElseThrow();
        List<SectionSnapshot.Entry> palette = new ArrayList<>(saved.palette());
        palette.add(new SectionSnapshot.Entry("anchor:unobtainium", 0L, Provenance.SIMULATED));
        SectionSnapshot edited = new SectionSnapshot(palette, saved.blocks(), new int[] {palette.size() - 1},
                saved.masses(), saved.enthalpies());

        HostedWorld again = hosted();
        assertEquals(0, again.importSection(ORIGIN, terrain(Map.of()), 290.0, edited));
        assertEquals(290.0, again.temperature(stone), 1e-9);
    }

    @Test
    void snapshotsRefuseWhatCouldNotBeASection() {
        List<SectionSnapshot.Entry> palette = List.of(new SectionSnapshot.Entry("anchor:air", 0L,
                Provenance.INITIAL));
        int[] blocks = {3, 7};
        int[] entries = {0, 0};
        double[] mass = {1.2, 1.2};
        double[] enthalpy = {-5.0, 7.0};
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(palette, blocks, new int[1], mass,
                enthalpy), "one palette index per block");
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(palette, new int[] {7, 3}, entries,
                mass, enthalpy), "blocks in ascending order");
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(palette, new int[] {3, 3}, entries,
                mass, enthalpy), "each block once");
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(palette,
                new int[] {3, SectionPos.BLOCKS}, entries, mass, enthalpy), "blocks inside the section");
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(palette, blocks, new int[] {0, 1},
                mass, enthalpy), "palette indices inside the palette");
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(palette, blocks, entries,
                new double[] {1.2, -1.0}, enthalpy), "no negative mass");
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(palette, blocks, entries,
                new double[] {1.2, 0.0}, enthalpy), "no heat without matter");
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(palette, blocks, entries, mass,
                new double[] {Double.NaN, 7.0}), "finite enthalpy");
        SectionSnapshot snapshot = new SectionSnapshot(palette, blocks, entries, mass, enthalpy);
        blocks[0] = 5;
        enthalpy[0] = 9.0;
        assertEquals(3, snapshot.block(0), "a snapshot keeps its own copy of the data");
        assertEquals(-5.0, snapshot.enthalpy(0));
        assertEquals(new SectionSnapshot(palette, new int[] {3, 7}, entries, mass, new double[] {-5.0, 7.0}),
                snapshot);
    }
}
