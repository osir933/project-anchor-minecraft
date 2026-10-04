package io.github.osir933.anchor.core.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.thermal.Sky;
import io.github.osir933.anchor.core.physics.thermal.SkyPhysics;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

class HostedSkyTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int BLACK_STONE = 2;
    private static final int WATER = 3;

    private static final BlockAppearance[] LOOKS = {
        BlockAppearance.of("anchor:air"),
        BlockAppearance.of("anchor:granite"),
        BlockAppearance.of("anchor:granite").withAlbedo(0.05),
        BlockAppearance.of("anchor:water").shownAs(Phase.LIQUID).becoming(Phase.SOLID, "ice")
                .becoming(Phase.GAS, "air"),
    };

    private static final long ORIGIN = SectionPos.pack(0, 0, 0);
    private static final double CLIMATE = 293.15;
    private static final Sky NOON = Sky.clear(90.0);

    private static HostedWorld hosted() {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(3), MaterialRegistry.withLibrary());
        return new HostedWorld(world, id -> LOOKS[id], HostedWorld.Settings.defaults());
    }

    /** Stone below y = 8, air above, and black stone in the columns where x is below 4. */
    private static final IntUnaryOperator TERRAIN = i -> SectionPos.localY(i) >= 8 ? AIR
            : SectionPos.localX(i) < 4 ? BLACK_STONE : STONE;

    private static HostedWorld sunlit(IntUnaryOperator terrain, double humidity) {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, terrain, CLIMATE, humidity, null);
        h.importSection(SectionPos.pack(0, 1, 0), i -> AIR, CLIMATE, humidity, null);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setSkyHeight(x, z, 8);
            }
        }
        h.setSky(NOON);
        return h;
    }

    @Test
    void withoutASkyTheWorldIsAsBefore() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, TERRAIN, CLIMATE);
        h.tick();
        assertNull(h.sky());
        assertEquals(0, h.status().skySurfaces());
        assertTrue(Double.isNaN(h.status().sunlightW()));
        HostedWorld.Inspection top = h.inspect(new GridPos(7, 7, 7)).orElseThrow();
        assertTrue(Double.isNaN(top.surfaceK()));
        assertEquals(0.0, top.sunlightW());
    }

    @Test
    void theSunWarmsTheGroundOfASleepingWorld() {
        HostedWorld h = sunlit(TERRAIN, 0.6);
        // Two hours of steps of 3.6 s.
        for (int i = 0; i < 2000; i++) {
            h.tick();
        }
        assertEquals(0, h.status().awakeSections(), "sunshine wakes nothing");
        assertEquals(256, h.status().skySurfaces());
        assertEquals(SkyPhysics.sunlightOnLevelGround(NOON), h.status().sunlightW(), 1e-9);
        GridPos ground = new GridPos(7, 7, 7);
        HostedWorld.Inspection top = h.inspect(ground).orElseThrow();
        double albedo = MaterialLibrary.GRANITE.surface(MaterialLibrary.GRANITE.specificEnthalpy(CLIMATE)).albedo();
        assertEquals((1 - albedo) * SkyPhysics.sunlightOnLevelGround(NOON), top.sunlightW(), 1e-6);
        assertTrue(top.surfaceK() > CLIMATE + 10.0, "two hours of noon sun: " + top.surfaceK());
        assertTrue(top.temperatureK() > CLIMATE && top.temperatureK() < top.surfaceK());
        assertEquals(top.surfaceK(), h.temperatureAt(7.5, 7.99, 7.5), 1e-9, "the top of the block reads its surface");
        assertEquals(top.temperatureK(), h.temperatureAt(7.5, 7.5, 7.5), 1e-9, "its middle the block");
        assertEquals(CLIMATE, h.temperature(new GridPos(7, 6, 7)), 1e-9, "the sleeping ground below is as it was");
        assertEquals(0.0, h.inspect(new GridPos(7, 6, 7)).orElseThrow().sunlightW(), "in the dark");
        assertTrue(h.world().audit().balanced());
    }

    @Test
    void dyedBlocksTakeInSunlightByTheirColour() {
        HostedWorld h = sunlit(TERRAIN, 0.6);
        h.tick();
        double sun = SkyPhysics.sunlightOnLevelGround(NOON);
        assertEquals(0.95 * sun, h.inspect(new GridPos(1, 7, 7)).orElseThrow().sunlightW(), 1e-6);
        assertTrue(h.inspect(new GridPos(9, 7, 7)).orElseThrow().sunlightW() < 0.75 * sun);
        for (int i = 0; i < 3000; i++) {
            h.tick();
        }
        assertTrue(h.inspect(new GridPos(1, 7, 7)).orElseThrow().surfaceK()
                > h.inspect(new GridPos(9, 7, 7)).orElseThrow().surfaceK() + 4.0, "black stone grows hotter");
    }

    @Test
    void dryAirEvaporatesMoreWater() {
        IntUnaryOperator pond = i -> SectionPos.localY(i) >= 8 ? AIR : WATER;
        HostedWorld dry = sunlit(pond, 0.2);
        HostedWorld humid = sunlit(pond, 0.9);
        dry.tick();
        humid.tick();
        assertTrue(dry.lastSky().vapourJ() < humid.lastSky().vapourJ() && dry.lastSky().vapourJ() < 0,
                dry.lastSky() + " vs " + humid.lastSky());
        assertThrows(IllegalArgumentException.class, () -> dry.importSection(ORIGIN, pond, CLIMATE, 1.5, null));
    }

    @Test
    void snowTheSunMeltsInSleepingGroundIsShownMelting() {
        BlockAppearance[] looks = {
            BlockAppearance.of("anchor:air"),
            BlockAppearance.of("anchor:granite"),
            // Snow darkened by soot, which the noon sun melts in a few hours even in air at 0 C.
            BlockAppearance.of("anchor:powder_snow").shownAs(Phase.SOLID).becoming(Phase.LIQUID, "air").withFill(0.125)
                    .withAlbedo(0.4),
        };
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(3), MaterialRegistry.withLibrary());
        HostedWorld h = new HostedWorld(world, id -> looks[id], HostedWorld.Settings.defaults().withTickSeconds(14.4));
        // A layer of snow on rock, all at 0 C in air at 0 C, so nothing flows and the world falls asleep.
        double freezing = 273.15;
        h.importSection(ORIGIN, i -> SectionPos.localY(i) < 8 ? 1 : SectionPos.localY(i) == 8 ? 2 : AIR, freezing, 0.6,
                null);
        h.importSection(SectionPos.pack(0, 1, 0), i -> AIR, freezing, 0.6, null);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setSkyHeight(x, z, 9);
            }
        }
        h.setSky(NOON);
        GridPos snow = new GridPos(7, 8, 7);
        boolean slept = false;
        for (int i = 0; i < 4000; i++) {
            HostedWorld.TickResult r = h.tick();
            for (PhaseChange c : r.phaseChanges()) {
                if (c.pos().equals(snow)) {
                    assertTrue(slept, "the snow melted while its ground slept");
                    assertEquals(Phase.SOLID, c.shown());
                    assertEquals(Phase.LIQUID, c.now());
                    assertEquals("air", c.hostBlock());
                    return;
                }
            }
            slept |= r.awakeSections() == 0;
        }
        fail("the snow never melted, or its melting was never shown: " + h.inspect(snow).orElseThrow());
    }
}
