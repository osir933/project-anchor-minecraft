package io.github.osir933.anchor.core.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.thermal.Sky;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.BlockCopy;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.WorldEvent;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class RegionSnapshotTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int WATER = 2;
    private static final int ICE = 3;

    private static final BlockAppearance[] LOOKS = {
        BlockAppearance.of("anchor:air"),
        BlockAppearance.of("anchor:granite"),
        BlockAppearance.of("anchor:water").shownAs(Phase.LIQUID).becoming(Phase.SOLID, "ice")
                .becoming(Phase.GAS, "air"),
        BlockAppearance.of("anchor:water").shownAs(Phase.SOLID).becoming(Phase.LIQUID, "water")
                .becoming(Phase.GAS, "air"),
    };

    private static final double CLIMATE = 290.0;
    private static final long ORIGIN = SectionPos.pack(0, 0, 0);
    private static final long EAST = SectionPos.pack(1, 0, 0);
    private static final long SOUTH = SectionPos.pack(0, 0, 1);
    private static final long SOUTH_EAST = SectionPos.pack(1, 0, 1);

    /** The host's world: stone below y = 8, air above, and the blocks placed in it. */
    private static final class Host {
        private final TreeMap<GridPos, Integer> placed = new TreeMap<>();

        int at(GridPos pos) {
            return placed.getOrDefault(pos, pos.y() < 8 ? STONE : AIR);
        }

        HostedWorld simulate(long... sections) {
            HostedWorld h = new HostedWorld(new PhysicalWorld(WorldSettings.airAt20C(3),
                    MaterialRegistry.withLibrary()), id -> LOOKS[id], HostedWorld.Settings.defaults());
            for (long key : sections) {
                h.importSection(key, i -> at(GridPos.of(key, i)), CLIMATE);
            }
            return h;
        }
    }

    private static List<BlockCopy> copies(HostedWorld h, GridPos min, GridPos max) {
        List<BlockCopy> copies = new ArrayList<>();
        for (int y = min.y(); y <= max.y(); y++) {
            for (int z = min.z(); z <= max.z(); z++) {
                for (int x = min.x(); x <= max.x(); x++) {
                    copies.add(h.world().copyBlock(new GridPos(x, y, z)));
                }
            }
        }
        return copies;
    }

    private static void tick(HostedWorld h, int steps) {
        for (int i = 0; i < steps; i++) {
            h.tick();
        }
    }

    @Test
    void aBoxComesBackCellForCellAndNothingOutsideItChanges() {
        Host host = new Host();
        GridPos hot = new GridPos(14, 6, 8);
        HostedWorld h = host.simulate(ORIGIN, EAST);
        assertTrue(h.setTemperature(hot, 900.0));
        tick(h, 20);
        assertTrue(h.inspect(hot).orElseThrow().refined(), "the hot stone is split into cells");
        GridPos min = new GridPos(10, 4, 4);
        GridPos max = new GridPos(20, 12, 12);
        RegionSnapshot snapshot = h.capture(min, max, host::at);
        List<BlockCopy> saved = copies(h, min, max);
        assertEquals(11 * 9 * 9, snapshot.volume());

        tick(h, 40);
        assertNotEquals(saved, copies(h, min, max), "heat has moved on");
        List<BlockCopy> outside = new ArrayList<>(copies(h, new GridPos(0, 0, 0), new GridPos(31, 15, 3)));
        outside.addAll(copies(h, new GridPos(21, 0, 0), new GridPos(31, 15, 15)));
        h.world().auditAndRebase();

        HostedWorld.Restored restored = h.restore(snapshot, min, host::at);
        assertEquals(snapshot.volume(), restored.blocks());
        assertTrue(restored.changed() > 0 && restored.changed() < restored.blocks(), restored::toString);
        assertEquals(0, restored.afresh());
        assertEquals(saved, copies(h, min, max), "every cell is as it was, to the last bit");
        assertTrue(h.inspect(hot).orElseThrow().refined());
        List<BlockCopy> outsideNow = new ArrayList<>(copies(h, new GridPos(0, 0, 0), new GridPos(31, 15, 3)));
        outsideNow.addAll(copies(h, new GridPos(21, 0, 0), new GridPos(31, 15, 15)));
        assertEquals(outside, outsideNow, "nothing outside the box is touched");
        assertTrue(h.world().audit().balanced(), () -> h.world().audit().toString());
        assertEquals(WorldEvent.Kind.RESTORE, h.world().events().recent(1).get(0).kind());
        assertEquals(2, h.status().awakeSections(), "the sections it touches wake up");

        tick(h, 20);
        assertTrue(h.world().audit().balanced(), "and heat runs on from the restored state");
        assertNotEquals(saved, copies(h, min, max));
        h.restore(snapshot, min, host::at);
        assertEquals(saved, copies(h, min, max), "a snapshot can be restored again and again");
    }

    @Test
    void aSnapshotKeepsOnlyWhatDiffersFromTheHostsBlocks() {
        Host host = new Host();
        HostedWorld h = host.simulate(ORIGIN);
        GridPos min = new GridPos(2, 2, 2);
        GridPos max = new GridPos(9, 11, 6);
        RegionSnapshot fresh = h.capture(min, max, host::at);
        assertEquals(0, fresh.size(), "a box where nothing happened comes back from the host's blocks");
        assertEquals(8, fresh.sizeX());
        assertEquals(10, fresh.sizeY());
        assertEquals(5, fresh.sizeZ());

        GridPos warm = new GridPos(4, 6, 3);
        assertTrue(h.setTemperature(warm, 350.0));
        RegionSnapshot one = h.capture(min, max, host::at);
        assertEquals(1, one.size());
        assertEquals(one.indexOf(2, 4, 1), one.block(0));
        assertEquals(1, one.cellCount(0));
        assertEquals(BlockCopy.WHOLE, one.cell(0));
        assertEquals(new SectionSnapshot.Entry("anchor:granite", 0L, Provenance.INITIAL), one.entry(0));
        assertEquals(h.world().readBlock(warm).mass(), one.mass(0));
        assertEquals(h.world().readBlock(warm).enthalpy(), one.enthalpy(0));

        assertTrue(h.setTemperature(warm, 900.0));
        tick(h, 20);
        RegionSnapshot refined = h.capture(min, max, host::at);
        assertTrue(refined.size() > 1 && refined.size() < refined.volume(), refined::toString);
        assertTrue(refined.cells().length > refined.size(), "refined blocks are kept cell for cell");
        RegionSnapshot stored = new RegionSnapshot(refined.sizeX(), refined.sizeY(), refined.sizeZ(),
                refined.palette(), refined.blocks(), refined.cells(), refined.paletteIndices(), refined.masses(),
                refined.enthalpies());
        assertEquals(refined, stored, "a snapshot is its arrays, for storing");
        assertEquals(refined.hashCode(), stored.hashCode());
    }

    @Test
    void theHostsUnreportedChangesAreTakenInBeforeSaving() {
        Host host = new Host();
        HostedWorld h = host.simulate(ORIGIN);
        GridPos dug = new GridPos(5, 7, 5);
        host.placed.put(dug, AIR);
        RegionSnapshot snapshot = h.capture(new GridPos(4, 4, 4), new GridPos(6, 9, 6), host::at);
        assertEquals("anchor:air", h.inspect(dug).orElseThrow().material());
        assertEquals(0, snapshot.size(), "the air dug out is as the host's block starts");
    }

    @Test
    void savedBlocksThatNoLongerFitStartAfresh() {
        Host host = new Host();
        GridPos water = new GridPos(5, 9, 5);
        GridPos stone = new GridPos(5, 3, 5);
        host.placed.put(water, WATER);
        HostedWorld h = host.simulate(ORIGIN);
        assertTrue(h.setTemperature(water, 330.0));
        assertTrue(h.setTemperature(stone, 350.0));
        GridPos min = new GridPos(3, 2, 3);
        GridPos max = new GridPos(7, 10, 7);
        RegionSnapshot snapshot = h.capture(min, max, host::at);
        assertEquals(2, snapshot.size());

        host.placed.put(water, STONE);
        assertTrue(h.reconcile(water, STONE, Double.NaN));
        HostedWorld.Restored restored = h.restore(snapshot, min, host::at);
        assertEquals(1, restored.afresh(), "warm water does not fit a block of stone");
        assertEquals("anchor:granite", h.inspect(water).orElseThrow().material());
        assertEquals(CLIMATE, h.temperature(water), 1e-9);
        assertEquals(350.0, h.temperature(stone), 1e-9, "the stone that is still there gets its heat back");

        List<SectionSnapshot.Entry> palette = new ArrayList<>(snapshot.palette());
        palette.add(new SectionSnapshot.Entry("anchor:unobtainium", 0L, Provenance.SIMULATED));
        int[] entries = snapshot.paletteIndices();
        entries[0] = palette.size() - 1;
        RegionSnapshot edited = new RegionSnapshot(snapshot.sizeX(), snapshot.sizeY(), snapshot.sizeZ(), palette,
                snapshot.blocks(), snapshot.cells(), entries, snapshot.masses(), snapshot.enthalpies());
        assertEquals(2, h.restore(edited, min, host::at).afresh(), "a material no longer registered");
        assertEquals(CLIMATE, h.temperature(stone), 1e-9);
    }

    @Test
    void onlyBoxesWhoseSectionsAreAllImportedCanBeSavedOrRestored() {
        Host host = new Host();
        HostedWorld h = host.simulate(ORIGIN);
        GridPos min = new GridPos(10, 2, 2);
        GridPos max = new GridPos(20, 4, 4);
        assertTrue(h.importsAll(min, new GridPos(15, 4, 4)));
        assertFalse(h.importsAll(min, max));
        assertThrows(IllegalStateException.class, () -> h.capture(min, max, host::at));
        RegionSnapshot snapshot = h.capture(min, new GridPos(15, 4, 4), host::at);
        assertThrows(IllegalStateException.class, () -> h.restore(snapshot, new GridPos(12, 2, 2), host::at));
        assertThrows(IllegalArgumentException.class, () -> h.importsAll(max, min));
    }

    @Test
    void aSnapshotCanBeRestoredSomewhereElse() {
        Host host = new Host();
        GridPos hot = new GridPos(8, 5, 8);
        HostedWorld h = host.simulate(ORIGIN, EAST, SOUTH, SOUTH_EAST);
        assertTrue(h.setTemperature(hot, 900.0));
        tick(h, 20);
        GridPos min = new GridPos(5, 2, 5);
        GridPos max = new GridPos(11, 9, 11);
        RegionSnapshot snapshot = h.capture(min, max, host::at);
        GridPos elsewhere = new GridPos(19, 2, 21);
        HostedWorld.Restored restored = h.restore(snapshot, elsewhere, host::at);
        assertEquals(0, restored.afresh());
        assertEquals(copies(h, min, max), copies(h, elsewhere, elsewhere.offset(6, 7, 6)),
                "the same terrain takes the same cells");
        assertTrue(h.inspect(new GridPos(22, 5, 24)).orElseThrow().refined());
    }

    @Test
    void restoringASnapshotUndoesWhatHappenedAndNothingElseDoes() {
        Host host = new Host();
        GridPos ice = new GridPos(5, 9, 5);
        host.placed.put(ice, ICE);
        HostedWorld h = host.simulate(ORIGIN);
        GridPos min = new GridPos(3, 7, 3);
        GridPos max = new GridPos(7, 11, 7);
        RegionSnapshot snapshot = h.capture(min, max, host::at);
        List<BlockCopy> before = copies(h, min, max);

        assertTrue(h.setTemperature(ice, 300.0));
        List<PhaseChange> changes = h.tick().phaseChanges();
        assertEquals(1, changes.size(), "the ice has melted");
        assertEquals("water", changes.get(0).hostBlock());
        host.placed.put(ice, WATER);
        h.reconcile(ice, WATER, changes.get(0).temperatureK());
        tick(h, 200);
        assertEquals(Phase.LIQUID, h.inspect(ice).orElseThrow().phase(), "nothing in the simulation refreezes it");

        host.placed.put(ice, ICE);
        h.restore(snapshot, min, host::at);
        assertEquals(before, copies(h, min, max), "the snapshot rewinds it");
        assertEquals(Phase.SOLID, h.inspect(ice).orElseThrow().phase());
        assertTrue(h.tick().phaseChanges().isEmpty(), "and the ice is shown as ice");
    }

    @Test
    void restoredSurfacesStartTheirSkinsAgain() {
        Host host = new Host();
        HostedWorld h = host.simulate(ORIGIN, SectionPos.pack(0, 1, 0));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setSkyHeight(x, z, 8);
            }
        }
        h.setSky(Sky.clear(90.0));
        tick(h, 400);
        GridPos ground = new GridPos(7, 7, 7);
        GridPos min = new GridPos(5, 5, 5);
        GridPos max = new GridPos(9, 9, 9);
        RegionSnapshot snapshot = h.capture(min, max, host::at);
        tick(h, 400);
        double skin = h.inspect(ground).orElseThrow().surfaceK();
        assertTrue(skin > h.temperature(ground), "the sun warms the ground's skin");
        double beside = h.inspect(new GridPos(12, 7, 7)).orElseThrow().surfaceK();

        h.restore(snapshot, min, host::at);
        assertTrue(Double.isNaN(h.inspect(ground).orElseThrow().surfaceK()), "the skin starts again");
        assertEquals(beside, h.inspect(new GridPos(12, 7, 7)).orElseThrow().surfaceK(), "outside the box it stays");
        h.tick();
        double again = h.inspect(ground).orElseThrow().surfaceK();
        assertTrue(again < skin && again > h.temperature(ground), () -> again + " K after one step, " + skin
                + " K before the restore");
    }

    @Test
    void snapshotsRefuseWhatCouldNotBeABox() {
        List<SectionSnapshot.Entry> palette = List.of(new SectionSnapshot.Entry("anchor:air", 0L,
                Provenance.INITIAL));
        long half = 1;
        long[] eighths = new long[8];
        for (int i = 0; i < 8; i++) {
            eighths[i] = half | (long) (i & 1) << 4 | (long) (i >> 1 & 1) << 14 | (long) (i >> 2) << 24;
        }
        int[] entries = new int[9];
        double[] mass = new double[9];
        double[] enthalpy = new double[9];
        long[] cells = new long[9];
        System.arraycopy(eighths, 0, cells, 1, 8);
        RegionSnapshot ok = new RegionSnapshot(2, 1, 1, palette, new int[] {0, 1}, cells, entries, mass, enthalpy);
        assertEquals(1, ok.cellCount(0));
        assertEquals(8, ok.cellCount(1));
        assertEquals(1, ok.firstCell(1));

        assertThrows(IllegalArgumentException.class, () -> new RegionSnapshot(0, 1, 1, palette, new int[0],
                new long[0], new int[0], new double[0], new double[0]), "an empty box");
        assertThrows(IllegalArgumentException.class, () -> new RegionSnapshot(65_536, 65_536, 1, palette,
                new int[0], new long[0], new int[0], new double[0], new double[0]), "too many blocks to number");
        assertThrows(IllegalArgumentException.class, () -> new RegionSnapshot(2, 1, 1, palette, new int[] {1, 0},
                cells, entries, mass, enthalpy), "blocks in ascending order");
        assertThrows(IllegalArgumentException.class, () -> new RegionSnapshot(2, 1, 1, palette, new int[] {0, 2},
                cells, entries, mass, enthalpy), "blocks inside the box");
        assertThrows(IllegalArgumentException.class, () -> new RegionSnapshot(2, 1, 1, palette, new int[] {0},
                cells, entries, mass, enthalpy), "cells of no block");
        assertThrows(IllegalArgumentException.class, () -> new RegionSnapshot(3, 1, 1, palette,
                new int[] {0, 1, 2}, cells, entries, mass, enthalpy), "a block without cells");
        assertThrows(IllegalArgumentException.class, () -> new RegionSnapshot(2, 1, 1, palette, new int[] {0, 1},
                cells, new int[8], mass, enthalpy), "one palette index per cell");
        int[] outside = new int[9];
        outside[4] = 1;
        assertThrows(IllegalArgumentException.class, () -> new RegionSnapshot(2, 1, 1, palette, new int[] {0, 1},
                cells, outside, mass, enthalpy), "palette indices inside the palette");
        double[] heat = new double[9];
        heat[2] = 5.0;
        assertThrows(IllegalArgumentException.class, () -> new RegionSnapshot(2, 1, 1, palette, new int[] {0, 1},
                cells, entries, mass, heat), "no heat without matter");
        double[] negative = new double[9];
        negative[0] = -1.0;
        assertThrows(IllegalArgumentException.class, () -> new RegionSnapshot(2, 1, 1, palette, new int[] {0, 1},
                cells, entries, negative, enthalpy), "no negative mass");
        assertThrows(IllegalArgumentException.class, () -> ok.indexOf(2, 0, 0));
    }
}
