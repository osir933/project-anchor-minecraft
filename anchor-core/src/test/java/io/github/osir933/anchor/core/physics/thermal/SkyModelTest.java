package io.github.osir933.anchor.core.physics.thermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.math.DeterministicRandom;
import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.matter.Surface;
import io.github.osir933.anchor.core.matter.ThermalState;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.model.Scheduler;
import io.github.osir933.anchor.core.model.SimulationScope;
import io.github.osir933.anchor.core.model.StepContext;
import io.github.osir933.anchor.core.model.TickReport;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.units.PhysicalConstants;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

class SkyModelTest {

    private static final double ENV = 293.15;
    private static final double SIGMA = PhysicalConstants.STEFAN_BOLTZMANN;
    /** One step of a hosted world: a Minecraft day of 24 000 ticks, stepped every fourth tick, lasts 24 hours. */
    private static final double DT = 14.4;
    private static final int STEPS_PER_HOUR = 250;

    private static PhysicalWorld world(double airK) {
        return new PhysicalWorld(new WorldSettings(1, "anchor:air", airK, WorldSettings.DEFAULT_MAX_LEAVES),
                MaterialRegistry.withLibrary());
    }

    private static SkyModel sky(double environmentK, double humidity, Sky sky) {
        SkyModel model = new SkyModel(key -> environmentK, key -> humidity);
        model.setSky(sky);
        return model;
    }

    private static void step(PhysicalWorld world, SkyModel model, double dt) {
        model.step(new StepContext(world, dt, new DeterministicRandom(1), SimulationScope.everywhere()));
    }

    private static void run(PhysicalWorld world, SkyModel model, int steps) {
        for (int i = 0; i < steps; i++) {
            step(world, model, DT);
        }
    }

    private static double temperature(PhysicalWorld world, GridPos pos) {
        CellState c = world.readBlock(pos);
        return world.materials().get(c.material()).temperatureFor(c.specificEnthalpy());
    }

    private static ThermalState state(PhysicalWorld world, GridPos pos) {
        CellState c = world.readBlock(pos);
        return world.materials().get(c.material()).stateFor(c.specificEnthalpy());
    }

    /** Finds where a decreasing function crosses zero between two temperatures, by bisection. */
    private static double root(DoubleUnaryOperator f, double low, double high) {
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (low + high);
            if (f.applyAsDouble(mid) > 0) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return 0.5 * (low + high);
    }

    @Test
    void aSunlitRockSettlesWhereItsSurfaceBalances() {
        // A dry rock at a clear noon: the skin settles where sunlight and the sky's radiation balance the rock's
        // emission, the heat the air carries off and the pull of the cooler rock beneath, as the force-restore
        // method says.
        PhysicalWorld world = world(ENV);
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.GRANITE, ENV);
        Sky noon = Sky.clear(90.0);
        SkyModel model = sky(ENV, 0.6, noon);
        run(world, model, 10 * STEPS_PER_HOUR);
        GridPos top = new GridPos(7, 3, 7);
        assertTrue(model.keepsTopOf(top.sectionKey(), top.indexInSection()));
        Material granite = MaterialLibrary.GRANITE;
        ThermalState s = state(world, top);
        double blockK = s.temperatureK();
        double emissivity = granite.emissivity(s);
        double effusivity = Math.sqrt(granite.conductivity(s) * granite.density(s) * granite.specificHeat(s));
        double restore = effusivity * Math.sqrt(SkyModel.DAY_FREQUENCY / 2.0);
        double vapour = 0.6 * SkyPhysics.saturationOverWater(ENV);
        double absorbed = (1 - granite.surface(granite.specificEnthalpy(blockK)).albedo())
                * SkyPhysics.sunlightOnLevelGround(noon);
        double sky = emissivity * SkyPhysics.skyRadiation(ENV, vapour, 0.0);
        double expected = root(t -> absorbed + sky - emissivity * SIGMA * t * t * t * t
                - ConductionModel.CONVECTION_COEFFICIENT * (t - ENV) - restore * (t - blockK), 200.0, 500.0);
        double skin = model.surfaceTemperature(top);
        assertEquals(expected, skin, 1.0, "the skin balances its fluxes");
        assertTrue(skin > ENV + 15.0, "a sunlit rock is far warmer than the air: " + skin);
        assertTrue(blockK > ENV + 1.0 && blockK < skin - 10.0, "the block as a whole warms more slowly: " + blockK);
        assertEquals(ENV, temperature(world, new GridPos(7, 2, 7)), 1e-9,
                "with no conduction running, only the top block takes the sun");
    }

    @Test
    void darkGroundWarmsMoreThanPaleGround() {
        PhysicalWorld world = world(ENV);
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 7), MaterialLibrary.BASALT, ENV);
        world.fill(new GridPos(0, 0, 8), new GridPos(15, 3, 15), MaterialLibrary.MARBLE, ENV);
        SkyModel model = sky(ENV, 0.6, Sky.clear(90.0));
        run(world, model, 4 * STEPS_PER_HOUR);
        double dark = model.surfaceTemperature(new GridPos(4, 3, 4));
        double pale = model.surfaceTemperature(new GridPos(4, 3, 12));
        assertTrue(dark > pale + 10.0, "basalt reflects 10 % of sunlight, marble 55 %: " + dark + " vs " + pale);
    }

    @Test
    void aClearNightBringsFrostAndCloudsKeepItAway() {
        double air = 275.15;
        Sky midnight = Sky.clear(-90.0);
        PhysicalWorld clear = world(air);
        clear.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.SOIL, air);
        SkyModel clearSky = sky(air, 0.7, midnight);
        run(clear, clearSky, 6 * STEPS_PER_HOUR);
        PhysicalWorld cloudy = world(air);
        cloudy.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.SOIL, air);
        SkyModel cloudySky = sky(air, 0.7, midnight.withWeather(1.0, 0.0, 0.0));
        run(cloudy, cloudySky, 6 * STEPS_PER_HOUR);
        GridPos top = new GridPos(5, 3, 5);
        double frosty = clearSky.surfaceTemperature(top);
        double mild = cloudySky.surfaceTemperature(top);
        assertTrue(frosty < PhysicalConstants.ZERO_CELSIUS, "the ground freezes under a clear sky at 2 C: " + frosty);
        assertTrue(mild > PhysicalConstants.ZERO_CELSIUS, "but not under cloud: " + mild);
        assertTrue(temperature(clear, top) > PhysicalConstants.ZERO_CELSIUS, "the block beneath stays above 0 C");
        assertTrue(clearSky.lastStep().thermalJ() < 0, "the ground loses heat to the sky");
        assertTrue(clearSky.lastStep().airJ() > 0, "and takes some back from the air");
    }

    @Test
    void sunlitSnowStaysAtItsMeltingPointAndMelts() {
        double air = 278.15;
        PhysicalWorld world = world(air);
        Material snow = MaterialLibrary.SNOW;
        int index = world.materials().indexOf(snow);
        double melting = PhysicalConstants.ZERO_CELSIUS;
        // A thin layer of snow, ten kilograms over a square metre, on frozen soil.
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 2, 15), MaterialLibrary.SOIL, melting);
        double mass = 10.0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                world.setBlock(new GridPos(x, 3, z), new CellState(index, mass, mass * snow.specificEnthalpy(melting),
                        0L, Provenance.INITIAL));
            }
        }
        long key = SectionPos.pack(0, 0, 0);
        ThermalActivity activity = new ThermalActivity(world.materials(), ThermalActivity.DEFAULT_CALM_RATE, 20);
        world.addWriteListener(activity);
        SkyModel model = new SkyModel(k -> air, k -> 0.6, null, activity, null);
        model.setSky(Sky.clear(90.0));
        GridPos top = new GridPos(3, 3, 3);
        double start = world.readBlock(top).enthalpy();
        SimulationScope nowhere = (w, d) -> new TreeSet<>();
        int steps = 0;
        boolean woke = false;
        while (!woke && steps < 8 * STEPS_PER_HOUR) {
            model.step(new StepContext(world, DT, new DeterministicRandom(1), nowhere));
            activity.endStep(world, DT);
            steps++;
            woke = activity.isAwake(key);
            if (!woke) {
                assertEquals(melting, temperature(world, top), 1e-9, "melting snow stays at 0 C");
                double skin = model.surfaceTemperature(top);
                assertTrue(Double.isNaN(skin) || skin <= melting, "and so does its surface: " + skin);
            }
        }
        assertTrue(woke, "the snow's section wakes once the snow has melted");
        assertFalse(snow.canAppearAs(state(world, top), Phase.SOLID), "all of it");
        double latent = mass * snow.thermal().transitions().get(0).latentHeat();
        assertTrue(world.readBlock(top).enthalpy() - start > latent, "all its latent heat went in");
        assertTrue(steps < 3 * STEPS_PER_HOUR, "a clear noon melts ten kilograms in under three hours: " + steps);
    }

    @Test
    void waterIceAndGlassLetLightDownAsTheyShould() {
        PhysicalWorld world = world(ENV);
        Material water = MaterialLibrary.WATER;
        // A pond five blocks deep on sand, ice over water, and a glass roof over sand.
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 0, 15), MaterialLibrary.SAND, ENV);
        world.fill(new GridPos(1, 1, 1), new GridPos(1, 5, 1), water, ENV);
        world.fill(new GridPos(3, 1, 3), new GridPos(3, 4, 3), water, 274.0);
        world.placeMaterial(new GridPos(3, 5, 3), water, 263.15);
        world.placeMaterial(new GridPos(5, 6, 5), MaterialLibrary.GLASS, ENV);
        Sky noon = Sky.clear(90.0);
        SkyModel model = sky(ENV, 0.6, noon);
        step(world, model, DT);
        double sun = SkyPhysics.sunlightOnLevelGround(noon);
        Surface liquid = water.surface(water.specificEnthalpy(ENV));
        Surface ice = water.surface(water.specificEnthalpy(263.15));
        Surface glass = MaterialLibrary.GLASS.surface(MaterialLibrary.GLASS.specificEnthalpy(ENV));
        double sand = MaterialLibrary.SAND.surface(MaterialLibrary.SAND.specificEnthalpy(ENV)).albedo();

        // The pond: its surface reflects, the water below it does not, and the bed hands its share to the water.
        double blueGreen = Surface.BLUE_GREEN_SHARE * (1 - liquid.albedo());
        double red = (1 - Surface.BLUE_GREEN_SHARE) * (1 - liquid.albedo());
        double[] taken = new double[6];
        for (int y = 5; y >= 1; y--) {
            taken[y] = blueGreen * (1 - liquid.blueGreenTransmittance())
                    + red * (1 - liquid.redInfraredTransmittance());
            blueGreen *= liquid.blueGreenTransmittance();
            red *= liquid.redInfraredTransmittance();
        }
        taken[1] += (blueGreen + red) * (1 - sand);
        for (int y = 1; y <= 5; y++) {
            assertEquals(taken[y] * sun, model.absorbedSunlight(new GridPos(1, y, 1)), 1e-9 * sun, "pond y=" + y);
        }
        assertEquals(0.0, model.absorbedSunlight(new GridPos(1, 0, 1)), "the bed passed its share on");
        assertTrue(taken[5] > 0.5 && taken[1] > 0.2, "most light goes in at the top, a fifth reaches the bed");
        GridPos pond = new GridPos(1, 5, 1);
        assertTrue(model.keepsTopOf(pond.sectionKey(), pond.indexInSection()), "the model keeps the pond's top");

        // Ice reflects more and lets less through.
        double iceTaken = (1 - ice.albedo()) * (Surface.BLUE_GREEN_SHARE * (1 - ice.blueGreenTransmittance())
                + (1 - Surface.BLUE_GREEN_SHARE) * (1 - ice.redInfraredTransmittance()));
        assertEquals(iceTaken * sun, model.absorbedSunlight(new GridPos(3, 5, 3)), 1e-9 * sun);
        assertTrue(model.absorbedSunlight(new GridPos(3, 4, 3)) > 0, "some light reaches the water under the ice");

        // Glass passes most light to the floor of a greenhouse, across the air.
        double glassTaken = (1 - glass.albedo()) * (Surface.BLUE_GREEN_SHARE * (1 - glass.blueGreenTransmittance())
                + (1 - Surface.BLUE_GREEN_SHARE) * (1 - glass.redInfraredTransmittance()));
        double through = (1 - glass.albedo()) * (Surface.BLUE_GREEN_SHARE * glass.blueGreenTransmittance()
                + (1 - Surface.BLUE_GREEN_SHARE) * glass.redInfraredTransmittance());
        assertEquals(glassTaken * sun, model.absorbedSunlight(new GridPos(5, 6, 5)), 1e-9 * sun);
        assertEquals(through * (1 - sand) * sun, model.absorbedSunlight(new GridPos(5, 0, 5)), 1e-9 * sun);
        assertTrue(through > 0.75, "a window lets most sunlight in: " + through);
    }

    @Test
    void shadowsFallStraightDownAndTheHostSaysWhereTheSkyBegins() {
        PhysicalWorld world = world(ENV);
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.SOIL, ENV);
        world.placeMaterial(new GridPos(5, 8, 5), MaterialLibrary.GRANITE, ENV);
        SkyModel model = sky(ENV, 0.6, Sky.clear(30.0));
        step(world, model, DT);
        assertEquals(0.0, model.absorbedSunlight(new GridPos(5, 3, 5)), "in the shade of the block above");
        assertTrue(model.absorbedSunlight(new GridPos(5, 8, 5)) > 0, "which takes the sun");
        assertTrue(model.absorbedSunlight(new GridPos(6, 3, 5)) > 0, "however low the sun, the next column is lit");
        assertFalse(model.keepsTopOf(new GridPos(5, 3, 5).sectionKey(), new GridPos(5, 3, 5).indexInSection()));

        // The host knows the sky begins right above the ground here, as if the block above were not there.
        model.setSkyHeight(5, 5, 4);
        step(world, model, DT);
        assertTrue(model.absorbedSunlight(new GridPos(5, 3, 5)) > 0);
        assertEquals(0.0, model.absorbedSunlight(new GridPos(5, 8, 5)));
        assertEquals(4, model.skyHeight(5, 5));
        // And here the sky begins above everything the world holds, so whatever covers the column is not known.
        model.setSkyHeight(6, 5, 40);
        step(world, model, DT);
        assertEquals(0.0, model.absorbedSunlight(new GridPos(6, 3, 5)));
        model.setSkyHeight(6, 5, SkyModel.UNKNOWN_SKY);
        step(world, model, DT);
        assertTrue(model.absorbedSunlight(new GridPos(6, 3, 5)) > 0);
    }

    @Test
    void flamesRefinedBlocksAndWhatLiesUnderThemOnlyTakeSunlight() {
        PhysicalWorld world = world(ENV);
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.GRANITE, ENV);
        GridPos lava = new GridPos(2, 3, 2);
        GridPos underFire = new GridPos(4, 3, 4);
        GridPos refined = new GridPos(6, 3, 6);
        GridPos plain = new GridPos(8, 3, 8);
        world.refine(CellId.of(refined).child(0));
        SkyModel model = new SkyModel(k -> ENV, k -> 0.6,
                pos -> pos.equals(lava) || pos.equals(underFire.offset(0, 1, 0)), null, null);
        model.setSky(Sky.clear(90.0));
        step(world, model, DT);
        for (GridPos pos : new GridPos[] {lava, underFire, refined}) {
            assertFalse(model.keepsTopOf(pos.sectionKey(), pos.indexInSection()), pos + " keeps its top face");
            assertTrue(model.absorbedSunlight(pos) > 0, pos + " still takes sunlight");
            assertTrue(Double.isNaN(model.surfaceTemperature(pos)));
        }
        assertTrue(model.keepsTopOf(plain.sectionKey(), plain.indexInSection()));
        assertTrue(model.surfaceTemperature(plain) > ENV);
        // The sunlight on the refined block went into its cells along the top.
        CellId topCell = CellId.of(refined).child(2);
        CellId bottomCell = CellId.of(refined).child(0);
        assertTrue(world.readLeaf(topCell).enthalpy() > world.readLeaf(bottomCell).enthalpy());
    }

    @Test
    void blocksHeatedOrChilledFarFromTheWeatherLeaveTheirTopsToConduction() {
        PhysicalWorld world = world(ENV);
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.GRANITE, ENV);
        GridPos hot = new GridPos(2, 3, 2);
        GridPos cold = new GridPos(5, 3, 5);
        GridPos sunWarmed = new GridPos(8, 3, 8);
        world.placeMaterial(hot, MaterialLibrary.IRON, 900.0);
        world.placeMaterial(cold, MaterialLibrary.GRANITE, ENV - SkyModel.WEATHER_RANGE_K - 1.0);
        world.placeMaterial(sunWarmed, MaterialLibrary.GRANITE, ENV + SkyModel.WEATHER_RANGE_K - 1.0);
        SkyModel model = sky(ENV, 0.6, Sky.clear(90.0));
        ConductionModel conduction = new ConductionModel(null, model);
        StepContext context = new StepContext(world, DT, new DeterministicRandom(1), SimulationScope.everywhere());
        model.step(context);
        conduction.step(context);
        for (GridPos pos : new GridPos[] {hot, cold}) {
            assertFalse(model.keepsTopOf(pos.sectionKey(), pos.indexInSection()), pos + " keeps its top face");
            assertTrue(model.absorbedSunlight(pos) > 0, pos + " still takes sunlight");
        }
        assertTrue(model.keepsTopOf(sunWarmed.sectionKey(), sunWarmed.indexInSection()));
        assertTrue(temperature(world, hot.offset(0, 1, 0)) > ENV + 1.0, "the air over the hot iron warms");
        assertTrue(temperature(world, cold.offset(0, 1, 0)) < ENV - 0.1, "the air over the cold rock cools");
        assertEquals(ENV, temperature(world, sunWarmed.offset(0, 1, 0)), 1e-6, "the sky keeps that face");
    }

    @Test
    void conductionAndRadiationLeaveTheTopFacesTheSkyKeeps() {
        PhysicalWorld world = world(ENV);
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.GRANITE, 280.0);
        SkyModel model = sky(ENV, 0.6, Sky.clear(90.0));
        step(world, model, DT);
        SortedSet<Long> everywhere = SimulationScope.everywhere().sections(world, Domain.THERMAL);
        GridPos top = new GridPos(7, 3, 7);
        int surface = top.indexInSection();
        int above = top.offset(0, 1, 0).indexInSection();
        ThermalGraph with = new ThermalGraph();
        with.rebuild(world, everywhere, new Isotherms(), model);
        ThermalGraph without = ThermalGraph.build(world, everywhere, new Isotherms());
        assertFalse(connects(with, surface, above), "the ground's top face is the sky's");
        assertTrue(connects(without, surface, above));
        assertTrue(connects(with, surface, top.offset(1, 0, 0).indexInSection()), "its sides still conduct");
        assertTrue(connects(with, top.offset(0, -1, 0).indexInSection(), surface), "and so does its bottom");

        // A warm block hanging in the air radiates from five faces; the sky takes care of the sixth.
        PhysicalWorld a = world(ENV);
        PhysicalWorld b = world(ENV);
        GridPos hot = new GridPos(8, 8, 8);
        a.placeMaterial(hot, MaterialLibrary.GRANITE, 340.0);
        b.placeMaterial(hot, MaterialLibrary.GRANITE, 340.0);
        SkyModel skyA = sky(ENV, 0.6, Sky.clear(90.0));
        step(a, skyA, 1e-3);
        assertTrue(skyA.keepsTopOf(hot.sectionKey(), hot.indexInSection()));
        RadiationModel kept = new RadiationModel(k -> ENV, null, skyA);
        RadiationModel all = new RadiationModel(k -> ENV);
        kept.step(new StepContext(a, 1e-3, new DeterministicRandom(1), SimulationScope.everywhere()));
        all.step(new StepContext(b, 1e-3, new DeterministicRandom(1), SimulationScope.everywhere()));
        assertEquals(5.0 / 6.0, kept.lastStepEnergy() / all.lastStepEnergy(), 1e-4);
    }

    private static boolean connects(ThermalGraph g, int a, int b) {
        for (int f = 0; f < g.faceCount; f++) {
            if (g.leafBlock[g.faceA[f]] == a && g.leafBlock[g.faceB[f]] == b) {
                return true;
            }
        }
        return false;
    }

    @Test
    void lightBoundForBlocksNothingSimulatesWarmsTheSurfaceInstead() {
        // The pond of the test above, and a block of iron too hot for the weather, in a section nothing simulates.
        PhysicalWorld simulated = world(ENV);
        PhysicalWorld asleep = world(ENV);
        GridPos iron = new GridPos(8, 1, 8);
        for (PhysicalWorld w : new PhysicalWorld[] {simulated, asleep}) {
            w.fill(new GridPos(0, 0, 0), new GridPos(15, 0, 15), MaterialLibrary.SAND, ENV);
            w.fill(new GridPos(1, 1, 1), new GridPos(1, 5, 1), MaterialLibrary.WATER, ENV);
            w.placeMaterial(iron, MaterialLibrary.IRON, 900.0);
        }
        Sky noon = Sky.clear(90.0);
        SkyModel live = sky(ENV, 0.6, noon);
        SkyModel sleeping = sky(ENV, 0.6, noon);
        SimulationScope nowhere = (w, d) -> new TreeSet<>();
        step(simulated, live, DT);
        sleeping.step(new StepContext(asleep, DT, new DeterministicRandom(1), nowhere));
        double column = 0;
        for (int y = 0; y <= 5; y++) {
            column += live.absorbedSunlight(new GridPos(1, y, 1));
        }
        GridPos top = new GridPos(1, 5, 1);
        assertTrue(live.absorbedSunlight(top) < 0.7 * column, "where heat flows, a third of it goes in below the top");
        assertEquals(column, sleeping.absorbedSunlight(top), 1e-9 * column, "elsewhere the top takes in all of it");
        for (int y = 0; y < 5; y++) {
            assertEquals(0.0, sleeping.absorbedSunlight(new GridPos(1, y, 1)), "and nothing below it, y=" + y);
        }
        assertTrue(live.absorbedSunlight(iron) > 0, "simulated, hot iron takes sunlight");
        assertEquals(0.0, sleeping.absorbedSunlight(iron), "but not where nothing could carry the heat away");

        double ironBefore = asleep.readBlock(iron).enthalpy();
        for (int i = 0; i < 10 * STEPS_PER_HOUR; i++) {
            sleeping.step(new StepContext(asleep, DT, new DeterministicRandom(1), nowhere));
        }
        assertTrue(temperature(asleep, top) > ENV, "the pond's top warmed");
        for (int y = 1; y < 5; y++) {
            assertEquals(ENV, temperature(asleep, new GridPos(1, y, 1)), 1e-9, "the water below it did not, y=" + y);
        }
        assertEquals(ENV, temperature(asleep, new GridPos(1, 0, 1)), 1e-9, "nor did its bed");
        assertEquals(ironBefore, asleep.readBlock(iron).enthalpy(), "nor the iron");
    }

    @Test
    void sleepingGroundFollowsTheSunWithoutWakingAnything() {
        PhysicalWorld world = world(ENV);
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.SOIL, ENV);
        ThermalActivity activity = new ThermalActivity(world.materials(), ThermalActivity.DEFAULT_CALM_RATE,
                ThermalActivity.DEFAULT_CALM_STEPS);
        world.addWriteListener(activity);
        SkyModel model = new SkyModel(k -> ENV, k -> 0.6, null, activity, null);
        SimulationScope scope = (w, d) -> activity.scope(w);
        GridPos top = new GridPos(9, 3, 9);
        double noon = Double.NaN;
        double night = Double.NaN;
        int day = 24 * STEPS_PER_HOUR;
        for (int i = 0; i < 2 * day; i++) {
            // Midnight at the start, sunrise six hours later.
            model.setSky(Sky.clear(360.0 * i / day - 90.0));
            model.step(new StepContext(world, DT, new DeterministicRandom(1), scope));
            activity.endStep(world, DT);
            assertTrue(model.lastStep().updated() <= 64, "a quarter of the sleeping columns each step");
            if (i == day + day / 2 + 8 * STEPS_PER_HOUR / 4) {
                noon = model.surfaceTemperature(top);
            }
            if (i == 2 * day - 4) {
                night = model.surfaceTemperature(top);
            }
        }
        assertTrue(activity.awakeSections().isEmpty(), "the sky woke nothing");
        assertTrue(noon > night + 10.0, "but the ground warmed by day and cooled by night: " + noon + ", " + night);
        assertTrue(night < ENV, "a clear night cools the ground below the air: " + night);
    }

    @Test
    void sleepingColumnsCatchUpOnTheTimeTheyWaited() {
        Sky noon = Sky.clear(70.0);
        PhysicalWorld awake = world(ENV);
        PhysicalWorld asleep = world(ENV);
        awake.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.SAND, ENV);
        asleep.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.SAND, ENV);
        SkyModel live = sky(ENV, 0.6, noon);
        SkyModel sleeping = sky(ENV, 0.6, noon);
        SimulationScope nowhere = (w, d) -> new TreeSet<>();
        double liveSun = 0;
        double sleepingSun = 0;
        for (int i = 0; i < 4 * SkyModel.SLEEPING_STRIDE; i++) {
            step(awake, live, DT);
            sleeping.step(new StepContext(asleep, DT, new DeterministicRandom(1), nowhere));
            liveSun += live.lastStep().sunlightJ();
            sleepingSun += sleeping.lastStep().sunlightJ();
            assertEquals(256, live.lastStep().updated());
            assertEquals(256 / SkyModel.SLEEPING_STRIDE, sleeping.lastStep().updated());
        }
        assertTrue(sleepingSun < liveSun, "some sleeping columns are still waiting");
        // Once the section is simulated again, every column catches up at once.
        step(awake, live, DT);
        step(asleep, sleeping, DT);
        liveSun += live.lastStep().sunlightJ();
        sleepingSun += sleeping.lastStep().sunlightJ();
        assertEquals(liveSun, sleepingSun, 1e-9 * liveSun, "every column took the same sunlight in the end");
    }

    @Test
    void waterEvaporatesIntoDryAirAndDewSettlesOnColdGround() {
        PhysicalWorld pond = world(ENV);
        pond.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.WATER, 303.15);
        SkyModel dry = sky(ENV, 0.3, Sky.clear(-90.0));
        run(pond, dry, 10);
        assertTrue(dry.lastStep().vapourJ() < 0, "warm water evaporating into dry air cools");
        assertTrue(temperature(pond, new GridPos(4, 3, 4)) < 303.15);
        assertEquals(303.15, temperature(pond, new GridPos(4, 2, 4)), 1e-9, "only the surface block felt it");

        PhysicalWorld field = world(285.15);
        field.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.SOIL, 278.15);
        SkyModel humid = sky(285.15, 0.95, Sky.clear(-90.0));
        run(field, humid, 10);
        assertTrue(humid.lastStep().vapourJ() > 0, "dew settling on cold ground in humid air warms it");

        PhysicalWorld rock = world(ENV);
        rock.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.GRANITE, 303.15);
        SkyModel onRock = sky(ENV, 0.3, Sky.clear(-90.0));
        run(rock, onRock, 10);
        assertEquals(0.0, onRock.lastStep().vapourJ(), "dry rock has no water to give");
    }

    @Test
    void warmAirAboveTheGroundGivesItsHeatToTheGround() {
        PhysicalWorld world = world(ENV);
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.GRANITE, ENV);
        GridPos air = new GridPos(7, 4, 7);
        world.placeMaterial(air, MaterialLibrary.AIR, 333.15);
        SkyModel model = sky(ENV, 0.6, Sky.clear(-90.0));
        double before = temperature(world, air);
        run(world, model, 20);
        double after = temperature(world, air);
        assertTrue(after < before - 5.0, "the warm air cools through the ground: " + before + " -> " + after);
        assertTrue(after > ENV, "but not past the air around it");
    }

    @Test
    void theSkyConservesEnergyAndRunsTheSameEveryTime() {
        String first = runScene();
        String second = runScene();
        assertEquals(first, second, "the same scene gives the same world");
    }

    /** Runs a mixed scene with every thermal model through a day, checking each step's conservation audit. */
    private static String runScene() {
        PhysicalWorld world = world(ENV);
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.SOIL, 288.15);
        world.fill(new GridPos(2, 1, 2), new GridPos(5, 3, 5), MaterialLibrary.WATER, 290.0);
        world.fill(new GridPos(9, 4, 9), new GridPos(12, 4, 12), MaterialLibrary.POWDER_SNOW, 272.0);
        world.fill(new GridPos(1, 7, 10), new GridPos(4, 7, 13), MaterialLibrary.GLASS, ENV);
        world.placeMaterial(new GridPos(14, 4, 1), MaterialLibrary.BASALT, 900.0);
        world.fill(new GridPos(0, 16, 0), new GridPos(0, 16, 0), MaterialLibrary.AIR, ENV);
        SkyModel sky = sky(ENV, 0.6, null);
        AtmosphereModel atmosphere = new AtmosphereModel(AtmosphereModel.DEFAULT_RELAXATION_SECONDS, ENV);
        Scheduler scheduler = new Scheduler(DT * 10, Long.MAX_VALUE / 8, 4, 1);
        scheduler.register(sky);
        scheduler.register(new ConductionModel(null, sky));
        scheduler.register(new RadiationModel(atmosphere::environment, null, sky));
        scheduler.register(atmosphere);
        int day = 24 * STEPS_PER_HOUR / 10;
        for (int i = 0; i < day; i++) {
            Sky now = Sky.clear(360.0 * i / day);
            sky.setSky(i % 50 < 40 ? now : now.withWeather(0.8, 0.5, 0.2));
            TickReport report = scheduler.tick(world);
            assertTrue(report.conserved(), () -> "step " + report.tick() + ": " + report.audit());
        }
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
        return world.stateHash();
    }

    @Test
    void withoutASkyNothingHappens() {
        PhysicalWorld world = world(ENV);
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.SOIL, 288.15);
        SkyModel model = sky(ENV, 0.6, Sky.clear(90.0));
        step(world, model, DT);
        GridPos top = new GridPos(3, 3, 3);
        assertTrue(model.keepsTopOf(top.sectionKey(), top.indexInSection()));
        model.setSky(null);
        assertNull(model.sky());
        double before = world.readBlock(top).enthalpy();
        step(world, model, DT);
        assertFalse(model.keepsTopOf(top.sectionKey(), top.indexInSection()), "no sky keeps no faces");
        assertNull(model.openTops(top.sectionKey()));
        assertEquals(before, world.readBlock(top).enthalpy());
        assertEquals(0.0, model.absorbedSunlight(top));
        model.setSky(Sky.clear(90.0));
        step(world, model, DT);
        assertTrue(model.keepsTopOf(top.sectionKey(), top.indexInSection()), "the sky comes back");
    }

    @Test
    void aMeltedSurfaceBecomesALiquidSurface() {
        PhysicalWorld world = world(ENV);
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.WATER, 263.15);
        SkyModel model = sky(ENV, 0.6, Sky.clear(90.0));
        step(world, model, DT);
        GridPos top = new GridPos(3, 3, 3);
        assertEquals(Phase.SOLID, MaterialLibrary.WATER.dominantPhase(state(world, top)));
        double iceSkin = model.surfaceTemperature(top);
        assertTrue(iceSkin > 263.15, "the ice's skin warms in the sun: " + iceSkin);
        world.placeMaterial(top, MaterialLibrary.WATER, 280.0);
        step(world, model, DT);
        assertEquals(temperature(world, top), model.surfaceTemperature(top), 0.01,
                "a liquid's surface is the liquid itself");
    }

    @Test
    void groupsFollowTheWorldsSections() {
        PhysicalWorld world = world(ENV);
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 3, 15), MaterialLibrary.SOIL, ENV);
        world.fill(new GridPos(-16, 0, -16), new GridPos(-1, 3, -1), MaterialLibrary.SOIL, ENV);
        SkyModel model = sky(ENV, 0.6, Sky.clear(90.0));
        step(world, model, DT);
        GridPos negative = new GridPos(-3, 3, -5);
        assertTrue(model.keepsTopOf(negative.sectionKey(), negative.indexInSection()), "negative coordinates work");
        assertEquals(512, model.lastStep().surfaces());
        world.removeSection(negative.sectionKey());
        step(world, model, DT);
        assertEquals(256, model.lastStep().surfaces(), "a column whose sections left is forgotten");
        assertFalse(model.keepsTopOf(negative.sectionKey(), negative.indexInSection()));
        // A section stacked above moves the sky up, and the old surface stays the surface.
        world.placeMaterial(new GridPos(0, 20, 0), MaterialLibrary.AIR, ENV);
        step(world, model, DT);
        GridPos top = new GridPos(5, 3, 5);
        assertTrue(model.keepsTopOf(top.sectionKey(), top.indexInSection()));
        assertEquals(256, model.lastStep().surfaces());
    }
}
