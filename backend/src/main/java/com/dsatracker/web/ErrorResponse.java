package com.dsatracker.web;

import java.util.Map;

/**
 * Standard error body for validation failures on the user API.
 *
 * <p>Wraps the {@code field -> message} map from {@link ValidationException} so
 * the JSON shape is stable and self-describing, e.g.:
 * <pre>{@code
 * { "errors": { "leetcodeUsername": "LeetCode username could not be verified." } }
 * }</pre>
 *
 * @param errors map of input field name to a human-readable error message
 */
public record ErrorResponse(Map<String, String> errors) {
}
