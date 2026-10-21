package com.lssoluciones.lscore.identity.application;

import java.nio.CharBuffer;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm;

/**
 * Real password verification only: no session, token, AuthenticatedIdentity or enterprise authorization.
 * Username normalization belongs to PostgreSQL; passwords are compared literally.
 * Only ACTIVE identities with usable, unexpired credentials can match. Unknown states fail closed.
 * Required password changes are denied until the separate change-password flow exists.
 * Checks the source snapshot and decision-time expiry, without promising concurrent-state revocation.
 */
public final class PasswordCredentialVerifier {
    /** Versioned storage format: this prefix followed by Spring's hex salt(16 bytes) + key(32 bytes). */
    public static final String HASH_PREFIX = "{pbkdf2-sha256-600000}";
    private static final Pattern HASH = Pattern.compile("\\{pbkdf2-sha256-600000\\}[0-9a-f]{96}");
    private final PasswordCredentialSource source;
    private final Clock clock;
    private final Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder(
            "", 16, 600000, SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
    private final String dummyHash = encoder.encode(UUID.randomUUID().toString());

    public PasswordCredentialVerifier(PasswordCredentialSource source, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Returns the matched user's identifier as data, never as an authorization credential.
     * Performs no writes or failed-attempt counter updates. Caller retains ownership of password.
     */
    public UUID verify(String username, char[] password) {
        if (username == null || username.isBlank() || username.length() > 1024
                || password == null || password.length == 0 || password.length > 1024) {
            throw new CredentialRejectedException();
        }
        char[] copy = password.clone();
        try {
            PasswordCredentialSource.Credential credential;
            try {
                credential = source.findByUsername(username).orElse(null);
            } catch (RuntimeException unavailable) {
                credential = null;
            }
            boolean supported = credential != null && credential.passwordHash() != null
                    && HASH.matcher(credential.passwordHash()).matches();
            String hash = supported ? credential.passwordHash().substring(HASH_PREFIX.length()) : dummyHash;
            // Missing/malformed credentials and unavailable sources also pay the configured KDF cost.
            boolean matched = encoder.matches(CharBuffer.wrap(copy), hash);
            var now = clock.instant();
            if (!supported || !matched || credential.userId() == null || !"ACTIVE".equals(credential.status())
                    || !Boolean.FALSE.equals(credential.mustChangePassword())
                    || credential.passwordExpiresAt() == null || !credential.passwordExpiresAt().isAfter(now)
                    || credential.lockedUntil() != null && credential.lockedUntil().isAfter(now)) {
                throw new CredentialRejectedException();
            }
            return credential.userId();
        } catch (RuntimeException failure) {
            // One public failure, no source diagnostics, identifiers, hashes or exception causes.
            throw new CredentialRejectedException();
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    public static final class CredentialRejectedException extends RuntimeException {
        private CredentialRejectedException() {
            super("Invalid credentials");
        }
    }
}
