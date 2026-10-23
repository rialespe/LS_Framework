package com.lssoluciones.lscore.identity.application;

import java.util.UUID;

/** Mandatory boundary for future F05 password attempts; result is data, never business authorization. */
public interface PasswordAuthentication {
    UUID verify(String username, char[] password);

    final class Rejected extends RuntimeException {
        public Rejected() {
            super("Invalid credentials");
        }
    }
}
