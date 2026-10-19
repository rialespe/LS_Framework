package com.lssoluciones.lscore.access.application.context;

/** Deliberately excludes identifiers, credentials and underlying source diagnostics. */
public final class ContextRejectedException extends RuntimeException {
    public ContextRejectedException() {
        super("Trusted transaction context rejected");
    }
}
