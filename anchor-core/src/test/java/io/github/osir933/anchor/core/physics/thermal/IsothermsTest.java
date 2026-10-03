package io.github.osir933.anchor.core.physics.thermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.model.SimulationScope;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Section;
import io.github.osir933.anchor.core.world.WorldSettings;
import io.github.osir933.anchor.core.world.WorldSnapshot;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class IsothermsTest {

    private static PhysicalWorld vacuumWorld() {
        return new PhysicalWorld(WorldSettings.vacuum(13), MaterialRegistry.withLibrary());
    }

    private static SortedSet<Long> everything(PhysicalWorld world) {
        return SimulationScope.everywhere().sections(world, Domain.THERMAL);
    }

    @Test
    void differentMaterialsAtOneTemperatureAreQuiet() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(new GridPos(1, 1, 1), MaterialLibrary.GRANITE, 300.0);
        world.placeMaterial(new GridPos(2, 1, 1), MaterialLibrary.WATER, 300.0);
        world.placeMaterial(new GridPos(3, 1, 1), MaterialLibrary.AIR, 300.0);
        world.refine(new CellId(new GridPos(1, 1, 1), 2, 1, 2, 3));
        Isotherms isotherms = new Isotherms();
        Section s = world.section(new GridPos(1, 1, 1).sectionKey());
        assertTrue(isotherms.isIsothermal(world, s));
        assertTrue(isotherms.isQuiet(world, everything(world), s));
        assertEquals(0, ThermalGraph.build(world, everything(world), isotherms).leafCount);
        world.placeMaterial(new GridPos(3, 1, 1), MaterialLibrary.AIR, 301.0);
        assertFalse(isotherms.isQuiet(world, everything(world), s));
    }

    @Test
    void aNeighbourCountsOnlyWhereItTouches() {
        PhysicalWorld world = vacuumWorld();
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 31, 15), MaterialLibrary.GRANITE, 300.0);
        Section below = world.section(new GridPos(0, 0, 0).sectionKey());
        world.placeMaterial(new GridPos(5, 31, 5), MaterialLibrary.GRANITE, 400.0);
        Isotherms isotherms = new Isotherms();
        assertTrue(isotherms.isQuiet(world, everything(world), below), "the hot block is on the far side");
        world.placeMaterial(new GridPos(5, 16, 5), MaterialLibrary.GRANITE, 400.0);
        assertFalse(isotherms.isQuiet(world, everything(world), below), "now it touches the shared face");
    }

    @Test
    void neighboursOutsideTheScopeDoNotCount() {
        PhysicalWorld world = vacuumWorld();
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 31, 15), MaterialLibrary.GRANITE, 300.0);
        world.placeMaterial(new GridPos(5, 16, 5), MaterialLibrary.GRANITE, 400.0);
        Section below = world.section(new GridPos(0, 0, 0).sectionKey());
        SortedSet<Long> onlyBelow = new TreeSet<>(List.of(below.key()));
        assertTrue(new Isotherms().isQuiet(world, onlyBelow, below));
    }

    @Test
    void editsAndRestoresAreNoticed() {
        PhysicalWorld world = vacuumWorld();
        GridPos pos = new GridPos(4, 4, 4);
        world.placeMaterial(pos, MaterialLibrary.IRON, 300.0);
        world.placeMaterial(pos.offset(1, 0, 0), MaterialLibrary.IRON, 300.0);
        WorldSnapshot calm = world.snapshot();
        Isotherms isotherms = new Isotherms();
        assertTrue(isotherms.isQuiet(world, everything(world), world.section(pos.sectionKey())));
        world.placeMaterial(pos, MaterialLibrary.IRON, 350.0);
        assertFalse(isotherms.isQuiet(world, everything(world), world.section(pos.sectionKey())));
        world.restore(calm);
        assertTrue(isotherms.isQuiet(world, everything(world), world.section(pos.sectionKey())));
        isotherms.prune(world);
        assertTrue(isotherms.isQuiet(world, everything(world), world.section(pos.sectionKey())));
    }
}
