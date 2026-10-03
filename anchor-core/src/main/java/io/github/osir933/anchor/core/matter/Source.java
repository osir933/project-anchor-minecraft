package io.github.osir933.anchor.core.matter;

import java.util.Objects;

/**
 * Where a piece of physical data comes from, so that every value Anchor uses can be checked.
 *
 * @param key a short identifier used to refer to the source, such as {@code nist-janaf}
 * @param citation the full citation
 * @param quality how carefully the values were checked against this source
 */
public record Source(String key, String citation, DataQuality quality) {

    /**
     * Validates the source.
     *
     * @param key the identifier
     * @param citation the citation
     * @param quality the data quality
     */
    public Source {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(citation, "citation");
        Objects.requireNonNull(quality, "quality");
        if (key.isBlank() || citation.isBlank()) {
            throw new IllegalArgumentException("source key and citation must not be blank");
        }
    }

    /** How carefully a value has been checked. */
    public enum DataQuality {
        /** Checked line by line against the primary source, with the source's uncertainty recorded. */
        VERIFIED,
        /** Typical value from standard handbooks; not yet checked line by line against a primary source. */
        HANDBOOK_TYPICAL,
        /** An engineering estimate where no good measurement exists; shown to players as an estimate. */
        ESTIMATED
    }
}
