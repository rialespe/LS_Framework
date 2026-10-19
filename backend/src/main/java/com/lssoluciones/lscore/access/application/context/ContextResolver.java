package com.lssoluciones.lscore.access.application.context;

import com.lssoluciones.lscore.access.application.context.TrustedContextSource.ApiKeyEvidence;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.AuthenticatedIdentity;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Origin;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.SessionEvidence;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Validity;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Inactive integration building block: only trusted composition may supply the source. */
public final class ContextResolver {
    private final TrustedContextSource source;
    private final Clock clock;

    public ContextResolver(TrustedContextSource source, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Client selectors constrain the trusted result; they never establish authorization. */
    public AuthorizedContext session(AuthenticatedIdentity identity, UUID contextId, Scope expected) {
        require(identity != null && contextId != null && expected != null);
        return resolve(() -> validateSession(source.session(identity, contextId), contextId, expected));
    }

    public AuthorizedContext apiKey(AuthenticatedIdentity identity, Scope expected) {
        require(identity != null && expected != null);
        return resolve(() -> validateKey(source.apiKey(identity), expected));
    }

    private AuthorizedContext resolve(Supplier<Claims> validation) {
        try {
            return new AuthorizedContext(validation.get(), validation);
        } catch (RuntimeException failure) {
            throw new ContextRejectedException();
        }
    }

    private Claims validateSession(SessionEvidence evidence, UUID selected, Scope expected) {
        require(evidence != null && evidence.validity() == Validity.VERIFIED);
        var session = Objects.requireNonNull(evidence.session());
        var context = Objects.requireNonNull(evidence.context());
        var membership = Objects.requireNonNull(evidence.membership());
        var branch = Objects.requireNonNull(evidence.branch());
        var access = Objects.requireNonNull(evidence.access());
        var entitlement = Objects.requireNonNull(evidence.entitlement());
        Scope scope = context.scope();
        validScope(scope);
        require(scope.equals(expected) && scope.branchId() != null);
        equal(selected, context.id());
        equal(session.id(), context.sessionId());
        equal(session.userId(), context.userId());
        equal(session.applicationId(), scope.applicationId());
        equal(context.membershipId(), membership.id());
        equal(context.userId(), membership.userId());
        equal(scope.tenantId(), membership.tenantId());
        equal(scope.branchId(), branch.id());
        equal(scope.tenantId(), branch.tenantId());
        equal(scope.tenantId(), access.tenantId());
        equal(membership.id(), access.membershipId());
        equal(branch.id(), access.branchId());
        require(access.id() != null);
        equal(scope.tenantId(), entitlement.tenantId());
        equal(scope.applicationId(), entitlement.applicationId());
        active(session.expiresAt(), session.revokedAt());
        require(context.revokedAt() == null);
        if (context.expiresAt() != null) {
            active(context.expiresAt(), context.revokedAt());
        }
        return new Claims(scope, Origin.SESSION, session.userId(), session.id(), context.id(),
                membership.id(), access.id());
    }

    private Claims validateKey(ApiKeyEvidence evidence, Scope expected) {
        require(evidence != null && evidence.validity() == Validity.VERIFIED);
        var key = Objects.requireNonNull(evidence.key());
        var entitlement = Objects.requireNonNull(evidence.entitlement());
        validScope(key.scope());
        require(key.scope().equals(expected) && key.id() != null);
        equal(key.scope().tenantId(), entitlement.tenantId());
        equal(key.scope().applicationId(), entitlement.applicationId());
        if (key.scope().branchId() != null) {
            var branch = Objects.requireNonNull(evidence.branch());
            equal(key.scope().branchId(), branch.id());
            equal(key.scope().tenantId(), branch.tenantId());
        } else {
            require(evidence.branch() == null);
        }
        active(key.expiresAt(), key.revokedAt());
        return new Claims(key.scope(), Origin.API_KEY, key.id(), key.id(), null, null, null);
    }

    private void active(Instant expiresAt, Instant revokedAt) {
        require(expiresAt != null && expiresAt.isAfter(clock.instant()) && revokedAt == null);
    }

    private static void validScope(Scope scope) {
        require(scope != null && scope.tenantId() != null && scope.applicationId() != null);
    }

    private static void equal(UUID left, UUID right) {
        require(left != null && left.equals(right));
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new ContextRejectedException();
        }
    }

    public record Claims(Scope scope, Origin origin, UUID actorId, UUID credentialId,
            UUID sessionContextId, UUID membershipId, UUID branchMembershipId) { }

    /** No public constructor. Immutable claims, single execution, fresh validation on use. */
    public static final class AuthorizedContext {
        private final Claims claims;
        private final Supplier<Claims> validation;
        private final AtomicBoolean used = new AtomicBoolean();

        private AuthorizedContext(Claims claims, Supplier<Claims> validation) {
            this.claims = claims;
            this.validation = validation;
        }

        public Claims claims() {
            return claims;
        }

        /** Executor-only lifecycle operation; consuming a context cannot create another one. */
        public void begin() {
            require(used.compareAndSet(false, true));
            revalidate();
        }

        public void revalidate() {
            try {
                require(used.get() && claims.equals(validation.get()));
            } catch (RuntimeException failure) {
                throw new ContextRejectedException();
            }
        }
    }
}
