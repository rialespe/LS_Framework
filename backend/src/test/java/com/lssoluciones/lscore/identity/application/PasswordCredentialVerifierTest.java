package com.lssoluciones.lscore.identity.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.lssoluciones.lscore.identity.application.PasswordCredentialSource.Credential;
import com.lssoluciones.lscore.identity.application.PasswordCredentialVerifier.CredentialRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;

class PasswordCredentialVerifierTest {
    private static final Instant NOW = Instant.parse("2026-10-21T18:00:00Z");
    private static final UUID USER = UUID.randomUUID();
    private static final String PASSWORD = "  a literal contraseña with whitespace  ";
    // Independent JDK derivation verifies interoperability with the production Spring encoder.
    private static final String HASH = storedHash();

    @Test
    void verifiesRealHashWithoutNormalizingPasswordOrReturningAnAuthorization() {
        var queried = new AtomicReference<String>();
        var verifier = verifier(username -> {
            queried.set(username);
            return Optional.of(valid());
        });
        char[] supplied = PASSWORD.toCharArray();
        assertEquals(USER, verifier.verify("  MiXeD User  ", supplied));
        assertEquals("  MiXeD User  ", queried.get());
        assertArrayEquals(PASSWORD.toCharArray(), supplied);
        rejected(() -> verifier.verify("user", PASSWORD.trim().toCharArray()));
        rejected(() -> verifier.verify("user", "wrong".toCharArray()));
        assertFalse(valid().toString().contains(HASH));
        assertFalse(valid().toString().contains(USER.toString()));
    }

    @Test
    void rejectsStateLockExpiryAndRequiredChangeWithTheSamePublicFailure() {
        List<Credential> invalid = List.of(
                new Credential(USER, "SUSPENDED", HASH, NOW.plusSeconds(60), null, false),
                new Credential(USER, "unknown", HASH, NOW.plusSeconds(60), null, false),
                new Credential(USER, null, HASH, NOW.plusSeconds(60), null, false),
                new Credential(null, "ACTIVE", HASH, NOW.plusSeconds(60), null, false),
                new Credential(USER, "ACTIVE", HASH, NOW, null, false),
                new Credential(USER, "ACTIVE", HASH, null, null, false),
                new Credential(USER, "ACTIVE", HASH, NOW.plusSeconds(60), NOW.plusSeconds(1), false),
                new Credential(USER, "ACTIVE", HASH, NOW.plusSeconds(60), null, true),
                new Credential(USER, "ACTIVE", HASH, NOW.plusSeconds(60), null, null));
        var current = new AtomicReference<>(valid());
        var verifier = verifier(username -> Optional.of(current.get()));
        for (Credential credential : invalid) {
            current.set(credential);
            rejected(() -> verifier.verify("user", PASSWORD.toCharArray()));
        }
        current.set(new Credential(USER, "ACTIVE", HASH, NOW.plusSeconds(60), NOW, false));
        assertEquals(USER, verifier.verify("user", PASSWORD.toCharArray()));
    }

    @Test
    void unknownUserUnavailableSourceAndMalformedHashesNeverMatch() {
        rejected(() -> verifier(username -> Optional.empty()).verify("missing", PASSWORD.toCharArray()));
        rejected(() -> verifier(username -> {
            throw new IllegalStateException("private JDBC password/hash diagnostic");
        }).verify("user", PASSWORD.toCharArray()));
        rejected(() -> verifier(username -> null).verify("user", PASSWORD.toCharArray()));
        var current = new AtomicReference<>(valid());
        var verifier = verifier(username -> Optional.of(current.get()));
        for (String unsupported : List.of(PASSWORD, "{noop}" + PASSWORD, "{pbkdf2}abc",
                PasswordCredentialVerifier.HASH_PREFIX + "z".repeat(96), "")) {
            current.set(new Credential(USER, "ACTIVE", unsupported, NOW.plusSeconds(60), null, false));
            rejected(() -> verifier.verify("user", PASSWORD.toCharArray()));
        }
        current.set(new Credential(USER, "ACTIVE", null, NOW.plusSeconds(60), null, false));
        rejected(() -> verifier.verify("user", PASSWORD.toCharArray()));
    }

    @Test
    void rejectsInvalidInputBeforeReadingTheSourceAndRechecksExpiryAfterHashing() {
        var verifier = verifier(username -> {
            throw new AssertionError("Invalid input must not reach the database");
        });
        rejected(() -> verifier.verify(null, PASSWORD.toCharArray()));
        rejected(() -> verifier.verify(" ", PASSWORD.toCharArray()));
        rejected(() -> verifier.verify("u".repeat(1025), PASSWORD.toCharArray()));
        rejected(() -> verifier.verify("user", null));
        rejected(() -> verifier.verify("user", new char[0]));
        rejected(() -> verifier.verify("user", new char[1025]));
        var expiredAtDecision = new PasswordCredentialVerifier(username -> Optional.of(valid()),
                Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
        rejected(() -> expiredAtDecision.verify("user", PASSWORD.toCharArray()));
    }

    private static PasswordCredentialVerifier verifier(PasswordCredentialSource source) {
        return new PasswordCredentialVerifier(source, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Credential valid() {
        return new Credential(USER, "ACTIVE", HASH, NOW.plusSeconds(60), null, false);
    }

    private static void rejected(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(CredentialRejectedException.class, action);
        assertEquals("Invalid credentials", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    private static String storedHash() {
        byte[] salt = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
        var spec = new PBEKeySpec(PASSWORD.toCharArray(), salt, 600000, 256);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return PasswordCredentialVerifier.HASH_PREFIX + HexFormat.of().formatHex(salt)
                    + HexFormat.of().formatHex(hash);
        } catch (java.security.GeneralSecurityException failure) {
            throw new AssertionError("Test KDF unavailable");
        } finally {
            spec.clearPassword();
        }
    }
}
