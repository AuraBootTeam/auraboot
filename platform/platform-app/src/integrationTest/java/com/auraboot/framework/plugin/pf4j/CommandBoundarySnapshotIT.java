package com.auraboot.framework.plugin.pf4j;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.service.ConcurrencyGuard;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.CommandExecutionTransaction;
import com.auraboot.framework.meta.service.impl.CommandExecutorImpl;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipeline;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.RecordActionScopeAccessor;
import com.auraboot.framework.test.util.OwnedTestDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL/transaction/executor boundaries; policy and pipeline collaborators are explicit seams. */
class CommandBoundarySnapshotIT {
    private DriverManagerDataSource source;
    private DataSourceTransactionManager manager;
    private JdbcTemplate jdbc;
    private String schema;
    private CommandRecordActionScopeAccessorFactory factory;
    private final List<Integer> observed = new ArrayList<>();

    @BeforeEach void prepare() {
        String url=System.getenv("TEST_DATABASE_URL");
        OwnedTestDatabase.require(url,OwnedTestDatabase.AURABOOT_SLOT_PATTERN,"An explicitly owned slot database is required");
        source=new DriverManagerDataSource(url,System.getenv("TEST_DATABASE_USERNAME"),System.getenv().getOrDefault("TEST_DATABASE_PASSWORD",""));
        manager=new DataSourceTransactionManager(source);jdbc=new JdbcTemplate(source);
        schema="command_boundary_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        jdbc.execute("CREATE SCHEMA "+schema);
        jdbc.execute("CREATE TABLE "+schema+".probe(id integer PRIMARY KEY,value integer NOT NULL)");
        jdbc.update("INSERT INTO "+schema+".probe VALUES(1,0)");
        jdbc.execute("CREATE TABLE "+schema+".append_head(version integer PRIMARY KEY)");
        var configuration=new Configuration(new Environment("real-pg",new JdbcTransactionFactory(),source));
        var beans=mock(ApplicationContext.class);var data=mock(DynamicDataService.class);var permissions=mock(PermissionFacade.class);
        when(beans.getBean(DynamicDataService.class)).thenReturn(data);when(beans.getBean(PermissionFacade.class)).thenReturn(permissions);
        when(permissions.canOperate(eq(30L),eq("probe"),eq("read"),anyMap())).thenReturn(PermissionResult.allow(List.of()));
        when(data.getById("probe","probe-1")).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals("repeatable read",jdbc.queryForObject("SHOW transaction_isolation",String.class));
            int first=jdbc.queryForObject("SELECT value FROM "+schema+".probe WHERE id=1",Integer.class);
            try(var connection=source.getConnection();var statement=connection.createStatement()) {
                statement.executeUpdate("UPDATE "+schema+".probe SET value=value+1 WHERE id=1");
            }
            assertEquals(first,jdbc.queryForObject("SELECT value FROM "+schema+".probe WHERE id=1",Integer.class),"The authorization read must retain its physical snapshot");
            observed.add(first);
            return Map.of("pid","probe-1","tenant_id",7L,"deleted_flag",false);
        });
        factory=new CommandRecordActionScopeAccessorFactory(beans,manager,new SqlSessionFactoryBuilder().build(configuration));
        actor();
    }

    private static void actor() {MetaContext.setContext(7L,20L,"actor","Actor");MetaContext.setMemberId(30L);}
    private static CommandPipelineContext context() {return CommandPipelineContext.builder().tenantId(7L).userId(20L)
            .authorizationVerdict(CommandAuthorizationVerdict.authorized("domain.configure")).build();}
    @AfterEach void clear() {
        MetaContext.clear();
        if(schema!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");
    }

    @Test void invocationChecksOwnFreshRepeatableReadSnapshots() {
        try(var scope=factory.forInvocation(context())) {
            assertTrue(scope.accessor().permits("probe","probe-1","read"));
            assertTrue(scope.accessor().permits("probe","probe-1","read"));
        }
        assertEquals(List.of(0,1),observed,"Each check is fresh, while every individual check is repeatable");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test void commandCheckSuspendsAndRestoresItsCallerWithoutCommittingCallerWrites() {
        var outer=new TransactionTemplate(manager);outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var bridge=new AtomicReference<RecordActionScopeAccessor>();
        outer.execute(status->{
            jdbc.update("INSERT INTO "+schema+".probe VALUES(99,99)");
            Object holder=TransactionSynchronizationManager.getResource(source);
            bridge.set(factory.forCommand(context()));assertNotNull(bridge.get());
            assertTrue(bridge.get().permits("probe","probe-1","read"));
            assertSame(holder,TransactionSynchronizationManager.getResource(source));
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            status.setRollbackOnly();return null;
        });
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+schema+".probe WHERE id=99",Integer.class));
        assertFalse(bridge.get().permits("probe","probe-1","read"),"A command bridge expires with its original synchronization");
        assertEquals(List.of(0),observed);
    }

    @Test void constantGuardSerializesImmutableAppendsBeforeSnapshotAndUntilCommit() throws Exception {
        var guard=new AlignedGuard();var extensions=mock(ExtensionRegistry.class);var definitions=mock(CommandDefinitionMapper.class);
        var handler=mock(CommandHandlerExtension.class);when(handler.commandReadIsolation()).thenReturn(BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        when(extensions.getCommandHandler("example:append")).thenReturn(Optional.of(handler));
        var definition=new CommandDefinition();definition.setCode("example:append");definition.setExecutionConfig("{\"type\":\"custom\",\"concurrencyKey\":\"append-head\"}");
        when(definitions.findCurrentByCode("example:append")).thenReturn(definition);
        var transaction=new CommandExecutionTransaction(manager,extensions);
        ReflectionTestUtils.setField(transaction,"definitions",definitions);ReflectionTestUtils.setField(transaction,"concurrency",guard);
        var pipeline=mock(CommandPipeline.class);
        when(pipeline.executePreGuardPhases(any())).thenAnswer(call->{
            var ctx=(CommandPipelineContext)call.getArgument(0);ctx.setConcurrencyKey("append-head");ctx.setLockTimeoutMs(5000);
            ctx.setExecConfig(Map.of("type","custom","concurrencyKey","append-head"));
            jdbc.queryForObject("SELECT count(*) FROM "+schema+".append_head",Integer.class);return null;
        });
        when(pipeline.executeGuardedPhases(any())).thenAnswer(call->{
            int next=jdbc.queryForObject("SELECT coalesce(max(version),0)+1 FROM "+schema+".append_head",Integer.class);
            jdbc.update("INSERT INTO "+schema+".append_head VALUES(?)",next);
            return CommandExecuteResult.builder().commandCode("example:append").data(Map.of("version",next)).build();
        });
        // Use the production execute and guarded-phase paths; the pipeline seam performs the
        // same snapshot-before-lock plus immutable append that used to race in a plugin.
        var executor=mock(CommandExecutorImpl.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(executor,"commandPipeline",pipeline);ReflectionTestUtils.setField(executor,"executionTransaction",transaction);
        ReflectionTestUtils.setField(executor,"concurrencyGuard",guard);
        try(var callers=Executors.newFixedThreadPool(2)) {
            var first=callers.submit(()->append(executor));var second=callers.submit(()->append(executor));
            var versions=new ArrayList<>(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)));versions.sort(Integer::compareTo);
            assertEquals(List.of(1,2),versions);
        }
        assertEquals(List.of(1,2),jdbc.queryForList("SELECT version FROM "+schema+".append_head ORDER BY version",Integer.class));
        assertEquals(2,guard.acquisitions);
    }

    private static int append(CommandExecutorImpl executor) {
        actor();try {var request=new CommandExecuteRequest();request.setPayload(Map.of());return ((Number)executor.execute("example:append",request).getData().get("version")).intValue();}
        finally {MetaContext.clear();}
    }
    private static final class AlignedGuard implements ConcurrencyGuard {
        private final CountDownLatch both=new CountDownLatch(2);
        private final ReentrantLock lock=new ReentrantLock();
        private int acquisitions;
        @Override public <T> T executeWithLock(String key,long timeout,Supplier<T> action) {
            assertFalse(lock.isHeldByCurrentThread(),"The executor must not reacquire its transaction guard");
            both.countDown();
            try {assertTrue(both.await(10,TimeUnit.SECONDS));assertTrue(lock.tryLock(timeout,TimeUnit.MILLISECONDS));}
            catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            try {acquisitions++;T result=action.get();assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Release follows the physical commit");return result;}
            finally {lock.unlock();}
        }
        @Override public boolean tryAcquire(String key,long lease) {throw new UnsupportedOperationException();}
        @Override public void release(String key) {throw new UnsupportedOperationException();}
    }
}
