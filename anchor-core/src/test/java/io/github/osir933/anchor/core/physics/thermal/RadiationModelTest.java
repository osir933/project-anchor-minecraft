package io.github.osir933.anchor.core.physics.thermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.math.DeterministicRandom;
import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.matter.ThermalState;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.model.SimulationScope;
import io.github.osir933.anchor.core.model.StepContext;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.units.PhysicalConstants;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RadiationModelTest {

    private static final double ENVIRONMENT_K = 300.0;
    private static final double SIGMA = PhysicalConstants.STEFAN_BOLTZMANN;
    private static final int FINE = 4096;

    private static PhysicalWorld vacuumWorld() {
        return new PhysicalWorld(WorldSettings.vacuum(5), MaterialRegistry.withLibrary());
    }

    private static RadiationModel model() {
        return new RadiationModel(key -> ENVIRONMENT_K);
    }

    private static RadiationModel model(int rays) {
        return new RadiationModel(key -> ENVIRONMENT_K, rays, RadiationModel.DEFAULT_RANGE_BLOCKS,
                RadiationModel.DEFAULT_RADIATING_DIFFERENCE_K);
    }

    private static void step(PhysicalWorld world, RadiationModel model, double dt) {
        step(world, model, dt, SimulationScope.everywhere());
    }

    private static void step(PhysicalWorld world, RadiationModel model, double dt, SimulationScope scope) {
        model.step(new StepContext(world, dt, new DeterministicRandom(1), scope));
    }

    private static ThermalState state(PhysicalWorld world, GridPos pos) {
        CellState c = world.readBlock(pos);
        return world.materials().get(c.material()).stateFor(c.specificEnthalpy());
    }

    private static double temperature(PhysicalWorld world, GridPos pos) {
        return state(world, pos).temperatureK();
    }

    private static double emissivity(PhysicalWorld world, GridPos pos) {
        return world.materials().get(world.readBlock(pos).material()).emissivity(state(world, pos));
    }

    private static double enthalpy(PhysicalWorld world, GridPos pos) {
        return world.readBlock(pos).enthalpy();
    }

    private static double fourth(double t) {
        return t * t * t * t;
    }

    /** Builds a shell of blocks: the box from -outer to outer on each axis, less the box from -inner to inner. */
    private static void shell(PhysicalWorld world, int outer, int inner, Material material, double temperatureK) {
        for (int x = -outer; x <= outer; x++) {
            for (int y = -outer; y <= outer; y++) {
                for (int z = -outer; z <= outer; z++) {
                    if (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) > inner) {
                        world.placeMaterial(new GridPos(x, y, z), material, temperatureK);
                    }
                }
            }
        }
    }

    @Test
    void facingSquaresSeeEachOtherAsTheFormulaSays() {
        // Two unit squares facing each other one block apart: F = 0.1998 for aligned parallel squares
        // (Incropera, Fundamentals of Heat and Mass Transfer, table 13.2). The cold block is at its
        // environment's temperature, so it does not radiate, and only the hot block's east face sees it.
        PhysicalWorld world = vacuumWorld();
        GridPos hot = new GridPos(0, 0, 0);
        GridPos cold = new GridPos(2, 0, 0);
        world.placeMaterial(hot, MaterialLibrary.IRON, 1000.0);
        world.placeMaterial(cold, MaterialLibrary.GRANITE, ENVIRONMENT_K);
        double exchange = SIGMA * emissivity(world, hot) * emissivity(world, cold)
                * (fourth(temperature(world, hot)) - fourth(temperature(world, cold)));
        double before = enthalpy(world, cold);
        step(world, model(FINE), 1.0);
        double viewFactor = (enthalpy(world, cold) - before) / exchange;
        assertEquals(0.1998, viewFactor, 0.002);
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void squaresMeetingAtAnEdgeSeeEachOtherAsTheFormulaSays() {
        // The cold block sits on the hot block's upper east edge: the hot top face sees its west face, and the
        // hot east face sees its bottom face, each with F = 0.2004 for perpendicular squares sharing an edge.
        PhysicalWorld world = vacuumWorld();
        GridPos hot = new GridPos(0, 0, 0);
        GridPos cold = new GridPos(1, 1, 0);
        world.placeMaterial(hot, MaterialLibrary.IRON, 1000.0);
        world.placeMaterial(cold, MaterialLibrary.GRANITE, ENVIRONMENT_K);
        double exchange = SIGMA * emissivity(world, hot) * emissivity(world, cold)
                * (fourth(temperature(world, hot)) - fourth(temperature(world, cold)));
        double before = enthalpy(world, cold);
        step(world, model(FINE), 1.0);
        double viewFactors = (enthalpy(world, cold) - before) / exchange;
        assertEquals(2 * 0.2004, viewFactors, 0.006);
    }

    @Test
    void aFaceUnderACeilingSendsNearlyEverythingToIt() {
        // A hot block set into a floor radiates only upwards, at a ceiling one block above; what misses the
        // ceiling leaves through the open sides to the surroundings.
        PhysicalWorld world = vacuumWorld();
        GridPos hot = new GridPos(0, 0, 0);
        world.fill(new GridPos(-7, -1, -7), new GridPos(7, 0, 7), MaterialLibrary.GRANITE, ENVIRONMENT_K);
        world.fill(new GridPos(-7, 2, -7), new GridPos(7, 2, 7), MaterialLibrary.GRANITE, ENVIRONMENT_K);
        world.placeMaterial(hot, MaterialLibrary.IRON, 1000.0);
        double[] ceilingBefore = ceiling(world);
        double hotBefore = enthalpy(world, hot);
        double emitted = SIGMA * emissivity(world, hot) * (fourth(temperature(world, hot)) - fourth(ENVIRONMENT_K));
        double absorbed = emissivity(world, new GridPos(0, 2, 0));
        RadiationModel model = model(FINE);
        step(world, model, 1.0);
        assertEquals(1, model.lastRadiatingFaces(), "only the top face is open");
        double[] ceilingAfter = ceiling(world);
        double received = 0;
        for (int i = 0; i < ceilingAfter.length; i++) {
            received += ceilingAfter[i] - ceilingBefore[i];
        }
        double rays = received / (emitted * absorbed) * FINE;
        assertEquals(Math.round(rays), rays, 1e-6, "a whole number of rays");
        double viewFactor = (double) Math.round(rays) / FINE;
        assertTrue(viewFactor > 0.98 && viewFactor < 0.99, "view factor " + viewFactor);
        assertEquals(emitted * (1 - viewFactor), model.lastStepEnergy(), emitted * 1e-12,
                "the rest went to the surroundings");
        assertEquals(emitted * (viewFactor * absorbed + 1 - viewFactor), hotBefore - enthalpy(world, hot),
                emitted * 1e-9);
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    private static double[] ceiling(PhysicalWorld world) {
        double[] enthalpies = new double[15 * 15];
        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                enthalpies[(x + 7) * 15 + z + 7] = enthalpy(world, new GridPos(x, 2, z));
            }
        }
        return enthalpies;
    }

    @Test
    void radiationLeavingTheWorldIsDeclared() {
        PhysicalWorld world = vacuumWorld();
        GridPos pos = new GridPos(3, 70, -9);
        world.placeMaterial(pos, MaterialLibrary.IRON, 1000.0);
        double expected = 6 * SIGMA * emissivity(world, pos) * (fourth(temperature(world, pos))
                - fourth(ENVIRONMENT_K)) * 2.0;
        double before = enthalpy(world, pos);
        RadiationModel model = model();
        step(world, model, 2.0);
        assertEquals(expected, before - enthalpy(world, pos), expected * 1e-9);
        assertEquals(expected, model.lastStepEnergy(), expected * 1e-12);
        assertEquals(6, model.lastRadiatingFaces());
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
        assertEquals(Provenance.SIMULATED, world.readBlock(pos).provenance());
    }

    @Test
    void aColdBlockTakesInTheSurroundingsRadiation() {
        PhysicalWorld world = vacuumWorld();
        GridPos ice = new GridPos(0, 0, 0);
        world.placeMaterial(ice, MaterialLibrary.WATER, 250.0);
        double before = enthalpy(world, ice);
        RadiationModel model = model();
        step(world, model, 10.0);
        assertTrue(enthalpy(world, ice) > before, "the ice warmed");
        assertTrue(model.lastStepEnergy() < 0, "the surroundings gave heat");
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void twoRadiatingBlocksCountTheirExchangeOnce() {
        // Both blocks radiate, so each estimates their exchange and counts half. By symmetry the estimates
        // agree, so the result must be what one block's estimate gives when the other does not radiate.
        PhysicalWorld calm = vacuumWorld();
        GridPos a = new GridPos(0, 0, 0);
        GridPos b = new GridPos(2, 0, 0);
        calm.placeMaterial(a, MaterialLibrary.IRON, 1000.0);
        calm.placeMaterial(b, MaterialLibrary.IRON, ENVIRONMENT_K);
        double calmBefore = enthalpy(calm, b);
        step(calm, model(FINE), 1.0);
        double rays = (enthalpy(calm, b) - calmBefore) * FINE / (SIGMA * emissivity(calm, a)
                * emissivity(calm, b) * (fourth(1000.0) - fourth(ENVIRONMENT_K)));
        int hits = (int) Math.round(rays);
        assertEquals(hits, rays, 1e-6, "a whole number of rays");

        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(a, MaterialLibrary.IRON, 1000.0);
        world.placeMaterial(b, MaterialLibrary.IRON, 500.0);
        double ta = temperature(world, a);
        double tb = temperature(world, b);
        double ea = emissivity(world, a);
        double eb = emissivity(world, b);
        double aBefore = enthalpy(world, a);
        double bBefore = enthalpy(world, b);
        step(world, model(FINE), 1.0);
        double between = SIGMA * ea * eb * (fourth(ta) - fourth(tb)) * hits / FINE;
        double aEscapes = SIGMA * ea * (fourth(ta) - fourth(ENVIRONMENT_K)) * (6.0 * FINE - hits) / FINE;
        double bEscapes = SIGMA * eb * (fourth(tb) - fourth(ENVIRONMENT_K)) * (6.0 * FINE - hits) / FINE;
        assertEquals(-between - aEscapes, enthalpy(world, a) - aBefore, aEscapes * 1e-9);
        assertEquals(between - bEscapes, enthalpy(world, b) - bBefore, aEscapes * 1e-9);
    }

    @Test
    void noHeatMovesBetweenEqualTemperatures() {
        // A cavity whose walls and contents are all at 600 K, inside an outer shell at the environment's
        // temperature: the walls and the block in the cavity radiate, but only at each other. One material
        // throughout makes the temperatures exactly equal, not just equal to within rounding.
        PhysicalWorld world = vacuumWorld();
        shell(world, 3, 2, MaterialLibrary.GRANITE, ENVIRONMENT_K);
        shell(world, 2, 1, MaterialLibrary.GRANITE, 600.0);
        world.placeMaterial(new GridPos(0, 0, 0), MaterialLibrary.GRANITE, 600.0);
        String before = world.stateHash();
        RadiationModel model = model();
        for (int i = 0; i < 3; i++) {
            step(world, model, 60.0);
        }
        assertEquals(60, model.lastRadiatingFaces(), "six walls of nine faces, and the block's six");
        assertEquals(before, world.stateHash());
        assertEquals(0.0, model.lastStepEnergy(), 0.0);
    }

    @Test
    void aClosedCavityKeepsItsHeat() {
        PhysicalWorld world = vacuumWorld();
        shell(world, 3, 2, MaterialLibrary.GRANITE, ENVIRONMENT_K);
        shell(world, 2, 1, MaterialLibrary.GRANITE, 400.0);
        GridPos hot = new GridPos(0, 0, 0);
        world.placeMaterial(hot, MaterialLibrary.IRON, 1000.0);
        GridPos wall = new GridPos(2, 0, 0);
        double total = world.totals().enthalpy();
        double hotBefore = temperature(world, hot);
        double wallBefore = temperature(world, wall);
        RadiationModel model = model();
        for (int i = 0; i < 20; i++) {
            step(world, model, 60.0);
            assertEquals(0.0, model.lastStepEnergy(), 0.0, "nothing reaches the surroundings");
            assertTrue(world.audit().balanced(), () -> world.audit().toString());
        }
        assertEquals(total, world.totals().enthalpy(), Math.abs(total) * 1e-12);
        assertTrue(temperature(world, hot) < hotBefore, "the block cooled");
        assertTrue(temperature(world, wall) > wallBefore, "the wall facing it warmed");
    }

    @Test
    void glassStopsRadiation() {
        PhysicalWorld world = vacuumWorld();
        GridPos hot = new GridPos(0, 0, 0);
        GridPos glass = new GridPos(2, 0, 0);
        GridPos behind = new GridPos(4, 0, 0);
        world.placeMaterial(hot, MaterialLibrary.IRON, 1000.0);
        world.placeMaterial(glass, MaterialLibrary.GLASS, ENVIRONMENT_K);
        world.placeMaterial(behind, MaterialLibrary.GRANITE, ENVIRONMENT_K);
        double glassBefore = enthalpy(world, glass);
        CellState behindBefore = world.readBlock(behind);
        step(world, model(), 1.0);
        assertTrue(enthalpy(world, glass) > glassBefore, "the glass took the radiation in");
        assertEquals(behindBefore, world.readBlock(behind), "nothing passed through the glass");
    }

    @Test
    void airLetsRadiationThrough() {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(5), MaterialRegistry.withLibrary());
        GridPos hot = new GridPos(0, 0, 0);
        GridPos cold = new GridPos(3, 0, 0);
        world.placeMaterial(hot, MaterialLibrary.IRON, 1000.0);
        world.placeMaterial(cold, MaterialLibrary.GRANITE, ENVIRONMENT_K);
        CellState air = world.readBlock(new GridPos(1, 0, 0));
        double before = enthalpy(world, cold);
        RadiationModel model = model();
        step(world, model, 1.0);
        assertTrue(enthalpy(world, cold) > before, "the radiation crossed the air");
        assertEquals(air, world.readBlock(new GridPos(1, 0, 0)), "the air took none of it");
        assertEquals(6, model.lastRadiatingFaces());
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void blocksNearTheirEnvironmentsTemperatureDoNotRadiate() {
        PhysicalWorld world = vacuumWorld();
        GridPos warm = new GridPos(0, 0, 0);
        GridPos cool = new GridPos(2, 0, 0);
        world.placeMaterial(warm, MaterialLibrary.IRON, ENVIRONMENT_K + 9.0);
        world.placeMaterial(cool, MaterialLibrary.GRANITE, ENVIRONMENT_K - 9.0);
        String before = world.stateHash();
        RadiationModel model = model();
        step(world, model, 60.0);
        assertEquals(before, world.stateHash());
        assertEquals(0, model.lastRadiatingFaces());
        assertEquals(0, model.lastRaysCast());

        world.placeMaterial(warm, MaterialLibrary.IRON, ENVIRONMENT_K + 11.0);
        step(world, model, 60.0);
        assertEquals(6, model.lastRadiatingFaces(), "just outside the band, the block radiates");
        assertNotEquals(before, world.stateHash());
        assertTrue(model.lastStepEnergy() > 0);

        world.placeMaterial(warm, MaterialLibrary.IRON, ENVIRONMENT_K);
        step(world, model, 60.0);
        assertEquals(0, model.lastRadiatingFaces());
        assertEquals(0.0, model.lastStepEnergy(), "a step without radiation sends nothing away");
    }

    @Test
    void radiationReachesBlocksOutsideTheScope() {
        PhysicalWorld world = vacuumWorld();
        GridPos hot = new GridPos(15, 0, 0);
        GridPos outside = new GridPos(17, 0, 0);
        GridPos idle = new GridPos(20, 0, 0);
        world.placeMaterial(hot, MaterialLibrary.IRON, 1000.0);
        world.placeMaterial(outside, MaterialLibrary.GRANITE, ENVIRONMENT_K);
        world.placeMaterial(idle, MaterialLibrary.IRON, 1000.0);
        double before = enthalpy(world, outside);
        CellState idleBefore = world.readBlock(idle);
        SimulationScope scope = SimulationScope.of(Map.of(Domain.THERMAL, List.of(hot.sectionKey())));
        step(world, model(), 1.0, scope);
        assertTrue(enthalpy(world, outside) > before, "the block in the next section took the radiation in");
        assertEquals(idleBefore, world.readBlock(idle), "a hot block outside the scope waits");
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void keptRaysAreUsedUntilSomethingOnTheirWayChanges() {
        PhysicalWorld world = vacuumWorld();
        GridPos hot = new GridPos(0, 0, 0);
        GridPos far = new GridPos(3, 0, 0);
        world.placeMaterial(hot, MaterialLibrary.IRON, 1000.0);
        world.placeMaterial(far, MaterialLibrary.GRANITE, ENVIRONMENT_K);
        RadiationModel model = model();
        int faceRays = 6 * RadiationModel.DEFAULT_RAYS_PER_FACE;
        step(world, model, 1.0);
        assertEquals(faceRays, model.lastRaysCast());
        step(world, model, 1.0);
        assertEquals(0, model.lastRaysCast(), "nothing changed, so the rays are kept");

        world.placeMaterial(far, MaterialLibrary.GRANITE, ENVIRONMENT_K + 5.0);
        step(world, model, 1.0);
        assertEquals(0, model.lastRaysCast(), "a warmer block still stops the rays where it did");

        world.placeMaterial(new GridPos(2, 0, 0), MaterialLibrary.GRANITE, ENVIRONMENT_K);
        double farBefore = enthalpy(world, far);
        step(world, model, 1.0);
        assertEquals(faceRays, model.lastRaysCast(), "a new block on the way means casting again");
        assertEquals(farBefore, enthalpy(world, far), "the new block shades the old one");

        world.refine(new CellId(new GridPos(2, 0, 0), 1, 0, 0, 0));
        CellState shaded = world.readBlock(new GridPos(2, 0, 0));
        step(world, model, 1.0);
        assertEquals(faceRays, model.lastRaysCast(), "refining the block it stopped at means casting again");
        assertEquals(shaded, world.readBlock(new GridPos(2, 0, 0)), "refined blocks take no radiation yet");
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void keptRaysChangeNothing() {
        // One model keeps its rays from step to step; a fresh model each step casts them again. Both must
        // give exactly the same world, as must a second world stepped the same way.
        PhysicalWorld kept = scene();
        PhysicalWorld fresh = scene();
        PhysicalWorld again = scene();
        RadiationModel keeping = model();
        RadiationModel second = model();
        for (int i = 0; i < 6; i++) {
            step(kept, keeping, 30.0);
            step(fresh, model(), 30.0);
            step(again, second, 30.0);
            assertEquals(fresh.stateHash(), kept.stateHash(), "step " + i);
            assertEquals(kept.stateHash(), again.stateHash(), "step " + i);
        }
        assertTrue(kept.audit().balanced(), () -> kept.audit().toString());
    }

    /** A few hot and cold blocks and walls on both sides of a section boundary. */
    private static PhysicalWorld scene() {
        PhysicalWorld world = vacuumWorld();
        world.fill(new GridPos(-3, -1, -3), new GridPos(3, -1, 3), MaterialLibrary.GRANITE, ENVIRONMENT_K);
        world.fill(new GridPos(4, -1, -3), new GridPos(4, 3, 3), MaterialLibrary.BRICK, ENVIRONMENT_K);
        world.placeMaterial(new GridPos(0, 0, 0), MaterialLibrary.IRON, 1100.0);
        world.placeMaterial(new GridPos(-1, 0, 1), MaterialLibrary.BASALT, 900.0);
        world.placeMaterial(new GridPos(2, 1, -1), MaterialLibrary.GLASS, 350.0);
        world.placeMaterial(new GridPos(-2, 0, -2), MaterialLibrary.WATER, 260.0);
        return world;
    }

    @Test
    void smallHotBlocksCoolSmoothly() {
        // 100 kg of basalt at 1400 K cools so fast that one explicit step of 100 s would overshoot far below
        // the surroundings; substeps keep it smooth.
        PhysicalWorld world = vacuumWorld();
        GridPos pos = new GridPos(0, 0, 0);
        world.setBlock(pos, block(world, MaterialLibrary.BASALT, 100.0, 1400.0));
        RadiationModel model = model();
        double last = temperature(world, pos);
        for (int i = 0; i < 40; i++) {
            step(world, model, 100.0);
            double t = temperature(world, pos);
            assertTrue(t < last && t > ENVIRONMENT_K, "step " + i + ": " + last + " K, then " + t + " K");
            assertTrue(model.checkValidity(world).isEmpty(), () -> model.checkValidity(world).toString());
            last = t;
        }
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void blocksTooSmallForTheSubstepsAreDampedAndReported() {
        // 100 g of basalt would need thousands of substeps; damped, it cools to its surroundings' temperature
        // without overshooting, and stops radiating once it is close.
        PhysicalWorld world = vacuumWorld();
        GridPos pos = new GridPos(0, 0, 0);
        world.setBlock(pos, block(world, MaterialLibrary.BASALT, 0.1, 1400.0));
        RadiationModel model = model();
        step(world, model, 100.0);
        assertEquals(1, model.checkValidity(world).size());
        assertTrue(model.checkValidity(world).get(0).message().startsWith("1 blocks need more than"),
                () -> model.checkValidity(world).toString());
        double last = temperature(world, pos);
        assertTrue(last < 1400.0 && last > ENVIRONMENT_K, last + " K");
        for (int i = 0; i < 5; i++) {
            step(world, model, 100.0);
            double t = temperature(world, pos);
            assertTrue(t <= last && t > ENVIRONMENT_K, "step " + i + ": " + last + " K, then " + t + " K");
            last = t;
        }
        assertTrue(last < ENVIRONMENT_K + RadiationModel.DEFAULT_RADIATING_DIFFERENCE_K, last + " K");
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    private static CellState block(PhysicalWorld world, Material material, double mass, double temperatureK) {
        return new CellState(world.materials().indexOf(material), mass, mass * material.specificEnthalpy(temperatureK),
                0, Provenance.INITIAL);
    }

    @Test
    void gasLetsRadiationThroughFromHalfwayThroughBoiling() {
        assertEquals(Double.NEGATIVE_INFINITY, RadiationModel.gasAbove(MaterialLibrary.AIR));
        assertEquals(Double.POSITIVE_INFINITY, RadiationModel.gasAbove(MaterialLibrary.GLASS));
        Material water = MaterialLibrary.WATER;
        double boiling = RadiationModel.gasAbove(water);
        assertEquals(Phase.LIQUID, water.dominantPhase(water.stateFor(boiling - 1.0)));
        assertEquals(Phase.GAS, water.dominantPhase(water.stateFor(boiling + 1.0)));
    }
}
