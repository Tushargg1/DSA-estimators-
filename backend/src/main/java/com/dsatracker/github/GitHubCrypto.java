package com.dsatracker.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class GitHubCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();

    private GitHubCrypto() { }

    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public static boolean constantTimeHexEquals(String expected, String supplied) {
        if (supplied == null) return false;
        String comparableExpected = expected == null ? "0".repeat(64) : expected;
        byte[] left = comparableExpected.getBytes(StandardCharsets.US_ASCII);
        byte[] right = sha256Hex(supplied).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(left, right) && expected != null;
    }
}
