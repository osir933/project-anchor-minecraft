package io.github.osir933.anchor.core.model;

/** The branches of physics a model can belong to; requests name them to say what should be simulated. */
public enum Domain {
    /** Heat: conduction, radiation, phase change. */
    THERMAL,
    /** Forces, stress, deformation, fracture and motion of solids. */
    MECHANICAL,
    /** Flow of liquids and gases. */
    FLUID,
    /** Reactions and diffusion of species. */
    CHEMICAL,
    /** Charge, current, fields and light. */
    ELECTROMAGNETIC,
    /** Living things. */
    BIOLOGICAL,
    /** People, institutions and economies. */
    SOCIETAL
}
