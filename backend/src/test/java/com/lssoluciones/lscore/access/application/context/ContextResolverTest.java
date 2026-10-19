package com.lssoluciones.lscore.access.application.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lssoluciones.lscore.access.application.context.TrustedContextSource.ApiKey;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.AuthenticatedIdentity;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Branch;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.BranchMembership;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Entitlement;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Membership;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Origin;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Session;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.SessionContext;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Validity;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ContextResolverTest {
    @Test
    void validSessionHasImmutableExactClaimsAndSingleUse() {
        var f = new ContextFixture();
        var context = f.authorized();
        assertEquals(f.scope, context.claims().scope());
        assertEquals(f.user, context.claims().actorId());
        assertEquals(Origin.SESSION, context.claims().origin());
        context.begin();
        context.revalidate();
        assertThrows(ContextRejectedException.class, context::begin);
        assertEquals(0, context.getClass().getConstructors().length);
    }

    @Test
    void rejectsMissingIdentityContextForeignSelectorAndForgedProof() {
        var f = new ContextFixture();
        assertThrows(ContextRejectedException.class, () -> f.resolver().session(null, f.selected, f.scope));
        assertThrows(ContextRejectedException.class, () -> f.resolver().session(f.identity, null, f.scope));
        assertThrows(ContextRejectedException.class, () -> f.resolver().session(f.identity, f.selected, null));
        assertThrows(ContextRejectedException.class,
                () -> f.resolver().session(f.identity, UUID.randomUUID(), f.scope));
        assertThrows(ContextRejectedException.class, () -> f.resolver().session(f.identity, f.selected,
                new Scope(UUID.randomUUID(), f.app, f.branchId)));
        AuthenticatedIdentity forged = new AuthenticatedIdentity() { };
        assertThrows(ContextRejectedException.class, () -> f.resolver().apiKey(forged, f.scope));
    }

    static Stream<Consumer<ContextFixture>> invalidSession() {
        return Stream.of(
                f -> f.membership = new Membership(f.member, UUID.randomUUID(), f.user),
                f -> f.membership = new Membership(f.member, f.tenant, UUID.randomUUID()),
                f -> f.branch = new Branch(f.branchId, UUID.randomUUID()),
                f -> f.access = null,
                f -> f.access = new BranchMembership(UUID.randomUUID(), f.tenant, UUID.randomUUID(), f.branchId),
                f -> f.access = new BranchMembership(UUID.randomUUID(), f.tenant, f.member, UUID.randomUUID()),
                f -> f.entitlement = new Entitlement(f.tenant, UUID.randomUUID()),
                f -> f.session = new Session(f.credential, f.user, UUID.randomUUID(), ContextFixture.NOW.plusSeconds(1), null),
                f -> f.session = new Session(f.credential, f.user, f.app, ContextFixture.NOW, null),
                f -> f.session = new Session(f.credential, f.user, f.app, ContextFixture.NOW.plusSeconds(1), ContextFixture.NOW),
                f -> f.context = new SessionContext(f.selected, UUID.randomUUID(), f.user, f.scope, f.member, null, null),
                f -> f.context = new SessionContext(f.selected, f.credential, UUID.randomUUID(), f.scope, f.member, null, null),
                f -> f.context = new SessionContext(f.selected, f.credential, f.user, f.scope, f.member, ContextFixture.NOW, null),
                f -> f.context = new SessionContext(f.selected, f.credential, f.user, f.scope, f.member, null, ContextFixture.NOW),
                f -> f.validity = Validity.UNKNOWN,
                f -> f.validity = Validity.DENIED,
                f -> f.unavailable = true);
    }

    @ParameterizedTest
    @MethodSource("invalidSession")
    void rejectsInconsistentOrUnverifiedEvidence(Consumer<ContextFixture> mutation) {
        var f = new ContextFixture();
        mutation.accept(f);
        var error = assertThrows(ContextRejectedException.class, f::authorized);
        assertNull(error.getCause());
        assertTrue(error.getMessage().contains("rejected"));
    }

    @Test
    void rechecksRevocationAndAvailabilityAtUse() {
        var f = new ContextFixture();
        var context = f.authorized();
        f.unavailable = true;
        assertThrows(ContextRejectedException.class, context::begin);
        f.unavailable = false;
        context = f.authorized();
        context.begin();
        f.validity = Validity.UNKNOWN;
        assertThrows(ContextRejectedException.class, context::revalidate);
    }

    @Test
    void apiKeyWithNoBranchDoesNotAuthorizeASelectedBranch() {
        var f = new ContextFixture();
        f.scope = new Scope(f.tenant, f.app, null);
        f.branch = null;
        f.key = new ApiKey(f.credential, f.scope, ContextFixture.NOW.plusSeconds(60), null);
        var context = f.resolver().apiKey(f.identity, f.scope);
        assertEquals(Origin.API_KEY, context.claims().origin());
        assertNull(context.claims().membershipId());
        assertNull(context.claims().scope().branchId());
        assertThrows(ContextRejectedException.class, () -> f.resolver().apiKey(f.identity,
                new Scope(f.tenant, f.app, f.branchId)));
    }

    static Stream<Consumer<ContextFixture>> invalidKey() {
        return Stream.of(
                f -> f.key = new ApiKey(f.credential, f.scope, ContextFixture.NOW, null),
                f -> f.key = new ApiKey(f.credential, f.scope, ContextFixture.NOW.plusSeconds(60), ContextFixture.NOW),
                f -> f.key = new ApiKey(f.credential, new Scope(UUID.randomUUID(), f.app, f.branchId),
                        ContextFixture.NOW.plusSeconds(60), null),
                f -> f.branch = new Branch(f.branchId, UUID.randomUUID()),
                f -> f.entitlement = new Entitlement(f.tenant, UUID.randomUUID()),
                f -> f.validity = Validity.UNKNOWN,
                f -> f.unavailable = true);
    }

    @ParameterizedTest
    @MethodSource("invalidKey")
    void rejectsInvalidApiKeys(Consumer<ContextFixture> mutation) {
        var f = new ContextFixture();
        mutation.accept(f);
        assertThrows(ContextRejectedException.class, () -> f.resolver().apiKey(f.identity, f.scope));
    }

    @Test
    void userIdentityCannotBecomeAnApiKeyThroughTheResolver() {
        var f = new ContextFixture();
        TrustedContextSource sessionOnly = new TrustedContextSource() {
            public SessionEvidence session(AuthenticatedIdentity identity, UUID contextId) {
                return f.session(identity, contextId);
            }

            public ApiKeyEvidence apiKey(AuthenticatedIdentity identity) {
                throw new IllegalStateException("wrong credential kind");
            }
        };
        var resolver = new ContextResolver(sessionOnly, java.time.Clock.systemUTC());
        assertThrows(ContextRejectedException.class, () -> resolver.apiKey(f.identity, f.scope));
    }
}
