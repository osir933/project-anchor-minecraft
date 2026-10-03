package io.github.osir933.anchor.core.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.Phase;
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
