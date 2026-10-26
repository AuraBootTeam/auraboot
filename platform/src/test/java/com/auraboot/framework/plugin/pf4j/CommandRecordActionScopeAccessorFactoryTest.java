package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.exception.MetaRecordNotFoundException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.engine.evaluator.DataScopeEvaluator;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.permission.service.RecordShareService;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic scope and capability lifetime checks; real policy/cache/PG checks are separate. */
class CommandRecordActionScopeAccessorFactoryTest {
    private final ApplicationContext beans = mock(ApplicationContext.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final DynamicDataService data = mock(DynamicDataService.class);
    private final PermissionFacade permissions = mock(PermissionFacade.class);
    private final DataScopeEvaluator scopes = mock(DataScopeEvaluator.class);
    private final RecordShareService shares = mock(RecordShareService.class);
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private CommandRecordActionScopeAccessorFactory factory;
    private CommandPipelineContext context;
    private Map<String,Object> row;

    @BeforeEach void prepare() {
        identity();
        context = CommandPipelineContext.builder().tenantId(7L).userId(20L)
                .authorizationVerdict(CommandAuthorizationVerdict.authorized("domain.configure")).build();
        var sessions = mock(SqlSessionFactory.class); var config = new Configuration();
        config.setEnvironment(new Environment("unit", mock(org.apache.ibatis.transaction.TransactionFactory.class), source));
        when(sessions.getConfiguration()).thenReturn(config);
        factory = new CommandRecordActionScopeAccessorFactory(beans, transactions, sessions);
        when(beans.getBean(DynamicDataService.class)).thenReturn(data);
        when(beans.getBean(PermissionFacade.class)).thenReturn(permissions);
        when(beans.getBean(DataScopeEvaluator.class)).thenReturn(scopes);
        when(beans.getBean(RecordShareService.class)).thenReturn(shares);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        row = new HashMap<>(Map.of("pid", "parent-1", "tenant_id", 7L, "deleted_flag", false));
        when(data.getById("parent", "parent-1")).thenAnswer(call -> row);
        when(permissions.canOperate(30L, "parent", "read", row)).thenReturn(PermissionResult.allow(List.of()));
        when(scopes.evaluate(30L, "parent", "update", row)).thenReturn(step(EvaluationVerdict.DENY));
    }
    private void identity() { MetaContext.setContext(7L,20L,"actor","Actor"); MetaContext.setMemberId(30L); }
    private static EvaluationStep step(EvaluationVerdict verdict) { return new EvaluationStep("DataScope", verdict, "unit"); }
    @AfterEach void clear() {
        if(TransactionSynchronizationManager.hasResource(source)) TransactionSynchronizationManager.unbindResource(source);
        if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false); MetaContext.clear();
    }
    private void physicalTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        var holder = new ConnectionHolder(connection); holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source, holder);
    }
    @Test void readAllCannotReplaceUpdateNoneAndNoGenericUpdatePermissionIsGranted() {
        try(var scope = factory.forInvocation(context)) {
            assertFalse(scope.accessor().permits("parent","parent-1","update"));
            when(scopes.evaluate(30L,"parent","update",row)).thenReturn(step(EvaluationVerdict.NOT_APPLICABLE));
            assertTrue(scope.accessor().permits("parent","parent-1","update"));
        }
        verify(permissions,never()).canOperate(anyLong(),anyString(),eq("update"),any());
        verify(scopes,never()).evaluate(anyLong(),anyString(),eq("read"),any());
        verify(transactions,times(2)).getTransaction(argThat(t -> t.isReadOnly()
                && t.getPropagationBehavior()==TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }
    @Test void exactActionSharingIsRecheckedOnEveryCallAndDoesNotBecomeReadAuthority() {
        try(var scope = factory.forInvocation(context)) {
            when(shares.isSharedByPid(7L,"parent","parent-1",30L,"actor","read")).thenReturn(true);
            assertFalse(scope.accessor().permits("parent","parent-1","update"));
            when(shares.isSharedByPid(7L,"parent","parent-1",30L,"actor","update")).thenReturn(true);
            assertTrue(scope.accessor().permits("parent","parent-1","update"));
            when(shares.isSharedByPid(7L,"parent","parent-1",30L,"actor","update")).thenReturn(false);
            assertFalse(scope.accessor().permits("parent","parent-1","update"));
            when(permissions.canOperate(30L,"parent","read",row)).thenReturn(PermissionResult.deny("Read scope denied",List.of()));
            assertFalse(scope.accessor().permits("parent","parent-1","read"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"wrong-pid","numeric-alias","wrong-tenant","missing-tenant","deleted","missing"})
    void rejectsUntrustedOrUnavailableRowIdentity(String fault) {
        switch(fault) {
            case "wrong-pid" -> row.put("pid","other");
            case "numeric-alias" -> when(data.getById("parent","123")).thenReturn(row);
            case "wrong-tenant" -> row.put("tenant_id",8L);
            case "missing-tenant" -> row.remove("tenant_id");
            case "deleted" -> row.put("deleted_flag",true);
            case "missing" -> when(data.getById("parent","parent-1")).thenThrow(new MetaRecordNotFoundException("parent","parent-1"));
        }
        try(var scope=factory.forInvocation(context)) {
            assertFalse(scope.accessor().permits("parent","numeric-alias".equals(fault)?"123":"parent-1","read"));
        }
        verifyNoInteractions(permissions);
    }
    @Test void storageAndScopeFailuresAreNotConvertedIntoPermissionOrFallbackQueries() {
        try(var scope=factory.forInvocation(context)) {
            when(data.getById("parent","parent-1")).thenThrow(new IllegalStateException("storage unavailable"));
            assertThrows(IllegalStateException.class,()->scope.accessor().permits("parent","parent-1","update"));
            doAnswer(call->row).when(data).getById("parent","parent-1");
            when(scopes.evaluate(30L,"parent","update",row)).thenReturn(null);
            assertFalse(scope.accessor().permits("parent","parent-1","update"));
        }
        verifyNoInteractions(shares);
    }
    @Test void absentOrDifferentTrustedActorsNeverReceiveTheBridge() {
        context.setAuthorizationVerdict(null); assertNull(factory.forInvocation(context).accessor());
        context.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("domain.configure"));
        context.setUserId(99L); assertNull(factory.forInvocation(context).accessor());
        context.setUserId(20L); context.setTenantId(99L); assertNull(factory.forInvocation(context).accessor());
        MetaContext.clear(); assertNull(factory.forInvocation(context).accessor());
        verifyNoInteractions(data);
    }
    @Test void callbackBridgeExpiresOnCloseActorChangeOrAnotherThread() throws Exception {
        var scope=factory.forInvocation(context); var bridge=scope.accessor();
        assertTrue(bridge.permits("parent","parent-1","read"));
        MetaContext.setMemberId(99L); assertFalse(bridge.permits("parent","parent-1","read")); identity();
        try(var executor=Executors.newSingleThreadExecutor()) {
            assertFalse(executor.submit(()-> {identity(); try{return bridge.permits("parent","parent-1","read");}
                finally{MetaContext.clear();}}).get());
        }
        scope.close(); assertFalse(bridge.permits("parent","parent-1","read"));
        verify(data,times(1)).getById("parent","parent-1");
    }
    @ParameterizedTest @ValueSource(strings={"no-tx","read-only","no-sync","foreign-resource","not-synchronized","autocommit","physical-read-only","closed"})
    void commandBridgeRequiresItsWritablePhysicalCoreTransaction(String fault) throws Exception {
        physicalTransaction();
        switch(fault) {
            case "no-tx" -> TransactionSynchronizationManager.setActualTransactionActive(false);
            case "read-only" -> TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            case "no-sync" -> TransactionSynchronizationManager.clearSynchronization();
            case "foreign-resource" -> TransactionSynchronizationManager.unbindResource(source);
            case "not-synchronized" -> ((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).setSynchronizedWithTransaction(false);
            case "autocommit" -> when(connection.getAutoCommit()).thenReturn(true);
            case "physical-read-only" -> when(connection.isReadOnly()).thenReturn(true);
            case "closed" -> when(connection.isClosed()).thenReturn(true);
        }
        assertNull(factory.forCommand(context)); verifyNoInteractions(data);
    }
    @Test void commandBridgeCannotSurviveTheOriginalSynchronizationEvenWithTheSameConnection() {
        physicalTransaction(); var bridge=factory.forCommand(context); assertNotNull(bridge);
        assertTrue(bridge.permits("parent","parent-1","read"));
        TransactionSynchronizationManager.clearSynchronization(); TransactionSynchronizationManager.initSynchronization();
        assertFalse(bridge.permits("parent","parent-1","read"));
        assertTrue(factory.forCommand(context).permits("parent","parent-1","read"));
    }
    @Test void malformedModelPidOrActionsNeverReachHostData() {
        try(var scope=factory.forInvocation(context)) {
            for(String bad:new String[]{"", " padded", "bad\n", "a\u200bb", "x".repeat(129)})
                assertThrows(IllegalArgumentException.class,()->scope.accessor().permits(bad,"parent-1","read"));
            for(String bad:new String[]{"", "parent ", "bad\n", "a\u200bb", "x".repeat(65)})
                assertThrows(IllegalArgumentException.class,()->scope.accessor().permits("parent",bad,"read"));
            assertThrows(IllegalArgumentException.class,()->scope.accessor().permits("parent","parent-1",null));
            assertThrows(IllegalArgumentException.class,()->scope.accessor().permits("parent","parent-1","create"));
        }
        verifyNoInteractions(data);
    }
}
