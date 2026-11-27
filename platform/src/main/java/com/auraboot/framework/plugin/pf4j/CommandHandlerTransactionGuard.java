package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization.ReadIsolation;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;

/** Checks the actual JDBC transaction before an owner with a read-isolation requirement runs. */
@Component
@RequiredArgsConstructor
public final class CommandHandlerTransactionGuard {
    private final SqlSessionFactory sessions;

    public void require(ReadIsolation isolation) {
        if (isolation == null) return;
        var source = sessions.getConfiguration().getEnvironment().getDataSource();
        var resource = TransactionSynchronizationManager.getResource(source);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction()) throw denied();
        try {
            var connection = holder.getConnection();
            if (connection.isClosed() || connection.getAutoCommit() || connection.isReadOnly()
                    || connection.getTransactionIsolation() != isolation.jdbcLevel()) throw denied();
        } catch (SQLException unavailable) { throw new IllegalStateException("Cannot validate command owner transaction", unavailable); }
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Command transaction does not satisfy its owner contract"); }
}
