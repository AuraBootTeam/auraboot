package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization.ReadIsolation;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandHandlerTransactionGuardTest {
    final DataSource source = mock(DataSource.class);
    final Connection connection = mock(Connection.class);
    final SqlSessionFactory sessions = mock(SqlSessionFactory.class);
    CommandHandlerTransactionGuard guard;
    @BeforeEach void prepare() throws Exception {
        var config = new Configuration();
        config.setEnvironment(new Environment("unit", mock(org.apache.ibatis.transaction.TransactionFactory.class), source));
        when(sessions.getConfiguration()).thenReturn(config);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        guard = new CommandHandlerTransactionGuard(sessions);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        var holder = new ConnectionHolder(connection); holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source, holder);
    }
    @AfterEach void clear() {
        if (TransactionSynchronizationManager.hasResource(source)) TransactionSynchronizationManager.unbindResource(source);
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.clear();
    }
    @Test void acceptsTheDeclaredActualConnectionAndPreservesLegacyUnspecifiedOwners() {
        guard.require(ReadIsolation.READ_COMMITTED);
        guard.require(null);
    }
    @Test void rejectsSnapshotIsolationAndMissingOrReadOnlyTransactions() throws Exception {
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        assertThatThrownBy(() -> guard.require(ReadIsolation.READ_COMMITTED)).isInstanceOf(AccessDeniedException.class);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> guard.require(ReadIsolation.READ_COMMITTED)).isInstanceOf(AccessDeniedException.class);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.unbindResource(source);
        assertThatThrownBy(() -> guard.require(ReadIsolation.READ_COMMITTED)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void rejectsAutocommitAndReadOnlyConnections() throws Exception {
        when(connection.getAutoCommit()).thenReturn(true);
        assertThatThrownBy(() -> guard.require(ReadIsolation.READ_COMMITTED)).isInstanceOf(AccessDeniedException.class);
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.isReadOnly()).thenReturn(true);
        assertThatThrownBy(() -> guard.require(ReadIsolation.READ_COMMITTED)).isInstanceOf(AccessDeniedException.class);
    }
}
