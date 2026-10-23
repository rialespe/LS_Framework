package com.lssoluciones.lscore.identity.application;

import java.util.Arrays;

/**
 * Trusted delivery port; recipient comes exclusively from verified database identity data.
 * Implementations must use a confidential delivery channel and never log/serialize the token.
 * No provider is installed by default. Ownership of a taken char[] includes erasing it after delivery.
 */
@FunctionalInterface
public interface TrustedRecoveryMail {
    void send(String verifiedRecipient, Secret token);

    final class Secret implements AutoCloseable {
        private char[] value;

        public Secret(char[] value) {
            this.value = value.clone();
        }

        public synchronized char[] take() {
            if (value == null) {
                throw new IllegalStateException("Recovery secret unavailable");
            }
            char[] result = value.clone();
            close();
            return result;
        }

        @Override
        public synchronized void close() {
            if (value != null) {
                Arrays.fill(value, '\0');
                value = null;
            }
        }

        @Override
        public String toString() {
            return "RecoverySecret[REDACTED]";
        }
    }
}
