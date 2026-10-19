package com.lssoluciones.lscore.access.application.context;

import com.lssoluciones.lscore.access.application.context.TrustedContextSource.ApiKey;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.ApiKeyEvidence;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.AuthenticatedIdentity;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Branch;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.BranchMembership;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Entitlement;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Membership;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Session;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.SessionContext;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.SessionEvidence;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Validity;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Synthetic evidence only: neither an authenticator nor a database reader. */
public final class ContextFixture implements TrustedContextSource {
    public static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    public final AuthenticatedIdentity identity = new AuthenticatedIdentity() { };
    public final UUID tenant = UUID.randomUUID();
    public final UUID app = UUID.randomUUID();
    public final UUID user = UUID.randomUUID();
    public final UUID credential = UUID.randomUUID();
    public final UUID selected = UUID.randomUUID();
    public final UUID member = UUID.randomUUID();
    public final UUID branchId = UUID.randomUUID();
    public Scope scope = new Scope(tenant, app, branchId);
    public Session session = new Session(credential, user, app, NOW.plusSeconds(60), null);
    public SessionContext context = new SessionContext(selected, credential, user, scope, member, null, null);
    public Membership membership = new Membership(member, tenant, user);
    public Branch branch = new Branch(branchId, tenant);
    public BranchMembership access = new BranchMembership(UUID.randomUUID(), tenant, member, branchId);
    public Entitlement entitlement = new Entitlement(tenant, app);
    public Validity validity = Validity.VERIFIED;
    public ApiKey key = new ApiKey(credential, scope, NOW.plusSeconds(60), null);
    public boolean unavailable;

    public ContextResolver resolver() {
        return new ContextResolver(this, Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    public ContextResolver.AuthorizedContext authorized() {
        return resolver().session(identity, selected, scope);
    }

    @Override
    public SessionEvidence session(AuthenticatedIdentity proof, UUID id) {
        if (unavailable || proof != identity) {
            throw new IllegalStateException("synthetic private diagnostic");
        }
        return new SessionEvidence(session, context, membership, branch, access, entitlement, validity);
    }

    @Override
    public ApiKeyEvidence apiKey(AuthenticatedIdentity proof) {
        if (unavailable || proof != identity) {
            throw new IllegalStateException("synthetic private diagnostic");
        }
        return new ApiKeyEvidence(key, branch, entitlement, validity);
    }
}
