package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.HostSerializableEventConsumer;
import com.auraboot.framework.integration.ReliableEventDeliveryExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import com.auraboot.framework.notification.dto.NotificationRuleRequest;
import com.auraboot.framework.notification.dto.NotificationTemplateCreateRequest;
import com.auraboot.framework.notification.service.NotificationRuleService;
import com.auraboot.framework.notification.service.NotificationTemplateService;
import com.auraboot.framework.notification.service.impl.PluginNotificationDeliveryAccessorImpl;
import com.auraboot.framework.notification.service.impl.StrictInAppDeliveryWriter;
import com.auraboot.framework.notification.wechat.WechatSubscriptionProperties;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.extension.DataPage;
import com.auraboot.framework.plugin.extension.NotificationDeliveryAccessor;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Approval;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Binding;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Entity;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Exact;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Owner;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Principal;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Source;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Target;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Verification;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real host source authority over officially imported, synthetic DSL data. No human policy or external delivery. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}", "spring.data.redis.database=${TEST_REDIS_DATABASE}",
        "security.field-encryption.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
@ActiveProfiles("integration-test")
@Import(RegisteredSourceBindingPostgresIT.PollerIsolation.class)
@EnabledIfEnvironmentVariable(named = "S06_NATIVE_SOURCE_BINDING", matches = "true")
class RegisteredSourceBindingPostgresIT {
    @TestConfiguration(proxyBeanMethods = false)
    static class PollerIsolation {
        @Bean
        static BeanFactoryPostProcessor preventTestAppAutomaticPollers() {
            return factory -> {
                if (!(factory instanceof BeanDefinitionRegistry registry)) {
                    throw new IllegalStateException("Native source fixture cannot isolate scheduling");
                }
                String name = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
                if (registry.containsBeanDefinition(name)) {
                    registry.removeBeanDefinition(name);
                }
            };
        }
    }

    @Autowired ApplicationContext context;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired TenantService tenants;
    @Autowired UserService users;
    @Autowired TenantMemberService members;
    @Autowired PluginImportService imports;
    @Autowired DynamicDataService data;
    @Autowired ModelFieldBindingService bindings;
    @Autowired NotificationRuleService rules;
    @Autowired NotificationTemplateService templates;
    @Autowired ImportedNotificationMetadataReader reader;
    @Autowired ImportedNotificationMetadataValidator validator;
    @Autowired ReliableIntegrationAccessor reliable;
    @Autowired ReliableEventDeliveryExecutor executor;
    @Autowired StrictInAppDeliveryWriter writer;
    @Autowired PluginNotificationDeliveryAccessorImpl defaultHost;
    @Autowired WechatSubscriptionProperties wechat;
    // Scheduling entry point only. No domain service, mapper, authority reader, writer or transport is mocked.
    @MockitoBean SchedulerEngine scheduler;

    private final String namespace = "sn" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final List<String> kinds = List.of("source", "target", "owner", "principal", "verification");
    private final Map<String, String> modelPids = new LinkedHashMap<>();
    private final Map<String, String> tables = new LinkedHashMap<>();
    private final Map<String, Object> trace = new LinkedHashMap<>();
    private final List<String> checkpoints = new ArrayList<>();
    private Tenant tenant, foreignTenant;
    private User first, second, foreign;
    private TenantMember secondMember;
    private String ownOwner, otherOwner, verification1, verification2, otherVerification;
    private String link1, link2, target, foreignTarget, sourcePid, templatePid;
    private long ruleId;
    private Binding approved;
    private RegisteredSourcePlanResolver resolver;
    private PluginNotificationDeliveryAccessorImpl host;
    private RegisteredSourceWorker worker;
    private Path evidence;
    private String stage = "fixture-not-started";
    private boolean businessStarted;
    private Map<String, Object> foreignBaseline;

    @BeforeEach
    void fixture(TestInfo info) throws Exception {
        stage = "fixture-admission";
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(System.getenv("TEST_REDIS_DATABASE")).isEqualTo("13");
        assertThat(System.getenv("S06_NATIVE_SOURCE_EVIDENCE")).isNotBlank();
        evidence = Path.of(System.getenv("S06_NATIVE_SOURCE_EVIDENCE")).toRealPath();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getCatalog()).isEqualTo("enterprise_22");
        }
        assertThat(wechat.isEnabled()).isFalse();
        trace.put("test", info.getTestMethod().orElseThrow().getName());
        trace.put("namespace", namespace);
        trace.put("authority", "test-owned host deployment descriptor; never Product/package self-approval");
        trace.put("externalProviderCalls", 0);
        stage = "fixture-native-identity-apis";
        MetaContext.clear();
        tenant = tenant("own");
        foreignTenant = tenant("foreign");
        first = user("first");
        second = user("second");
        foreign = user("foreign");
        members.addMember(first.getId(), tenant.getId(), "active");
        secondMember = members.addMember(second.getId(), tenant.getId(), "active");
        members.addMember(foreign.getId(), foreignTenant.getId(), "active");
        bind(tenant, first);
        assertThat(defaultHost.enqueue(new NotificationDeliveryAccessor.Request(tenant.getId(), "unknown", NotificationDeliveryAccessor.Channel.IN_APP)).status())
                .isEqualTo(NotificationDeliveryAccessor.Status.BLOCKED);
        stage = "fixture-formal-plugin-import";
        importFixture(tenant);
        importFixture(foreignTenant);
        bind(tenant, first);
        for (String kind : kinds) {
            Map<String, Object> model = jdbc.queryForMap("SELECT pid,table_name FROM ab_meta_model WHERE tenant_id=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE", tenant.getId(), model(kind));
            modelPids.put(kind, (String) model.get("pid"));
            String table = (String) model.get("table_name");
            assertThat(table).matches("[A-Za-z_][A-Za-z0-9_]{0,127}");
            tables.put(kind, table);
        }
        stage = "fixture-native-data-apis";
        ownOwner = create("owner", Map.of(field("state"), "active"));
        otherOwner = create("owner", Map.of(field("state"), "active"));
        verification1 = create("verification", Map.of(field("state"), "active", field("owner"), ownOwner));
        verification2 = create("verification", Map.of(field("state"), "active", field("owner"), ownOwner));
        otherVerification = create("verification", Map.of(field("state"), "active", field("owner"), otherOwner));
        link1 = principal(first, ownOwner, verification1);
        link2 = principal(second, ownOwner, verification2);
        target = create("target", Map.of(field("state"), "ready", field("owner"), ownOwner));
        bind(foreignTenant, foreign);
        String foreignOwner = create("owner", Map.of(field("state"), "active"));
        foreignTarget = create("target", Map.of(field("state"), "ready", field("owner"), foreignOwner));
        bind(tenant, first);
        sourcePid = create("source", Map.of(field("state"), "pending", field("owner"), ownOwner,
                field("occurred"), Instant.parse("2026-10-13T03:00:00.123456Z"), field("targets"), List.of(target)));
        NotificationTemplateCreateRequest template = templateRequest("Synthetic registered source body");
        templatePid = templates.create(template).getPid();
        ruleId = rules.createRule(ruleRequest(first.getId() + "," + second.getId())).getId();
        stage = "fixture-test-only-host-approval";
        // Admission diagnostics only: expose actual linked metadata ownership, not any authority fallback.
        trace.put("metadataOwnershipAdmission", jdbc.queryForList("""
                SELECT m.code AS model_code,m.tenant_id AS model_tenant,m.plugin_pid AS model_plugin,
                       b.pid AS binding_pid,b.tenant_id AS binding_tenant,b.is_system_binding,
                       f.pid AS field_pid,f.code AS field_code,f.tenant_id AS field_tenant,
                       f.plugin_pid AS field_plugin,f.data_type,f.is_current,f.deleted_flag
                FROM ab_meta_model m JOIN ab_meta_model_field_binding b ON b.model_id=m.id
                JOIN ab_meta_field f ON f.id=b.field_id
                WHERE m.tenant_id=? AND m.code IN (?,?,?,?,?) AND m.is_current=TRUE AND m.deleted_flag=FALSE
                ORDER BY m.code,b.field_order,b.id
                """, tenant.getId(), model("source"), model("target"), model("owner"), model("principal"), model("verification")));
        approved = approveActualFixtureMetadata();
        connect(approved, reader);
        serializable(() -> {
            validator.validate(approved, reader);
            assertThat(resolver.plan(approved, sourcePid)).hasSize(2);
            return null;
        });
        assertThat(counts()).isEqualTo(Map.of("outbox", 0, "receipt", 0, "inbox", 0));
        foreignBaseline = foreignSnapshot();
        trace.put("identities", Map.of("tenantId", tenant.getId(), "foreignTenantId", foreignTenant.getId(),
                "users", List.of(first.getPid(), second.getPid(), foreign.getPid()), "sourcePid", sourcePid));
        trace.put("approvedBinding", approved);
        trace.put("initialBusinessSnapshotSha256", SourceBindingCanonical.hash(businessSnapshot()));
        stage = "fixture-ready-business-did-not-run";
    }

    @AfterEach
    void evidence() throws Exception {
        if (evidence != null) {
            trace.put("stage", stage);
            trace.put("businessStarted", businessStarted);
            trace.put("checkpoints", checkpoints);
            trace.put("retention", "Only API-created unique tenant/namespace facts retained; no shared deletes, table drops or pollers");
            if (tenant != null && sourcePid != null) {
                bind(tenant, first);
                trace.put("finalCounts", counts());
                trace.put("finalBusinessSnapshotSha256", SourceBindingCanonical.hash(businessSnapshot()));
                if (foreignBaseline != null) {
                    trace.put("foreignBaselineUnchanged", foreignBaseline.equals(foreignSnapshot()));
                    assertThat(foreignSnapshot()).isEqualTo(foreignBaseline);
                }
            }
            Files.writeString(evidence.resolve(namespace + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(trace));
        }
        MetaContext.clear();
    }

    private Tenant tenant(String suffix) {
        Tenant result = new Tenant();
        result.setPid(UniqueIdGenerator.generate());
        result.setName(namespace + "-" + suffix);
        result.setStatus("active");
        return tenants.createTenant(result);
    }

    private User user(String suffix) {
        String name = namespace + "_" + suffix;
        // One transient synthetic password in memory, never written to trace/log/env by this fixture.
        return users.signUp(name + "@example.invalid", "Native-" + UUID.randomUUID(), "Native S06 " + suffix, name);
    }

    private String model(String kind) { return namespace + "_" + kind; }
    private String field(String name) { return namespace + "_" + name; }
    private void bind(Tenant selected, User actor) { MetaContext.setContext(selected.getId(), actor.getId(), actor.getPid(), "native-source-fixture"); }

    private void importFixture(Tenant selected) throws Exception {
        bind(selected, selected == foreignTenant ? foreign : first);
        Map<String, String> types = Map.of("owner", "string", "state", "string", "principal", "string",
                "verification", "string", "occurred", "datetime", "targets", "json");
        Map<String, List<String>> bound = Map.of("source", List.of("owner", "state", "occurred", "targets"),
                "target", List.of("owner", "state"), "owner", List.of("state"),
                "principal", List.of("owner", "state", "principal", "verification"), "verification", List.of("owner", "state"));
        List<Map<String, Object>> fields = new ArrayList<>(), models = new ArrayList<>(), bindings = new ArrayList<>();
        types.forEach((name, type) -> fields.add(Map.of("code", field(name), "displayName", name, "dataType", type)));
        for (String kind : kinds) {
            models.add(Map.of("code", model(kind), "displayName", kind, "modelType", "entity"));
            for (String name : bound.get(kind)) {
                bindings.add(Map.of("modelCode", model(kind), "fieldCode", field(name), "sequence", bindings.size() + 1));
            }
        }
        String manifest = json.writeValueAsString(Map.of("pluginId", "com.test.s06." + namespace, "namespace", namespace,
                "version", "1.0.0", "displayName", "Synthetic host source authority", "pluginType", "config",
                "fields", fields, "models", models, "modelFieldBindings", bindings));
        var preview = imports.parseJson(manifest, namespace + ".json");
        assertThat(preview.isValid()).as("actual import preview %s", preview.getErrors()).isTrue();
        var result = imports.execute(preview.getImportId(), new ImportRequest());
        assertThat(result.isSuccess()).as("actual import %s", result.getErrorMessage()).isTrue();
    }

    private String create(String kind, Map<String, Object> values) {
        return MetaContext.runWithCommandPermitScope("ALL", () -> (String) data.create(model(kind), values).get("pid"));
    }

    private void update(String kind, String pid, Map<String, Object> values) {
        MetaContext.runWithCommandPermitScope("ALL", () -> data.update(model(kind), pid, values));
    }

    private String principal(User user, String owner, String verification) {
        return create("principal", Map.of(field("state"), "active", field("owner"), owner,
                field("principal"), user.getPid(), field("verification"), verification));
    }

    private Entity entity(String kind, String state) {
        return new Entity(model(kind), List.of(new Exact(field("state"), state)));
    }

    private Binding approveActualFixtureMetadata() {
        return serializable(() -> {
            Map<String, Object> plugin = jdbc.queryForMap("SELECT pid,plugin_id,namespace,version FROM ab_plugin WHERE tenant_id=? AND plugin_id=? AND deleted_flag=FALSE", tenant.getId(), "com.test.s06." + namespace);
            Owner owner = new Owner((String) plugin.get("pid"), (String) plugin.get("plugin_id"), (String) plugin.get("namespace"), (String) plugin.get("version"));
            List<Approval> approvals = new ArrayList<>();
            for (String kind : kinds) {
                // Explicit native fields are ONLY this fixture's actual tenant-scoped system bindings with null owner.
                List<String> nativeFields = jdbc.queryForList("SELECT f.pid FROM ab_meta_field f JOIN ab_meta_model_field_binding b ON b.field_id=f.id JOIN ab_meta_model m ON m.id=b.model_id WHERE m.tenant_id=? AND m.code=? AND m.is_current=TRUE AND m.deleted_flag=FALSE AND b.tenant_id=? AND b.deleted_flag=FALSE AND b.is_system_binding=TRUE AND f.tenant_id=? AND f.deleted_flag=FALSE AND f.is_current=TRUE AND f.plugin_pid IS NULL", String.class, tenant.getId(), model(kind), tenant.getId(), tenant.getId());
                Approval lookup = new Approval("METADATA_SHAPE_V1", model(kind), "0".repeat(64), List.of(owner), Set.copyOf(nativeFields));
                JsonNode actual = reader.metadata(tenant.getId(), lookup);
                assertThat(actual.path("model").path("tenant_id").longValue()).isEqualTo(tenant.getId());
                approvals.add(new Approval(lookup.mode(), lookup.modelCode(), SourceBindingCanonical.hash(actual), lookup.owners(), lookup.nativeFieldPids()));
            }
            return new Binding(1, namespace + "_notice", tenant.getId(), approvals,
                    new Source(entity("source", "pending"), "pid", field("owner"), field("occurred"), new Target(field("targets"), entity("target", "ready"), field("owner"))),
                    entity("owner", "active"), new Principal(entity("principal", "active"), field("principal"), field("owner"), field("verification")),
                    new Verification(entity("verification", "active"), field("owner")), ruleId, namespace + "_template", Map.of());
        });
    }

    private void connect(Binding binding, SourceBindingReader selectedReader) {
        HostSourceBindingProperties properties = new HostSourceBindingProperties();
        properties.setEnabled(true);
        properties.setBindings(List.of(binding));
        resolver = new RegisteredSourcePlanResolver(properties, selectedReader, validator);
        resolver.initialize();
        host = new PluginNotificationDeliveryAccessorImpl(List.of(resolver), reliable, writer);
        ReflectionTestUtils.setField(host, "enabled", true);
        worker = new RegisteredSourceWorker(resolver, selectedReader, host, manager);
    }

    private NotificationRuleRequest ruleRequest(String recipients) {
        NotificationRuleRequest request = new NotificationRuleRequest();
        request.setCode(namespace + "_rule"); request.setName("Native explicit recipient proof"); request.setEnabled(true);
        request.setTriggerType("EVENT"); request.setTriggerConfig("{\"modelCode\":\"" + model("source") + "\",\"event\":\"created\"}");
        request.setConditionModelCode(model("source")); request.setConditionFilter("[{\"fieldName\":\"" + field("state") + "\",\"operator\":\"EQ\",\"value\":\"pending\"}]");
        request.setActionChannel("IN_APP"); request.setActionTemplateCode(namespace + "_template");
        request.setRecipientType("SPECIFIC_USERS"); request.setRecipientField(recipients);
        return request;
    }

    private NotificationTemplateCreateRequest templateRequest(String body) {
        NotificationTemplateCreateRequest request = new NotificationTemplateCreateRequest();
        request.setCode(namespace + "_template"); request.setName("Native source template"); request.setChannel("IN_APP");
        request.setEnabled(true); request.setSubjectTemplate("Native source approval proof"); request.setBodyTemplate(body);
        return request;
    }

    private <T> T serializable(Supplier<T> work) {
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        return tx.execute(status -> work.get());
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var entry : Map.of("outbox", "ab_outbox", "receipt", "ab_integration_receipt", "inbox", "ab_notification").entrySet()) {
            result.put(entry.getKey(), jdbc.queryForObject("SELECT COUNT(*) FROM " + entry.getValue() + " WHERE tenant_id=?", Integer.class, tenant.getId()));
        }
        return result;
    }

    private Map<String, Object> businessSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String kind : kinds) result.put(kind, jdbc.queryForList("SELECT to_jsonb(r)::text AS row FROM \"" + tables.get(kind) + "\" r WHERE tenant_id=? ORDER BY pid", tenant.getId()));
        return result;
    }

    private Map<String, Object> foreignSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String kind : kinds) result.put(kind, jdbc.queryForList("SELECT to_jsonb(r)::text AS row FROM \"" + tables.get(kind) + "\" r WHERE tenant_id=? ORDER BY pid", foreignTenant.getId()));
        for (String table : List.of("ab_outbox", "ab_integration_receipt", "ab_notification")) result.put(table, jdbc.queryForList("SELECT to_jsonb(r)::text AS row FROM " + table + " r WHERE tenant_id=? ORDER BY id", foreignTenant.getId()));
        return result;
    }

    private List<IntegrationEventEnvelope> events() {
        return jdbc.query("SELECT * FROM ab_outbox WHERE tenant_id=? ORDER BY event_id", (row, index) -> {
            try {
                return new IntegrationEventEnvelope(row.getString("schema_version"), row.getString("event_id"), row.getString("event_type"), row.getString("event_source"), row.getString("subject"), row.getTimestamp("occurred_at").toInstant(), row.getLong("tenant_id"), row.getString("correlation_id"), row.getString("causation_id"), row.getString("ordering_key"), row.getLong("event_sequence"), json.readValue(row.getString("payload"), new TypeReference<Map<String, Object>>() {}), json.readValue(row.getString("headers"), new TypeReference<Map<String, String>>() {}));
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                // CATCH: expected persisted JSON validation error in fixture read, no envelope fallback.
                throw new IllegalStateException("Invalid persisted native fixture envelope", error);
            }
        }, tenant.getId());
    }

    private void startBusiness() { businessStarted = true; stage = "business-running"; }
    private void proved(String name) { checkpoints.add(name); stage = "business-checkpoints-completed"; }

    @Test
    void actualImportedMetadataExplicitRecipientsReplayAndConsumePersistExactlyOnce() {
        startBusiness();
        Map<String, Object> before = businessSnapshot();
        assertThat(worker.process(tenant.getId(), approved.code(), sourcePid)).isEqualTo(2);
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 0, "inbox", 0));
        List<IntegrationEventEnvelope> original = events();
        assertThat(original).hasSize(2);
        assertThat(original).extracting(IntegrationEventEnvelope::occurredAt)
                .containsOnly(Instant.parse("2026-10-13T03:00:00.123456Z"));
        assertThat(worker.process(tenant.getId(), approved.code(), sourcePid)).isEqualTo(2);
        assertThat(events()).isEqualTo(original);
        for (IntegrationEventEnvelope event : original) {
            executor.deliver(event, host, UniqueIdGenerator.generate());
        }
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 2, "inbox", 2));
        List<Map<String, Object>> inbox = jdbc.queryForList("SELECT id,user_id,source_id,title,content,created_at FROM ab_notification WHERE tenant_id=? ORDER BY user_id", tenant.getId());
        assertThat(inbox).extracting(row -> row.get("user_id")).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(inbox).allSatisfy(row -> {
            assertThat(row.get("source_id")).isEqualTo(sourcePid);
            assertThat(row.get("title")).isEqualTo("Native source approval proof");
            assertThat(row.get("content")).isEqualTo("Synthetic registered source body");
        });
        original.forEach(event -> executor.deliver(event, host, UniqueIdGenerator.generate()));
        assertThat(jdbc.queryForList("SELECT id,user_id,source_id,title,content,created_at FROM ab_notification WHERE tenant_id=? ORDER BY user_id", tenant.getId())).isEqualTo(inbox);
        assertThat(businessSnapshot()).isEqualTo(before);
        proved("actual raw metadata approved; two explicit recipients; stable queue replay; receipt/inbox duplicate consume suppressed; immutable source");
    }

    @Test
    void foreignWrongOwnerAmbiguousAndInvalidExplicitUserCannotPartiallyEnqueue() {
        startBusiness();
        update("principal", link2, Map.of(field("owner"), otherOwner));
        rejectWithoutEffects();
        update("principal", link2, Map.of(field("owner"), ownOwner));
        update("source", sourcePid, Map.of(field("targets"), List.of(foreignTarget)));
        rejectWithoutEffects();
        update("source", sourcePid, Map.of(field("targets"), List.of(target)));
        rules.updateRule(ruleId, ruleRequest(first.getId() + "," + foreign.getId()));
        rejectWithoutEffects();
        rules.updateRule(ruleId, ruleRequest(first.getId() + "," + second.getId()));
        String ambiguous = principal(first, otherOwner, otherVerification);
        rejectWithoutEffects();
        update("principal", ambiguous, Map.of(field("state"), "inactive"));
        assertThat(members.deactivateMember(secondMember.getId())).isTrue();
        rejectWithoutEffects();
        assertThat(worker.process(foreignTenant.getId(), approved.code(), sourcePid)).isZero();
        proved("second user owner mismatch, foreign target, foreign platform member, cross-owner active-link ambiguity and inactive member: whole source zero enqueue");
    }

    @Test
    void changedTemplateSourceAndMembershipInvalidateQueuedAuthorityWithoutChangingOldEnvelope() {
        startBusiness();
        assertThat(worker.process(tenant.getId(), approved.code(), sourcePid)).isEqualTo(2);
        List<IntegrationEventEnvelope> original = events();
        templates.update(templatePid, templateRequest("Changed current configuration"));
        assertThatThrownBy(() -> worker.process(tenant.getId(), approved.code(), sourcePid)).hasMessage("RELIABLE_ENVELOPE_CONFLICT");
        assertThatThrownBy(() -> executor.deliver(original.getFirst(), host, "changed-template"))
                .hasMessage("NOTIFICATION_ENVELOPE_CONFLICT");
        assertThat(events()).isEqualTo(original);
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 0, "inbox", 0));
        templates.update(templatePid, templateRequest("Synthetic registered source body"));
        update("source", sourcePid, Map.of(field("state"), "cancelled"));
        assertThatThrownBy(() -> executor.deliver(original.getFirst(), host, "changed-source"))
                .isInstanceOf(IllegalArgumentException.class);
        update("source", sourcePid, Map.of(field("state"), "pending"));
        assertThat(members.deactivateMember(secondMember.getId())).isTrue();
        assertThatThrownBy(() -> executor.deliver(original.getFirst(), host, "changed-membership"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(events()).isEqualTo(original);
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 0, "inbox", 0));
        proved("same identity changed template conflict; current source eligibility and every explicit recipient membership rechecked; receipt/inbox rolled back");
    }

    @Test
    void actualSecondEnqueueFailureRollsBackFirstQueueAndRestoresCallerContext() {
        startBusiness();
        Map<String, Object> before = businessSnapshot();
        AtomicBoolean firstQueued = new AtomicBoolean();
        NotificationDeliveryAccessor fault = new NotificationDeliveryAccessor() {
            @Override
            public Result enqueue(Request request) {
                if (firstQueued.compareAndSet(false, true)) {
                    Result result = host.enqueue(request);
                    assertThat(counts().get("outbox")).isEqualTo(1);
                    return result;
                }
                throw new IllegalStateException("native fault before second enqueue");
            }
        };
        RegisteredSourceWorker failing = new RegisteredSourceWorker(resolver, reader, fault, manager);
        MetaContext.Snapshot previous = MetaContext.snapshot();
        assertThatThrownBy(() -> failing.process(tenant.getId(), approved.code(), sourcePid))
                .hasMessage("native fault before second enqueue");
        assertThat(firstQueued).isTrue();
        assertThat(MetaContext.snapshot()).isEqualTo(previous);
        assertThat(counts()).isEqualTo(Map.of("outbox", 0, "receipt", 0, "inbox", 0));
        assertThat(businessSnapshot()).isEqualTo(before);
        proved("real first enqueue visible within source transaction; second fault rolls it back on same physical resource; context restored");
    }

    @Test
    void actualInboxAndReceiptRollbackTogetherBeforeSuccessfulDuplicateConsume() {
        startBusiness();
        worker.process(tenant.getId(), approved.code(), sourcePid);
        IntegrationEventEnvelope event = events().getFirst();
        FailingAfterPersist failing = new FailingAfterPersist();
        assertThatThrownBy(() -> executor.deliver(event, failing, "native-after-persist"))
                .hasMessage("native fault after persisted inbox");
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 0, "inbox", 0));
        executor.deliver(event, host, "native-success");
        List<Map<String, Object>> before = jdbc.queryForList("SELECT to_jsonb(n)::text AS row FROM ab_notification n WHERE tenant_id=? ORDER BY id", tenant.getId());
        executor.deliver(event, host, "native-duplicate");
        assertThat(jdbc.queryForList("SELECT to_jsonb(n)::text AS row FROM ab_notification n WHERE tenant_id=? ORDER BY id", tenant.getId())).isEqualTo(before);
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 1, "inbox", 1));
        proved("actual persisted inbox fault rolls back inbox plus receipt; one later successful effect and duplicate suppression");
    }

    @Test
    void actualBindingMutationCannotBeAuthorizedByCachedMetadataOrOldApproval() {
        startBusiness();
        String fieldPid = jdbc.queryForObject("SELECT pid FROM ab_meta_field WHERE tenant_id=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE", String.class, tenant.getId(), field("state"));
        bindings.updateFieldConfig(modelPids.get("source"), fieldPid, true, false, true);
        rejectWithoutEffects();
        proved("official binding configuration API changes raw PG shape; old host approval rejected before business read/queue");
    }

    @Test
    void actualSerializableCrossOwnerPhantomCycleFailsWithoutAutomaticRetryOrQueue() throws Exception {
        startBusiness();
        CountDownLatch predicateRead = new CountDownLatch(1), foreignCommitted = new CountDownLatch(1);
        AtomicBoolean barrierUsed = new AtomicBoolean();
        SourceBindingReader barrier = new DelegatingReader(reader) {
            @Override
            public DataPage page(Binding binding, String model, Map<String, Object> filters, int page, int size) {
                DataPage result = super.page(binding, model, filters, page, size);
                if (model.equals(model("principal")) && first.getPid().equals(filters.get(field("principal"))) && barrierUsed.compareAndSet(false, true)) {
                    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
                    assertThat(result.total()).isEqualTo(1);
                    predicateRead.countDown();
                    await(foreignCommitted);
                }
                return result;
            }
        };
        connect(approved, barrier);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var inserted = pool.submit(() -> {
                bind(tenant, first);
                try {
                    await(predicateRead);
                    String pid = serializable(() -> {
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ab_outbox WHERE tenant_id=?", Integer.class, tenant.getId())).isZero();
                        return principal(first, otherOwner, otherVerification);
                    });
                    foreignCommitted.countDown();
                    return pid;
                } finally {
                    MetaContext.clear();
                }
            });
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> worker.process(tenant.getId(), approved.code(), sourcePid));
            assertThat(failure).isNotNull();
            assertThat(sqlState(failure)).as("actual dangerous read/write predicate cycle must be PostgreSQL serialization failure, not fixture or permission failure").isEqualTo("40001");
            assertThat(inserted.get(30, TimeUnit.SECONDS)).isNotBlank();
            assertThat(barrierUsed).isTrue();
            assertThat(counts()).isEqualTo(Map.of("outbox", 0, "receipt", 0, "inbox", 0));
            connect(approved, reader);
            rejectWithoutEffects();
            proved("two real SERIALIZABLE transactions: active-link predicate/outbox predicate dangerous cycle; PG 40001, zero enqueue, no automatic retry; fresh ambiguous link refused");
        } finally {
            foreignCommitted.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void rejectWithoutEffects() {
        Map<String, Object> before = businessSnapshot();
        Map<String, Integer> beforeCounts = counts();
        assertThatThrownBy(() -> worker.process(tenant.getId(), approved.code(), sourcePid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(counts()).isEqualTo(beforeCounts);
        assertThat(businessSnapshot()).isEqualTo(before);
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).as("native fixture concurrency barrier").isTrue();
        } catch (InterruptedException error) {
            // CATCH: test barrier cancellation, never retry a real transaction or hide a business failure.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("native barrier interrupted", error);
        }
    }

    private String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }

    private final class FailingAfterPersist implements ReliableEventConsumerExtension, HostSerializableEventConsumer {
        @Override public String consumerCode() { return host.consumerCode(); }
        @Override public Set<String> subscribedEventTypes() { return host.subscribedEventTypes(); }
        @Override
        public boolean requiresSerializableTransaction(IntegrationEventEnvelope envelope) {
            return host.requiresSerializableTransaction(envelope);
        }
        @Override public void consume(IntegrationEventEnvelope event) {
            host.consume(event);
            assertThat(counts().get("inbox")).isEqualTo(1);
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
            throw new IllegalStateException("native fault after persisted inbox");
        }
    }

    private static class DelegatingReader implements SourceBindingReader {
        private final SourceBindingReader delegate;
        DelegatingReader(SourceBindingReader delegate) { this.delegate = delegate; }
        @Override public JsonNode metadata(long tenant, Approval approval) { return delegate.metadata(tenant, approval); }
        @Override public Map<String, Object> record(Binding binding, String model, String pid) { return delegate.record(binding, model, pid); }
        @Override public DataPage page(Binding binding, String model, Map<String, Object> filters, int page, int size) { return delegate.page(binding, model, filters, page, size); }
        @Override public JsonNode rule(long tenant, long id) { return delegate.rule(tenant, id); }
        @Override public JsonNode template(long tenant, String code) { return delegate.template(tenant, code); }
        @Override public String userPid(long tenant, long id) { return delegate.userPid(tenant, id); }
    }
}
