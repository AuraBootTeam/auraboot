package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.ReliableEventDeliveryExecutor;
import com.auraboot.framework.notification.dto.NotificationQueryRequest;
import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver;
import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver.Plan;
import com.auraboot.framework.notification.service.NotificationQueryService;
import com.auraboot.framework.plugin.extension.NotificationDeliveryAccessor.*;
import com.auraboot.framework.plugin.extension.integration.*;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/** Real migrated PostgreSQL, synthetic explicitly resolved recipients; no schema DDL or external transport. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}", "spring.data.redis.database=${TEST_REDIS_DATABASE}",
        "security.field-encryption.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
@ActiveProfiles("integration-test")
@Import(PluginNotificationDeliveryPostgresIT.PollerIsolation.class)
class PluginNotificationDeliveryPostgresIT {
    @TestConfiguration(proxyBeanMethods = false)
    static class PollerIsolation {
        @Bean static BeanFactoryPostProcessor preventTestAppAutomaticPollers() {
            return factory -> {
                if (!(factory instanceof BeanDefinitionRegistry registry))
                    throw new IllegalStateException("Native fixture cannot isolate scheduled pollers");
                String name = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
                if (registry.containsBeanDefinition(name)) registry.removeBeanDefinition(name);
            };
        }
    }
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ReliableIntegrationAccessor reliable;
    @Autowired ReliableEventDeliveryExecutor executor;
    @Autowired StrictInAppDeliveryWriter writer;
    @Autowired NotificationQueryService inbox;
    @Autowired ObjectMapper json;
    @Autowired PluginNotificationDeliveryAccessorImpl defaultHost;
    // Test-app scheduling isolation only: prevents startup from registering real global outbox poll tasks.
    @MockitoBean SchedulerEngine scheduler;
    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private final List<Long> tenantIds = new ArrayList<>(), userIds = new ArrayList<>(), memberIds = new ArrayList<>();
    private PluginNotificationDeliveryAccessorImpl host;
    private long tenant, otherTenant, user, otherUser;
    private String userPid, otherUserPid, sourceId;

    @BeforeEach void fixture() throws Exception {
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(System.getenv("TEST_DATABASE_URL")).isNotBlank();
        assertThat(System.getenv("TEST_REDIS_DATABASE")).isEqualTo("13");
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getCatalog()).isEqualTo("enterprise_22");
        }
        // INSERT only fresh IDs into the migrated schema; these are synthetic fixture facts, not real recipient policy.
        tenant = tenant(); otherTenant = tenant();
        userPid = UniqueIdGenerator.generate(); otherUserPid = UniqueIdGenerator.generate();
        user = user(userPid); otherUser = user(otherUserPid);
        member(tenant, user); member(otherTenant, otherUser);
        sourceId = UniqueIdGenerator.generate();
        NotificationDeliveryPlanResolver resolver = (id, ref) -> Optional.ofNullable(plans.get(ref));
        host = new PluginNotificationDeliveryAccessorImpl(List.of(resolver), reliable, writer);
        ReflectionTestUtils.setField(host, "enabled", true);
        MetaContext.setContext(tenant, user, userPid, "S06 synthetic fixture");
        assertThat(defaultHost.enqueue(new Request(tenant, "unknown", Channel.IN_APP)).reason()).isEqualTo(Reason.DISABLED);
    }
    @AfterEach void cleanup() {
        MetaContext.clear();
        // Never truncate or clean a shared table: all deletes use only IDs successfully inserted by this test.
        for (long id : tenantIds) {
            jdbc.update("DELETE FROM ab_integration_receipt WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM ab_outbox WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM ab_notification WHERE tenant_id = ?", id);
        }
        for (long id : memberIds) jdbc.update("DELETE FROM ab_tenant_member WHERE id = ?", id);
        for (long id : userIds) jdbc.update("DELETE FROM ab_user WHERE id = ?", id);
        for (long id : tenantIds) jdbc.update("DELETE FROM ab_tenant WHERE id = ?", id);
    }
    private long tenant() {
        long id = IdWorker.getId();
        String pid = UniqueIdGenerator.generate();
        assertThat(jdbc.update("INSERT INTO ab_tenant(id,pid,name,status,deleted_flag) VALUES (?,?,?,'active',FALSE)",
                id, pid, "S06 native fixture " + pid)).isEqualTo(1);
        tenantIds.add(id); return id;
    }
    private long user(String pid) {
        long id = IdWorker.getId();
        assertThat(jdbc.update("INSERT INTO ab_user(id,pid,user_name,deleted_flag) VALUES (?,?,?,FALSE)",
                id, pid, "s06-" + pid)).isEqualTo(1);
        userIds.add(id); return id;
    }
    private void member(long tenantId, long userId) {
        long id = IdWorker.getId();
        assertThat(jdbc.update("INSERT INTO ab_tenant_member(id,pid,tenant_id,user_id,status,deleted_flag) VALUES (?,?,?,?,'active',FALSE)",
                id, UniqueIdGenerator.generate(), tenantId, userId)).isEqualTo(1);
        memberIds.add(id);
    }
    private Plan plan(String suffix, String recipient) {
        String ref = sourceId + "-" + suffix;
        Plan plan = new Plan(tenant, ref, recipient, "synthetic-config-v1", "procurement-reminder", sourceId,
                Instant.parse("2026-10-13T00:00:00.123456789Z"), "Synthetic S06", "Native fixture content");
        plans.put(ref, plan); return plan;
    }
    private <T> T transaction(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }
    private Result queue(Plan plan) {
        return transaction(() -> host.enqueue(new Request(tenant, plan.reference(), Channel.IN_APP)));
    }
    private IntegrationEventEnvelope readEvent(String eventId) {
        return jdbc.queryForObject("SELECT * FROM ab_outbox WHERE tenant_id = ? AND event_id = ?", (row, index) -> {
            try {
                return new IntegrationEventEnvelope(row.getString("schema_version"), row.getString("event_id"),
                        row.getString("event_type"), row.getString("event_source"), row.getString("subject"),
                        row.getTimestamp("occurred_at").toInstant(), row.getLong("tenant_id"), row.getString("correlation_id"),
                        row.getString("causation_id"), row.getString("ordering_key"), row.getLong("event_sequence"),
                        json.readValue(row.getString("payload"), new TypeReference<Map<String,Object>>() {}),
                        json.readValue(row.getString("headers"), new TypeReference<Map<String,String>>() {}));
            } catch (java.io.IOException e) { throw new IllegalStateException("Invalid stored fixture envelope", e); }
        }, tenant, eventId);
    }
    private int count(String table) {
        if (!Set.of("ab_outbox", "ab_integration_receipt", "ab_notification").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
    }
    private Map<String,Integer> counts() {
        return Map.of("outbox", count("ab_outbox"), "receipt", count("ab_integration_receipt"), "inbox", count("ab_notification"));
    }
    private <T> List<T> concurrent(Callable<T> call) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), release = new CountDownLatch(1);
        try {
            Callable<T> run = () -> {
                MetaContext.setContext(tenant, user, userPid, "S06 native concurrent fixture");
                try {
                    ready.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture barrier timeout");
                    return call.call();
                } finally { MetaContext.clear(); }
            };
            var first = workers.submit(run); var second = workers.submit(run);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); release.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            release.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void actualConcurrentExactEnvelopeReplayAndChangedPayloadRejection() throws Exception {
        Plan plan = plan("concurrent", userPid);
        List<Result> results = concurrent(() -> queue(plan));
        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(Status.QUEUED, Status.REPLAYED);
        assertThat(results).extracting(Result::deliveryKey).containsOnly(results.getFirst().deliveryKey());
        assertThat(counts()).isEqualTo(Map.of("outbox",1,"receipt",0,"inbox",0));
        assertThat(queue(plan).status()).isEqualTo(Status.REPLAYED);
        assertThat(readEvent(results.getFirst().deliveryKey()).occurredAt())
                .isEqualTo(plan.occurredAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        // The notification envelope intentionally discards sub-microsecond digits, not the source fact timestamp.
        plans.put(plan.reference(), new Plan(tenant,plan.reference(),userPid,plan.configurationVersion(),plan.sourceType(),
                plan.sourceId(),plan.occurredAt().minusNanos(500),plan.title(),plan.content()));
        assertThat(queue(plan).status()).isEqualTo(Status.REPLAYED);
        var beforeTimeChange = jdbc.queryForMap("SELECT * FROM ab_outbox WHERE tenant_id = ?",tenant);
        plans.put(plan.reference(), new Plan(tenant,plan.reference(),userPid,plan.configurationVersion(),plan.sourceType(),
                plan.sourceId(),plan.occurredAt().plusNanos(1000),plan.title(),plan.content()));
        assertThatThrownBy(() -> queue(plan)).hasMessage("RELIABLE_ENVELOPE_CONFLICT");
        assertThat(jdbc.queryForMap("SELECT * FROM ab_outbox WHERE tenant_id = ?",tenant)).isEqualTo(beforeTimeChange);
        plans.put(plan.reference(), new Plan(tenant,plan.reference(),userPid,plan.configurationVersion(),plan.sourceType(),
                plan.sourceId(),plan.occurredAt(),plan.title(),"Changed content"));
        var before = jdbc.queryForMap("SELECT * FROM ab_outbox WHERE tenant_id = ?", tenant);
        assertThatThrownBy(() -> queue(plan)).hasMessage("RELIABLE_ENVELOPE_CONFLICT");
        assertThat(jdbc.queryForMap("SELECT * FROM ab_outbox WHERE tenant_id = ?", tenant)).isEqualTo(before);
        assertThat(counts()).isEqualTo(Map.of("outbox",1,"receipt",0,"inbox",0));
    }
    @Test void realSourceRollbackLeavesNoQueuedOrPersistedFact() {
        Plan plan = plan("source-rollback", userPid);
        assertThatThrownBy(() -> transaction(() -> {
            assertThat(host.enqueue(new Request(tenant,plan.reference(),Channel.IN_APP)).status()).isEqualTo(Status.QUEUED);
            throw new IllegalStateException("synthetic source rollback");
        })).hasMessage("synthetic source rollback");
        assertThat(counts()).isEqualTo(Map.of("outbox",0,"receipt",0,"inbox",0));
    }
    @Test void inboxAndReceiptRollbackTogetherThenConcurrentReplayPersistsOnce() throws Exception {
        Plan plan = plan("atomic", userPid); var result = queue(plan); var event = readEvent(result.deliveryKey());
        ReliableEventConsumerExtension failAfterPersist = new ReliableEventConsumerExtension() {
            public String consumerCode() { return host.consumerCode(); }
            public Set<String> subscribedEventTypes() { return host.subscribedEventTypes(); }
            public void consume(IntegrationEventEnvelope input) {
                host.consume(input);
                assertThat(count("ab_notification")).isEqualTo(1);
                throw new IllegalStateException("synthetic crash after persisted inbox");
            }
        };
        assertThatThrownBy(() -> executor.deliver(event,failAfterPersist,"native-failure-fence"))
                .hasMessage("synthetic crash after persisted inbox");
        assertThat(counts()).isEqualTo(Map.of("outbox",1,"receipt",0,"inbox",0));
        concurrent(() -> { executor.deliver(event,host,UniqueIdGenerator.generate()); return true; });
        assertThat(counts()).isEqualTo(Map.of("outbox",1,"receipt",1,"inbox",1));
        assertThat(jdbc.queryForObject("SELECT status FROM ab_integration_receipt WHERE tenant_id = ? AND event_id = ?",String.class,tenant,event.eventId())).isEqualTo("applied");
        var original = jdbc.queryForMap("SELECT * FROM ab_notification WHERE tenant_id = ?",tenant);
        executor.deliver(event,host,"native-replay-fence");
        assertThat(jdbc.queryForMap("SELECT * FROM ab_notification WHERE tenant_id = ?",tenant)).isEqualTo(original);
        assertThat(original.get("user_id")).isEqualTo(user);
        assertThat(original.get("source_id")).isEqualTo(sourceId);
        assertThat(original.get("title")).isEqualTo("Synthetic S06");
        assertThat(original.get("content")).isEqualTo("Native fixture content");
    }
    @Test void recipientValidityAndMembershipRecheckedWithoutHiddenWrites() {
        Plan foreign = plan("foreign",otherUserPid);
        assertThatThrownBy(() -> queue(foreign)).hasMessage("NOTIFICATION_RECIPIENT_UNAVAILABLE");
        Plan own = plan("validity",userPid); queue(own);
        var before = counts();
        for (String column : List.of("is_enabled","is_account_non_expired","is_account_non_locked","is_credentials_non_expired")) {
            jdbc.update("UPDATE ab_user SET " + column + " = FALSE WHERE id = ?",user);
            assertThatThrownBy(() -> queue(own)).hasMessage("NOTIFICATION_RECIPIENT_UNAVAILABLE");
            assertThat(counts()).isEqualTo(before);
            jdbc.update("UPDATE ab_user SET " + column + " = TRUE WHERE id = ?",user);
        }
        jdbc.update("UPDATE ab_user SET deactivation_status = 'pending' WHERE id = ?",user);
        assertThatThrownBy(() -> queue(own)).hasMessage("NOTIFICATION_RECIPIENT_UNAVAILABLE");
        jdbc.update("UPDATE ab_user SET deactivation_status = NULL WHERE id = ?",user);
        var eventId = jdbc.queryForObject("SELECT event_id FROM ab_outbox WHERE tenant_id = ?",String.class,tenant);
        jdbc.update("UPDATE ab_tenant_member SET status = 'inactive' WHERE tenant_id = ? AND user_id = ?",tenant,user);
        assertThatThrownBy(() -> executor.deliver(readEvent(eventId),host,"membership-revoked-fence"))
                .hasMessage("NOTIFICATION_RECIPIENT_UNAVAILABLE");
        assertThat(counts()).isEqualTo(before);
    }
    @Test void realInboxTenantAndRecipientReadMarkIsolation() {
        Plan own = plan("inbox-isolation",userPid); var queued = queue(own);
        executor.deliver(readEvent(queued.deliveryKey()),host,"own-inbox-fence");
        var page = inbox.listByUser(user,new NotificationQueryRequest());
        assertThat(page.getTotal()).isEqualTo(1); assertThat(page.getRecords()).hasSize(1);
        long id = page.getRecords().getFirst().getId();
        assertThat(page.getRecords().getFirst().getContent()).isEqualTo("Native fixture content");
        // A different active user in the same tenant cannot read or mark this receipt's notification.
        member(tenant,otherUser); MetaContext.setContext(tenant,otherUser,otherUserPid,"Other member");
        assertThat(inbox.listByUser(otherUser,new NotificationQueryRequest()).getTotal()).isZero();
        inbox.markAsRead(id);
        assertThat(jdbc.queryForObject("SELECT is_read FROM ab_notification WHERE id = ?",Boolean.class,id)).isFalse();
        MetaContext.setContext(otherTenant,otherUser,otherUserPid,"Other tenant");
        assertThat(inbox.listByUser(user,new NotificationQueryRequest()).getTotal()).isZero();
        inbox.markAsRead(id);
        assertThat(jdbc.queryForObject("SELECT is_read FROM ab_notification WHERE id = ?",Boolean.class,id)).isFalse();
        MetaContext.setContext(tenant,user,userPid,"Own member"); inbox.markAsRead(id);
        assertThat(jdbc.queryForObject("SELECT is_read FROM ab_notification WHERE id = ?",Boolean.class,id)).isTrue();
        assertThat(counts()).isEqualTo(Map.of("outbox",1,"receipt",1,"inbox",1));
    }
}
