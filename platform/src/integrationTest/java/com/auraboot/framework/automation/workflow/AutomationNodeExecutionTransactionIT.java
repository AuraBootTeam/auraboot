package com.auraboot.framework.automation.workflow;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.AutomationNodeExecution;
import com.auraboot.framework.automation.executor.impl.SendWebhookExecutor;
import com.auraboot.framework.automation.mapper.AutomationNodeExecutionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL transactions, production mapper/executor, and a real HTTP receiver. */
class AutomationNodeExecutionTransactionIT {
    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TransactionTemplate businessTransaction;
    private AutomationNodeExecutionMapper mapper;
    private AutomationActionAccessorImpl accessor;
    private HttpServer receiver;
    private final AtomicInteger received = new AtomicInteger();
    private volatile int upstreamStatus = 200;

    @BeforeEach
    void setUp() throws Exception {
        String runtime = required("AURA_RUNTIME_NAME");
        assertThat(required("AURA_RUNTIME_REPO")).isEqualTo("auraboot");
        String slot = required("AURA_WORKSPACE_SLOT");
        assertThat(slot).matches("[0-9]+");
        String database = "auraboot_" + slot;
        assertThat(required("POSTGRES_DB")).isEqualTo(database);
        var ds = new PGSimpleDataSource();
        ds.setServerNames(new String[]{required("POSTGRES_HOST")});
        ds.setPortNumbers(new int[]{Integer.parseInt(required("POSTGRES_PORT"))});
        ds.setDatabaseName(required("POSTGRES_DB"));
        ds.setUser(required("POSTGRES_USER"));
        ds.setPassword(required("POSTGRES_PASSWORD"));
        var schema = "node_audit_it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(ds).execute("CREATE SCHEMA " + schema);
        ds.setCurrentSchema(schema);
        jdbc = new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(database);
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);
        jdbc.execute(Files.readString(Path.of(
                "src/main/resources/db/migration/core/V20260912010000__automation_node_execution.sql")));
        jdbc.execute("CREATE TABLE business_write (id int PRIMARY KEY)");
        System.out.println("node-audit fixture runtime=" + runtime + " database=" + database + " schema=" + schema);

        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("node-audit-it", new SpringManagedTransactionFactory(), ds));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(AutomationNodeExecutionMapper.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        mapper = sessions.getMapper(AutomationNodeExecutionMapper.class);
        var manager = new DataSourceTransactionManager(ds);
        businessTransaction = new TransactionTemplate(manager);
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/receiver", exchange -> {
            exchange.getRequestBody().readAllBytes();
            received.incrementAndGet();
            exchange.sendResponseHeaders(upstreamStatus, -1);
            exchange.close();
        });
        receiver.start();

        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class, () -> manager);
        context.registerBean(AutomationNodeExecutionMapper.class, () -> mapper);
        context.registerBean("compositeActionExecutor", SendWebhookExecutor.class,
                () -> new SendWebhookExecutor(new ObjectMapper()));
        context.registerBean(AutomationNodeExecutionRecorder.class);
        context.registerBean(AutomationActionAccessorImpl.class);
        context.refresh();
        assertThat(AopUtils.isAopProxy(context.getBean(AutomationNodeExecutionRecorder.class))).isTrue();
        accessor = context.getBean(AutomationActionAccessorImpl.class);
        MetaContext.setContext(7L, 11L, "user-11", "Automation Owner");
        MetaContext.setMemberId(22L);
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (receiver != null) receiver.stop(0);
        MetaContext.clear();
        // Fixture schemas and rows remain in the owned runtime database as evidence.
    }

    @Test
    void failedHttpActionRollsBackBusinessWriteButRetainsFailedNodeAndTenant() {
        upstreamStatus = 500;
        assertThatThrownBy(() -> businessTransaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO business_write VALUES (1)");
            accessor.execute("webhook", "process-1", variables());
        })).hasMessageContaining("Webhook POST failed with status 500");

        assertThat(received.get()).isEqualTo(1);
        assertThat(businessRows()).isZero();
        var node = onlyNode();
        assertThat(node.getStatus()).isEqualTo("failed");
        assertThat(node.getErrorMessage()).contains("status 500");
        assertThat(node.getTenantId()).isEqualTo(7L);
        assertThat(node.getAutomationLogId()).isEqualTo(42L);
        assertThat(node.getAutomationId()).isEqualTo("AUTO-1");
        assertThat(node.getProcessInstanceId()).isEqualTo("process-1");
        assertThat(node.getNodeId()).isEqualTo("webhook");
        assertThat(node.getStartedAt()).isNotNull();
        assertThat(node.getCompletedAt()).isAfterOrEqualTo(node.getStartedAt());
        assertThat(mapper.findByLogIdAndTenant(8L, 42L)).isEmpty();
        assertThat(mapper.findByProcessInstanceIdAndTenant(8L, "process-1")).isEmpty();
    }

    @Test
    void successfulActionCommitsBusinessWriteAndCompletedNode() {
        businessTransaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO business_write VALUES (1)");
            accessor.execute("webhook", "process-1", variables());
        });

        assertThat(received.get()).isEqualTo(1);
        assertThat(businessRows()).isEqualTo(1);
        assertThat(onlyNode().getStatus()).isEqualTo("completed");
        assertThat(onlyNode().getErrorMessage()).isNull();
    }

    @Test
    void laterBusinessFailureKeepsAuditOfAlreadyCompletedAction() {
        assertThatThrownBy(() -> businessTransaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO business_write VALUES (1)");
            accessor.execute("webhook", "process-1", variables());
            throw new IllegalStateException("later action failed");
        })).hasMessage("later action failed");

        assertThat(received.get()).isEqualTo(1);
        assertThat(businessRows()).isZero();
        assertThat(onlyNode().getStatus()).isEqualTo("completed");
    }

    @Test
    void failedAuditInsertDoesNotAbortSuccessfulBusinessTransaction() {
        var vars = variables();
        vars.put(AutomationActionAccessorImpl.AUTOMATION_ID_VAR, "A".repeat(65));
        businessTransaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO business_write VALUES (1)");
            accessor.execute("webhook", "process-1", vars);
        });

        assertThat(received.get()).isEqualTo(1);
        assertThat(businessRows()).isEqualTo(1);
        assertThat(mapper.findByLogIdAndTenant(7L, 42L)).isEmpty();
    }

    @Test
    void alreadyAbortedBusinessTransactionStillRetainsOriginalHttpFailureAudit() {
        upstreamStatus = 500;
        assertThatThrownBy(() -> businessTransaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO business_write VALUES (1)");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO business_write VALUES (1)"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            accessor.execute("webhook", "process-1", variables());
        })).hasMessageContaining("Webhook POST failed with status 500");

        assertThat(received.get()).isEqualTo(1);
        assertThat(businessRows()).isZero();
        assertThat(onlyNode().getStatus()).isEqualTo("failed");
        assertThat(onlyNode().getErrorMessage()).contains("status 500");
    }

    private Map<String, Object> variables() {
        Map<String, Object> vars = new HashMap<>();
        vars.put(AutomationActionAccessorImpl.ACTIONS_VAR, Map.of("webhook", Map.of(
                "type", "send_webhook", "config", Map.of("url",
                        "http://127.0.0.1:" + receiver.getAddress().getPort() + "/receiver"))));
        vars.put(AutomationActionAccessorImpl.LOG_ID_VAR, 42L);
        vars.put(AutomationActionAccessorImpl.TENANT_ID_VAR, 7L);
        vars.put(AutomationActionAccessorImpl.USER_ID_VAR, 11L);
        vars.put(AutomationActionAccessorImpl.USER_PID_VAR, "user-11");
        vars.put(AutomationActionAccessorImpl.USERNAME_VAR, "Automation Owner");
        vars.put(AutomationActionAccessorImpl.MEMBER_ID_VAR, 22L);
        vars.put(AutomationActionAccessorImpl.AUTOMATION_ID_VAR, "AUTO-1");
        return vars;
    }

    private AutomationNodeExecution onlyNode() {
        List<AutomationNodeExecution> nodes = mapper.findByLogIdAndTenant(7L, 42L);
        assertThat(nodes).hasSize(1);
        return nodes.getFirst();
    }

    private int businessRows() {
        return jdbc.queryForObject("SELECT count(*) FROM business_write", Integer.class);
    }

    private static String required(String key) {
        String value = System.getenv(key);
        assertThat(value).as("Managed runtime must supply " + key).isNotBlank();
        return value;
    }
}
