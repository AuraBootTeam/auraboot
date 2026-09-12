package com.auraboot.framework.agent.service;

import com.auraboot.framework.behavior.mapper.BehaviorOutcomeOutboxMapper;
import com.auraboot.framework.behavior.outcome.BehaviorOutcomePublisher;
import com.auraboot.framework.behavior.outcome.BehaviorOutcomeEvent;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Real Spring transaction proxies and production MyBatis mappers against a migrated PostgreSQL database. */
class AgentRunTerminalStoreIT {
    @Configuration @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}
    static class FaultPublisher extends BehaviorOutcomePublisher {
        boolean failAfterInsert;
        FaultPublisher(BehaviorOutcomeOutboxMapper mapper) { super(mapper, new ObjectMapper()); }
        @Override @Transactional(propagation = Propagation.MANDATORY)
        public boolean publish(BehaviorOutcomeEvent event) {
            boolean inserted = super.publish(event);
            if (failAfterInsert) throw new IllegalStateException("injected after real outbox insert");
            return inserted;
        }
        public void failNextInsert() { failAfterInsert = true; }
    }
    private AnnotationConfigApplicationContext context;
    private AgentRunTerminalStore store;
    private JdbcTemplate jdbc;
    private DynamicDataMapper data;
    private long tenant;
    private String runPid;
    private String taskPid;
    private final Map<String,Object> runUpdate = Map.of("run_status", "success");
    private final Map<String,Object> taskUpdate = Map.of("task_status", "done");
    @BeforeEach void setup() {
        DataSource ds = new UnpooledDataSource("org.postgresql.Driver",
                Objects.requireNonNull(System.getenv("TEST_DATABASE_URL")),
                Objects.requireNonNull(System.getenv("TEST_DATABASE_USERNAME")), System.getenv("TEST_DATABASE_PASSWORD"));
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("terminal-it", new SpringManagedTransactionFactory(), ds));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(DynamicDataMapper.class);
        config.addMapper(BehaviorOutcomeOutboxMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        data = session.getMapper(DynamicDataMapper.class);
        jdbc = new JdbcTemplate(ds);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        context.registerBean(FaultPublisher.class, () -> new FaultPublisher(session.getMapper(BehaviorOutcomeOutboxMapper.class)));
        context.registerBean(AgentRunTerminalStore.class, () -> new AgentRunTerminalStore(jdbc, data, context.getBean(FaultPublisher.class)));
        context.refresh();
        store = context.getBean(AgentRunTerminalStore.class);
        assertThat(AopUtils.isAopProxy(store)).isTrue();
        tenant = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        taskPid = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        runPid = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        assertThat(data.insert("ab_agent_task", Map.of("tenant_id", tenant, "pid", taskPid,
                "title", "Terminal transaction fixture", "task_status", "in_progress"))).isEqualTo(1);
        assertThat(data.insert("ab_agent_run", Map.of("tenant_id", tenant, "pid", runPid,
                "task_id", taskPid, "agent_id", "terminal-fixture", "run_status", "running",
                "actor_user_id", 91L, "principal_type", "human_delegated"))).isEqualTo(1);
    }
    @AfterEach void close() { if (context != null) context.close(); com.auraboot.framework.application.tenant.MetaContext.clear(); }
    private String runStatus() { return jdbc.queryForObject("SELECT run_status FROM ab_agent_run WHERE tenant_id=? AND pid=?", String.class, tenant, runPid); }
    private String taskStatus() { return jdbc.queryForObject("SELECT task_status FROM ab_agent_task WHERE tenant_id=? AND pid=?", String.class, tenant, taskPid); }
    private int events() { return jdbc.queryForObject("SELECT count(*) FROM ab_behavior_outcome_outbox WHERE tenant_id=? AND run_id=?", Integer.class, tenant, runPid); }
    @Test void lifecycleCreationCommitsRunAndScopedTaskWithoutInventingStartFact() {
        String attempt = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        data.update("ab_agent_task", Map.of("task_status", "todo"), Map.of("tenant_id", tenant, "pid", taskPid));
        lifecycle(new AtomicInteger()).createRunRecord(tenant, attempt, taskPid,
                "terminal-fixture", "fixture-model", java.time.LocalDateTime.now());
        assertThat(jdbc.queryForMap("SELECT task_id, run_status, run_model FROM ab_agent_run WHERE tenant_id=? AND pid=?",
                tenant, attempt)).containsEntry("task_id", taskPid).containsEntry("run_status", "running")
                .containsEntry("run_model", "fixture-model");
        assertThat(taskStatus()).isEqualTo("in_progress");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_behavior_outcome_outbox WHERE tenant_id=? AND run_id=?",
                Integer.class, tenant, attempt)).isZero();
    }

    @Test void lifecycleCreationRollbackRemovesRunAndRestoresTask() {
        String attempt = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        data.update("ab_agent_task", Map.of("task_status", "todo"), Map.of("tenant_id", tenant, "pid", taskPid));
        var tx = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            lifecycle(new AtomicInteger()).createRunRecord(tenant, attempt, taskPid,
                    "terminal-fixture", "fixture-model", java.time.LocalDateTime.now());
            throw new IllegalStateException("injected after run/task creation");
        })).hasMessage("injected after run/task creation");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_agent_run WHERE tenant_id=? AND pid=?",
                Integer.class, tenant, attempt)).isZero();
        assertThat(taskStatus()).isEqualTo("todo");
    }

    @Test void taskWriteFailureRollsBackInsertedRunThroughStoreProxy() {
        String attempt = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        assertThatThrownBy(() -> store.create(tenant, attempt, taskPid,
                Map.of("tenant_id", tenant, "pid", attempt, "task_id", taskPid,
                        "agent_id", "terminal-fixture", "run_status", "running"),
                Map.of("task_status", "x".repeat(1000))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_agent_run WHERE tenant_id=? AND pid=?",
                Integer.class, tenant, attempt)).isZero();
        assertThat(taskStatus()).isEqualTo("in_progress");
    }

    @Test void lifecycleCreationRejectsForeignTaskWithoutOrphanRun() {
        String attempt = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        assertThatThrownBy(() -> lifecycle(new AtomicInteger()).createRunRecord(tenant + 1, attempt, taskPid,
                "terminal-fixture", "fixture-model", java.time.LocalDateTime.now()))
                .hasMessage("Run task is unavailable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_agent_run WHERE pid=?", Integer.class, attempt)).isZero();
        assertThat(taskStatus()).isEqualTo("in_progress");
    }

    @Test void lifecycleCreationRejectsDuplicateRunBeforeChangingTask() {
        data.update("ab_agent_task", Map.of("task_status", "todo"), Map.of("tenant_id", tenant, "pid", taskPid));
        assertThatThrownBy(() -> lifecycle(new AtomicInteger()).createRunRecord(tenant, runPid, taskPid,
                "terminal-fixture", "fixture-model", java.time.LocalDateTime.now()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(taskStatus()).isEqualTo("todo");
        assertThat(runStatus()).isEqualTo("running");
        assertThat(events()).isZero();
    }

    @Test void readsWinningAttemptResponseAndRejectsWrongScopeOrNonterminalState() {
        RunLifecycleService lifecycle = lifecycle(new AtomicInteger());
        assertThatThrownBy(() -> lifecycle.readTerminalOutcome(tenant, runPid, taskPid))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("terminal state");
        var result = new AgentRunService.AgentLoopResult();
        result.success = true;
        result.lastResponse = "Persisted answer";
        result.totalInputTokens = 13;
        result.totalOutputTokens = 5;
        result.totalCost = 0.0012;
        assertThat(lifecycle.completeRunRecord(tenant, runPid, taskPid,
                java.time.LocalDateTime.now(), result, "fixture")).isTrue();
        data.update("ab_agent_task", Map.of("output_data", "Later task output"),
                Map.of("tenant_id", tenant, "pid", taskPid));
        assertThat(lifecycle.readTerminalOutcome(tenant, runPid, taskPid))
                .isEqualTo(new RunOutcome.Success(runPid, "Persisted answer", 13, 5, 0.0012));
        assertThatThrownBy(() -> lifecycle.readTerminalOutcome(tenant + 1, runPid, taskPid))
                .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
        assertThatThrownBy(() -> lifecycle.readTerminalOutcome(tenant, runPid, "unrelated"))
                .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
    }

    @Test void commitsPairAndOutboxOnceWithPersistedActor() {
        AtomicInteger signals = new AtomicInteger();
        assertThat(store.complete(tenant, runPid, taskPid, runUpdate, taskUpdate, signals::incrementAndGet)).isTrue();
        assertThat(runStatus()).isEqualTo("success"); assertThat(taskStatus()).isEqualTo("done");
        assertThat(events()).isEqualTo(1); assertThat(signals.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_id FROM ab_behavior_outcome_outbox WHERE tenant_id=? AND run_id=?", Long.class, tenant, runPid)).isEqualTo(91L);
        assertThat(store.complete(tenant, runPid, taskPid, runUpdate, taskUpdate, signals::incrementAndGet)).isFalse();
        assertThat(events()).isEqualTo(1); assertThat(signals.get()).isEqualTo(1);
    }
    @Test void outerRollbackRemovesAllWritesAndSuppressesSignal() {
        AtomicInteger signals = new AtomicInteger();
        var tx = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            store.complete(tenant, runPid, taskPid, runUpdate, taskUpdate, signals::incrementAndGet);
            throw new IllegalStateException("rollback after outcome insert");
        })).hasMessage("rollback after outcome insert");
        assertThat(runStatus()).isEqualTo("running"); assertThat(taskStatus()).isEqualTo("in_progress");
        assertThat(events()).isZero(); assertThat(signals.get()).isZero();
    }
    @Test void outboxFailureRollsBackAlreadyWrittenRunAndTask() {
        context.getBean(FaultPublisher.class).failNextInsert();
        assertThatThrownBy(() -> store.complete(tenant, runPid, taskPid, runUpdate, taskUpdate, () -> fail("must not signal")))
                .hasMessage("injected after real outbox insert");
        assertThat(runStatus()).isEqualTo("running"); assertThat(taskStatus()).isEqualTo("in_progress"); assertThat(events()).isZero();
    }
    @Test void wrongTenantCannotChangeExistingPair() {
        assertThatThrownBy(() -> store.complete(tenant + 1, runPid, taskPid, runUpdate, taskUpdate, () -> fail("must not signal")))
                .hasMessage("Run/task relationship is unavailable");
        assertThat(runStatus()).isEqualTo("running"); assertThat(taskStatus()).isEqualTo("in_progress"); assertThat(events()).isZero();
    }
    @Test void competingTerminalStatesCommitOnlyOneOutcome() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger signals = new AtomicInteger();
        try {
            var success = pool.submit(() -> {
                ready.countDown(); start.await();
                return store.complete(tenant, runPid, taskPid, runUpdate, taskUpdate, signals::incrementAndGet);
            });
            var failed = pool.submit(() -> {
                ready.countDown(); start.await();
                return store.complete(tenant, runPid, taskPid, Map.of("run_status", "failed"),
                        Map.of("task_status", "blocked"), signals::incrementAndGet);
            });
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(success.get(10, java.util.concurrent.TimeUnit.SECONDS), failed.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(events()).isEqualTo(1); assertThat(signals.get()).isEqualTo(1);
            String status = runStatus();
            assertThat(taskStatus()).isEqualTo("success".equals(status) ? "done" : "blocked");
            assertThat(jdbc.queryForObject("SELECT payload->>'status' FROM ab_behavior_outcome_outbox WHERE tenant_id=? AND run_id=?", String.class, tenant, runPid))
                    .isEqualTo(status);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    private RunLifecycleService lifecycle(AtomicInteger signals) {
        return new RunLifecycleService(data, new ObjectMapper(), null, null, null, jdbc,
                event -> signals.incrementAndGet(), store);
    }
    @Test void exceptionalRunFailureCommitsStatusDiagnosticAndOutcome() {
        AtomicInteger signals = new AtomicInteger();
        String childPid = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        assertThat(data.insert("ab_agent_task", Map.of("tenant_id", tenant, "pid", childPid,
                "parent_id", taskPid, "title", "Pending child", "task_status", "todo"))).isEqualTo(1);
        lifecycle(signals).failRun(tenant, runPid, taskPid, java.time.LocalDateTime.now().minusSeconds(1), "tool failed");
        assertThat(runStatus()).isEqualTo("failed");
        assertThat(taskStatus()).isEqualTo("blocked");
        assertThat(events()).isEqualTo(1); assertThat(signals.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT task_status FROM ab_agent_task WHERE tenant_id=? AND pid=?", String.class, tenant, childPid))
                .isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT error_message FROM ab_agent_run WHERE tenant_id=? AND pid=?", String.class, tenant, runPid))
                .isEqualTo("tool failed");
        assertThat(jdbc.queryForObject("SELECT payload->>'status' FROM ab_behavior_outcome_outbox WHERE tenant_id=? AND run_id=?", String.class, tenant, runPid))
                .isEqualTo("failed");
    }
    @Test void lateFailureCannotOverwriteCommittedSuccess() {
        AtomicInteger signals = new AtomicInteger();
        assertThat(store.complete(tenant, runPid, taskPid, runUpdate, taskUpdate, signals::incrementAndGet)).isTrue();
        lifecycle(signals).failRun(tenant, runPid, taskPid, java.time.LocalDateTime.now(), "late error");
        assertThat(runStatus()).isEqualTo("success"); assertThat(taskStatus()).isEqualTo("done");
        assertThat(events()).isEqualTo(1); assertThat(signals.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT error_message FROM ab_agent_run WHERE tenant_id=? AND pid=?", String.class, tenant, runPid)).isNull();
    }

    private InterruptDispatcher dispatcher(AtomicInteger signals) {
        com.auraboot.framework.application.tenant.MetaContext.setContext(tenant, 92L, "terminal-requester", "terminal-requester");
        return new InterruptDispatcher(jdbc, event -> signals.incrementAndGet(), lifecycle(signals), null);
    }
    private InterruptClassifier.Classification replaceIntent() {
        return InterruptClassifier.Classification.builder().subPolicy(InterruptClassifier.REPLACE_INTENT)
                .tier("keyword").confidence(1.0).reason("transaction fixture").build();
    }
    @Test void interruptCancellationCommitsTaskRunAndOutcomeAndLogsActualAction() {
        AtomicInteger signals = new AtomicInteger();
        var result = dispatcher(signals).dispatch(tenant, taskPid, runPid, "stop", replaceIntent());
        assertThat(result.getActionTaken()).isEqualTo("cancelled_run");
        assertThat(runStatus()).isEqualTo("cancelled"); assertThat(taskStatus()).isEqualTo("cancelled");
        assertThat(events()).isEqualTo(1); assertThat(signals.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT action_taken FROM ab_agent_interrupt_log WHERE tenant_id=? AND pid=?", String.class, tenant, result.getInterruptLogPid()))
                .isEqualTo("cancelled_run");
        assertThat(store.complete(tenant, runPid, taskPid, runUpdate, taskUpdate, signals::incrementAndGet)).isFalse();
        assertThat(runStatus()).isEqualTo("cancelled"); assertThat(events()).isEqualTo(1);
    }
    @Test void cancellationAfterSuccessAndWrongTenantReportNoop() {
        AtomicInteger signals = new AtomicInteger();
        assertThat(dispatcher(signals).dispatch(tenant + 1, taskPid, runPid, "foreign stop", replaceIntent()).getActionTaken()).isEqualTo("noop");
        assertThat(runStatus()).isEqualTo("running"); assertThat(events()).isZero();
        assertThat(store.complete(tenant, runPid, taskPid, runUpdate, taskUpdate, signals::incrementAndGet)).isTrue();
        var result = dispatcher(signals).dispatch(tenant, taskPid, runPid, "late stop", replaceIntent());
        assertThat(result.getActionTaken()).isEqualTo("noop");
        assertThat(jdbc.queryForObject("SELECT action_taken FROM ab_agent_interrupt_log WHERE tenant_id=? AND pid=?", String.class, tenant, result.getInterruptLogPid()))
                .isEqualTo("noop");
        assertThat(dispatcher(signals).dispatch(tenant + 1, taskPid, runPid, "foreign stop", replaceIntent()).getActionTaken()).isEqualTo("noop");
        assertThat(runStatus()).isEqualTo("success"); assertThat(taskStatus()).isEqualTo("done");
        assertThat(events()).isEqualTo(1); assertThat(signals.get()).isEqualTo(1);
    }
    @Test void cancellationCompetingWithSuccessPreservesWinningPairAndAudit() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger signals = new AtomicInteger();
        try {
            var success = pool.submit(() -> { ready.countDown(); start.await();
                return store.complete(tenant, runPid, taskPid, runUpdate, taskUpdate, signals::incrementAndGet); });
            var cancellation = pool.submit(() -> { ready.countDown(); start.await();
                return dispatcher(signals).dispatch(tenant, taskPid, runPid, "stop", replaceIntent()); });
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); start.countDown();
            boolean won = success.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var result = cancellation.get(10, java.util.concurrent.TimeUnit.SECONDS);
            String status = won ? "success" : "cancelled";
            assertThat(runStatus()).isEqualTo(status);
            assertThat(taskStatus()).isEqualTo(won ? "done" : "cancelled");
            assertThat(result.getActionTaken()).isEqualTo(won ? "noop" : "cancelled_run");
            assertThat(events()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT payload->>'status' FROM ab_behavior_outcome_outbox WHERE tenant_id=? AND run_id=?", String.class, tenant, runPid)).isEqualTo(status);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

}
