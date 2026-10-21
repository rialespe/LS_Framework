package com.lssoluciones.lscore.access.application.context;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lssoluciones.lscore.access.application.context.ContextResolver.Claims;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Origin;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationAdmissionTest {
    @Test
    void rejectsAbsentIncoherentAndReusedEvidenceWithoutAnIssuer() {
        Instant now = Instant.parse("2026-10-21T15:00:00Z");
        var admission = new OperationAdmission(Clock.fixed(now, ZoneOffset.UTC));
        var scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var session = new Claims(scope, Origin.SESSION, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID keyId = UUID.randomUUID();
        var key = new Claims(scope, Origin.API_KEY, keyId, keyId, null, null, null);

        assertThrows(ContextRejectedException.class, () -> admission.session(null, scope, null));
        assertThrows(ContextRejectedException.class,
                () -> admission.session(OperationIdentity.pending(), scope, null));
        assertThrows(ContextRejectedException.class,
                () -> admission.apiKey(OperationIdentity.pending(), scope, null));

        var operation = OperationIdentity.pending();
        var coherent = new OperationEvidence.Session(operation, session, now.plusSeconds(60));
        // No issuer: a structurally coherent, unexpired public record still grants nothing.
        assertThrows(ContextRejectedException.class, () -> admission.session(operation, scope, coherent));
        // Consumption is checked independently of the unconditional default denial below.
        assertThrows(ContextRejectedException.class, operation::beginAdmission);
        assertThrows(ContextRejectedException.class, () -> admission.session(operation, scope, coherent));
        assertThrows(ContextRejectedException.class,
                () -> admission.session(OperationIdentity.pending(), scope, coherent));

        var wrongOrigin = OperationIdentity.pending();
        assertThrows(ContextRejectedException.class, () -> admission.session(wrongOrigin, scope,
                new OperationEvidence.Session(wrongOrigin, key, now.plusSeconds(60))));
        var wrongScope = OperationIdentity.pending();
        assertThrows(ContextRejectedException.class, () -> admission.session(wrongScope,
                new Scope(UUID.randomUUID(), scope.applicationId(), scope.branchId()),
                new OperationEvidence.Session(wrongScope, session, now.plusSeconds(60))));
        var expired = OperationIdentity.pending();
        assertThrows(ContextRejectedException.class, () -> admission.session(expired, scope,
                new OperationEvidence.Session(expired, session, now)));

        var keyOperation = OperationIdentity.pending();
        var keyEvidence = new OperationEvidence.ApiKey(keyOperation, key, now.plusSeconds(60));
        assertThrows(ContextRejectedException.class, () -> admission.apiKey(keyOperation, scope, keyEvidence));
        assertThrows(ContextRejectedException.class, keyOperation::beginAdmission);
        assertThrows(ContextRejectedException.class, () -> admission.apiKey(keyOperation, scope, keyEvidence));
        var wrongKeyOrigin = OperationIdentity.pending();
        assertThrows(ContextRejectedException.class, () -> admission.apiKey(wrongKeyOrigin, scope,
                new OperationEvidence.ApiKey(wrongKeyOrigin, session, now.plusSeconds(60))));
    }
}
