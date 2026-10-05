package io.github.osir933.anchor.core.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.structure.Shape;
import io.github.osir933.anchor.core.physics.thermal.HeatSourceModel;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockAppearanceTest {

    @Test
    void aPlainBlockIsWholeAndNeverReplaced() {
        BlockAppearance stone = BlockAppearance.of("anchor:granite");
        assertEquals(1.0, stone.fill());
        assertTrue(Double.isNaN(stone.temperatureK()));
        assertFalse(stone.presentable());
        assertEquals(stone, BlockAppearance.of("anchor:granite"), "NaN temperatures compare equal");
    }

    @Test
    void copiesChangeOneThingEach() {
        HeatSourceModel.Source flame = new HeatSourceModel.Source(1300.0, 1500.0);
        BlockAppearance water = BlockAppearance.of("anchor:water").withFill(0.5).shownAs(Phase.LIQUID)
                .startingAt(280.0).heatedBy(flame).becoming(Phase.SOLID, "minecraft:ice")
                .becoming(Phase.GAS, "minecraft:air");
        assertEquals(0.5, water.fill());
        assertEquals(Phase.LIQUID, water.phase());
        assertEquals(280.0, water.temperatureK());
        assertEquals(flame, water.source());
        assertEquals(List.of(Phase.SOLID, Phase.GAS), List.copyOf(water.becomes().keySet()));
        assertTrue(water.presentable());
        assertFalse(water.withoutReplacements().presentable());
        assertFalse(water.shownAs(null).presentable(), "a block shown however it is never needs replacing");
        assertThrows(UnsupportedOperationException.class, () -> water.becomes().put(Phase.LIQUID, "x"));
    }

    @Test
    void shapesFollowTheFillUnlessGivenAndFramesCarryThinBlocks() {
        BlockAppearance slab = BlockAppearance.of("anchor:granite").withFill(0.5);
        assertEquals(Shape.bottom(0.5), slab.shape());
        assertEquals(Shape.FULL, slab.withFill(1.0).shape(), "a shape that follows the fill keeps following it");
        Shape stairs = Shape.of(new double[] {0, 0, 0, 1, 0.5, 1, 0.5, 0.5, 0, 1, 1, 1});
        BlockAppearance stair = BlockAppearance.of("anchor:granite").withFill(0.75).withShape(stairs);
        assertEquals(stairs, stair.withFill(0.7).shape(), "a shape of its own stays");
        assertEquals(stairs, stair.shownAs(Phase.SOLID).heatedBy(null).withAlbedo(0.3).shape());
        assertEquals(Shape.bottom(0.75), stair.withShape(null).shape());
        assertNull(stair.frame());
        Shape post = Shape.of(new double[] {0.375, 0, 0.375, 0.625, 1, 0.625});
        BlockAppearance fence = BlockAppearance.of("anchor:air").withShape(post).framedIn("anchor:hardwood");
        assertEquals("anchor:hardwood", fence.frame());
        assertEquals("anchor:hardwood", fence.heatedBy(new HeatSourceModel.Source(1000.0, 300.0)).withFill(0.5)
                .becoming(Phase.GAS, "minecraft:air").frame(), "copies keep the frame");
        assertNull(fence.framedIn(null).frame());
        assertThrows(IllegalArgumentException.class, () -> fence.framedIn(" "));
    }

    @Test
    void nonsenseIsRejected() {
        BlockAppearance stone = BlockAppearance.of("anchor:granite");
        assertThrows(IllegalArgumentException.class, () -> stone.withFill(0.0));
        assertThrows(IllegalArgumentException.class, () -> stone.withFill(1.5));
        assertThrows(IllegalArgumentException.class, () -> stone.withFill(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> stone.startingAt(-3.0));
        assertThrows(IllegalArgumentException.class, () -> stone.startingAt(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> BlockAppearance.of(" "));
        assertThrows(IllegalArgumentException.class,
                () -> stone.shownAs(Phase.SOLID).becoming(Phase.SOLID, "minecraft:stone"));
        assertThrows(IllegalArgumentException.class, () -> stone.becoming(Phase.LIQUID, ""));
        Map<Phase, String> withNull = new EnumMap<>(Phase.class);
        withNull.put(Phase.GAS, null);
        assertThrows(NullPointerException.class,
                () -> new BlockAppearance("anchor:air", 1.0, null, Double.NaN, null, withNull));
    }
}
