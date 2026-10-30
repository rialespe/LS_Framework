package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import com.lssoluciones.lscore.identity.application.PasswordCredentialVerifier;

import java.nio.CharBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

/** F04 material prepared in memory; persistence and delivery belong to the owning transaction. */
public final class TemporaryCredential implements AutoCloseable {
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final char[] secret = new char[32];
    private final String hash;
    private boolean consumed;

    public TemporaryCredential() {
        for (int i = 0; i < secret.length; i++) {
            secret[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        hash =
                PasswordCredentialVerifier.HASH_PREFIX
                        + PasswordCredentialVerifier.newEncoder().encode(CharBuffer.wrap(secret));
    }

    public String hash() {
        return hash;
    }

    public synchronized char[] take() {
        if (consumed) {
            throw new IllegalStateException("Credential unavailable");
        }
        char[] result = secret.clone();
        close();
        return result;
    }

    @Override
    public synchronized void close() {
        Arrays.fill(secret, '\0');
        consumed = true;
    }

    @Override
    public String toString() {
        return "TemporaryCredential[REDACTED]";
    }
}
