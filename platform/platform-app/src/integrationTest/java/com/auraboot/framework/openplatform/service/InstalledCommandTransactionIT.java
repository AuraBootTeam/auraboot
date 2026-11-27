package com.auraboot.framework.openplatform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.CommandExecutionTransaction;
import com.auraboot.framework.openplatform.mapper.*;
import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.test.util.OwnedTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual Spring service proxies and PostgreSQL; binding/policy reads are explicit fixture seams. */
class InstalledCommandTransactionIT {
    private DriverManagerDataSource source;
    private DataSourceTransactionManager transactions;
    private JdbcTemplate jdbc;
    private CommandExecutionTransaction commands;
    private String schema;
    private final AtomicInteger callbacks = new AtomicInteger();

    @BeforeEach void prepare() {
        String url = System.getenv("TEST_DATABASE_URL");
        OwnedTestDatabase.require(url, OwnedTestDatabase.AURABOOT_SLOT_PATTERN, "An owned slot database is required");
        source = new DriverManagerDataSource(url, System.getenv("TEST_DATABASE_USERNAME"),
                System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        transactions = new DataSourceTransactionManager(source);
        jdbc = new JdbcTemplate(source);
        schema = "installed_command_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("CREATE TABLE " + schema + ".probe(id integer PRIMARY KEY)");
        var extensions = mock(ExtensionRegistry.class);
        var handler = mock(CommandHandlerExtension.class);
        when(handler.commandReadIsolation()).thenReturn(BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        when(extensions.getBusinessResourceOwner("fixture.status")).thenReturn(Optional.empty());
        when(extensions.getCommandHandler("fixture.status")).thenReturn(Optional.of(handler));
        when(extensions.getSecondaryCommandHandlers("fixture.status")).thenReturn(List.of());
        commands = new CommandExecutionTransaction(transactions, extensions);
    }

    @AfterEach void clear() {
        MetaContext.clear();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        if (jdbc != null && schema != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private MachinePrincipal principal() {
        return new MachinePrincipal(1L, "fixture", "credential", Set.of("fixture.scope"),
                "application", "installation", null, null);
    }

    private <T> T proxied(T target, Class<T> type) {
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice);
        return type.cast(proxy.getProxy());
    }

    private void verifyPhysicalSnapshotAndJoinCommand() {
        callbacks.incrementAndGet();
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        assertEquals("repeatable read", jdbc.queryForObject("SHOW transaction_isolation", String.class));
        var connection = TransactionSynchronizationManager.getResource(source);
        assertNotNull(connection);
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, jdbc.execute(
                (ConnectionCallback<Integer>) current -> current.getTransactionIsolation()).intValue());
        int value = commands.execute("fixture.status", false, () -> {
            assertSame(connection, TransactionSynchronizationManager.getResource(source));
            jdbc.update("INSERT INTO " + schema + ".probe(id) VALUES (17)");
            return jdbc.queryForObject("SELECT id FROM " + schema + ".probe", Integer.class);
        });
        assertEquals(17, value);
    }

    @Test void resultOpensThePhysicalSnapshotBeforeDispatchAndKeepsMissingReceiptDenialAtomic() {
        var receipts = mock(InstalledIntegrationReceiptMapper.class);
        when(receipts.findSnapshot(1L, "installation", "source", "event")).thenAnswer(invocation -> {
            assertEquals(1L, MetaContext.getCurrentTenantId());
            verifyPhysicalSnapshotAndJoinCommand();
            return null;
        });
        var service = new InstalledIntegrationService(mock(InstalledIntegrationRegistry.class),
                mock(ExtensionRegistry.class), mock(AuraPluginManager.class), mock(ApplicationInstallationMapper.class),
                mock(ApplicationScopeGrantMapper.class), mock(com.auraboot.framework.meta.mapper.CommandDefinitionMapper.class),
                mock(PluginRecordMapper.class), receipts, mock(CommandExecutor.class),
                mock(com.auraboot.framework.user.service.UserService.class),
                mock(com.auraboot.framework.tenant.service.TenantMemberService.class), new ObjectMapper(),
                mock(OpenPlatformSecretCodec.class));
        var proxy = proxied(service, InstalledIntegrationService.class);
        assertThrows(SecurityException.class, () -> proxy.result(principal(), "source", "event"));
        assertEquals(1, callbacks.get());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".probe", Integer.class));
    }

    @Test void resultSnapshotSurvivesConcurrentConsumerCompletionWithoutLockingItsReceipt() throws Exception {
        jdbc.execute("CREATE TABLE " + schema + ".ab_installed_integration_receipt ("
                + "tenant_id bigint NOT NULL,installation_pid text NOT NULL,source_code text NOT NULL,"
                + "external_event_id text NOT NULL,durable_event_id text NOT NULL,envelope_sha256 text NOT NULL,"
                + "binding_sha256 text NOT NULL,command_sha256 text NOT NULL,provider_sha256 text NOT NULL,"
                + "request_pid text,state text NOT NULL,updated_at timestamptz NOT NULL DEFAULT now(),"
                + "PRIMARY KEY(tenant_id,installation_pid,source_code,external_event_id))");
        jdbc.update("INSERT INTO " + schema + ".ab_installed_integration_receipt VALUES "
                + "(1,'installation','source','event','durable','envelope','binding','command','provider',"
                + "'request','accepted',now())");
        var properties = new java.util.Properties();
        properties.setProperty("currentSchema", schema);
        source.setConnectionProperties(properties);
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("receipt-snapshot-it",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(InstalledIntegrationReceiptMapper.class);
        var factory = new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
        var mapper = new org.mybatis.spring.SqlSessionTemplate(factory).getMapper(InstalledIntegrationReceiptMapper.class);
        var reads = mock(InstalledIntegrationReceiptMapper.class);
        var writer = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            when(reads.findSnapshot(1L, "installation", "source", "event")).thenAnswer(invocation -> {
                assertEquals("repeatable read", jdbc.queryForObject("SHOW transaction_isolation", String.class));
                assertEquals("accepted", jdbc.queryForObject("SELECT state FROM " + schema
                        + ".ab_installed_integration_receipt WHERE external_event_id='event'", String.class));
                // Commit on a different physical connection after the result transaction's snapshot.
                assertEquals(1, writer.submit(() -> new JdbcTemplate(source).update("UPDATE " + schema
                        + ".ab_installed_integration_receipt SET state='completed' WHERE external_event_id='event'"))
                        .get(5, java.util.concurrent.TimeUnit.SECONDS));
                var observed = mapper.findSnapshot(1L, "installation", "source", "event");
                assertEquals("accepted", observed.state());
                callbacks.incrementAndGet();
                return observed;
            });
            var service = new InstalledIntegrationService(mock(InstalledIntegrationRegistry.class),
                    mock(ExtensionRegistry.class), mock(AuraPluginManager.class), mock(ApplicationInstallationMapper.class),
                    mock(ApplicationScopeGrantMapper.class), mock(com.auraboot.framework.meta.mapper.CommandDefinitionMapper.class),
                    mock(PluginRecordMapper.class), reads, mock(CommandExecutor.class),
                    mock(com.auraboot.framework.user.service.UserService.class),
                    mock(com.auraboot.framework.tenant.service.TenantMemberService.class), new ObjectMapper(),
                    mock(OpenPlatformSecretCodec.class));
            var proxy = proxied(service, InstalledIntegrationService.class);
            // Authorization remains denied by the explicit absent-binding seam after the real receipt read.
            assertThrows(SecurityException.class, () -> proxy.result(principal(), "source", "event"));
            assertEquals(1, callbacks.get());
            verify(reads, never()).find(anyLong(), anyString(), anyString(), anyString());
            assertEquals("completed", jdbc.queryForObject("SELECT state FROM " + schema
                    + ".ab_installed_integration_receipt WHERE external_event_id='event'", String.class));
            assertEquals("completed", mapper.findSnapshot(1L, "installation", "source", "event").state());
        } finally {
            writer.shutdownNow();
            assertTrue(writer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void synchronousBusinessActionOpensTheSameSnapshotWithoutBypassingItsAuthorityFailure() {
        var publications = mock(OpenApiActionPublicationService.class);
        when(publications.authority(any(), eq("action"))).thenAnswer(invocation -> {
            verifyPhysicalSnapshotAndJoinCommand();
            throw new SecurityException("fixture-authority-denial");
        });
        var service = new OpenApiActionService(publications, mock(OpenApiActionValidator.class),
                mock(OpenApiResourceAuthorizer.class), mock(OpenApiResourceService.class),
                mock(PublicApiHandlerRegistry.class), mock(DynamicDataService.class), mock(CommandExecutor.class),
                mock(OpenApiPublicationMapper.class), mock(OpenApiIdempotencyMapper.class),
                mock(OpenApiProtocolTokenCodec.class), mock(OpenApiProtocolHash.class), new ObjectMapper());
        var proxy = proxied(service, OpenApiActionService.class);
        var denied = assertThrows(SecurityException.class,
                () -> proxy.execute(principal(), "action", null, null, null, java.util.Map.of()));
        assertEquals("fixture-authority-denial", denied.getMessage());
        assertEquals(1, callbacks.get());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".probe", Integer.class));
    }
}
