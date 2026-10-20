package com.lssoluciones.lscore.identity.application;

/**
 * Immutable, presence-validated input for a future manual global identity creation.
 * Values retain their original spelling; PostgreSQL owns normalized uniqueness.
 * This input is neither a persisted identity nor evidence of email ownership.
 */
public record ManualIdentityInput(String username, String displayName, String email) {
    public ManualIdentityInput {
        requirePresent(username);
        requirePresent(displayName);
        if (email != null) {
            requirePresent(email);
        }
    }

    /** A supplied email never establishes verification; server evidence is required. */
    public boolean emailVerified() {
        return false;
    }

    private static void requirePresent(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Invalid manual identity input");
        }
    }

    @Override
    public String toString() {
        return "ManualIdentityInput[redacted]";
    }
}
