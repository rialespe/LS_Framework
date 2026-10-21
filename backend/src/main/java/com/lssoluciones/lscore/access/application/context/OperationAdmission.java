package com.lssoluciones.lscore.access.application.context;

import com.lssoluciones.lscore.access.application.context.ContextResolver.AuthorizedContext;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Origin;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope;
import java.time.Clock;
import java.util.Objects;

/**
 * Inactive admission contract. No issuer/verifier, bean, adapter or successful issuance path.
 * Structural checks are not authentication. Every attempt currently fails closed.
 * Neither this contract nor its candidate records replace AuthorizedContext/ContextTransaction.
 */
public final class OperationAdmission {
    private final Clock clock;

    public OperationAdmission(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public AuthorizedAdmission session(OperationIdentity operation, Scope expected, OperationEvidence.Session evidence) {
        return reject(operation, expected, evidence, Origin.SESSION);
    }

    public AuthorizedAdmission apiKey(OperationIdentity operation, Scope expected, OperationEvidence.ApiKey evidence) {
        return reject(operation, expected, evidence, Origin.API_KEY);
    }

    private AuthorizedAdmission reject(OperationIdentity operation, Scope expected,
            OperationEvidence evidence, Origin origin) {
        require(operation != null);
        operation.beginAdmission();
        require(evidence != null && evidence.operation() == operation && evidence.claims() != null);
        var claims = evidence.claims();
        require(expected != null && expected.tenantId() != null && expected.applicationId() != null
                && expected.equals(claims.scope()) && claims.origin() == origin);
        require(evidence.expiresAt() != null && evidence.expiresAt().isAfter(clock.instant()));
        // No approved trusted verifier exists: even a coherent candidate must be rejected.
        throw new ContextRejectedException();
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new ContextRejectedException();
        }
    }

    /**
     * Reserved output shape. Nothing can issue it in this increment.
     * A future approved issuer must supply an existing AuthorizedContext and independently
     * verified binding; private construction alone would not prove either property.
     */
    public static final class AuthorizedAdmission {
        private final OperationIdentity operation;
        private final AuthorizedContext context;

        private AuthorizedAdmission(OperationIdentity operation, AuthorizedContext context) {
            this.operation = operation;
            this.context = context;
        }

        public OperationIdentity operation() {
            return operation;
        }

        public AuthorizedContext context() {
            return context;
        }
    }
}
