package io.github.osir933.anchor.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockCopyTest {

    private static final GridPos ORIGIN = new GridPos(0, 64, 0);

    private static PhysicalWorld airWorld(int maxLeaves) {
        WorldSettings air = WorldSettings.airAt20C(42);
        return new PhysicalWorld(new WorldSettings(air.seed(), air.ambientMaterial(), air.ambientTemperatureK(),
                maxLeaves), MaterialRegistry.withLibrary());
    }

    /** Granite refined around one small cell, with heat moved between two of its leaves as conduction would. */
    private static PhysicalWorld refinedGranite() {
        PhysicalWorld world = airWorld(WorldSettings.DEFAULT_MAX_LEAVES);
        world.placeMaterial(ORIGIN, MaterialLibrary.GRANITE, 300.0);
        CellId small = new CellId(ORIGIN, 3, 5, 2, 7);
        assertTrue(world.refine(small).applied());
        CellId big = new CellId(ORIGIN, 1, 0, 0, 0);
        CellState a = world.readLeaf(small);
        CellState b = world.readLeaf(big);
        double joules = 0.25 * a.enthalpy();
        world.writeLeaf(small, new CellState(a.material(), a.mass(), a.enthalpy() + joules, a.owner(),
                Provenance.SIMULATED));
        world.writeLeaf(big, new CellState(b.material(), b.mass(), b.enthalpy() - joules, b.owner(),
                Provenance.SIMULATED));
        return world;
    }

    private static List<String> leaves(PhysicalWorld world, GridPos pos) {
        List<String> leaves = new ArrayList<>();
        world.refinedBlock(pos).forEachLeaf((cell, state) -> leaves.add(cell + " " + state));
        return leaves;
    }

    @Test
    void cellsPackIntoNumbersAndBack() {
        CellId cell = new CellId(ORIGIN, 10, 1023, 5, 777);
        assertEquals(cell, BlockCopy.unpack(ORIGIN, BlockCopy.pack(cell)));
        assertEquals(new CellId(ORIGIN, 2, 3, 0, 1), BlockCopy.unpack(ORIGIN, 2 | 3L << 4 | 1L << 24));
        assertEquals(BlockCopy.WHOLE, BlockCopy.pack(CellId.of(ORIGIN)));
        assertThrows(IllegalArgumentException.class, () -> BlockCopy.unpack(ORIGIN, 11), "no level 11");
        assertThrows(IllegalArgumentException.class, () -> BlockCopy.unpack(ORIGIN, 1L << 34), "unused bits");
        assertThrows(IllegalArgumentException.class, () -> BlockCopy.unpack(ORIGIN, -1L));
        assertThrows(IllegalArgumentException.class, () -> BlockCopy.unpack(ORIGIN, 1 | 2L << 4),
                "a level 1 cell has no x index 2");
    }

    @Test
    void theCellsOfABlockTellWhereTheNextOneBegins() {
        CellId block = CellId.of(ORIGIN);
        long[] run = new long[1 + 8 + 15];
        run[0] = BlockCopy.WHOLE;
        for (int i = 0; i < 8; i++) {
            run[1 + i] = BlockCopy.pack(block.child(i));
        }
        for (int i = 0; i < 8; i++) {
            run[9 + i] = BlockCopy.pack(block.child(0).child(i));
        }
        for (int i = 1; i < 8; i++) {
            run[16 + i] = BlockCopy.pack(block.child(i));
        }
        assertEquals(1, BlockCopy.cellsOfBlock(run, 0), "a whole block is one cell");
        assertEquals(8, BlockCopy.cellsOfBlock(run, 1));
        assertEquals(15, BlockCopy.cellsOfBlock(run, 9), "cells of different sizes, depth first");

        long[] swapped = run.clone();
        swapped[2] = run[3];
        swapped[3] = run[2];
        assertThrows(IllegalArgumentException.class, () -> BlockCopy.cellsOfBlock(swapped, 1), "octant order");
        long[] overlapping = {BlockCopy.pack(block.child(0)), BlockCopy.pack(block.child(0).child(1))};
        assertThrows(IllegalArgumentException.class, () -> BlockCopy.cellsOfBlock(overlapping, 0));
        long[] gap = {BlockCopy.pack(block.child(0)), BlockCopy.pack(block.child(2))};
        assertThrows(IllegalArgumentException.class, () -> BlockCopy.cellsOfBlock(gap, 0));
        assertThrows(IllegalArgumentException.class, () -> BlockCopy.cellsOfBlock(run, 2), "ends too soon");
        assertThrows(IllegalArgumentException.class, () -> BlockCopy.cellsOfBlock(new long[0], 0));
    }

    @Test
    void aRefinedBlockComesBackCellForCell() {
        PhysicalWorld world = refinedGranite();
        BlockCopy copy = world.copyBlock(ORIGIN);
        assertTrue(copy.isRefined());
        assertEquals(22, copy.cellCount());
        assertEquals(world.readBlock(ORIGIN), copy.total());
        List<String> leaves = leaves(world, ORIGIN);
        String hash = world.stateHash();
        world.auditAndRebase();

        world.placeMaterial(ORIGIN, MaterialLibrary.IRON, 1200.0);
        assertFalse(world.isRefined(ORIGIN));
        assertEquals(1, world.restoreBlocks(List.of(ORIGIN), List.of(copy), "the test"));

        assertEquals(leaves, leaves(world, ORIGIN));
        assertEquals(22, world.leafCount());
        assertEquals(copy.total(), world.readBlock(ORIGIN));
        assertEquals(hash, world.stateHash(), "the world is bit for bit as it was");
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
        WorldEvent restored = world.events().recent(1).get(0);
        assertEquals(WorldEvent.Kind.RESTORE, restored.kind());
        assertEquals("the test", restored.subject());

        long version = world.section(ORIGIN.sectionKey()).version();
        assertEquals(0, world.restoreBlocks(List.of(ORIGIN), List.of(copy), "again"));
        assertEquals(version, world.section(ORIGIN.sectionKey()).version(), "a block that holds its copy is left");
    }

    @Test
    void aWholeCopyReplacesARefinedBlock() {
        PhysicalWorld world = refinedGranite();
        world.auditAndRebase();
        BlockCopy ambient = world.copyBlock(ORIGIN.offset(0, 0, 40));
        assertEquals(BlockCopy.whole(world.ambientBlock()), ambient, "where there is no section, the ambient state");
        assertEquals(1, world.restoreBlocks(List.of(ORIGIN), List.of(ambient), "air"));
        assertFalse(world.isRefined(ORIGIN));
        assertEquals(0, world.leafCount());
        assertEquals(world.ambientBlock(), world.readBlock(ORIGIN));
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void aCopyThatWouldExceedTheLeafBudgetComesBackWhole() {
        BlockCopy copy = refinedGranite().copyBlock(ORIGIN);
        PhysicalWorld small = airWorld(10);
        assertEquals(1, small.restoreBlocks(List.of(ORIGIN), List.of(copy), "into a small world"));
        assertFalse(small.isRefined(ORIGIN));
        assertEquals(copy.total(), small.readBlock(ORIGIN), "as its totals");
        assertTrue(small.events().recent(1).get(0).detail().contains("leaf budget"));
        assertTrue(small.audit().balanced(), () -> small.audit().toString());
    }

    @Test
    void copiesAreBuiltFromTheirCellsAndChecked() {
        BlockCopy copy = refinedGranite().copyBlock(ORIGIN);
        long[] cells = new long[copy.cellCount()];
        List<CellState> states = new ArrayList<>();
        for (int i = 0; i < cells.length; i++) {
            cells[i] = copy.cell(i);
            states.add(copy.state(i));
        }
        BlockCopy rebuilt = BlockCopy.of(cells, states);
        assertEquals(copy, rebuilt);
        assertEquals(copy.hashCode(), rebuilt.hashCode());
        assertEquals(copy.total(), rebuilt.total(), "the totals the world would show");

        CellState granite = states.get(0);
        assertEquals(BlockCopy.whole(granite), BlockCopy.of(new long[] {BlockCopy.WHOLE}, List.of(granite)));
        assertNotEquals(BlockCopy.whole(granite), copy);
        assertThrows(IllegalArgumentException.class, () -> BlockCopy.of(cells, states.subList(1, states.size())),
                "one state per cell");
        assertThrows(IllegalArgumentException.class, () -> BlockCopy.of(new long[] {BlockCopy.WHOLE,
                BlockCopy.WHOLE}, List.of(granite, granite)), "the cells of one block");
        assertThrows(IllegalArgumentException.class, () -> BlockCopy.of(new long[0], List.of()));
        PhysicalWorld world = airWorld(WorldSettings.DEFAULT_MAX_LEAVES);
        assertThrows(IllegalArgumentException.class, () -> world.restoreBlocks(List.of(ORIGIN), List.of(),
                "nothing"));
    }
}
