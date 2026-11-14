package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndependentTransactionAccessorImplTest {

    @Test
    void requiresNew_commitsTheBoundedCallbackAndSuppliesDynamicDataAccess() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        DynamicDataService dynamicData = mock(DynamicDataService.class);
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(status);
        when(dynamicData.create("sample_model", Map.of("value", 7)))
                .thenReturn(Map.of("pid", "row-1"));

        IndependentTransactionAccessorImpl accessor =
                new IndependentTransactionAccessorImpl(transactions, dynamicData);
        String pid = accessor.requiresNew(db -> String.valueOf(
                db.create("sample_model", Map.of("value", 7)).get("pid")));

        assertThat(pid).isEqualTo("row-1");
        verify(transactions).commit(status);
    }
    private static final String MODEL = "qo_quote_common";
    private static final String PID = "01M7CPP1W6XNS8MGAW6B73B35K";

    @org.junit.jupiter.api.AfterEach
    void clearActor() { com.auraboot.framework.application.tenant.MetaContext.clear(); }

    private record LockedFixture(IndependentTransactionAccessorImpl accessor,
            org.springframework.jdbc.datasource.DataSourceTransactionManager transactions,
            DynamicDataService data, javax.sql.DataSource source, java.sql.Connection connection,
            java.sql.PreparedStatement statement, SimpleTransactionStatus status) { }

    private LockedFixture lockedFixture() throws Exception {
        com.auraboot.framework.application.tenant.MetaContext.setContext(7L, 9L, "actor", "actor");
        var source = mock(javax.sql.DataSource.class);
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.PreparedStatement.class);
        var transactions = mock(org.springframework.jdbc.datasource.DataSourceTransactionManager.class);
        var data = mock(DynamicDataService.class);
        var status = new SimpleTransactionStatus();
        when(transactions.getDataSource()).thenReturn(source);
        when(transactions.getTransaction(any())).thenReturn(status);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(any(String.class))).thenReturn(statement);
        when(data.getById(MODEL, PID)).thenReturn(Map.of("pid", PID, "tenant_id", 7L));
        return new LockedFixture(new IndependentTransactionAccessorImpl(transactions, data),
                transactions, data, source, connection, statement, status);
    }

    @Test
    void serializedCheckpointAcquiresBeforeSnapshotAndRechecksTheReadableRoot() throws Exception {
        var f = lockedFixture();
        String result = f.accessor.requiresNewSerialized(MODEL, PID, db -> "saved");
        assertThat(result).isEqualTo("saved");
        var order = org.mockito.Mockito.inOrder(f.data, f.connection, f.statement, f.transactions);
        order.verify(f.data).getById(MODEL, PID);
        order.verify(f.connection).setAutoCommit(false);
        order.verify(f.statement).setString(1, "plugin-checkpoint:7:qo_quote_common:" + PID);
        order.verify(f.statement).execute();
        order.verify(f.transactions).getTransaction(any());
        order.verify(f.data).getById(MODEL, PID);
        order.verify(f.transactions).commit(f.status);
        order.verify(f.connection).rollback();
        verify(f.connection).close();
    }

    @Test
    void serializedCallbackFailureRollsBackWorkAndReleasesTheLock() throws Exception {
        var f = lockedFixture();
        var failure = new IllegalStateException("replacement failed");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.accessor.requiresNewSerialized(
                MODEL, PID, db -> { throw failure; })).isSameAs(failure);
        verify(f.transactions).rollback(f.status);
        verify(f.transactions, org.mockito.Mockito.never()).commit(any());
        verify(f.connection).rollback();
        verify(f.connection).close();
    }

    @Test
    void inaccessibleRootCannotAcquireALockOrInvokeWork() throws Exception {
        var f = lockedFixture();
        when(f.data.getById(MODEL, PID)).thenReturn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.accessor.requiresNewSerialized(
                MODEL, PID, db -> { throw new AssertionError("must not run"); }))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(f.source, org.mockito.Mockito.never()).getConnection();
    }

    @Test
    void lockFailureReleasesTheLockTransactionWithoutStartingWork() throws Exception {
        var f = lockedFixture();
        when(f.statement.execute()).thenThrow(new java.sql.SQLException("lock unavailable"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.accessor.requiresNewSerialized(
                MODEL, PID, db -> { throw new AssertionError("must not run"); }))
                .isInstanceOf(IllegalStateException.class).hasRootCauseMessage("lock unavailable");
        verify(f.connection).rollback();
        verify(f.transactions, org.mockito.Mockito.never()).getTransaction(any());
    }

}
