package com.dsatracker.model;

/**
 * The external platforms a submission can originate from.
 *
 * <p>Persisted as its {@code name()} string (e.g. {@code "LEETCODE"}) via
 * {@code @Enumerated(EnumType.STRING)} so the stored value matches the
 * {@code VARCHAR(20)} column and the {@code 'LEETCODE' | 'CODEFORCES' | 'GFG'}
 * contract documented in design.md.
 */
public enum Platform {
    LEETCODE,
    CODEFORCES,
    GFG
}
