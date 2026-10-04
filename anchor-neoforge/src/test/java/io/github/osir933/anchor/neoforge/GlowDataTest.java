package io.github.osir933.anchor.neoforge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.host.GlowingBlock;
import io.github.osir933.anchor.core.physics.thermal.Incandescence;
import io.github.osir933.anchor.core.space.Direction;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class GlowDataTest {

    private static final int ALL_FACES = 0b111111;

    private static double[] even(double kelvin, int faces) {
        double[] t = new double[6 * GlowingBlock.SAMPLES];
        Arrays.fill(t, Double.NaN);
        for (Direction d : Direction.values()) {
            if ((faces & 1 << d.ordinal()) != 0) {
                Arrays.fill(t, d.ordinal() * GlowingBlock.SAMPLES, (d.ordinal() + 1) * GlowingBlock.SAMPLES, kelvin);
            }
        }
        return t;
    }

    @Test
    void nothingGlowingTakesNoBytes() {
        assertSame(GlowData.NONE, GlowData.encode(List.of()));
        assertEquals(List.of(), GlowData.decode(GlowData.NONE));
    }

    @Test
    void anEvenBlockTravelsAsOneTemperaturePerFace() {
        int faces = ALL_FACES & ~(1 << Direction.DOWN.ordinal());
        GlowingBlock block = new GlowingBlock(1234, 0.7, faces, even(1300.4, faces));
        byte[] data = GlowData.encode(List.of(block));
        assertEquals(4 + 5 + 5 * 2, data.length, "count, header and five temperatures");

        List<GlowData.Block> back = GlowData.decode(data);
        assertEquals(1, back.size());
        GlowData.Block b = back.get(0);
        assertEquals(1234, b.index());
        assertEquals(0.7, b.emissivity(), 0.5 / 255);
        assertEquals(faces, b.faces());
        for (Direction d : Direction.values()) {
            for (int s = 0; s < GlowingBlock.SAMPLES; s++) {
                double t = b.temperatures()[d.ordinal() * GlowingBlock.SAMPLES + s];
                if (d == Direction.DOWN) {
                    assertTrue(Double.isNaN(t));
                } else {
                    assertEquals(1300.0, t, "rounded to whole kelvin");
                }
            }
        }
    }

    @Test
    void anUnevenFaceTravelsSpotBySpot() {
        double[] t = even(1000.0, ALL_FACES);
        for (int v = 0; v < GlowingBlock.GRID; v++) {
            for (int u = 0; u < GlowingBlock.GRID; u++) {
                t[GlowingBlock.sample(Direction.WEST, u, v)] = 900.0 + 50.0 * v + u;
            }
        }
        t[GlowingBlock.sample(Direction.UP, 2, 2)] = Double.NaN;
        GlowingBlock block = new GlowingBlock(7, 0.95, ALL_FACES, t);
        byte[] data = GlowData.encode(List.of(block, new GlowingBlock(8, 0.4, 1, even(2000.0, 1))));
        assertEquals(4 + 5 + 4 * 2 + 2 * 16 * 2 + 5 + 2, data.length);

        List<GlowData.Block> back = GlowData.decode(data);
        assertEquals(2, back.size());
        assertArrayEquals(t, back.get(0).temperatures(), 0.0, "whole temperatures and gaps come back as they were");
        assertEquals(8, back.get(1).index());
        assertEquals(2000.0, back.get(1).temperatures()[0]);
    }

    @Test
    void coloursFollowTheBlackBody() {
        GlowingBlock block = new GlowingBlock(0, 0.7, ALL_FACES, even(1300.0, ALL_FACES));
        GlowData.Block b = GlowData.decode(GlowData.encode(List.of(block))).get(0);
        int[] colours = b.colours();
        assertEquals(Incandescence.glow(1300.0, b.emissivity()).argb(), colours[0]);
        assertTrue(GlowData.Block.uniform(colours, Direction.NORTH));
        int alpha = colours[0] >>> 24;
        int red = colours[0] >> 16 & 0xFF;
        int green = colours[0] >> 8 & 0xFF;
        int blue = colours[0] & 0xFF;
        assertTrue(alpha > 100 && red == 255 && green < red && blue < green, Integer.toHexString(colours[0]));

        double[] cool = even(600.0, ALL_FACES);
        cool[GlowingBlock.sample(Direction.EAST, 3, 3)] = 1500.0;
        int[] spotted = new GlowData.Block(0, 0.7, ALL_FACES, cool).colours();
        assertEquals(0, spotted[0], "600 K does not glow");
        assertFalse(GlowData.Block.uniform(spotted, Direction.EAST));
        assertTrue(GlowData.Block.uniform(spotted, Direction.WEST));
    }

    @Test
    void temperaturesAreKeptWithinWhatAShortHolds() {
        GlowingBlock hot = new GlowingBlock(0, 1.0, 1, even(1.0e6, 1));
        assertEquals(65535.0, GlowData.decode(GlowData.encode(List.of(hot))).get(0).temperatures()[0]);
    }

    @Test
    void damagedDataIsRefused() {
        byte[] data = GlowData.encode(List.of(new GlowingBlock(5, 0.5, ALL_FACES, even(1200.0, ALL_FACES))));
        assertThrows(IllegalArgumentException.class, () -> GlowData.decode(Arrays.copyOf(data, data.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> GlowData.decode(Arrays.copyOf(data, data.length + 1)));
        byte[] badIndex = data.clone();
        badIndex[4] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> GlowData.decode(badIndex));
        byte[] badCount = data.clone();
        badCount[0] = (byte) 0x80;
        assertThrows(IllegalArgumentException.class, () -> GlowData.decode(badCount));
        assertTrue(GlowData.MAX_BYTES >= data.length);
    }
}
