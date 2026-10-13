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
    RegisteredSourcePlanResolverTest.Fixture fixture = new RegisteredSourcePlanResolverTest.Fixture();
    @BeforeEach
    void setup() {
        when(sessions.getConfiguration()).thenReturn(new Configuration(new Environment("test", new JdbcTransactionFactory(), ds)));
        reader = new ImportedNotificationMetadataReader(ds, new DataSourceTransactionManager(ds), sessions, mock(DynamicDataService.class));
        ReflectionTestUtils.setField(reader, "jdbc", jdbc);
        ReflectionTestUtils.setField(reader, "data", data);
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clear();
    }
    @Test
    void physicalIdentifiersComeFromServerMetadataAndTenantPidAreBound() {
        jdbc.rows.add(SourceBindingCanonical.tree(Map.of("table_name", "approved_table")));
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
            jdbc.rows.add(SourceBindingCanonical.tree(Map.of("table_name", "approved_table",
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
            jdbc.rows.add(SourceBindingCanonical.tree(Map.of("table_name", "approved_table", "extension", Map.of("softDelete", value))));
            assertThatThrownBy(() -> reader.record(fixture.binding, "source", "src")).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.sql).hasSize(1);
        }
        verifyNoInteractions(data);
    }
    @Test
    void softDeletedAndHardMissingRowsCannotBecomeLiveProjection() {
        jdbc.rows.add(SourceBindingCanonical.tree(Map.of("table_name", "approved_table", "extension", Map.of("softDelete", true))));
        jdbc.rows.add(SourceBindingCanonical.tree(Map.of("tenant_id", 1, "pid", "src", "deleted_flag", true)));
        when(data.getById(1, "source", "src")).thenReturn(Map.of("pid", "src"));
        assertThatThrownBy(() -> reader.record(fixture.binding, "source", "src")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(data);
        jdbc.rows.add(SourceBindingCanonical.tree(Map.of("table_name", "approved_table")));
        jdbc.rows.add(null);
        assertThatThrownBy(() -> reader.record(fixture.binding, "source", "src")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(data);
    }
    @Test
    void textualExtensionUsesCanonicalNestedPrecedenceAndInvalidFormsReject() {
        jdbc.rows.add(SourceBindingCanonical.tree(Map.of("table_name", "approved_table", "extension",
                "{\"extension\":{\"softDelete\":false},\"softDelete\":true}")));
        jdbc.rows.add(SourceBindingCanonical.tree(Map.of("tenant_id", 1, "pid", "src")));
        when(data.getById(1, "source", "src")).thenReturn(Map.of("pid", "src"));
        assertThat(reader.record(fixture.binding, "source", "src")).doesNotContainKey("deleted_flag");
        assertThat(jdbc.sql.get(1)).doesNotContain("deleted_flag=FALSE");
        for (Object invalid : List.of("not-json", List.of("not-object"), Map.of("softDelete", "false "))) {
            jdbc.rows.clear(); jdbc.sql.clear();
            jdbc.rows.add(SourceBindingCanonical.tree(Map.of("table_name", "approved_table", "extension", invalid)));
            assertThatThrownBy(() -> reader.record(fixture.binding, "source", "src")).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.sql).hasSize(1);
        }
    }
    @Test
    void lockedPhysicalRowCannotBypassMissingDataScopeProjection() {
        jdbc.rows.add(SourceBindingCanonical.tree(Map.of("table_name", "approved_table")));
        jdbc.rows.add(SourceBindingCanonical.tree(Map.of("tenant_id", 1, "pid", "src")));
        assertThatThrownBy(() -> reader.record(fixture.binding, "source", "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void callerCannotSelectUnapprovedModelOrUnsafeTable() {
        assertThatThrownBy(() -> reader.record(fixture.binding, "unregistered", "src")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql).isEmpty();
        jdbc.rows.add(SourceBindingCanonical.tree(Map.of("table_name", "x\"; DROP TABLE t")));
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
        var different = new ImportedNotificationMetadataReader(ds, new DataSourceTransactionManager(mock(DataSource.class)), sessions, mock(DynamicDataService.class));
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
