package com.dsatracker.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Small test-only helper that loads adapter fixtures from the classpath
 * ({@code src/test/resources/fixtures/}). Keeps the adapter tests network-free:
 * every parse test reads a saved sample response rather than hitting a live API.
 */
final class FixtureLoader {

    private FixtureLoader() {
    }

    /**
     * Reads a fixture file under {@code fixtures/} on the test classpath and
     * returns its contents as a UTF-8 string.
     *
     * @param name file name relative to the {@code fixtures/} directory
     * @return the file contents
     */
    static String load(String name) {
        String path = "fixtures/" + name;
        try (InputStream in = FixtureLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read fixture: " + path, e);
        }
    }
}
