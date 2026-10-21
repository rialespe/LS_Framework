package com.lssoluciones.lscore.access.application.context;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-local identity of one admission attempt, created by trusted composition only.
 * Not a credential or proof of a physical PostgreSQL binding; never deserialize from HTTP.
 */
public final class OperationIdentity {
    private final AtomicBoolean attempted = new AtomicBoolean();

    private OperationIdentity() { }

    /** Creating another handle conveys no authorization. */
    public static OperationIdentity pending() {
        return new OperationIdentity();
    }

    /** A rejected attempt also consumes the handle; a retry requires a new admission. */
    void beginAdmission() {
        if (!attempted.compareAndSet(false, true)) {
            throw new ContextRejectedException();
        }
    }
}
