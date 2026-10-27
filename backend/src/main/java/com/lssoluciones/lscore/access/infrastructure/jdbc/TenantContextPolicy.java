package com.lssoluciones.lscore.access.infrastructure.jdbc;

/** Shared positive state predicate only; cannot authenticate a subject or issue authorization evidence. */
public final class TenantContextPolicy {
    private TenantContextPolicy() { }

    public static boolean eligible(String user, String tenant, String membership, String branch,
            String access, String application, String entitlement) {
        // Approved F03.T02 policy. accepted_at is not an additional condition.
        return "ACTIVE".equals(user) && "ACTIVE".equals(tenant) && "ACTIVE".equals(membership)
                && "ACTIVE".equals(branch) && "ACTIVE".equals(access) && "PUBLISHED".equals(application)
                && "ACTIVE".equals(entitlement);
    }
}
