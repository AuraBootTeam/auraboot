package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImportedNotificationMetadataReaderTest {
    DataSource ds = mock(DataSource.class);
    SqlSessionFactory sessions = mock(SqlSessionFactory.class);
    BackgroundDataAccessor data = mock(BackgroundDataAccessor.class);
    CapturingJdbc jdbc = new CapturingJdbc();
    ImportedNotificationMetadataReader reader;
    com.auraboot.framework.meta.service.MetaModelService models = mock(com.auraboot.framework.meta.service.MetaModelService.class);
    RegisteredSourcePlanResolverTest.Fixture fixture = new RegisteredSourcePlanResolverTest.Fixture();
    @BeforeEach
    void setup() {
        when(sessions.getConfiguration()).thenReturn(new Configuration(new Environment("test", new JdbcTransactionFactory(), ds)));
        reader = new ImportedNotificationMetadataReader(ds, new DataSourceTransactionManager(ds), sessions, mock(DynamicDataService.class), models);
        ReflectionTestUtils.setField(reader, "jdbc", jdbc);
        ReflectionTestUtils.setField(reader, "data", data);
        when(models.findByCode("source")).thenReturn(definition());
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clear();
    }
    private com.auraboot.framework.meta.dto.MetaModelDTO definition() {
        return com.auraboot.framework.meta.dto.MetaModelDTO.builder().id(11L).pid("model-source").tenantId(1L)
                .code("source").version(1).isCurrent(true).status("published").sourceType("physical")
                .tableName("approved_table").build();
    }
    private JsonNode model(Map<String,Object> facts) {
        var values=new java.util.LinkedHashMap<String,Object>(Map.of("id",11,"pid","model-source","tenant_id",1,
                "code","source","version",1,"is_current",true,"deleted_flag",false,"status","published",
                "source_type","physical","plugin_pid","plugin"));
        values.putAll(facts);return SourceBindingCanonical.tree(values);
    }
    @Test void nullableRawTableUsesCanonicalProjectionWithoutChangingApprovalFacts() {
        var raw=(com.fasterxml.jackson.databind.node.ObjectNode)model(Map.of());raw.putNull("table_name");
        String original=raw.toString();jdbc.rows.add(raw);
        jdbc.rows.add(SourceBindingCanonical.tree(Map.of("tenant_id",1,"pid","src")));
        when(data.getById(1,"source","src")).thenReturn(Map.of("pid","src"));
        assertThat(reader.record(fixture.binding,"source","src")).containsEntry("tenant_id",1L);
        assertThat(jdbc.sql.get(1)).contains("FROM \"approved_table\" r").doesNotContain("FROM \"null\"");
        assertThat(raw.toString()).isEqualTo(original);
    }
    @Test void canonicalProjectionCannotUseForeignTenantOwnerTableOrModelRevision() {
        for(String change:List.of("tenant","id","pid","code","revision","current","status","source","table","missing")) {
            jdbc.rows.clear();jdbc.sql.clear();var dto=definition();
            switch(change) {
                case "tenant"->dto.setTenantId(2L);case "id"->dto.setId(12L);case "pid"->dto.setPid("other-model");
                case "code"->dto.setCode("other");case "revision"->dto.setVersion(2);case "current"->dto.setIsCurrent(false);
                case "status"->dto.setStatus("draft");case "source"->dto.setSourceType("namedQuery");
                case "table"->dto.setTableName("different_table");case "missing"->dto=null;
            }
            when(models.findByCode("source")).thenReturn(dto);jdbc.rows.add(model(Map.of("table_name","approved_table")));
            assertThatThrownBy(()->reader.record(fixture.binding,"source","src")).as(change).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.sql).as(change).hasSize(1);
        }
        verifyNoInteractions(data);
        when(models.findByCode("source")).thenReturn(definition());
        for(var invalid:List.of(Map.<String,Object>of("plugin_pid","foreign-owner"),Map.<String,Object>of("tenant_id",2),
                Map.<String,Object>of("version",2),Map.<String,Object>of("is_current",false),Map.<String,Object>of("deleted_flag",true))) {
            jdbc.rows.clear();jdbc.sql.clear();jdbc.rows.add(model(invalid));
            assertThatThrownBy(()->reader.record(fixture.binding,"source","src")).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.sql).hasSize(1);
        }
        verifyNoInteractions(data);
    }
    @Test
    void physicalIdentifiersComeFromServerMetadataAndTenantPidAreBound() {
        jdbc.rows.add(model(Map.of("table_name", "approved_table")));
        jdbc.rows.add(SourceBindingCanonical.tree(Map.of("tenant_id", 1, "pid", "src")));
        when(data.getById(1, "source", "src")).thenReturn(Map.of("pid", "src"));
        assertThat(reader.record(fixture.binding, "source", "src")).containsEntry("tenant_id", 1L).doesNotContainKey("deleted_flag");
        assertThat(jdbc.sql.get(0)).contains("ab_meta_model").contains("FOR SHARE");
        assertThat(jdbc.sql.get(1)).isEqualTo("SELECT to_jsonb(r)::text FROM \"approved_table\" r WHERE tenant_id=? AND pid=? FOR SHARE");
        assertThat(jdbc.args.get(1)).containsExactly(1L, "src");
    }
    @Test
    void explicitFalseUsesHardPolicyAndTrueUsesActualPhysicalMarker() {
        for (Object policy : List.of(false, "false", true, "true")) {
            jdbc.rows.clear(); jdbc.sql.clear(); jdbc.args.clear();
            boolean soft = Boolean.TRUE.equals(policy) || "true".equals(policy);
            jdbc.rows.add(model(Map.of("table_name", "approved_table",
                    "extension", Map.of("extension", Map.of("softDelete", policy)))));
            jdbc.rows.add(SourceBindingCanonical.tree(soft
                    ? Map.of("tenant_id", 1, "pid", "src", "deleted_flag", false)
                    : Map.of("tenant_id", 1, "pid", "src")));
            when(data.getById(1, "source", "src")).thenReturn(Map.of("pid", "src"));
            Map<String, Object> result = reader.record(fixture.binding, "source", "src");
            assertThat(jdbc.sql.get(1).contains("deleted_flag=FALSE")).isEqualTo(soft);
            assertThat(result.containsKey("deleted_flag")).isEqualTo(soft);
            assertThat(jdbc.args.get(1)).containsExactly(1L, "src");
        }
    }
    @Test
    void malformedPolicyIsRejectedBeforeBusinessQueryOrProjection() {
        for (Object value : List.of("TRUE", "garbage", 1, Map.of("nested", true))) {
            jdbc.rows.clear(); jdbc.sql.clear();
            jdbc.rows.add(model(Map.of("table_name", "approved_table", "extension", Map.of("softDelete", value))));
            assertThatThrownBy(() -> reader.record(fixture.binding, "source", "src")).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.sql).hasSize(1);
        }
        verifyNoInteractions(data);
    }
    @Test
    void softDeletedAndHardMissingRowsCannotBecomeLiveProjection() {
        jdbc.rows.add(model(Map.of("table_name", "approved_table", "extension", Map.of("softDelete", true))));
        jdbc.rows.add(SourceBindingCanonical.tree(Map.of("tenant_id", 1, "pid", "src", "deleted_flag", true)));
        when(data.getById(1, "source", "src")).thenReturn(Map.of("pid", "src"));
        assertThatThrownBy(() -> reader.record(fixture.binding, "source", "src")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(data);
        jdbc.rows.add(model(Map.of("table_name", "approved_table")));
        jdbc.rows.add(null);
        assertThatThrownBy(() -> reader.record(fixture.binding, "source", "src")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(data);
    }
    @Test
    void textualExtensionUsesCanonicalNestedPrecedenceAndInvalidFormsReject() {
        jdbc.rows.add(model(Map.of("table_name", "approved_table", "extension",
                "{\"extension\":{\"softDelete\":false},\"softDelete\":true}")));
        jdbc.rows.add(SourceBindingCanonical.tree(Map.of("tenant_id", 1, "pid", "src")));
        when(data.getById(1, "source", "src")).thenReturn(Map.of("pid", "src"));
        assertThat(reader.record(fixture.binding, "source", "src")).doesNotContainKey("deleted_flag");
        assertThat(jdbc.sql.get(1)).doesNotContain("deleted_flag=FALSE");
        for (Object invalid : List.of("not-json", List.of("not-object"), Map.of("softDelete", "false "))) {
            jdbc.rows.clear(); jdbc.sql.clear();
            jdbc.rows.add(model(Map.of("table_name", "approved_table", "extension", invalid)));
            assertThatThrownBy(() -> reader.record(fixture.binding, "source", "src")).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.sql).hasSize(1);
        }
    }
    @Test
    void lockedPhysicalRowCannotBypassMissingDataScopeProjection() {
        jdbc.rows.add(model(Map.of("table_name", "approved_table")));
        jdbc.rows.add(SourceBindingCanonical.tree(Map.of("tenant_id", 1, "pid", "src")));
        assertThatThrownBy(() -> reader.record(fixture.binding, "source", "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void callerCannotSelectUnapprovedModelOrUnsafeTable() {
        assertThatThrownBy(() -> reader.record(fixture.binding, "unregistered", "src")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql).isEmpty();
        jdbc.rows.add(model(Map.of("table_name", "x\"; DROP TABLE t")));
        assertThatThrownBy(() -> reader.record(fixture.binding, "source", "src")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql).hasSize(1);
        verifyNoInteractions(data);
    }
    @Test
    void readOutsideTransactionAndDifferentPhysicalResourceCannotStartQuery() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> reader.rule(1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql).isEmpty();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var different = new ImportedNotificationMetadataReader(ds, new DataSourceTransactionManager(mock(DataSource.class)), sessions, mock(DynamicDataService.class), models);
        ReflectionTestUtils.setField(different, "jdbc", jdbc);
        assertThatThrownBy(() -> different.rule(1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql).isEmpty();
    }
    @Test
    void emptyDisabledPropertiesDoNotActivateAndUnknownApprovalFlagRejects() {
        var runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class)).withUserConfiguration(HostSourceBindingProperties.class);
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var p = ctx.getBean(HostSourceBindingProperties.class);
            assertThat(p.isEnabled()).isFalse();
            assertThat(p.getBindings()).isEmpty();
        });
        runner.withPropertyValues("aura.notification.source-bindings.approved=true").run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test
    void explicitScalarDoesNotCoerceBooleanIntoString() {
        assertThat(new SourceBindingTypes.Scalar(SourceBindingTypes.ScalarType.BOOLEAN, "true").raw()).isEqualTo(true);
        assertThat(new SourceBindingTypes.Scalar(SourceBindingTypes.ScalarType.STRING, "true").raw()).isEqualTo("true");
        assertThatThrownBy(() -> new SourceBindingTypes.Scalar(SourceBindingTypes.ScalarType.BOOLEAN, "TRUE")).isInstanceOf(IllegalArgumentException.class);
    }
    private JsonNode snapshot(long importId, String activatedAt, String changedSection) {
        var model = (com.fasterxml.jackson.databind.node.ObjectNode) model(Map.of("unknown_security_policy", "bound"));
        var binding = SourceBindingCanonical.tree(Map.of("tenant_id", 1, "model_id", 11, "field_id", 21, "pid", "binding", "deleted_flag", false));
        var field = SourceBindingCanonical.tree(Map.of("id", 21, "pid", "field", "tenant_id", 1, "feature", Map.of("readOnly", false)));
        var tracking = SourceBindingCanonical.tree(Map.of("pid", "tracking", "resource_pid", "binding", "plugin_pid", "plugin", "resource_type", "model_field_binding", "import_id", importId));
        var plugin = (com.fasterxml.jackson.databind.node.ObjectNode) SourceBindingCanonical.tree(Map.of("pid", "plugin", "tenant_id", 1, "version", "1.0.0", "enabled", true, "status", "enabled", "enabled_at", activatedAt,
                "installed_at", "2026-10-20T12:27:50.06032+00:00"));
        plugin.putNull("disabled_at");
        switch (changedSection) {
            case "model" -> model.put("unknown_security_policy", "changed");
            case "modelImport" -> model.put("import_id", importId);
            case "binding" -> ((com.fasterxml.jackson.databind.node.ObjectNode) binding).put("command_scope", "other");
            case "field" -> ((com.fasterxml.jackson.databind.node.ObjectNode) field).put("feature", "{\"readOnly\":true}");
            case "tracking" -> ((com.fasterxml.jackson.databind.node.ObjectNode) tracking).put("plugin_pid", "other-owner");
            case "plugin" -> ((com.fasterxml.jackson.databind.node.ObjectNode) plugin).put("enabled", false);
            case "version" -> plugin.put("version", "2.0.0");
            case "offsetAndReenabled" -> {
                plugin.put("installed_at", "2026-10-20T20:27:50.06032+08:00");
                plugin.put("disabled_at", "2026-10-24T01:46:40.527941+08:00");
            }
            case "disabledStatus" -> plugin.put("status", "disabled");
            case "installedInstant" -> plugin.put("installed_at", "2026-10-20T12:27:51.06032+00:00");
            case "invalidInstalled" -> plugin.put("installed_at", "invalid");
            default -> { }
        }
        jdbc.rows.addAll(List.of(model, binding, field, tracking, plugin));
        var approval = fixture.binding.approvals().stream().filter(row -> row.modelCode().equals("source")).findFirst().orElseThrow();
        return reader.metadata(1, approval);
    }

    @Test
    void sameMetadataShapeSurvivesNewImportExecutionAndPluginActivationTime() {
        var before = snapshot(10, "2026-10-23T01:00:00Z", "");
        var after = snapshot(11, "2026-10-23T02:00:00Z", "");
        assertThat(SourceBindingCanonical.hash(after)).isEqualTo(SourceBindingCanonical.hash(before));
        assertThat(after.path("tracking").get(0).has("import_id")).isFalse();
        assertThat(after.path("plugins").get(0).has("enabled_at")).isFalse();
    }

    @Test
    void metadataApprovalSurvivesEquivalentOffsetAndReenabledPluginHistory() {
        JsonNode original = snapshot(10, "2026-10-23T01:00:00Z", "");
        JsonNode reenabled = snapshot(11, "2026-10-23T02:00:00Z", "offsetAndReenabled");
        assertThat(SourceBindingCanonical.hash(reenabled)).isEqualTo(SourceBindingCanonical.hash(original));
        assertThat(reenabled.path("plugins").get(0).path("installed_at").asText())
                .isEqualTo("2026-10-20T12:27:50.06032+00:00");
        assertThat(reenabled.path("plugins").get(0).path("disabled_at").isNull()).isTrue();
        assertThat(SourceBindingCanonical.hash(snapshot(12, "2026-10-23T03:00:00Z", "disabledStatus")))
                .isNotEqualTo(SourceBindingCanonical.hash(original));
        assertThat(SourceBindingCanonical.hash(snapshot(12, "2026-10-23T03:00:00Z", "installedInstant")))
                .isNotEqualTo(SourceBindingCanonical.hash(original));
        assertThatThrownBy(() -> snapshot(12, "2026-10-23T03:00:00Z", "invalidInstalled"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvalStillChangesForSecurityFactsAndUnknownMetadataExtensions() {
        String original = SourceBindingCanonical.hash(snapshot(10, "2026-10-23T01:00:00Z", ""));
        for (String section : List.of("model", "modelImport", "binding", "field", "tracking", "plugin", "version")) {
            assertThat(SourceBindingCanonical.hash(snapshot(11, "2026-10-23T02:00:00Z", section)))
                    .as(section).isNotEqualTo(original);
        }
    }

    static class CapturingJdbc extends JdbcTemplate {
        List<JsonNode> rows = new ArrayList<>();
        List<String> sql = new ArrayList<>();
        List<Object[]> args = new ArrayList<>();
        @Override
        @SuppressWarnings("unchecked")
        public<T> List<T> query(String sql, RowMapper<T> mapper, Object...args) {
            this.sql.add(sql);
            this.args.add(args);
            JsonNode next = rows.removeFirst();
            return next == null ? List.of() : (List<T>) List.of(next);
        }
    }
}
