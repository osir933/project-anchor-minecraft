package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.matter.Element;
import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.model.PhysicsModel;
import io.github.osir933.anchor.core.model.StepContext;
import io.github.osir933.anchor.core.model.ValidityIssue;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.RefinedBlock;
import io.github.osir933.anchor.core.world.Section;
import io.github.osir933.anchor.core.world.Totals;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.TreeMap;

/**
 * The open atmosphere around the simulated region. Gas cells exchange heat with the surrounding weather,
 * relaxing towards their section's environment temperature with a fixed time constant; everything else
 * reaches the environment only through the gas. Without this, heat from a fire would pile up in the air
 * forever, because the simulated region is a closed box.
 *
 * <p>The relaxation is exact for any step length: a gas cell closes the fraction {@code 1 − exp(−dt/τ)} of
 * its gap to the environment. The energy exchanged with the environment is declared. Gas is kept gas: the
 * target for steam in cold weather is the coldest vapour, not liquid water.
 */
public final class AtmosphereModel implements PhysicsModel {

    /** The model id. */
    public static final String ID = "anchor:atmosphere";

    /** The default time constant, in seconds, of a gas cell's exchange with the weather. */
    public static final double DEFAULT_RELAXATION_SECONDS = 300.0;

    private final double relaxationSeconds;
    private final double defaultEnvironmentK;
    private final TreeMap<Long, Double> environment = new TreeMap<>();
    private double lastEnergy;

    /**
     * Creates the model.
     *
     * @param relaxationSeconds the time constant of a gas cell's exchange with the weather, in seconds
     * @param defaultEnvironmentK the environment temperature of sections without their own, in kelvin
     */
    public AtmosphereModel(double relaxationSeconds, double defaultEnvironmentK) {
        if (!(relaxationSeconds > 0) || !Double.isFinite(relaxationSeconds)) {
            throw new IllegalArgumentException("relaxation time must be positive: " + relaxationSeconds);
        }
        if (!(defaultEnvironmentK > 0) || !Double.isFinite(defaultEnvironmentK)) {
            throw new IllegalArgumentException("temperature must be positive: " + defaultEnvironmentK);
        }
        this.relaxationSeconds = relaxationSeconds;
        this.defaultEnvironmentK = defaultEnvironmentK;
    }

    /**
     * Sets the environment temperature of a section.
     *
     * @param sectionKey the packed section position
     * @param temperatureK the temperature in kelvin
     */
    public void setEnvironment(long sectionKey, double temperatureK) {
        if (!(temperatureK > 0) || !Double.isFinite(temperatureK)) {
            throw new IllegalArgumentException("temperature must be positive: " + temperatureK);
        }
        environment.put(sectionKey, temperatureK);
    }

    /**
     * Forgets a section's environment temperature.
     *
     * @param sectionKey the packed section position
     */
    public void removeEnvironment(long sectionKey) {
        environment.remove(sectionKey);
    }

    /**
     * Returns the environment temperature of a section.
     *
     * @param sectionKey the packed section position
     * @return the temperature in kelvin
     */
    public double environment(long sectionKey) {
        return environment.getOrDefault(sectionKey, defaultEnvironmentK);
    }

    /**
     * Returns the heat the atmosphere took from the world in the last step; negative if it gave heat.
     *
     * @return the energy in joules
     */
    public double lastStepEnergy() {
        return lastEnergy;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Domain domain() {
        return Domain.THERMAL;
    }

    @Override
    public String assumptions() {
        return "Gas cells relax towards their section's environment temperature with a time constant of "
                + relaxationSeconds + " s, standing in for wind and weather; the exchange is declared.";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public long estimateCost(StepContext context) {
        long cells = 0;
        for (long key : context.sections(Domain.THERMAL)) {
            Section s = context.world().section(key);
            if (s != null) {
                cells += s.isUniform() ? 1 : 4096 - s.refinedBlocks().size() + s.leafCount();
            }
        }
        return cells;
    }

    @Override
    public void step(StepContext context) {
        PhysicalWorld world = context.world();
        Relaxation relaxation = new Relaxation(world.materials(), -StrictMath.expm1(-context.dt() / relaxationSeconds));
        CellState written = CellState.vacuum(Provenance.SIMULATED);
        List<CellId> leaves = new ArrayList<>();
        List<CellState> updates = new ArrayList<>();
        for (long key : context.sections(Domain.THERMAL)) {
            Section section = world.section(key);
            if (section == null) {
                continue;
            }
            relaxation.environment(environment(key));
            if (section.isUniform() && sameBits(relaxation.of(section.material(0), section.mass(0),
                    section.enthalpy(0)), section.enthalpy(0))) {
                continue;
            }
            for (int b = 0; b < SectionPos.BLOCKS; b++) {
                RefinedBlock block = section.refinedBlock(b);
                if (block == null) {
                    double before = section.enthalpy(b);
                    double h = relaxation.of(section.material(b), section.mass(b), before);
                    if (!sameBits(h, before)) {
                        written.set(section.material(b), section.mass(b), h, section.owner(b), Provenance.SIMULATED);
                        world.writeBlock(key, b, written);
                        relaxation.record(h - before);
                    }
                    continue;
                }
                leaves.clear();
                updates.clear();
                block.forEachLeaf((cell, state) -> {
                    double h = relaxation.of(state.material(), state.mass(), state.enthalpy());
                    if (!sameBits(h, state.enthalpy())) {
                        leaves.add(cell);
                        updates.add(new CellState(state.material(), state.mass(), h, state.owner(),
                                Provenance.SIMULATED));
                        relaxation.record(h - state.enthalpy());
                    }
                });
                for (int i = 0; i < leaves.size(); i++) {
                    world.writeLeaf(leaves.get(i), updates.get(i));
                }
            }
        }
        if (relaxation.absolute > 0) {
            // Declared every step, so not logged as an event; lastStepEnergy() reports it.
            world.recordExchange(new Totals(0, relaxation.given, relaxation.absolute, new EnumMap<>(Element.class)),
                    null);
        }
        lastEnergy = -relaxation.given;
    }

    private static boolean sameBits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    /** Works out where gas cells move in one step, remembering each material's target per environment. */
    private static final class Relaxation {
        private final MaterialRegistry registry;
        private final double fraction;
        private final double[] targets;
        private double environmentK = Double.NaN;
        private double given;
        private double absolute;

        private Relaxation(MaterialRegistry registry, double fraction) {
            this.registry = registry;
            this.fraction = fraction;
            this.targets = new double[registry.size()];
        }

        /** Sets the environment temperature of the section being worked on. */
        private void environment(double temperatureK) {
            if (temperatureK != environmentK) {
                environmentK = temperatureK;
                Arrays.fill(targets, Double.NaN);
            }
        }

        /** Returns the enthalpy matter moves to this step: closer to the environment if it is a gas. */
        private double of(int material, double mass, double enthalpy) {
            if (material == MaterialRegistry.VACUUM || mass == 0) {
                return enthalpy;
            }
            Material m = registry.get(material);
            Phase only = m.onlyPhase();
            if (only != null ? only != Phase.GAS : m.dominantPhase(m.stateFor(enthalpy / mass)) != Phase.GAS) {
                return enthalpy;
            }
            double target = targets[material];
            if (Double.isNaN(target)) {
                target = m.nearestSpecificEnthalpyIn(Phase.GAS, m.specificEnthalpy(environmentK));
                targets[material] = target;
            }
            return enthalpy + (mass * target - enthalpy) * fraction;
        }

        /** Counts heat given to a cell by the environment; negative if the cell gave heat. */
        private void record(double joules) {
            given += joules;
            absolute += Math.abs(joules);
        }
    }

    @Override
    public List<ValidityIssue> checkValidity(PhysicalWorld world) {
        return List.of();
    }
}
