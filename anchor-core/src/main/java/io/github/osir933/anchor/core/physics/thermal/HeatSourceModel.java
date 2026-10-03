package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.matter.Element;
import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.model.PhysicsModel;
import io.github.osir933.anchor.core.model.StepContext;
import io.github.osir933.anchor.core.model.ValidityIssue;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.RefinedBlock;
import io.github.osir933.anchor.core.world.Section;
import io.github.osir933.anchor.core.world.Totals;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;

/**
 * Blocks that release heat from something the simulation does not model yet, such as a flame or a lit
 * furnace. Each source heats its block towards a temperature with at most a given power, and never cools it.
 * The energy comes from outside the world, so every step declares what the sources added.
 *
 * <p>Sources are set by whoever knows what the blocks are, such as the Minecraft side, and are keyed by
 * block. Sources in sections outside the thermal scope wait, like everything else there.
 */
public final class HeatSourceModel implements PhysicsModel {

    /** The model id. */
    public static final String ID = "anchor:heat_sources";

    /**
     * What one source does.
     *
     * @param temperatureK the temperature the source heats its block towards, in kelvin
     * @param powerW the most heat the source can release, in watts
     */
    public record Source(double temperatureK, double powerW) {

        /**
         * Validates the source.
         *
         * @param temperatureK the target temperature
         * @param powerW the maximum power
         */
        public Source {
            if (!(temperatureK > 0) || !Double.isFinite(temperatureK)) {
                throw new IllegalArgumentException("source temperature must be positive: " + temperatureK);
            }
            if (!(powerW >= 0) || !Double.isFinite(powerW)) {
                throw new IllegalArgumentException("source power must be finite and non-negative: " + powerW);
            }
        }
    }

    private final TreeMap<GridPos, Source> sources = new TreeMap<>();
    private List<ValidityIssue> issues = List.of();
    private double lastEnergy;
    private int lastHeated;

    /** Creates the model with no sources. */
    public HeatSourceModel() {
    }

    /**
     * Makes a block a heat source, replacing any source it had.
     *
     * @param pos the block
     * @param source what the source does
     */
    public void put(GridPos pos, Source source) {
        sources.put(Objects.requireNonNull(pos, "pos"), Objects.requireNonNull(source, "source"));
    }

    /**
     * Stops a block being a heat source.
     *
     * @param pos the block
     */
    public void remove(GridPos pos) {
        sources.remove(pos);
    }

    /**
     * Removes every source in a section.
     *
     * @param sectionKey the packed section position
     */
    public void removeSection(long sectionKey) {
        sources.keySet().removeIf(pos -> pos.sectionKey() == sectionKey);
    }

    /**
     * Returns the sources in block order.
     *
     * @return an unmodifiable view
     */
    public SortedMap<GridPos, Source> sources() {
        return Collections.unmodifiableSortedMap(sources);
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
        return "Each source heats its block towards a set temperature with at most a set power and never cools "
                + "it; the energy comes from outside the simulation and is declared as such.";
    }

    @Override
    public int priority() {
        return -10;
    }

    @Override
    public long estimateCost(StepContext context) {
        return sources.size();
    }

    @Override
    public void step(StepContext context) {
        PhysicalWorld world = context.world();
        SortedSet<Long> scope = context.sections(Domain.THERMAL);
        MaterialRegistry registry = world.materials();
        double added = 0;
        int heated = 0;
        List<ValidityIssue> found = new ArrayList<>();
        for (var entry : sources.entrySet()) {
            GridPos pos = entry.getKey();
            if (!scope.contains(pos.sectionKey())) {
                continue;
            }
            Section section = world.section(pos.sectionKey());
            if (section == null) {
                continue;
            }
            Source source = entry.getValue();
            List<CellId> leaves = new ArrayList<>();
            List<CellState> states = new ArrayList<>();
            RefinedBlock block = section.refinedBlock(pos.indexInSection());
            if (block == null) {
                leaves.add(CellId.of(pos));
                states.add(section.blockState(pos.indexInSection()));
            } else {
                block.forEachLeaf((cell, state) -> {
                    leaves.add(cell);
                    states.add(state.copy());
                });
            }
            double[] deficit = new double[leaves.size()];
            double totalDeficit = 0;
            for (int i = 0; i < leaves.size(); i++) {
                CellState s = states.get(i);
                if (s.material() == MaterialRegistry.VACUUM || s.mass() == 0) {
                    continue;
                }
                Material m = registry.get(s.material());
                double target = s.mass() * m.specificEnthalpy(source.temperatureK());
                deficit[i] = Math.max(0, target - s.enthalpy());
                totalDeficit += deficit[i];
                if (!m.isDescribedAt(source.temperatureK())) {
                    found.add(new ValidityIssue(ID, leaves.get(i), "the source temperature "
                            + source.temperatureK() + " K is outside what the data for " + m.id() + " covers"));
                }
            }
            if (!(totalDeficit > 0)) {
                continue;
            }
            double share = Math.min(1.0, source.powerW() * context.dt() / totalDeficit);
            for (int i = 0; i < leaves.size(); i++) {
                if (deficit[i] > 0) {
                    CellState s = states.get(i);
                    double gain = deficit[i] * share;
                    world.writeLeaf(leaves.get(i), new CellState(s.material(), s.mass(), s.enthalpy() + gain,
                            s.owner(), Provenance.SIMULATED));
                    added += gain;
                }
            }
            heated++;
        }
        if (added > 0) {
            // Declared every step, so not logged as an event; lastStep() reports it.
            world.recordExchange(new Totals(0, added, added, new EnumMap<>(Element.class)), null);
        }
        lastEnergy = added;
        lastHeated = heated;
        issues = found;
    }

    /**
     * Returns how much heat the sources released in the last step, and how many blocks they heated.
     *
     * @return the energy in joules and the number of blocks
     */
    public StepSummary lastStep() {
        return new StepSummary(lastEnergy, lastHeated);
    }

    /**
     * What the sources did in one step.
     *
     * @param energyJ the heat released, in joules
     * @param blocks the number of blocks that received heat
     */
    public record StepSummary(double energyJ, int blocks) {
    }

    @Override
    public List<ValidityIssue> checkValidity(PhysicalWorld world) {
        return issues;
    }
}
