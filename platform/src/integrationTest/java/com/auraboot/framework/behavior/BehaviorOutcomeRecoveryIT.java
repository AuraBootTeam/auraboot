package com.auraboot.framework.behavior;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.ingest.*;
import com.auraboot.framework.behavior.mapper.*;
import com.auraboot.framework.behavior.outcome.*;
import com.auraboot.framework.infrastructure.mq.MqProvider;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real database transaction recovery; an injected transport boundary replaces the broker. */
class BehaviorOutcomeRecoveryIT {
    @Configuration @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}
    static class ProcessInterruption extends Error {}

    static class DeliveredThenInterrupted extends BehaviorIngestPublisher {
        final BehaviorEventPersister persister;
        final TransactionTemplate consumerTransaction;
        final List<Integer> storedOrDuplicateCounts = new ArrayList<>();
        final List<String> deliveredIds = new ArrayList<>();
        boolean interrupt = true;

        DeliveredThenInterrupted(BehaviorEventPersister persister, DataSourceTransactionManager manager) {
            super(mock(MqProvider.class), new ObjectMapper(), BehaviorIngestMetrics.noop());
            this.persister = persister;
            consumerTransaction = new TransactionTemplate(manager);
            consumerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        @Override public int publish(long tenantId, Long userId, List<BehaviorEventInput> events) {
            assertThat(events).hasSize(1);
            storedOrDuplicateCounts.add(consumerTransaction.execute(status ->
                    persister.persistBatch(new BehaviorIngestEnvelope(tenantId, userId, events))));
            deliveredIds.add(events.get(0).getEventId());
            if (interrupt) {
                interrupt = false;
                throw new ProcessInterruption();
            }
            return events.size();
        }
    }

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate evidence;
    private BehaviorOutcomePublisher publisher;
    private BehaviorOutcomeRelay relay;
    private DeliveredThenInterrupted transport;
    private BehaviorIngestPublisher quarantine;
    private TransactionTemplate transaction;
    private long tenant;
    private String eventId;

    @BeforeEach void setup() {
        DataSource ds = new UnpooledDataSource("org.postgresql.Driver",
                Objects.requireNonNull(System.getenv("TEST_DATABASE_URL")),
                Objects.requireNonNull(System.getenv("TEST_DATABASE_USERNAME")), System.getenv("TEST_DATABASE_PASSWORD"));
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("outcome-recovery-it", new SpringManagedTransactionFactory(), ds));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(BehaviorOutcomeOutboxMapper.class);
        config.addMapper(BehaviorEventMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var manager = new DataSourceTransactionManager(ds);
        var mapper = session.getMapper(BehaviorOutcomeOutboxMapper.class);
        var json = new ObjectMapper();
        quarantine = mock(BehaviorIngestPublisher.class);
        var persister = new BehaviorEventPersister(session.getMapper(BehaviorEventMapper.class),
                quarantine, json, BehaviorIngestMetrics.noop());
        transport = new DeliveredThenInterrupted(persister, manager);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class, () -> manager);
        context.registerBean(BehaviorOutcomePublisher.class, () -> new BehaviorOutcomePublisher(mapper, json));
        context.registerBean(BehaviorOutcomeRelay.class, () -> new BehaviorOutcomeRelay(mapper, transport, json));
        context.refresh();
        publisher = context.getBean(BehaviorOutcomePublisher.class);
        relay = context.getBean(BehaviorOutcomeRelay.class);
        assertThat(AopUtils.isAopProxy(relay)).isTrue();
        assertThat(AopUtils.isAopProxy(publisher)).isTrue();
        transaction = new TransactionTemplate(manager);
        evidence = new JdbcTemplate(ds); // Read-only database assertions; no fixture DDL or SQL writes.
        tenant = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        eventId = UUID.randomUUID().toString();
        MetaContext.setContext(tenant, 7L, "outcome-recovery", "outcome-recovery");
        assertThat(evidence.queryForObject("SELECT count(*) FROM ab_behavior_outcome_outbox WHERE status='pending' AND next_attempt_at <= NOW()", Long.class))
                .as("The isolated runtime relay must be stopped and its previous queue drained").isZero();
    }

    @AfterEach void close() {
        if (context != null) context.close();
        MetaContext.clear();
        if (quarantine != null) verifyNoInteractions(quarantine);
    }

    @Test void deliveredEventSurvivesSenderRollbackAndRedeliveryIsIdempotent() {
        var outcome = BehaviorOutcomeEvent.builder().tenantId(tenant).userId(7L).eventId(eventId)
                .eventName("agent_execution_completed").targetType("agent_run").targetKey("recovery-run")
                .runId("recovery-run").causedByEventId("recovery-start").occurredAt(Instant.now())
                .props(Map.of("taskPid", "recovery-task", "status", "success", "principalType", "human_delegated"))
                .build();
        transaction.executeWithoutResult(status -> assertThat(publisher.publish(outcome)).isTrue());
        assertThatThrownBy(() -> relay.publishPending(1)).isInstanceOf(ProcessInterruption.class);
        assertThat(outboxStatus()).isEqualTo("pending");
        assertThat(eventCount()).isEqualTo(1);
        assertThat(relay.publishPending(1)).isEqualTo(1);
        assertThat(outboxStatus()).isEqualTo("published");
        assertThat(relay.publishPending(1)).isZero();
        // The persister acknowledges both new and already stored events; SQL proves uniqueness.
        assertThat(transport.storedOrDuplicateCounts).containsExactly(1, 1);
        assertThat(transport.deliveredIds).containsExactly(eventId, eventId);
        assertThat(eventCount()).isEqualTo(1);
        var saved = evidence.queryForMap("SELECT source, producer_name, run_id, caused_by_event_id, props->>'targetKey' AS target, props->>'status' AS status FROM ab_behavior_event WHERE tenant_id=? AND event_id=?", tenant, eventId);
        assertThat(saved).containsEntry("source", "server").containsEntry("producer_name", "server-outcome-outbox")
                .containsEntry("run_id", "recovery-run").containsEntry("caused_by_event_id", "recovery-start")
                .containsEntry("target", "recovery-run").containsEntry("status", "success");
    }

    private String outboxStatus() {
        return evidence.queryForObject("SELECT status FROM ab_behavior_outcome_outbox WHERE tenant_id=? AND event_id=?", String.class, tenant, eventId);
    }
    private long eventCount() {
        return evidence.queryForObject("SELECT count(*) FROM ab_behavior_event WHERE tenant_id=? AND event_id=?", Long.class, tenant, eventId);
    }
}
