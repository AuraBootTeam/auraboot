package com.auraboot.testing.meta;

import com.auraboot.framework.meta.aspect.CommandTransactionIsolationAspect;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.impl.CommandMetadataCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring advice ordering with an in-memory transaction manager; not PostgreSQL qualification. */
class CommandTransactionIsolationAspectTest {
    private static final String CODE="qa:confirm_change";
    private static final String SERIAL="{\"handlerParams\":{\"transactionIsolation\":\"serializable\"}}";
    @AfterEach void clear() { MetaContext.clear(); }
    private AnnotationConfigApplicationContext context() {
        MetaContext.setContext(9L,1L,"reviewer","reviewer");
        var context=new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("command-transaction-probe");context.register(Config.class);context.refresh();return context;
    }
    private void definition(AnnotationConfigApplicationContext context,String config) {
        var command=new CommandDefinition();command.setCode(CODE);command.setExecutionConfig(config);
        when(context.getBean(CommandMetadataCacheService.class).findCurrentCommandByCode(CODE)).thenReturn(command);
    }
    @Test void probeConfigurationCannotEnterTheFrameworkComponentScan() {
        // The framework test launcher belongs to the application module on newer baselines.
        // This library fixture must remain outside the framework scan without linking that launcher.
        assertFalse(Config.class.getName().startsWith("com.auraboot.framework."));
        assertTrue(Config.class.isAnnotationPresent(org.springframework.boot.test.context.TestConfiguration.class));
        assertArrayEquals(new String[]{"command-transaction-probe"},Config.class.getAnnotation(Profile.class).value());
    }
    @Test void probeConfigurationDoesNotRegisterAnExecutorWithoutItsExplicitTestProfile() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            assertEquals(0,context.getBeanNamesForType(CommandExecutor.class).length);
        }
    }
    @Test void serializableMetadataWrapsTheOrdinaryTransactionalExecutor() {
        try(var context=context()) {
            definition(context,SERIAL);context.getBean(CommandExecutor.class).execute(CODE,new CommandExecuteRequest());
            var probe=context.getBean(Probe.class);var tx=context.getBean(RecordingTransactions.class);
            assertEquals(1,probe.calls);assertTrue(probe.transactionActive);assertEquals(TransactionDefinition.ISOLATION_SERIALIZABLE,probe.isolation);
            assertEquals(1,tx.begins);assertEquals(1,tx.commits);assertEquals(0,tx.rollbacks);
            verifyNoInteractions(context.getBean(CommandService.class));assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }
    }
    @Test void ordinaryCommandsRetainDefaultIsolationAndPayloadCannotSelectIt() {
        try(var context=context()) {
            definition(context,"{\"handlerParams\":{}}");var request=new CommandExecuteRequest();request.setPayload(Map.of("transactionIsolation","serializable"));
            context.getBean(CommandExecutor.class).execute(CODE,request);
            assertNull(context.getBean(Probe.class).isolation);assertEquals(1,context.getBean(RecordingTransactions.class).begins);
        }
    }
    @Test void weakOuterTransactionIsRejectedWithoutStartingAnIndependentCommit() {
        try(var context=context()) {
            definition(context,SERIAL);var tx=context.getBean(RecordingTransactions.class);var outer=new TransactionTemplate(tx);
            outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            outer.execute(status->{
                var failure=assertThrows(IllegalStateException.class,()->context.getBean(CommandExecutor.class).execute(CODE,new CommandExecuteRequest()));
                assertEquals("COMMAND_SERIALIZABLE_TRANSACTION_REQUIRED",failure.getMessage());
                assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());return null;
            });
            assertEquals(0,context.getBean(Probe.class).calls);assertEquals(1,tx.begins);assertEquals(1,tx.commits);
        }
    }
    @Test void strongOuterTransactionIsJoinedAndBusinessFailureRollsBackOnce() {
        try(var context=context()) {
            definition(context,SERIAL);var tx=context.getBean(RecordingTransactions.class);var outer=new TransactionTemplate(tx);
            outer.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);context.getBean(Probe.class).fail=true;
            var failure=assertThrows(IllegalStateException.class,()->outer.execute(status->context.getBean(CommandExecutor.class).execute(CODE,new CommandExecuteRequest())));
            assertEquals("PROBE_FAILURE",failure.getMessage());assertEquals(1,tx.begins);assertEquals(0,tx.commits);assertEquals(1,tx.rollbacks);
        }
    }
    @Test void asyncDispatchCannotEscapeAnExplicitSerializableCommand() {
        try(var context=context()) {
            for(String async:new String[]{"true","\"TRUE\"","\"true\""}) {
                definition(context,"{\"handlerParams\":{\"transactionIsolation\":\"serializable\",\"async\":"+async+"}}");
                var failure=assertThrows(IllegalArgumentException.class,()->context.getBean(CommandExecutor.class).execute(CODE,new CommandExecuteRequest()));
                assertEquals("COMMAND_TRANSACTION_POLICY_ASYNC_UNSUPPORTED",failure.getMessage());
            }
            assertEquals(0,context.getBean(Probe.class).calls);assertEquals(0,context.getBean(RecordingTransactions.class).begins);
            definition(context,"{\"handlerParams\":{\"transactionIsolation\":\"serializable\",\"async\":false}}");
            context.getBean(CommandExecutor.class).execute(CODE,new CommandExecuteRequest());
            assertEquals(TransactionDefinition.ISOLATION_SERIALIZABLE,context.getBean(Probe.class).isolation);
        }
    }
    @Test void invalidPolicyAndMalformedMetadataRejectBeforeAnyBusinessInvocation() {
        try(var context=context()) {
            for(String raw:new String[]{"{\"handlerParams\":{\"transactionIsolation\":true}}",
                    "{\"handlerParams\":{\"transactionIsolation\":null}}",
                    "{\"handlerParams\":{\"transactionIsolation\":\"SERIALIZABLE\"}}","{"}) {
                definition(context,raw);
                var failure=assertThrows(IllegalArgumentException.class,()->context.getBean(CommandExecutor.class).execute(CODE,new CommandExecuteRequest()));
                assertEquals("COMMAND_TRANSACTION_POLICY_INVALID",failure.getMessage());
            }
            assertEquals(0,context.getBean(Probe.class).calls);assertEquals(0,context.getBean(RecordingTransactions.class).begins);
        }
    }
    @Test void releaseDefinitionUsesTheSameExplicitTransactionPolicy() {
        try(var context=context()) {
            var released=new CommandDefinitionDTO();released.setCode(CODE);released.setExecutionConfig(SERIAL);
            when(context.getBean(CommandService.class).findByCode(CODE)).thenReturn(released);
            context.getBean(CommandExecutor.class).execute(CODE,new CommandExecuteRequest());
            assertEquals(TransactionDefinition.ISOLATION_SERIALIZABLE,context.getBean(Probe.class).isolation);
        }
    }
    @Test void checkedInvocationFailurePreservesItsCauseAndRollsBack() throws Throwable {
        try(var context=context()) {
            definition(context,SERIAL);var invocation=mock(org.aspectj.lang.ProceedingJoinPoint.class);
            var original=new java.io.IOException("controlled checked failure");when(invocation.proceed()).thenThrow(original);
            var failure=assertThrows(java.io.IOException.class,()->context.getBean(CommandTransactionIsolationAspect.class)
                    .execute(invocation,CODE,new CommandExecuteRequest()));
            assertSame(original,failure);assertEquals(1,context.getBean(RecordingTransactions.class).rollbacks);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }
    }
    @Test void absentDefinitionLeavesNotFoundHandlingToTheOrdinaryPipeline() {
        try(var context=context()) {
            var ordinary=new com.auraboot.framework.meta.exception.CommandNotFoundException(CODE);
            when(context.getBean(CommandService.class).findByCode(CODE))
                    .thenThrow(new com.auraboot.framework.meta.exception.CommandNotFoundException("selector"));
            context.getBean(Probe.class).failure=ordinary;
            assertSame(ordinary,assertThrows(com.auraboot.framework.meta.exception.CommandNotFoundException.class,
                    ()->context.getBean(CommandExecutor.class).execute(CODE,new CommandExecuteRequest())));
            assertEquals(1,context.getBean(Probe.class).calls);
        }
    }
    @Test void metadataFailureOrSubstitutedCommandCannotRunTheBusinessHandler() {
        try(var context=context()) {
            var problem=new IllegalStateException("controlled metadata failure");
            when(context.getBean(CommandService.class).findByCode(CODE)).thenThrow(problem);
            assertSame(problem,assertThrows(IllegalStateException.class,()->context.getBean(CommandExecutor.class).execute(CODE,new CommandExecuteRequest())));
            var wrong=new CommandDefinition();wrong.setCode("another:command");wrong.setExecutionConfig(SERIAL);
            when(context.getBean(CommandMetadataCacheService.class).findCurrentCommandByCode(CODE)).thenReturn(wrong);
            assertEquals("COMMAND_TRANSACTION_DEFINITION_INVALID",assertThrows(IllegalStateException.class,
                    ()->context.getBean(CommandExecutor.class).execute(CODE,new CommandExecuteRequest())).getMessage());
            assertEquals(0,context.getBean(Probe.class).calls);assertEquals(0,context.getBean(RecordingTransactions.class).begins);
        }
    }
    @Test void missingTenantDoesNotStartMetadataDiscoveryBeforeNormalAuthorization() {
        try(var context=context()) {
            MetaContext.clear();context.getBean(CommandExecutor.class).execute(CODE,new CommandExecuteRequest());
            verifyNoInteractions(context.getBean(CommandMetadataCacheService.class),context.getBean(CommandService.class));
            assertEquals(1,context.getBean(Probe.class).calls);
        }
    }
    @Test void absentOrNullHandlerConfigurationRetainsTheExistingDefaultTransaction() {
        try(var context=context()) {
            for(String config:new String[]{null," ","null","{}","{\"handlerParams\":null}"}) {
                definition(context,config);context.getBean(CommandExecutor.class).execute(CODE,new CommandExecuteRequest());
                assertNull(context.getBean(Probe.class).isolation);
            }
            assertEquals(5,context.getBean(Probe.class).calls);
        }
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @Profile("command-transaction-probe")
    @EnableAspectJAutoProxy
    @EnableTransactionManagement
    static class Config {
        @Bean CommandMetadataCacheService metadata() { return mock(CommandMetadataCacheService.class); }
        @Bean CommandService commands() { return mock(CommandService.class); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean RecordingTransactions transactionManager() { return new RecordingTransactions(); }
        @Bean Probe probe() { return new Probe(); }
        @Bean CommandExecutor executor(Probe probe) { return new ProbeExecutor(probe); }
        @Bean CommandTransactionIsolationAspect policy(CommandMetadataCacheService metadata,CommandService commands,ObjectMapper json,RecordingTransactions tx) {
            return new CommandTransactionIsolationAspect(metadata,commands,json,tx);
        }
    }
    static class Probe { int calls;Integer isolation;boolean transactionActive;boolean fail;RuntimeException failure; }
    public static class ProbeExecutor implements CommandExecutor {
        private final Probe probe;
        ProbeExecutor(Probe probe) { this.probe=probe; }
        @Transactional public CommandExecuteResult execute(String code,CommandExecuteRequest request) {
            probe.calls++;probe.isolation=TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            probe.transactionActive=TransactionSynchronizationManager.isActualTransactionActive();
            if(probe.fail)throw new IllegalStateException("PROBE_FAILURE");
            if(probe.failure!=null)throw probe.failure;
            return CommandExecuteResult.builder().commandCode(code).phaseReached("completion").build();
        }
    }
    static final class RecordingTransactions extends AbstractPlatformTransactionManager {
        private final ThreadLocal<Tx> current=new ThreadLocal<>();int begins,commits,rollbacks;
        protected Object doGetTransaction() { var tx=current.get();return tx==null?new Tx():tx; }
        protected boolean isExistingTransaction(Object transaction) { return ((Tx)transaction).active; }
        protected void doBegin(Object transaction,TransactionDefinition definition) { var tx=(Tx)transaction;tx.active=true;current.set(tx);begins++; }
        protected void doCommit(DefaultTransactionStatus status) { commits++; }
        protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
        protected void doSetRollbackOnly(DefaultTransactionStatus status) { ((Tx)status.getTransaction()).rollbackOnly=true; }
        protected void doCleanupAfterCompletion(Object transaction) { ((Tx)transaction).active=false;current.remove(); }
        private static final class Tx implements SmartTransactionObject {
            boolean active,rollbackOnly;
            public boolean isRollbackOnly() { return rollbackOnly; }
            public void flush() {}
        }
    }
}
