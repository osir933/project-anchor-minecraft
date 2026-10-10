package io.github.osir933.anchor.core.physics.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.Mechanics;
import io.github.osir933.anchor.core.matter.PropertyCurve;
import io.github.osir933.anchor.core.matter.Source;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;

class ThermalShockTest {

    private static final Source TEST = new Source("test", "test", Source.DataQuality.ESTIMATED);

    /** A brittle solid whose properties do not change with temperature, so that results can be worked by hand. */
    private static final Mechanics STEADY = new Mechanics(Mechanics.Failure.BRITTLE,
            PropertyCurve.constant(50e9, TEST), 0.25, PropertyCurve.constant(10e6, TEST),
            PropertyCurve.constant(150e6, TEST), 0.6, PropertyCurve.constant(8e-6, TEST), 1.5e6, TEST);

    /** The cells of a block split into 4 along each side, 25 cm apart, with a temperature for each centre. */
    private static ThermalShock.Result grid(Mechanics mechanics, ToDoubleFunction<double[]> temperature) {
        int n = 64;
        double[] x = new double[n];
        double[] y = new double[n];
        double[] z = new double[n];
        double[] mass = new double[n];
        double[] t = new double[n];
        int k = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                for (int l = 0; l < 4; l++) {
                    x[k] = 10.125 + 0.25 * i;
                    y[k] = 64.125 + 0.25 * j;
                    z[k] = -3.875 + 0.25 * l;
                    mass[k] = 2700.0 / 64;
                    t[k] = temperature.applyAsDouble(new double[] {x[k] - 10.5, y[k] - 64.5, z[k] + 3.5});
                    k++;
                }
            }
        }
        return ThermalShock.analyse(mechanics, x, y, z, mass, t, n);
    }

    @Test
    void aBlockAtOneTemperatureIsNotStressed() {
        assertSame(ThermalShock.NONE, grid(STEADY, p -> 700.0));
        assertSame(ThermalShock.NONE, ThermalShock.analyse(STEADY, new double[] {0.5}, new double[] {0.5},
                new double[] {0.5}, new double[] {2700.0}, new double[] {900.0}, 1));
    }

    @Test
    void aTemperatureThatChangesInAStraightLineOnlyBendsTheBlock() {
        // 600 K from one face to the other, as through a wall between a fire and the cold, and still no stress:
        // granite's stiffness changes with temperature, which moves where the block bends about but not whether.
        ThermalShock.Result r = grid(MaterialLibrary.GRANITE.mechanics(), p -> 600.0 + 600.0 * p[0] - 200.0 * p[2]);
        assertTrue(r.load() < 1e-9, "a straight-line field: " + r);
    }

    @Test
    void aSlabCooledOnBothFacesIsPulledThereByAQuarterOfTheFallTimesEAlphaOverOneMinusNu() {
        // T = 500 - 4 dT z^2 across the slab: the cells' mean is 500 - 0.3125 dT and the outer cells, at
        // z = ±0.375, are 500 - 0.5625 dT, so the rest of the block holds them stretched by alpha · 0.25 dT.
        double fall = 100.0;
        ThermalShock.Result r = grid(STEADY, p -> 500.0 - 4 * fall * p[1] * p[1]);
        double expected = 50e9 * 8e-6 * 0.25 * fall / 0.75;
        assertEquals(expected, r.stressPa(), expected * 1e-9);
        assertTrue(r.tension());
        assertEquals(expected / 10e6, r.load(), 1e-9);
        assertTrue(r.load() > 1, "granite-like stone cracks when its faces are 100 K colder than its middle");
        // Heated on both faces instead, the faces are pressed, which they hold, and the middle, now the coolest
        // part, is pulled just as hard, so the slab cracks inside.
        ThermalShock.Result warmed = grid(STEADY, p -> 500.0 + 4 * fall * p[1] * p[1]);
        assertTrue(warmed.tension());
        assertEquals(expected, warmed.stressPa(), expected * 1e-9);
        assertEquals(r.load(), warmed.load(), 1e-9);
    }

    @Test
    void heatOnOneFacePressesThatFaceAndPullsTheBlockBehindIt() {
        // A face suddenly heated: hot cells by it, the rest still cold. A straight line through that profile runs
        // above the cold middle, which is therefore pulled, and below the hot face, which is pressed.
        ThermalShock.Result r = grid(STEADY, p -> p[0] < -0.25 ? 900.0 : 300.0);
        assertTrue(r.tension(), "the block behind the heated face cracks first: " + r);
        assertTrue(r.load() > 1, "600 K over a quarter of a block cracks stone: " + r);
    }

    @Test
    void theMostLoadedCellIsCheckedAtItsOwnTemperature() {
        // Glass loses strength and stiffness as it softens, so the same profile is checked differently when hot.
        Mechanics glass = MaterialLibrary.GLASS.mechanics();
        ThermalShock.Result cool = grid(glass, p -> 330.0 - 120.0 * p[1] * p[1]);
        ThermalShock.Result hot = grid(glass, p -> 900.0 - 120.0 * p[1] * p[1]);
        assertTrue(cool.load() > 0 && hot.load() > 0);
        assertTrue(hot.load() != cool.load(), cool + " against " + hot);
    }

    @Test
    void cellsThatAllLieInOnePlaneAreFittedInThatPlane() {
        double[] x = {0.25, 0.75, 0.25, 0.75, 0.5};
        double[] y = {0.25, 0.25, 0.75, 0.75, 0.5};
        double[] z = {0.5, 0.5, 0.5, 0.5, 0.5};
        double[] mass = {1, 1, 1, 1, 1};
        double[] t = {300, 300, 300, 300, 500};
        // The mean is 340 K and, by symmetry, the fit has no slope: the hot middle is pressed by 160 K of expansion,
        // well within the compressive strength, while the corners are pulled by 40 K, past the tensile strength.
        ThermalShock.Result r = ThermalShock.analyse(STEADY, x, y, z, mass, t, 5);
        assertEquals(0, r.part(), "the first of the equally pulled corners: " + r);
        assertTrue(r.tension());
        double stretched = 50e9 * 8e-6 * 40 / 0.75;
        assertEquals(stretched, r.stressPa(), stretched * 1e-9);
        assertEquals(stretched / 10e6, r.load(), 1e-9);
    }

    @Test
    void rejectsPartsThatAreNotThere() {
        double[] one = {1};
        assertThrows(IllegalArgumentException.class,
                () -> ThermalShock.analyse(STEADY, one, one, one, one, one, 2));
        assertThrows(IllegalArgumentException.class, () -> ThermalShock.analyse(STEADY, new double[] {0, 1},
                new double[] {0, 1}, new double[] {0, 1}, new double[] {1, 0}, new double[] {300, 300}, 2));
        assertThrows(IllegalArgumentException.class, () -> ThermalShock.analyse(STEADY, new double[] {0, 1},
                new double[] {0, 1}, new double[] {0, 1}, new double[] {1, 1}, new double[] {300, Double.NaN}, 2));
    }
}
