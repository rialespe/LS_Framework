package com.lssoluciones.lscore.access.infrastructure.jdbc;

import com.lssoluciones.lscore.access.application.context.ContextRejectedException;
import com.lssoluciones.lscore.access.application.context.OperationAdmission;
import com.lssoluciones.lscore.access.application.context.OperationIdentity;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope;
import com.lssoluciones.lscore.operations.infrastructure.jdbc.ContextTransaction;
import com.lssoluciones.lscore.operations.infrastructure.jdbc.ContextTransaction.BranchView;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Trusted composition only. Re-authenticates through existing F04/V14 on EVERY operation.
 * No reusable authorization input, caller-supplied subject, physical identifier or arbitrary SQL.
 * Fresh next-operation decisions, not universal concurrent revocation/draining of already admitted reads.
 */
public final class BranchReadCoordinator {
    private final JdbcTenantAuthorization authorization;
    private final ContextTransaction transactions;
    private final DataSource emitter;
    private final OperationAdmission admission = new OperationAdmission(Clock.systemUTC());

    public BranchReadCoordinator(JdbcTenantAuthorization authorization, HikariDataSource operationPool, DataSource emitter) {
        this.authorization = Objects.requireNonNull(authorization);
        this.transactions = new ContextTransaction(Objects.requireNonNull(operationPool));
        this.emitter = Objects.requireNonNull(emitter);
    }

    public BranchView read(String username, char[] password, Scope selected) {
        try {
            var decision = authorization.evaluate(username, password, selected);
            if (decision.outcome() != JdbcTenantAuthorization.Outcome.ALLOWED) {
                throw new ContextRejectedException();
            }
            return transactions.readBranch(connection -> {
                var operation = OperationIdentity.pending();
                var proof = ProtectedBranchRead.issue(connection, emitter, operation, decision);
                try {
                    return admission.branchRead(operation, selected, proof);
                } catch (RuntimeException failure) {
                    proof.close();
                    throw failure;
                }
            });
        } catch (RuntimeException failure) {
            throw new ContextRejectedException();
        }
    }
}
