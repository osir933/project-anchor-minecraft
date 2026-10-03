package io.github.osir933.anchor.core.matter;

import io.github.osir933.anchor.core.units.PhysicalConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A kind of matter: its composition and how its physical properties depend on its state.
 *
 * <p>A material is a description, not a game item. Two cells of the same material can be in very different
 * states (one frozen, one half melted); the state lives in the world, and the material turns state into
 * properties. Every property curve carries its {@link Source}.
 */
public final class Material {

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private final String id;
    private final String name;
    private final Composition composition;
    private final EnthalpyCurve thermal;
    private final String notes;
    private final double minSpecificHeat;
    private final Phase onlyPhase;

    private Material(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.composition = Objects.requireNonNull(builder.composition, "composition");
        this.thermal = new EnthalpyCurve(builder.regions, builder.transitions);
        this.notes = builder.notes;
        double min = Double.POSITIVE_INFINITY;
        for (PhaseRegion r : thermal.regions()) {
            PropertyCurve c = r.specificHeat();
            min = Math.min(min, Math.min(c.at(r.fromK()), c.at(r.toK())));
            for (double t : c.breakpointsBetween(r.fromK(), r.toK())) {
                min = Math.min(min, c.at(t));
            }
        }
        this.minSpecificHeat = min;
        Phase only = thermal.regions().get(0).phase();
        for (PhaseRegion r : thermal.regions()) {
            if (r.phase() != only) {
                only = null;
                break;
            }
        }
        this.onlyPhase = only;
    }

    /**
     * Starts building a material.
     *
     * @param id a namespaced id such as {@code anchor:iron}
     * @param name the display name
     * @return the builder
     */
    public static Builder builder(String id, String name) {
        return new Builder(id, name);
    }

    /**
     * Returns the namespaced id.
     *
     * @return the id
     */
    public String id() {
        return id;
    }

    /**
     * Returns the display name.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the chemical composition.
     *
     * @return the composition
     */
    public Composition composition() {
        return composition;
    }

    /**
     * Returns the enthalpy curve with the material's phase regions and transitions.
     *
     * @return the thermal description
     */
    public EnthalpyCurve thermal() {
        return thermal;
    }

    /**
     * Returns free-text notes on assumptions and known limits of this description.
     *
     * @return the notes, possibly empty
     */
    public String notes() {
        return notes;
    }

    /**
     * Returns the specific enthalpy at a temperature, in the phase stable there.
     *
     * @param temperatureK the temperature in kelvin
     * @return the specific enthalpy in J/kg
     */
    public double specificEnthalpy(double temperatureK) {
        return thermal.specificEnthalpy(temperatureK);
    }

    /**
     * Returns the thermal state for a specific enthalpy.
     *
     * @param specificEnthalpy the specific enthalpy in J/kg
     * @return the thermal state
     */
    public ThermalState stateFor(double specificEnthalpy) {
        return thermal.stateFor(specificEnthalpy);
    }

    /**
     * Returns the temperature for a specific enthalpy, the same as {@code stateFor(h).temperatureK()} but
     * without building the state.
     *
     * @param specificEnthalpy the specific enthalpy in J/kg
     * @return the temperature in kelvin
     */
    public double temperatureFor(double specificEnthalpy) {
        return thermal.temperatureFor(specificEnthalpy);
    }

    /**
     * Returns the thermal conductivity in a state. During a transition the two phases are combined in
     * proportion to their mass fractions, a first approximation that ignores their geometry.
     *
     * @param state the thermal state
     * @return the conductivity in W/(m·K)
     */
    public double conductivity(ThermalState state) {
        return blend(state, PhaseRegion::conductivity);
    }

    /**
     * Returns the density in a state, combining phases during a transition by volume.
     *
     * @param state the thermal state
     * @return the density in kg/m³
     */
    public double density(ThermalState state) {
        PhaseRegion region = thermal.regions().get(state.region());
        double rho = region.density().at(state.temperatureK());
        if (!state.inTransition()) {
            return rho;
        }
        PhaseRegion next = thermal.regions().get(state.region() + 1);
        double rhoNext = next.density().at(state.temperatureK());
        double f = state.transitionFraction();
        return 1.0 / ((1.0 - f) / rho + f / rhoNext);
    }

    /**
     * Returns the dynamic viscosity of a liquid.
     *
     * @param state the thermal state
     * @return the viscosity in Pa·s, or {@link Double#NaN} if the state is not wholly liquid or the liquid has
     *     no viscosity data
     */
    public double viscosity(ThermalState state) {
        PhaseRegion region = thermal.regions().get(state.region());
        if (state.inTransition() || region.viscosity() == null) {
            return Double.NaN;
        }
        return region.viscosity().at(state.temperatureK());
    }

    /**
     * Returns the surface emissivity in a state.
     *
     * @param state the thermal state
     * @return the emissivity, dimensionless
     */
    public double emissivity(ThermalState state) {
        return blend(state, PhaseRegion::emissivity);
    }

    /**
     * Returns the specific heat of the phase present, ignoring latent heat.
     *
     * @param state the thermal state
     * @return the specific heat in J/(kg·K)
     */
    public double specificHeat(ThermalState state) {
        return blend(state, PhaseRegion::specificHeat);
    }

    /**
     * Returns the lowest specific heat of any phase within the described temperatures, for quick estimates
     * that should err towards larger temperature changes.
     *
     * @return the specific heat in J/(kg·K)
     */
    public double minSpecificHeat() {
        return minSpecificHeat;
    }

    /**
     * Returns the phase the material is always in, if it has only one, so that callers can skip working out
     * the state of each cell.
     *
     * @return the phase of every region, or {@code null} if the material can change phase
     */
    public Phase onlyPhase() {
        return onlyPhase;
    }

    /**
     * Returns the mass fraction of matter in each state of aggregation.
     *
     * @param state the thermal state
     * @return the fractions of solid, liquid and gas, indexed by {@link Phase#ordinal()}
     */
    public double[] phaseFractions(ThermalState state) {
        double[] fractions = new double[Phase.values().length];
        PhaseRegion region = thermal.regions().get(state.region());
        fractions[region.phase().ordinal()] += 1.0 - state.transitionFraction();
        if (state.inTransition()) {
            PhaseRegion next = thermal.regions().get(state.region() + 1);
            fractions[next.phase().ordinal()] += state.transitionFraction();
        }
        return fractions;
    }

    /**
     * Returns the phase that holds most of the mass in a state. Exactly halfway through a transition the
     * phase being left still counts.
     *
     * @param state the thermal state
     * @return the dominant phase
     */
    public Phase dominantPhase(ThermalState state) {
        List<PhaseRegion> regions = thermal.regions();
        if (state.inTransition() && state.transitionFraction() > 0.5) {
            return regions.get(state.region() + 1).phase();
        }
        return regions.get(state.region()).phase();
    }

    /**
     * Tells whether matter in a state may be shown as a phase: it is entirely in that phase, or somewhere in
     * a transition into or out of it. A block of melting ice may be shown as ice or as water.
     *
     * @param state the thermal state
     * @param phase the phase
     * @return {@code true} if the state is compatible with the phase
     */
    public boolean canAppearAs(ThermalState state, Phase phase) {
        List<PhaseRegion> regions = thermal.regions();
        if (regions.get(state.region()).phase() == phase) {
            return true;
        }
        return state.inTransition() && regions.get(state.region() + 1).phase() == phase;
    }

    /**
     * Returns the specific enthalpy closest to a given one at which the matter is entirely in a phase. Below
     * the coldest region and above the hottest the phase is taken to continue.
     *
     * @param phase the phase
     * @param specificEnthalpy the specific enthalpy in J/kg
     * @return the given value if it already lies in the phase, the nearest value that does, or
     *     {@link Double#NaN} if the material has no region of that phase
     */
    public double nearestSpecificEnthalpyIn(Phase phase, double specificEnthalpy) {
        List<PhaseRegion> regions = thermal.regions();
        int last = regions.size() - 1;
        double best = Double.NaN;
        double bestDistance = Double.POSITIVE_INFINITY;
        int i = 0;
        while (i <= last) {
            if (regions.get(i).phase() != phase) {
                i++;
                continue;
            }
            int j = i;
            while (j < last && regions.get(j + 1).phase() == phase) {
                j++;
            }
            double lo = i == 0 ? Double.NEGATIVE_INFINITY : thermal.regionStartEnthalpy(i);
            double hi = j == last ? Double.POSITIVE_INFINITY : thermal.regionEndEnthalpy(j);
            if (specificEnthalpy >= lo && specificEnthalpy <= hi) {
                return specificEnthalpy;
            }
            double candidate = specificEnthalpy < lo ? lo : hi;
            double distance = Math.abs(candidate - specificEnthalpy);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
            i = j + 1;
        }
        return best;
    }

    /**
     * Returns the reference density, at 25 °C in the phase stable there.
     *
     * @return the density in kg/m³
     */
    public double referenceDensity() {
        double t = Math.max(PhysicalConstants.STANDARD_TEMPERATURE, thermal.minTemperatureK());
        return density(stateFor(specificEnthalpy(t)));
    }

    /**
     * Returns the temperature of the first transition from solid to liquid.
     *
     * @return the melting temperature in kelvin, or {@link Double#NaN} if the material does not melt within
     *     its described range
     */
    public double meltingPointK() {
        List<PhaseRegion> regions = thermal.regions();
        for (int i = 0; i < regions.size() - 1; i++) {
            if (regions.get(i).phase() == Phase.SOLID && regions.get(i + 1).phase() == Phase.LIQUID) {
                return thermal.transitions().get(i).temperatureK();
            }
        }
        return Double.NaN;
    }

    /**
     * Tells whether the material description covers a temperature with measured data.
     *
     * @param temperatureK the temperature in kelvin
     * @return {@code false} outside the described range, where results are extrapolated
     */
    public boolean isDescribedAt(double temperatureK) {
        return temperatureK >= thermal.minTemperatureK() && temperatureK <= thermal.maxTemperatureK();
    }

    /**
     * Returns every source this material's data refers to, keyed by source key.
     *
     * @return the sources, in order of first use
     */
    public Map<String, Source> sources() {
        Map<String, Source> sources = new LinkedHashMap<>();
        for (PhaseRegion r : thermal.regions()) {
            for (PropertyCurve c : List.of(r.specificHeat(), r.conductivity(), r.density(), r.emissivity())) {
                sources.putIfAbsent(c.source().key(), c.source());
            }
            if (r.viscosity() != null) {
                sources.putIfAbsent(r.viscosity().source().key(), r.viscosity().source());
            }
        }
        for (PhaseTransition t : thermal.transitions()) {
            sources.putIfAbsent(t.source().key(), t.source());
        }
        return sources;
    }

    private double blend(ThermalState state, Function<PhaseRegion, PropertyCurve> property) {
        PhaseRegion region = thermal.regions().get(state.region());
        double value = property.apply(region).at(state.temperatureK());
        if (!state.inTransition()) {
            return value;
        }
        PhaseRegion next = thermal.regions().get(state.region() + 1);
        double nextValue = property.apply(next).at(state.temperatureK());
        double f = state.transitionFraction();
        return (1.0 - f) * value + f * nextValue;
    }

    @Override
    public String toString() {
        return "Material[" + id + "]";
    }

    /** Builds a {@link Material}. */
    public static final class Builder {
        private final String id;
        private final String name;
        private Composition composition;
        private final List<PhaseRegion> regions = new ArrayList<>();
        private final List<PhaseTransition> transitions = new ArrayList<>();
        private String notes = "";

        private Builder(String id, String name) {
            this.id = Objects.requireNonNull(id, "id");
            this.name = Objects.requireNonNull(name, "name");
            if (!ID.matcher(id).matches()) {
                throw new IllegalArgumentException("material id must look like namespace:path, got " + id);
            }
        }

        /**
         * Sets the composition.
         *
         * @param composition the composition
         * @return this builder
         */
        public Builder composition(Composition composition) {
            this.composition = composition;
            return this;
        }

        /**
         * Appends a phase region; regions are added in order of increasing temperature.
         *
         * @param region the region
         * @return this builder
         */
        public Builder region(PhaseRegion region) {
            if (regions.size() != transitions.size()) {
                throw new IllegalStateException("add a transition between two regions");
            }
            regions.add(region);
            return this;
        }

        /**
         * Appends a transition after the last region added.
         *
         * @param transition the transition
         * @return this builder
         */
        public Builder transition(PhaseTransition transition) {
            if (regions.size() != transitions.size() + 1) {
                throw new IllegalStateException("a transition must follow a region");
            }
            transitions.add(transition);
            return this;
        }

        /**
         * Sets notes on assumptions and limits.
         *
         * @param notes the notes
         * @return this builder
         */
        public Builder notes(String notes) {
            this.notes = Objects.requireNonNull(notes, "notes");
            return this;
        }

        /**
         * Builds the material.
         *
         * @return the material
         */
        public Material build() {
            return new Material(this);
        }
    }
}
