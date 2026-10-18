package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.ReliableEventDeliveryExecutor;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.notification.service.impl.PluginNotificationDeliveryAccessorImpl;
import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import com.auraboot.framework.plugin.extension.BackgroundTenantAccessor;
import com.auraboot.framework.plugin.extension.DataPageQuery;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit controlled tick over one new synthetic tenant; never a global timer or external delivery proof. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}", "spring.data.redis.database=${TEST_REDIS_DATABASE}",
        "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'UTC'",
        "s06.native.source-binding.fixture=true", "aura.notification.wechat-subscription.enabled=false",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.slf4j.Slf4jImpl",
        "logging.level.com.auraboot.framework=INFO", "logging.level.org.mybatis=INFO"})
@ActiveProfiles("integration-test")
@Import(RegisteredSourceBindingPostgresIT.PollerIsolation.class)
@EnabledIfEnvironmentVariable(named = "PROCUREMENT_REMINDER_TICK_NATIVE", matches = "true")
class ProcurementReminderTickNativeIT {
    static final Instant SLOT = Instant.parse("2026-10-13T01:30:00Z");
    static final List<String> BUSINESS = List.of("prod_product", "pr_purchase_request", "s2p_sourcing_event", "s2p_bid",
            "qo_procurement_quote_line_common", "qo_procurement_history_common", "qo_procurement_submission_common",
            "qo_procurement_submission_line_common", "qo_procurement_allocation_common", "qo_procurement_execution_common",
            "pr_purchase_order", "pr_purchase_order_line", "pr_supplier_common", "pr_supplier_pcba_contact",
            "qo_procurement_membership_common", "pr_spend_category", "qo_procurement_category_policy_common",
            "qo_procurement_category_supplier_common", "customer_pcba_int_external_ref");
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @MockitoBean SchedulerEngine schedulerEngine;

    @DynamicPropertySource
    static void matchingCryptoAndProductDirectory(DynamicPropertyRegistry properties) {
        String key = S06NativeEncryptionAdmission.requiredKey(System.getenv());
        properties.add("security.field-encryption.key", () -> key);
        ProductQuoteNativePluginDirectory.select(true, Path.of(required("S06_NATIVE_PRODUCT_INPUT")),
                required("S06_NATIVE_PRODUCT_INPUT_SHA256"), Path.of(required("S06_NATIVE_PRODUCT_PLUGIN_DIR")),
                Path.of("/Users/ghj/work/auraboot/.workspace/runtimes/procurement-web-stack-20261010"))
                .ifPresent(path -> properties.add("aura.plugins.dir", path::toString));
    }

    @Test
    void actualControlledTickQueuesOnceAndDeliversTwoExplicitInAppRecipients() throws Exception {
        Path evidence = Path.of(required("PROCUREMENT_REMINDER_TICK_EVIDENCE_DIR")).toRealPath();
        assertThat(evidence.startsWith(Path.of("/Users/ghj/work/auraboot/.workspace/evidence/procurement-web-stack-20261010").toRealPath())).isTrue();
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(context.getBean(com.auraboot.framework.notification.wechat.WechatSubscriptionProperties.class).isEnabled()).isFalse();
        assertThat(System.getenv("TEST_REDIS_DATABASE")).isEqualTo("13");
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getCatalog()).isEqualTo("enterprise_22");
            assertThat(connection.getMetaData().getURL()).matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):5432/enterprise_22(?:\\?.*)?");
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("matchingEncryptionAdmission", S06NativeEncryptionAdmission.admit(System.getenv(), json,
                context.getBean(FieldEncryptionService.class), getClass(), context.getEnvironment().getActiveProfiles()));
        var baselineTables = physicalTables(Long.parseLong(required("TEST_TENANT_ID")));
        Map<String, Object> protectedBefore = snapshot(baselineTables, -1L, false);
        var fixture = ProductQuoteReminderNativeFacet.prepareTickFixture(services(), evidence);
        Map<String, Object> newMetadataBefore = metadata(fixture.tenantId(), true);
        Map<String, Object> businessBeforeTick = snapshot(fixture.tables(), fixture.tenantId(), true);
        ControlledClock clock = new ControlledClock();
        var restricted = new TenantBoundBackground(fixture.tenantId(), fixture.supplierPid(), fixture.quotePid(),
                context.getBean(BackgroundDataAccessor.class));
        String stage = "controlled-tick-starting";
        boolean completed = false;
        try (var loader = new URLClassLoader(new java.net.URL[]{fixture.productJar().toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type = loader.loadClass("com.auraboot.quote.engine.procurement.ProcurementReminderScheduler");
            assertThat(type.getClassLoader()).isSameAs(loader);
            assertThat(Files.isSameFile(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()), fixture.productJar())).isTrue();
            var constructor = type.getDeclaredConstructor(Clock.class);
            constructor.setAccessible(true);
            Object scheduler = constructor.newInstance(clock);
            ReflectionTestUtils.setField(scheduler, "background", restricted.proxy());
            ReflectionTestUtils.setField(scheduler, "tenants", (BackgroundTenantAccessor) () -> List.of(fixture.tenantId()));
            ReflectionTestUtils.setField(scheduler, "enabled", true);
            Method tick = type.getMethod("tick");
            MetaContext.clear();
            invokeTick(tick, scheduler);
            assertThat(MetaContext.exists()).isFalse();
            List<String> reminders = rows(fixture.tables().get("qo_procurement_reminder_common"), fixture.tenantId());
            assertThat(reminders).hasSize(1);
            JsonNode reminder = json.readTree(reminders.getFirst());
            assertThat(reminder.path("qo_pr_reminder_kind").asText()).isEqualTo("quote");
            assertThat(reminder.path("qo_pr_reminder_state").asText()).isEqualTo("pending");
            JsonNode targets = reminder.path("qo_pr_reminder_targets");
            if (targets.isTextual()) targets = json.readTree(targets.textValue());
            assertThat(targets).isEqualTo(json.valueToTree(List.of(fixture.quotePid())));
            String sourcePid = reminder.path("pid").asText();
            assertThat(sourcePid).isNotBlank();
            assertThat(count("ab_outbox", fixture.tenantId())).isZero();
            invokeTick(tick, scheduler);
            assertThat(rows(fixture.tables().get("qo_procurement_reminder_common"), fixture.tenantId())).isEqualTo(reminders);
            int calls = restricted.calls.size();
            clock.now = SLOT.plusSeconds(60);
            invokeTick(tick, scheduler);
            assertThat(restricted.calls).hasSize(calls);
            clock.now = SLOT.plusSeconds(7200);
            invokeTick(tick, scheduler);
            assertThat(rows(fixture.tables().get("qo_procurement_reminder_common"), fixture.tenantId())).isEqualTo(reminders);
            assertThat(restricted.tryCreates).isEqualTo(2);
            assertThat(count("ab_outbox", fixture.tenantId())).isZero();
            assertThat(count("ab_notification", fixture.tenantId())).isZero();
            assertThat(count("ab_integration_receipt", fixture.tenantId())).isZero();
            Map<String, Object> afterTick = snapshot(fixture.tables(), fixture.tenantId(), true);
            Map<String, Object> nonReminderBefore = new LinkedHashMap<>(businessBeforeTick);
            Map<String, Object> nonReminderAfter = new LinkedHashMap<>(afterTick);
            nonReminderBefore.remove("qo_procurement_reminder_common");
            nonReminderAfter.remove("qo_procurement_reminder_common");
            assertThat(nonReminderAfter).isEqualTo(nonReminderBefore);
            clock.now = SLOT;
            stage = "actual-registered-worker-and-consumer";
            HostSourceBindingProperties settings = new HostSourceBindingProperties();
            settings.setEnabled(true);
            settings.setBindings(List.of(fixture.binding()));
            var planner = new RegisteredSourcePlanResolver(settings, context.getBean(ImportedNotificationMetadataReader.class),
                    context.getBean(ImportedNotificationMetadataValidator.class), clock);
            planner.initialize();
            var host = new PluginNotificationDeliveryAccessorImpl(List.of(planner),
                    context.getBean(com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor.class),
                    context.getBean(com.auraboot.framework.notification.service.impl.StrictInAppDeliveryWriter.class));
            ReflectionTestUtils.setField(host, "enabled", true);
            var worker = new RegisteredSourceWorker(planner, context.getBean(ImportedNotificationMetadataReader.class), host,
                    context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            assertThat(worker.process(fixture.tenantId(), fixture.binding().code(), sourcePid)).isEqualTo(2);
            List<IntegrationEventEnvelope> events = events(fixture.tenantId());
            assertThat(events).hasSize(2);
            List<String> originalOutbox = rows("ab_outbox", fixture.tenantId());
            assertThat(worker.process(fixture.tenantId(), fixture.binding().code(), sourcePid)).isEqualTo(2);
            assertThat(rows("ab_outbox", fixture.tenantId())).isEqualTo(originalOutbox);
            var executor = context.getBean(ReliableEventDeliveryExecutor.class);
            for (var event : events) executor.deliver(event, host, UniqueIdGenerator.generate());
            assertThat(jdbc.queryForList("SELECT user_id FROM ab_notification WHERE tenant_id=? ORDER BY user_id", Long.class,
                    fixture.tenantId())).containsExactlyInAnyOrderElementsOf(fixture.recipientIds());
            assertThat(count("ab_integration_receipt", fixture.tenantId())).isEqualTo(2);
            List<String> inbox = rows("ab_notification", fixture.tenantId());
            List<String> receipts = rows("ab_integration_receipt", fixture.tenantId());
            for (var event : events) executor.deliver(event, host, UniqueIdGenerator.generate());
            assertThat(rows("ab_notification", fixture.tenantId())).isEqualTo(inbox);
            assertThat(rows("ab_integration_receipt", fixture.tenantId())).isEqualTo(receipts);
            assertThat(snapshot(fixture.tables(), fixture.tenantId(), true)).isEqualTo(afterTick);
            assertThat(snapshot(baselineTables, fixture.tenantId(), false)).isEqualTo(protectedBefore);
            assertThat(metadata(fixture.tenantId(), true)).isEqualTo(newMetadataBefore);
            receipt.put("sourcePid", sourcePid);
            receipt.put("ownedOutboxRowsBeforeTeardown", originalOutbox.stream().map(SourceBindingCanonical::parse).toList());
            receipt.put("protectedSnapshot", protectedBefore);
            receipt.put("businessAfterTick", afterTick);
            receipt.put("inboxCount", inbox.size());
            receipt.put("receiptCount", receipts.size());
            receipt.put("nativeTickExecuted", true);
            receipt.put("tickInvocations", Map.of("sameSlot", 2, "offSlot", 1, "expired", 1));
            receipt.put("explicitRecipientIds", fixture.recipientIds());
            receipt.put("testTenantOnly", true);
            receipt.put("backgroundBeanClass", restricted.actual.getClass().getName());
            receipt.put("sameSlotTryCreateCalls", restricted.tryCreates);
            receipt.put("newTenantMetadataBeforeAndAfter", newMetadataBefore);
            receipt.put("automaticTimerVerified", false);
            receipt.put("formalRecipientPolicyApproved", false);
            receipt.put("providerCalls", 0);
            receipt.put("wechatCalls", 0);
            receipt.put("productJarSha256", hash(fixture.productJar()));
            receipt.put("schedulerClass", type.getName());
            stage = "controlled-tick-completed";
            completed = true;
        } finally {
            MetaContext.clear();
            receipt.put("stage", stage);
            receipt.put("completed", completed);
            receipt.put("tenantId", Long.toString(fixture.tenantId()));
            receipt.put("fixtureSetupPath", fixture.setupEvidence().toString());
            receipt.put("fixtureSetupSha256", fixture.setupSha256());
            receipt.put("backgroundCalls", restricted.calls);
            Path sealed = evidence.resolve("reminder-tick-receipt.json");
            Files.writeString(sealed, json.writerWithDefaultPrettyPrinter().writeValueAsString(receipt), StandardOpenOption.CREATE_NEW);
            if (completed) {
                String sourcePid = (String) receipt.get("sourcePid");
                Set<String> references = fixture.recipientIds().stream().map(id -> SourceBindingCanonical.reference(
                        fixture.binding().code(), sourcePid, id)).collect(java.util.stream.Collectors.toSet());
                TestOwnedNotificationIntentTeardown.run(dataSource, new TestOwnedNotificationIntentTeardown.Scope(
                        fixture.tenantId(), fixture.binding().code(), sourcePid, references),
                        rows("ab_outbox", fixture.tenantId()).stream().map(SourceBindingCanonical::parse).toList(), sealed,
                        evidence.resolve("reminder-tick-teardown.json"));
                assertThat(snapshot(baselineTables, fixture.tenantId(), false)).isEqualTo(protectedBefore);
                assertThat(metadata(fixture.tenantId(), true)).isEqualTo(newMetadataBefore);
                assertThat(snapshot(fixture.tables(), fixture.tenantId(), true)).isEqualTo(receipt.get("businessAfterTick"));
                assertThat(count("ab_notification", fixture.tenantId())).isEqualTo(2);
                assertThat(count("ab_integration_receipt", fixture.tenantId())).isEqualTo(2);
            }
        }
    }

    private ProductQuoteReminderNativeFacet.Services services() {
        return new ProductQuoteReminderNativeFacet.Services(dataSource, jdbc, json,
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class),
                context.getBean(com.auraboot.framework.tenant.service.TenantService.class),
                context.getBean(com.auraboot.framework.user.service.UserService.class),
                context.getBean(com.auraboot.framework.tenant.service.TenantMemberService.class),
                context.getBean(com.auraboot.framework.tenant.service.TenantBootstrapService.class),
                context.getBean(com.auraboot.framework.plugin.service.PluginImportService.class),
                context.getBean(com.auraboot.framework.meta.service.DynamicDataService.class),
                context.getBean(MetaModelService.class), context.getBean(com.auraboot.framework.meta.service.CommandExecutor.class),
                context.getBean(com.auraboot.framework.auth.service.AuthService.class), context.getBean(BackgroundDataAccessor.class),
                context.getBean(com.auraboot.framework.notification.service.NotificationTemplateService.class),
                context.getBean(com.auraboot.framework.notification.service.NotificationRuleService.class),
                context.getBean(ImportedNotificationMetadataReader.class), context.getBean(ImportedNotificationMetadataValidator.class),
                context.getBean(com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor.class),
                context.getBean(com.auraboot.framework.notification.service.impl.StrictInAppDeliveryWriter.class),
                context.getBean(ReliableEventDeliveryExecutor.class), context.getBean(com.auraboot.framework.plugin.pf4j.ExtensionRegistry.class), List.of());
    }

    private Map<String, String> physicalTables(long baselineTenant) {
        if (baselineTenant <= 0) throw new IllegalArgumentException("Positive sealed baseline tenant required");
        Map<String, String> result = new LinkedHashMap<>();
        MetaContext.Snapshot prior = MetaContext.snapshot();
        try {
            MetaContext.setContext(baselineTenant, 0L, null, "system");
            for (String model : BUSINESS) {
                String table = context.getBean(MetaModelService.class).getTableName(model);
                identifier(table);
                assertThat(table).startsWith("mt_");
                result.put(model, table);
            }
        } finally { MetaContext.clear(); MetaContext.restore(prior); }
        return Map.copyOf(result);
    }

    private Map<String, Object> snapshot(Map<String, String> tables, long tenant, boolean onlyTenant) {
        Map<String, Object> result = new LinkedHashMap<>();
        tables.forEach((model, table) -> result.put(model, summary(table, tenant, onlyTenant)));
        if (!onlyTenant) result.put("metadataAndDelivery", metadata(tenant, false));
        return result;
    }

    private Map<String, Object> metadata(long tenant, boolean onlyTenant) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String table : List.of("ab_meta_model", "ab_meta_field", "ab_meta_model_field_binding", "ab_plugin", "ab_plugin_resource",
                "ab_command_definition", "ab_notification_rule", "ab_notification_template")) {
            result.put(table, summary(table, tenant, onlyTenant));
        }
        if (!onlyTenant) {
            for (String table : List.of("ab_notification", "ab_integration_receipt", "ab_outbox"))
                result.put(table, summary(table, tenant, false));
        }
        return result;
    }

    private Map<String, Object> summary(String table, long tenant, boolean onlyTenant) {
        identifier(table);
        List<String> values = jdbc.queryForList("SELECT row_to_json(t)::text FROM \"" + table + "\" t WHERE tenant_id "
                + (onlyTenant ? "=" : "IS DISTINCT FROM") + " ? ORDER BY row_to_json(t)::text", String.class, tenant);
        return Map.of("count", values.size(), "sha256", SourceBindingCanonical.hash(values));
    }

    private List<String> rows(String table, long tenant) {
        identifier(table);
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM \"" + table + "\" t WHERE tenant_id=? ORDER BY row_to_json(t)::text", String.class, tenant);
    }

    private long count(String table, long tenant) {
        identifier(table);
        return jdbc.queryForObject("SELECT count(*) FROM \"" + table + "\" WHERE tenant_id=?", Long.class, tenant);
    }

    private List<IntegrationEventEnvelope> events(long tenant) {
        return jdbc.query("SELECT * FROM ab_outbox WHERE tenant_id=? ORDER BY event_id", (rs, n) -> {
            try {
                return new IntegrationEventEnvelope(rs.getString("schema_version"), rs.getString("event_id"), rs.getString("event_type"),
                        rs.getString("event_source"), rs.getString("subject"), rs.getTimestamp("occurred_at").toInstant(), rs.getLong("tenant_id"),
                        rs.getString("correlation_id"), rs.getString("causation_id"), rs.getString("ordering_key"), rs.getLong("event_sequence"),
                        json.readValue(rs.getString("payload"), new TypeReference<Map<String, Object>>() {}),
                        json.readValue(rs.getString("headers"), new TypeReference<Map<String, String>>() {}));
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalStateException("Malformed actual persisted envelope", error);
            }
        }, tenant);
    }

    static void identifier(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Unsafe physical identifier");
    }

    static void invokeTick(Method method, Object scheduler) throws Exception {
        try { method.invoke(scheduler); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }

    static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required fixture environment: " + key);
        return value;
    }

    static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    static final class ControlledClock extends Clock {
        Instant now = SLOT;
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) {
            if (!zone.equals(getZone())) throw new IllegalArgumentException("Only controlled UTC clock supported");
            return this;
        }
        @Override public Instant instant() { return now; }
    }

    /** Adds only a test-owned tenant/model boundary; all admitted work uses the real host bean unchanged. */
    static final class TenantBoundBackground implements java.lang.reflect.InvocationHandler {
        final long tenant;
        final String supplier;
        final String quote;
        final BackgroundDataAccessor actual;
        final List<Map<String, Object>> calls = new ArrayList<>();
        int tryCreates;
        TenantBoundBackground(long tenant, String supplier, String quote, BackgroundDataAccessor actual) {
            if (tenant <= 0 || supplier == null || supplier.isBlank() || quote == null || quote.isBlank())
                throw new IllegalArgumentException("Exact new fixture identity required");
            this.tenant = tenant; this.supplier = supplier; this.quote = quote; this.actual = java.util.Objects.requireNonNull(actual);
        }
        BackgroundDataAccessor proxy() {
            return (BackgroundDataAccessor) Proxy.newProxyInstance(BackgroundDataAccessor.class.getClassLoader(),
                    new Class<?>[]{BackgroundDataAccessor.class}, this);
        }
        @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            Object[] args = arguments == null ? new Object[0] : arguments;
            validate(method.getName(), args);
            calls.add(Map.of("operation", method.getName(), "tenantId", Long.toString(tenant),
                    "model", args.length > 1 ? String.valueOf(args[1]) : "transaction"));
            try { return method.invoke(actual, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        }
        static String reminderKey(String supplier) {
            StringBuilder key = new StringBuilder();
            for (String part : List.of(supplier, "quote", "2026-10-13", "09:30"))
                key.append(part.length()).append(':').append(part);
            return key.toString();
        }
        void validate(String operation, Object[] args) {
            if (operation.equals("executeInTransaction") && args.length == 1 && args[0] instanceof Runnable) return;
            if (args.length < 2 || !(args[0] instanceof Long id) || id != tenant)
                throw new SecurityException("Tick tenant boundary rejected");
            boolean allowed = false;
            if (operation.equals("queryPage") && args.length == 5 && args[1].equals("pr_supplier_common")) {
                allowed = Map.of("pr_supplier_status", "enabled").equals(args[2]) && (args[3] == null || args[3] instanceof String)
                        && Integer.valueOf(100).equals(args[4]);
            }
            if (operation.equals("queryPage") && args.length == 3 && args[1].equals("s2p_bid") && args[2] instanceof DataPageQuery page) {
                allowed = page.exactFilters().equals(Map.of("s2p_bid_supplier_id", supplier, "s2p_bid_collaboration_managed", true))
                        && page.anyOfFilters().isEmpty() && page.keyword().isEmpty() && page.page() >= 1
                        && page.size() == DataPageQuery.MAX_SIZE && page.sorts().equals(List.of(new DataPageQuery.Sort("pid", false)));
            }
            if (operation.equals("tryCreate") && args.length == 3 && args[1].equals("qo_procurement_reminder_common") && args[2] instanceof Map<?, ?> values) {
                allowed = values.keySet().equals(Set.of("qo_pr_reminder_key", "qo_pr_reminder_supplier_pid", "qo_pr_reminder_kind",
                        "qo_pr_reminder_slot_at", "qo_pr_reminder_state", "qo_pr_reminder_revision", "qo_pr_reminder_targets"))
                        && supplier.equals(values.get("qo_pr_reminder_supplier_pid")) && "quote".equals(values.get("qo_pr_reminder_kind"))
                        && SLOT.toString().equals(values.get("qo_pr_reminder_slot_at")) && "pending".equals(values.get("qo_pr_reminder_state"))
                        && Integer.valueOf(0).equals(values.get("qo_pr_reminder_revision")) && List.of(quote).equals(values.get("qo_pr_reminder_targets"))
                        && reminderKey(supplier).equals(values.get("qo_pr_reminder_key"));
                if (allowed) tryCreates++;
            }
            if (!allowed) throw new SecurityException("Tick operation boundary rejected");
        }
    }
}
