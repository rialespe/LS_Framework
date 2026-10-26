package com.lssoluciones.lscore.access.application.context;

import com.lssoluciones.lscore.access.application.context.ContextResolver.AuthorizedContext;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Origin;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope;
import com.lssoluciones.lscore.access.infrastructure.jdbc.ProtectedBranchRead;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;

/**
 * Session/API Key candidates remain inactive. Only the bounded, protected password branch-read path is enabled.
 * Public candidate records are never authority; physical PostgreSQL evidence is mandatory for branch reads.
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

    public AuthorizedAdmission branchRead(OperationIdentity operation, Scope expected, ProtectedBranchRead evidence) {
        require(operation != null);
        operation.beginAdmission();
        require(evidence != null && evidence.belongsTo(operation));
        var context = ContextResolver.branchRead(evidence, expected);
        return new AuthorizedAdmission(operation, context, evidence);
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
     * Single-operation result, issued only after native PostgreSQL evidence verification.
     * Not returned to clients. Closure invalidates the Java handle and removes the protected registry row.
     */
    public static final class AuthorizedAdmission implements AutoCloseable {
        private final OperationIdentity operation;
        private final AuthorizedContext context;
        private final ProtectedBranchRead evidence;

        private AuthorizedAdmission(OperationIdentity operation, AuthorizedContext context, ProtectedBranchRead evidence) {
            this.operation = operation;
            this.context = context;
            this.evidence = evidence;
        }

        public OperationIdentity operation() {
            return operation;
        }

        public AuthorizedContext context() {
            return context;
        }

        public void verify(Connection connection) {
            evidence.verify(connection);
        }

        @Override
        public void close() {
            evidence.close();
        }
    }
}
