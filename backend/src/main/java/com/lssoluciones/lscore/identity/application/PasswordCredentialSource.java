package com.lssoluciones.lscore.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Authoritative credential lookup, exclusively for the password verifier, never enterprise queries. */
@FunctionalInterface
public interface PasswordCredentialSource {
    Optional<Credential> findByUsername(String username);

    /** Sensitive internal data; never serialize, return over HTTP or log accessors. */
    record Credential(UUID userId, String status, String passwordHash, Instant passwordExpiresAt,
            Instant lockedUntil, Boolean mustChangePassword) {
        @Override
        public String toString() {
            return "Credential[redacted]";
        }
    }
}
