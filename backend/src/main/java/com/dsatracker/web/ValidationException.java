package com.dsatracker.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raised when a {@code POST /api/users} request fails validation
 * (Requirements 1.2, 1.3): missing required fields, a duplicate email, or a
 * platform username that could not be verified.
 *
 * <p>Carries a {@code field -> message} map rather than a single message so the
 * client can render errors next to the specific input that failed (e.g. show a
 * per-platform error under the LeetCode field while other fields stay valid).
 * The map preserves insertion order so errors surface in a stable, predictable
 * order. This is translated to an HTTP 422 response with a
 * {@link ErrorResponse} body by the controller's exception handler.
 */
public class ValidationException extends RuntimeException {

    private final Map<String, String> errors;

    public ValidationException(Map<String, String> errors) {
        super("Validation failed: " + errors);
        // Defensive copy; keep field order for a stable client-facing response.
        this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }

    /** @return an immutable, insertion-ordered map of {@code field -> message}. */
    public Map<String, String> getErrors() {
        return errors;
    }
}
