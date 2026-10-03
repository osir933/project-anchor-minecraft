package io.github.osir933.anchor.core.model;

import io.github.osir933.anchor.core.space.CellId;
import java.util.Objects;

/**
 * A place where a model is being used outside the conditions it was built for, such as conduction across
 * a gap smaller than its assumptions allow or a temperature beyond a material's data.
 *
 * @param modelId the model that raised it
 * @param cell where, or {@code null} for the world as a whole
 * @param message what assumption fails and what that means for the result
 */
public record ValidityIssue(String modelId, CellId cell, String message) {

    /**
     * Validates the issue.
     *
     * @param modelId the model id
     * @param cell the cell, may be null
     * @param message the message
     */
    public ValidityIssue {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(message, "message");
    }
}
