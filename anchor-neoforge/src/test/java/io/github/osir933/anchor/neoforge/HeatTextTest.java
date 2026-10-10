package io.github.osir933.anchor.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.host.BlockAppearance;
import io.github.osir933.anchor.core.host.HostedWorld;
import io.github.osir933.anchor.core.host.Pacer;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.structure.ThermalShock;
import io.github.osir933.anchor.core.physics.thermal.HeatSourceModel;
import io.github.osir933.anchor.core.physics.thermal.Sky;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeatTextTest {

    /** A world running at normal speed. */
    private static final Pacer.Status NORMAL = new Pacer(4, 20.0).status();

    /** Structures that have done nothing yet. */
    private static final LevelStructures.Report STRUCTURES = new LevelStructures.Report(true, true, 0L, 0L, 0L, 0L, 0,
            0.0, 0, false, null);

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
    void ratesReadPerMinuteOrPerHour() {
        assertEquals("rising 3.0 K/min", HeatText.rate(0.05));
        assertEquals("falling 120.0 K/min", HeatText.rate(-2.0));
        assertEquals("falling 0.4 K/h", HeatText.rate(-0.4 / 3600));
        assertEquals("steady", HeatText.rate(0.01 / 3600));
        assertEquals("steady", HeatText.rate(0.0));
        assertEquals("", HeatText.rate(Double.NaN), "not known yet");
        assertEquals(26.85, HeatText.toCelsius(300.0), 1e-12);
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

        List<String> water = HeatText.describe(hosted.inspect(new GridPos(0, 0, 0)).orElseThrow(), "minecraft:water",
                true);
        assertEquals("minecraft:water at 0 0 0: Water (anchor:water)", water.get(0));
        assertTrue(water.get(1).startsWith("  Temperature " + HeatText.temperature(290.0) + ", liquid"), water.get(1));
        assertTrue(water.stream().anyMatch(l -> l.equals("  Shown as liquid; becomes minecraft:ice as solid")),
                water.toString());
        assertTrue(water.stream().anyMatch(l -> l.startsWith("  State set from the block")), water.toString());

        List<String> torch = HeatText.describe(hosted.inspect(new GridPos(1, 0, 0)).orElseThrow(), "minecraft:torch",
                true);
        assertTrue(torch.stream().anyMatch(l -> l.equals("  Heat source: holds " + HeatText.celsius(1300.0)
                + " with up to 1.50 kW")), torch.toString());
        assertTrue(torch.stream().anyMatch(l -> l.startsWith("  Section awake")), torch.toString());
    }

    @Test
    void aRefinedBlockSaysHowFarApartItsCellsAre() {
        BlockAppearance[] looks = {
            BlockAppearance.of("anchor:air"),
            BlockAppearance.of("anchor:granite"),
            BlockAppearance.of("anchor:basalt").shownAs(Phase.LIQUID).startingAt(1450.0)
                    .heatedBy(new HeatSourceModel.Source(1450.0, 1.5e6)),
        };
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(1), MaterialRegistry.withLibrary());
        HostedWorld hosted = new HostedWorld(world, id -> looks[id], HostedWorld.Settings.defaults());
        // Lava with granite on its east side, in the air.
        hosted.importSection(SectionPos.pack(0, 0, 0), i -> i == 0 ? 2 : i == 1 ? 1 : 0, 290.0);
        for (int i = 0; i < 5; i++) {
            hosted.tick();
        }
        HostedWorld.Inspection granite = hosted.inspect(new GridPos(1, 0, 0)).orElseThrow();
        assertTrue(granite.refined(), granite.toString());
        List<String> lines = HeatText.describe(granite, "minecraft:granite", true);
        assertTrue(lines.contains("  Refined into smaller cells from " + HeatText.celsius(granite.coolestK())
                + " to " + HeatText.celsius(granite.hottestK()) + "; the figures above are for the whole block"),
                lines.toString());

        HostedWorld.Status status = hosted.status();
        List<String> summary = HeatText.status("minecraft:overworld",
                new HeatReport(status, 14.4, 1.0, 1.0, 0L, 0L, 0, null, NORMAL, 4, false, STRUCTURES));
        assertTrue(summary.contains("  " + status.refinedBlocks() + " blocks refined into " + status.refinedCells()
                + " smaller cells where temperatures change steeply"), summary.toString());
    }

    @Test
    void thePaceSaysHowFastHeatRuns() {
        Pacer pacer = new Pacer(4, 20.0);
        assertEquals("Running at normal speed, a step of 14 s every 4 game ticks",
                HeatText.pace(pacer.status(), 14.4, 4));
        pacer.setSpeed(1000);
        assertEquals("Running at 10× normal speed, 2.5 steps of 14 s every game tick",
                HeatText.pace(pacer.status(), 14.4, 4));
        pacer.setSpeed(400);
        assertEquals("Running at 4× normal speed, a step of 14 s every game tick",
                HeatText.pace(pacer.status(), 14.4, 4));
        pacer.setSpeed(25);
        assertEquals("Running at 0.25× normal speed, a step of 14 s every 16 game ticks",
                HeatText.pace(pacer.status(), 14.4, 4));

        pacer.setSpeed(10_000);
        for (int t = 0; t < 10; t++) {
            pacer.beginTick();
            double spent = 0.0;
            while (pacer.wantsStep(spent)) {
                pacer.stepped(5.0);
                spent += 5.0;
            }
            pacer.endTick();
        }
        assertEquals("Running at 100× normal speed, 25 steps of 14 s every game tick; this server keeps up with only "
                + "16× lately", HeatText.pace(pacer.status(), 14.4, 4));

        pacer.pause();
        assertEquals("Paused: temperatures hold until /anchor time resume, and /anchor time step takes steps by hand",
                HeatText.pace(pacer.status(), 14.4, 4));
        pacer.request(2500);
        assertTrue(HeatText.pace(pacer.status(), 14.4, 4).startsWith("Going ahead: 0 s of 10 h 0 min done, at "),
                HeatText.pace(pacer.status(), 14.4, 4));
        assertTrue(HeatText.pace(pacer.status(), 14.4, 4).endsWith(", then paused again"),
                HeatText.pace(pacer.status(), 14.4, 4));
    }

    @Test
    void lengthsOfTimeReadAsPlayersTypeThem() {
        assertEquals(90.0, HeatText.parseDuration("90s"));
        assertEquals(900.0, HeatText.parseDuration("15m"));
        assertEquals(900.0, HeatText.parseDuration("15min"));
        assertEquals(36_000.0, HeatText.parseDuration("10h"));
        assertEquals(5400.0, HeatText.parseDuration("1h30m"));
        assertEquals(129_600.0, HeatText.parseDuration("1.5d"));
        assertEquals(36_000.0, HeatText.parseDuration(" 10H "));
        for (String bad : List.of("", "10", "h", "10x", "10ms", "1h 30m", "0s", "-5m", "1..5h")) {
            assertThrows(IllegalArgumentException.class, () -> HeatText.parseDuration(bad), bad);
        }
        assertEquals("10", HeatText.multiple(10.0));
        assertEquals("0.25", HeatText.multiple(0.25));
        assertEquals("3.33", HeatText.multiple(3.333));
        assertEquals("0", HeatText.multiple(0.0));
    }

    @Test
    void theStatusSummarisesTheWorld() {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(1), MaterialRegistry.withLibrary());
        HostedWorld hosted = new HostedWorld(world, id -> BlockAppearance.of("anchor:air"),
                HostedWorld.Settings.defaults());
        hosted.tick();
        List<String> lines = HeatText.status("minecraft:overworld",
                new HeatReport(hosted.status(), 14.4, 1.25, 0.8, 0L, 1200L, 3, null, NORMAL, 4, false, STRUCTURES));
        assertEquals("Heat in minecraft:overworld", lines.get(0));
        assertTrue(lines.get(2).contains(" steps of 14 s"), lines.get(2));
        assertTrue(lines.get(2).contains("1.25 ms"), lines.get(2));
        assertTrue(lines.contains("  1200 blocks in 3 sections came back as they were saved"), lines.toString());
        assertEquals("  Running at normal speed, a step of 14 s every 4 game ticks", lines.get(3));
        List<String> stopped = HeatText.status("minecraft:the_nether",
                new HeatReport(hosted.status(), 14.4, 0.0, 0.0, 0L, 0L, 0, "java.lang.IllegalStateException: boom",
                        NORMAL, 4, false, STRUCTURES));
        assertEquals("Heat in minecraft:the_nether: stopped after an error", stopped.get(0));
        assertEquals("  java.lang.IllegalStateException: boom", stopped.get(1));
        assertTrue(stopped.stream().noneMatch(l -> l.contains("came back")), "nothing restored, nothing said");
    }

    @Test
    void theStatusSaysWhatStructuresHaveDone() {
        assertEquals("Structures: none analysed yet", HeatText.structures(STRUCTURES));
        assertEquals("Structures: 12 structures analysed, 1 in the background, one of them now; the largest had 300 "
                + "blocks and the last took 2.5 ms; 1 joint cracked and 2 blocks fell; 1 built block waits to be "
                + "checked", HeatText.structures(new LevelStructures.Report(true, true, 12L, 1L, 1L, 2L, 1, 2.5, 300,
                        true, null)));
        assertTrue(HeatText.structures(new LevelStructures.Report(false, true, 12L, 1L, 1L, 2L, 1, 2.5, 300, false,
                null)).contains("switched off"));
        assertEquals("Structures stopped after an error, while heat carries on: java.lang.IllegalStateException: boom",
                HeatText.structures(new LevelStructures.Report(true, true, 0L, 0L, 0L, 0L, 0, 0.0, 0, false,
                        "java.lang.IllegalStateException: boom")));
    }

    @Test
    void unevenHeatSaysHowCloseItComesToCrackingABlock() {
        ThermalShock.Result pulled = new ThermalShock.Result(0.454, 4.54e6, 3);
        String strained = "Uneven heat strains it to 45% of what cracks it, pulling its cooler part apart";
        assertEquals(strained, HeatText.thermalStress(pulled, true, false, true));
        assertEquals(strained + "; it is natural, so it does not crack",
                HeatText.thermalStress(pulled, false, false, true));
        assertEquals(strained + "; cracking from heat is switched off in Anchor's settings",
                HeatText.thermalStress(pulled, true, false, false));
        assertEquals("Uneven heat strains it to 120% of what cracks it, crushing its hotter part",
                HeatText.thermalStress(new ThermalShock.Result(1.2, -1.8e8, 0), true, false, true));
        assertEquals("Cracked through by uneven heat", HeatText.thermalStress(null, true, true, true));
        assertNull(HeatText.thermalStress(null, true, false, true), "not refined, or not brittle");
        assertNull(HeatText.thermalStress(ThermalShock.NONE, true, false, true), "not strained at all");
        assertEquals("No built block has cracked from uneven heat yet", HeatText.thermalShock(true, 0));
        assertEquals("1 built block cracked through by uneven heat", HeatText.thermalShock(true, 1));
        assertEquals("3 built blocks cracked through by uneven heat", HeatText.thermalShock(true, 3));
        assertEquals("Built blocks do not crack from uneven heat: switched off in Anchor's settings",
                HeatText.thermalShock(false, 3));
    }

    @Test
    void stonePlacedBesideLavaSaysHowUnevenHeatStrainsItUntilItCracks() {
        BlockAppearance[] looks = {
            BlockAppearance.of("anchor:air"),
            BlockAppearance.of("anchor:granite").fracturingInto("minecraft:cobblestone"),
            BlockAppearance.of("anchor:basalt").shownAs(Phase.LIQUID).startingAt(1450.0)
                    .heatedBy(new HeatSourceModel.Source(1450.0, 1.5e6)),
        };
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(1), MaterialRegistry.withLibrary());
        HostedWorld hosted = new HostedWorld(world, id -> looks[id],
                HostedWorld.Settings.defaults().withTickSeconds(14.4));
        hosted.importSection(SectionPos.pack(0, 0, 0), i -> SectionPos.localY(i) < 8 ? 1 : 0, 290.0);
        GridPos stone = new GridPos(5, 8, 4);
        hosted.reconcile(new GridPos(4, 8, 4), 2, Double.NaN);
        hosted.reconcile(stone, 1, Double.NaN);
        // Halfway to cracking, as lava warms the face against it ahead of the rest.
        for (int s = 0; s < 60; s++) {
            hosted.tick();
        }
        List<String> strained = HeatText.describe(hosted.inspect(stone).orElseThrow(), "minecraft:stone", true);
        assertTrue(strained.stream().anyMatch(l -> l.startsWith("  Uneven heat strains it to ")
                && l.endsWith("% of what cracks it, pulling its cooler part apart")), strained.toString());
        List<String> off = HeatText.describe(hosted.inspect(stone).orElseThrow(), "minecraft:stone", false);
        assertTrue(off.stream().anyMatch(l -> l.startsWith("  Uneven heat strains it to ")
                && l.endsWith("; cracking from heat is switched off in Anchor's settings")), off.toString());
        for (int s = 0; s < 240 && !hosted.isFractured(stone); s++) {
            hosted.tick();
        }
        List<String> cracked = HeatText.describe(hosted.inspect(stone).orElseThrow(), "minecraft:stone", true);
        assertTrue(cracked.contains("  Cracked through by uneven heat"), cracked.toString());
        assertTrue(status(hosted).contains("  1 built block cracked through by uneven heat"),
                status(hosted).toString());
    }

    @Test
    void sunlitGroundSaysHowWarmItsTopIsAndHowMuchSunlightItTakesIn() {
        BlockAppearance[] looks = {BlockAppearance.of("anchor:air"), BlockAppearance.of("anchor:granite")};
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(1), MaterialRegistry.withLibrary());
        HostedWorld hosted = new HostedWorld(world, id -> looks[id], HostedWorld.Settings.defaults());
        hosted.importSection(SectionPos.pack(0, 0, 0), i -> SectionPos.localY(i) < 8 ? 1 : 0, 290.0);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                hosted.setSkyHeight(x, z, 8);
            }
        }
        hosted.setSky(Sky.clear(90.0));
        hosted.tick();
        HostedWorld.Inspection ground = hosted.inspect(new GridPos(3, 7, 3)).orElseThrow();
        List<String> lines = HeatText.describe(ground, "minecraft:stone", true);
        assertTrue(lines.contains("  Its top, open to the sky, is at " + HeatText.celsius(ground.surfaceK())),
                lines.toString());
        assertTrue(lines.contains("  Taking in " + HeatText.power(ground.sunlightW()) + " of sunlight"),
                lines.toString());
        List<String> below = HeatText.describe(hosted.inspect(new GridPos(3, 6, 3)).orElseThrow(), "minecraft:stone",
                true);
        assertTrue(below.stream().noneMatch(l -> l.contains("sky") || l.contains("sunlight")), below.toString());

        assertTrue(status(hosted).contains("  Sunlight " + HeatText.power(hosted.status().sunlightW())
                + "/m² on level ground; 256 surfaces open to the sky"), status(hosted).toString());
        hosted.setSky(Sky.clear(-90.0));
        hosted.tick();
        assertTrue(status(hosted).contains("  Night; 256 surfaces open to the sky"), status(hosted).toString());
        hosted.setSky(null);
        hosted.tick();
        assertTrue(status(hosted).contains("  No sun or night sky here"), status(hosted).toString());
    }

    @Test
    void aLaboratorySaysWhyItHasNoSun() {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(1), MaterialRegistry.withLibrary());
        HostedWorld hosted = new HostedWorld(world, id -> BlockAppearance.of("anchor:air"),
                HostedWorld.Settings.defaults());
        hosted.tick();
        List<String> lines = HeatText.status("minecraft:overworld", new HeatReport(hosted.status(), 14.4, 1.0, 1.0,
                0L, 0L, 0, null, NORMAL, 4, true, STRUCTURES));
        assertTrue(lines.contains("  A laboratory: no sun or night sky, so what nothing heats or cools settles at the "
                + "air's temperature"), lines.toString());
        assertTrue(lines.stream().noneMatch(l -> l.contains("No sun or night sky here")), lines.toString());
    }

    private static List<String> status(HostedWorld hosted) {
        return HeatText.status("minecraft:overworld", new HeatReport(hosted.status(), 14.4, 1.0, 1.0, 0L, 0L, 0, null,
                NORMAL, 4, false, STRUCTURES));
    }
}
