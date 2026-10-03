package io.github.osir933.anchor.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.host.BlockAppearance;
import io.github.osir933.anchor.core.host.HostedWorld;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.thermal.HeatSourceModel;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeatTextTest {

    @Test
    void numbersReadNaturally() {
        assertEquals("21.4 °C", HeatText.celsius(294.55));
        assertEquals("-3.1 °C (270.0 K)", HeatText.temperature(270.0));
        assertEquals("45 s", HeatText.duration(45.2));
        assertEquals("12 min 30 s", HeatText.duration(750));
        assertEquals("3 h 5 min", HeatText.duration(3 * 3600 + 5 * 60 + 20));
        assertEquals("2 d 4 h", HeatText.duration(2 * 86_400 + 4 * 3600 + 100));
        assertEquals("1.23 MJ", HeatText.energy(1.234e6));
        assertEquals("850.0 J", HeatText.energy(850));
        assertEquals("1.50 kW", HeatText.power(1500));
        assertEquals("80.0 W", HeatText.power(80));
    }

    @Test
    void anInspectionSaysWhatTheBlockIsAndHowSureTheSimulationIs() {
        BlockAppearance[] looks = {
            BlockAppearance.of("anchor:air"),
            BlockAppearance.of("anchor:water").shownAs(Phase.LIQUID).becoming(Phase.SOLID, "minecraft:ice"),
            BlockAppearance.of("anchor:air").heatedBy(new HeatSourceModel.Source(1300.0, 1500.0)),
        };
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(1), MaterialRegistry.withLibrary());
        HostedWorld hosted = new HostedWorld(world, id -> looks[id], HostedWorld.Settings.defaults());
        hosted.importSection(SectionPos.pack(0, 0, 0), i -> i == 0 ? 1 : i == 1 ? 2 : 0, 290.0);

        List<String> water = HeatText.describe(hosted.inspect(new GridPos(0, 0, 0)).orElseThrow(), "minecraft:water");
        assertEquals("minecraft:water at 0 0 0: Water (anchor:water)", water.get(0));
        assertTrue(water.get(1).startsWith("  Temperature " + HeatText.temperature(290.0) + ", liquid"), water.get(1));
        assertTrue(water.stream().anyMatch(l -> l.equals("  Shown as liquid; becomes minecraft:ice as solid")),
                water.toString());
        assertTrue(water.stream().anyMatch(l -> l.startsWith("  State set from the block")), water.toString());

        List<String> torch = HeatText.describe(hosted.inspect(new GridPos(1, 0, 0)).orElseThrow(), "minecraft:torch");
        assertTrue(torch.stream().anyMatch(l -> l.equals("  Heat source: holds " + HeatText.celsius(1300.0)
                + " with up to 1.50 kW")), torch.toString());
        assertTrue(torch.stream().anyMatch(l -> l.startsWith("  Section awake")), torch.toString());
    }

    @Test
    void theStatusSummarisesTheWorld() {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(1), MaterialRegistry.withLibrary());
        HostedWorld hosted = new HostedWorld(world, id -> BlockAppearance.of("anchor:air"),
                HostedWorld.Settings.defaults());
        hosted.tick();
        List<String> lines = HeatText.status("minecraft:overworld",
                new HeatReport(hosted.status(), 14.4, 1.25, 0.8, 0L, 1200L, 3, null));
        assertEquals("Heat in minecraft:overworld", lines.get(0));
        assertTrue(lines.get(2).contains(" steps of 14 s"), lines.get(2));
        assertTrue(lines.get(2).contains("1.25 ms"), lines.get(2));
        assertTrue(lines.contains("  1200 blocks in 3 sections came back as they were saved"), lines.toString());
        List<String> stopped = HeatText.status("minecraft:the_nether",
                new HeatReport(hosted.status(), 14.4, 0.0, 0.0, 0L, 0L, 0, "java.lang.IllegalStateException: boom"));
        assertEquals("Heat in minecraft:the_nether: stopped after an error", stopped.get(0));
        assertEquals("  java.lang.IllegalStateException: boom", stopped.get(1));
        assertTrue(stopped.stream().noneMatch(l -> l.contains("came back")), "nothing restored, nothing said");
    }
}
