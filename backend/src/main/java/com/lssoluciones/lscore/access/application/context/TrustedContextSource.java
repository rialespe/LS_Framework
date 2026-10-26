package com.lssoluciones.lscore.access.application.context;

import java.time.Instant;
import java.util.UUID;

/** Trusted server-side integration port. No production adapter exists in LFD-28. */
public interface TrustedContextSource {
    /** Opaque evidence owned by a future authenticator; never implement from HTTP identifiers. */
    interface AuthenticatedIdentity { }

    enum Origin { SESSION, API_KEY, PASSWORD_OPERATION }

    /** An explicit policy decision, not a database status vocabulary. */
    enum Validity { VERIFIED, DENIED, UNKNOWN }

    record Scope(UUID tenantId, UUID applicationId, UUID branchId) { }

    record Session(UUID id, UUID userId, UUID applicationId, Instant expiresAt, Instant revokedAt) { }

    record SessionContext(UUID id, UUID sessionId, UUID userId, Scope scope,
            UUID membershipId, Instant expiresAt, Instant revokedAt) { }

    record Membership(UUID id, UUID tenantId, UUID userId) { }

    record Branch(UUID id, UUID tenantId) { }

    record BranchMembership(UUID id, UUID tenantId, UUID membershipId, UUID branchId) { }

    record Entitlement(UUID tenantId, UUID applicationId) { }

    /** Returned only after authenticating identity and reading authoritative, coherent evidence. */
    record SessionEvidence(Session session, SessionContext context, Membership membership,
            Branch branch, BranchMembership access, Entitlement entitlement, Validity validity) { }

    record ApiKey(UUID id, Scope scope, Instant expiresAt, Instant revokedAt) { }

    record ApiKeyEvidence(ApiKey key, Branch branch, Entitlement entitlement, Validity validity) { }

    /** Must bind identity to this session/user, verify freshness, and fail on unavailable data. */
    SessionEvidence session(AuthenticatedIdentity identity, UUID contextId);

    /** Must authenticate an API Key identity; a user session cannot satisfy this method. */
    ApiKeyEvidence apiKey(AuthenticatedIdentity identity);
}
