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

/** Real PostgreSQL rollback of public-API-created approvals; only the post-insert fault is synthetic. */
class AgentApprovalRollbackIT {
    @Configuration @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}
    static class FaultPublisher extends BehaviorOutcomePublisher {
        private boolean failing;
        private int injected;
        FaultPublisher(BehaviorOutcomeOutboxMapper mapper) { super(mapper, new ObjectMapper()); }
        public void setFailing(boolean value) { failing = value; }
        public int injectedCount() { return injected; }
        @Override @Transactional(propagation = Propagation.MANDATORY)
        public boolean publish(BehaviorOutcomeEvent event) {
            boolean inserted = super.publish(event);
            if (failing) {
                assertThat(inserted).isTrue();
                injected++;
                throw new IllegalStateException("injected after actual approval terminal insert");
            }
            return inserted;
        }
    }

    @Test void failedTerminalInsertRollsBackDecisionThenSameFixtureCanCommit() throws Exception {
        var json = new ObjectMapper();
        var fixtures = json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(
                Objects.requireNonNull(System.getenv("AURA_APPROVAL_ROLLBACK_FIXTURES")))));
        assertThat(fixtures.size()).isEqualTo(2);
        DataSource ds = new UnpooledDataSource("org.postgresql.Driver",
                Objects.requireNonNull(System.getenv("TEST_DATABASE_URL")),
                Objects.requireNonNull(System.getenv("TEST_DATABASE_USERNAME")), System.getenv("TEST_DATABASE_PASSWORD"));
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("approval-rollback-it", new SpringManagedTransactionFactory(), ds));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(DynamicDataMapper.class);
        config.addMapper(BehaviorOutcomeOutboxMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var data = session.getMapper(DynamicDataMapper.class);
        var jdbc = new JdbcTemplate(ds);
        AtomicInteger notifications = new AtomicInteger();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(ds));
            context.registerBean(FaultPublisher.class, () -> new FaultPublisher(session.getMapper(BehaviorOutcomeOutboxMapper.class)));
            context.registerBean(AgentRunTerminalStore.class, () -> new AgentRunTerminalStore(jdbc, data, context.getBean(FaultPublisher.class)));
            context.registerBean(com.auraboot.framework.event.AuraEventBus.class,
                    () -> new com.auraboot.framework.event.AuraEventBus(context));
            context.registerBean(AgentApprovalGateService.class, () -> new AgentApprovalGateService(data, json,
                    context.getBean(com.auraboot.framework.event.AuraEventBus.class), null));
            context.addApplicationListener(event -> {
                if (event instanceof com.auraboot.framework.agent.event.AgentApprovalEvent) notifications.incrementAndGet();
            });
            context.refresh();
            var gate = context.getBean(AgentApprovalGateService.class);
            var publisher = context.getBean(FaultPublisher.class);
            assertThat(AopUtils.isAopProxy(gate)).isTrue();
            assertThat(AopUtils.isAopProxy(context.getBean(AgentRunTerminalStore.class))).isTrue();
            for (var fixture : fixtures) {
                long tenant = Long.parseLong(fixture.path("tenantId").asText());
                long actor = Long.parseLong(fixture.path("actorId").asText());
                String pid = fixture.path("pid").asText();
                String run = fixture.path("run_id").asText();
                String task = fixture.path("task_id").asText();
                boolean expiry = "expired".equals(fixture.path("mode").asText());
                String stateSql = "SELECT a.approval_status, a.consumed_at, r.run_status, t.task_status "
                        + "FROM ab_agent_approval a JOIN ab_agent_run r ON r.pid=a.run_id AND r.tenant_id=a.tenant_id "
                        + "JOIN ab_agent_task t ON t.pid=a.task_id AND t.tenant_id=a.tenant_id "
                        + "WHERE a.tenant_id=? AND a.pid=? AND r.pid=? AND t.pid=?";
                String eventSql = "SELECT event_id, event_name, caused_by_event_id, payload::text AS payload "
                        + "FROM ab_behavior_outcome_outbox WHERE tenant_id=? AND run_id=? ORDER BY id";
                var before = jdbc.queryForMap(stateSql, tenant, pid, run, task);
                assertThat(before).containsEntry("approval_status", "pending").containsEntry("run_status", "pending");
                var events = jdbc.queryForList(eventSql, tenant, run);
                assertThat(events).hasSize(1);
                assertThat(events.get(0)).containsEntry("event_name", "agent_execution_started");
                if (expiry) {
                    // Refuse to run a global scheduler over unrelated pending expiry fixtures.
                    assertThat(jdbc.queryForList("SELECT pid FROM ab_agent_approval WHERE approval_status='pending' AND expires_at < NOW()", String.class))
                            .containsExactly(pid);
                }
                int notificationsBefore = notifications.get();
                int faultsBefore = publisher.injectedCount();
                publisher.setFailing(true);
                if (expiry) {
                    com.auraboot.framework.application.tenant.MetaContext.clear();
                    gate.enforceApprovalTimeouts();
                    assertThat(com.auraboot.framework.application.tenant.MetaContext.exists()).isFalse();
                } else {
                    com.auraboot.framework.application.tenant.MetaContext.setContext(tenant, actor, "rollback-fixture", "rollback-fixture");
                    assertThatThrownBy(() -> gate.reject(tenant, pid, actor, "Rollback fixture"))
                            .hasMessageContaining("injected after actual approval terminal insert");
                }
                assertThat(publisher.injectedCount()).isEqualTo(faultsBefore + 1);
                assertThat(jdbc.queryForMap(stateSql, tenant, pid, run, task)).isEqualTo(before);
                assertThat(jdbc.queryForList(eventSql, tenant, run)).isEqualTo(events);
                assertThat(notifications.get()).isEqualTo(notificationsBefore);
                publisher.setFailing(false);
                if (expiry) gate.enforceApprovalTimeouts();
                else gate.reject(tenant, pid, actor, "Rollback fixture");
                assertThat(jdbc.queryForMap(stateSql, tenant, pid, run, task))
                        .containsEntry("approval_status", expiry ? "expired" : "rejected")
                        .containsEntry("run_status", "failed").containsEntry("task_status", "blocked");
                var committedEvents = jdbc.queryForList(eventSql, tenant, run);
                assertThat(committedEvents).hasSize(2);
                assertThat(committedEvents.get(1)).containsEntry("event_name", "agent_execution_completed")
                        .containsEntry("caused_by_event_id", events.get(0).get("event_id"));
                assertThat(notifications.get()).isEqualTo(notificationsBefore + 1);
            }
        } finally {
            com.auraboot.framework.application.tenant.MetaContext.clear();
        }
    }
}
