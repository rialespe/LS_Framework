package com.lssoluciones.lscore.access.application.context;

import com.lssoluciones.lscore.access.application.context.ContextResolver.Claims;
import java.time.Instant;

/**
 * Untrusted candidate descriptions, separate from TrustedContextSource input records.
 * Public construction, claims and expiry never establish authenticity or admission.
 * A future trusted issuer must prove scope, freshness and physical-operation binding.
 */
public sealed interface OperationEvidence permits OperationEvidence.Session, OperationEvidence.ApiKey {
    OperationIdentity operation();

    Claims claims();

    Instant expiresAt();

    /** SESSION candidate; it cannot be supplied to the API_KEY admission method. */
    record Session(OperationIdentity operation, Claims claims, Instant expiresAt) implements OperationEvidence { }

    /** API_KEY candidate; it cannot be supplied to the SESSION admission method. */
    record ApiKey(OperationIdentity operation, Claims claims, Instant expiresAt) implements OperationEvidence { }
}
