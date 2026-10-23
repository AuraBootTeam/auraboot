package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import com.auraboot.framework.plugin.extension.DataPage;
import com.auraboot.framework.plugin.extension.DataPageQuery;
import com.auraboot.framework.plugin.pf4j.BackgroundDataAccessorImpl;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/** Fixed metadata SQL only; business records go through a finite approved model facade. */
@Component
final class ImportedNotificationMetadataReader implements SourceBindingReader {
    private final JdbcTemplate jdbc;
    private final BackgroundDataAccessor data;
    private final com.auraboot.framework.meta.service.MetaModelService models;
    private final boolean sameResource;
    ImportedNotificationMetadataReader(@Qualifier("dataSource") DataSource ds, PlatformTransactionManager manager, SqlSessionFactory sessions, DynamicDataService dynamic, com.auraboot.framework.meta.service.MetaModelService models) {
        sameResource = manager instanceof DataSourceTransactionManager tx && tx.getDataSource() == ds && sessions.getConfiguration().getEnvironment().getDataSource() == ds;
        this.models = models;
        jdbc = new JdbcTemplate(ds);
        data = new BackgroundDataAccessorImpl(dynamic, null, ds, manager);
    }
    private List<JsonNode> rows(String sql, Object...args) {
        SourceBindingTypes.require(sameResource && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        return jdbc.query(sql,(rs, n) -> SourceBindingCanonical.parse(rs.getString(1)), args);
    }
    private JsonNode one(List<JsonNode> rows) {
        SourceBindingTypes.require(rows.size() == 1);
        return rows.getFirst();
    }
    @Override
    public JsonNode metadata(long tenant, SourceBindingTypes.Approval approval) {
        JsonNode model = one(rows("SELECT to_jsonb(m)::text FROM ab_meta_model m WHERE tenant_id=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE FOR SHARE", tenant, approval.modelCode()));
        List<JsonNode> bindings = rows("SELECT to_jsonb(b)::text FROM ab_meta_model_field_binding b WHERE tenant_id=? AND model_id=? AND deleted_flag=FALSE ORDER BY pid,id FOR SHARE", tenant, model.path("id").longValue());
        List<JsonNode> fields = new ArrayList<>(), tracking = new ArrayList<>(), plugins = new ArrayList<>();
        for (JsonNode binding : bindings) {
            fields.add(one(rows("SELECT to_jsonb(f)::text FROM ab_meta_field f WHERE tenant_id=? AND id=? FOR SHARE", tenant, binding.path("field_id").longValue())));
            tracking.addAll(rows("SELECT to_jsonb(r)::text FROM ab_plugin_resource r WHERE tenant_id=? AND resource_type='model_field_binding' AND resource_pid=? ORDER BY id FOR SHARE", tenant, binding.path("pid").textValue()));
        }
        for (var owner : approval.owners()) plugins.add(one(rows("SELECT to_jsonb(p)::text FROM ab_plugin p WHERE tenant_id=? AND pid=? AND deleted_flag=FALSE FOR SHARE", tenant, owner.pid())));
        // Raw security facts remain approved. Import execution identity and activation time are operational history, not shape.
        return SourceBindingCanonical.tree(Map.of("model", withoutTimes(model), "bindings", bindings.stream().map(this::withoutTimes).toList(), "fields", fields.stream().map(this::withoutTimes).toList(), "tracking", tracking.stream().map(row -> withoutOperationalField(row, "import_id")).toList(), "plugins", plugins.stream().map(this::pluginShape).toList()));
    }
    private JsonNode withoutTimes(JsonNode row) {
        var result = row.deepCopy();
        if (result instanceof com.fasterxml.jackson.databind.node.ObjectNode o) o.remove(List.of("created_at", "updated_at", "imported_at", "last_synced_at", "user_modified_at"));
        return result;
    }
    private JsonNode withoutOperationalField(JsonNode row, String field) {
        var result = withoutTimes(row);
        if (result instanceof com.fasterxml.jackson.databind.node.ObjectNode object) object.remove(field);
        return result;
    }
    private JsonNode pluginShape(JsonNode row) {
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) withoutOperationalField(row, "enabled_at");
        if (result.hasNonNull("installed_at")) {
            result.put("installed_at", canonicalTimestamp(result.get("installed_at")));
        }
        // status remains in the approved graph. An enabled plugin can retain an older stop timestamp.
        if (result.hasNonNull("disabled_at")) canonicalTimestamp(result.get("disabled_at"));
        if ("enabled".equals(result.path("status").asText()) && result.has("disabled_at")) {
            result.putNull("disabled_at");
        }
        return result;
    }

    /** Preserve the installation instant and PostgreSQL's variable fractional precision in UTC. */
    private String canonicalTimestamp(JsonNode value) {
        SourceBindingTypes.require(value.isTextual());
        try {
            var instant = java.time.OffsetDateTime.parse(value.textValue()).withOffsetSameInstant(java.time.ZoneOffset.UTC);
            return new java.time.format.DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd'T'HH:mm:ss")
                    .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 0, 9, true)
                    .appendOffset("+HH:MM", "+00:00").toFormatter(java.util.Locale.ROOT).format(instant);
        } catch (java.time.format.DateTimeParseException invalid) {
            // Expected metadata validation failure; do not include the raw value in diagnostics.
            throw new IllegalArgumentException("NOTIFICATION_SOURCE_METADATA_TIMESTAMP_INVALID");
        }
    }

    private void allowed(SourceBindingTypes.Binding b, String model) {
        SourceBindingTypes.require(sameResource && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        SourceBindingTypes.require(b.approvals().stream().anyMatch(a -> a.modelCode().equals(model)));
    }
    @Override
    public Map<String, Object> record(SourceBindingTypes.Binding b, String model, String pid) {
        allowed(b, model);
        SourceBindingTypes.pid(pid);
        JsonNode nativeModel = one(rows("SELECT to_jsonb(m)::text FROM ab_meta_model m WHERE tenant_id=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE FOR SHARE", b.tenantId(), model));
        String table = physicalTable(b, model, nativeModel);
        boolean softDelete = softDelete(nativeModel);
        String predicate = softDelete ? " AND deleted_flag=FALSE" : "";
        JsonNode nativeRow = one(rows("SELECT to_jsonb(r)::text FROM \"" + table + "\" r WHERE tenant_id=? AND pid=?" + predicate + " FOR SHARE", b.tenantId(), pid));
        SourceBindingTypes.require(pid.equals(nativeRow.path("pid").textValue())
                && nativeRow.path("tenant_id").isIntegralNumber()
                && nativeRow.path("tenant_id").longValue() == b.tenantId());
        if (softDelete) SourceBindingTypes.require(nativeRow.path("deleted_flag").isBoolean()
                && !nativeRow.path("deleted_flag").booleanValue());
        Map<String, Object> scoped = data.getById(b.tenantId(), model, pid);
        SourceBindingTypes.require(scoped != null && pid.equals(scoped.get("pid")));
        Map<String, Object> record = new LinkedHashMap<>(scoped);
        record.put("tenant_id", nativeRow.path("tenant_id").longValue());
        if (softDelete) record.put("deleted_flag", nativeRow.path("deleted_flag").booleanValue());
        return new SourceBindingReader.LiveRecord(record, softDelete);
    }
    /** Resolve through the canonical projection, while binding it to the locked approved identity. */
    private String physicalTable(SourceBindingTypes.Binding b, String model, JsonNode raw) {
        var approval=b.approvals().stream().filter(a->a.modelCode().equals(model)).findFirst().orElseThrow();
        SourceBindingTypes.require(raw.path("tenant_id").isIntegralNumber() && raw.path("tenant_id").longValue()==b.tenantId()
                && model.equals(raw.path("code").textValue()) && raw.path("id").isIntegralNumber()
                && raw.path("version").isIntegralNumber() && raw.path("is_current").isBoolean() && raw.path("is_current").booleanValue()
                && raw.path("deleted_flag").isBoolean() && !raw.path("deleted_flag").booleanValue()
                && "published".equals(raw.path("status").textValue()) && "physical".equals(raw.path("source_type").textValue())
                && (!raw.has("source_ref") || raw.path("source_ref").isNull())
                && approval.owners().stream().anyMatch(owner->owner.pid().equals(raw.path("plugin_pid").textValue())));
        var definition=models.findByCode(model);
        SourceBindingTypes.require(definition!=null && java.util.Objects.equals(definition.getTenantId(),b.tenantId())
                && java.util.Objects.equals(definition.getId(),raw.path("id").longValue())
                && java.util.Objects.equals(definition.getPid(),raw.path("pid").textValue())
                && model.equals(definition.getCode()) && java.util.Objects.equals(definition.getVersion(),raw.path("version").intValue())
                && Boolean.TRUE.equals(definition.getIsCurrent()) && "published".equals(definition.getStatus())
                && "physical".equals(definition.getSourceType()) && definition.getSourceRef()==null);
        String table=definition.getTableName();
        SourceBindingTypes.require(table!=null && table.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"));
        JsonNode declared=raw.path("table_name");
        SourceBindingTypes.require(declared.isMissingNode() || declared.isNull()
                || (declared.isTextual() && (declared.textValue().isBlank() || table.equals(declared.textValue().trim()))));
        return table;
    }
    private boolean softDelete(JsonNode model) {
        JsonNode raw = model.path("extension");
        if (raw.isMissingNode() || raw.isNull()) return false;
        if (raw.isTextual()) raw = SourceBindingCanonical.parse(raw.textValue());
        SourceBindingTypes.require(raw.isObject());
        ExtensionBean extension = new ObjectMapper().convertValue(raw, ExtensionBean.class);
        Object value = extension.get("softDelete");
        boolean declared = (extension.getExtension() != null && extension.getExtension().containsKey("softDelete"))
                || extension.getDynamicProperties().containsKey("softDelete");
        if (!declared) return false;
        SourceBindingTypes.require(value instanceof Boolean || "true".equals(value) || "false".equals(value));
        // Exactly the canonical ModelDefinitionProjection policy, with invalid declared values rejected.
        return Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value));
    }
    @Override
    public DataPage page(SourceBindingTypes.Binding b, String model, Map<String, Object> filters, int page, int size) {
        allowed(b, model);
        return data.queryPage(b.tenantId(), model, new DataPageQuery(filters, Map.of(), List.of(new DataPageQuery.Sort("pid", false)), page, size));
    }
    @Override
    public JsonNode rule(long tenant, long id) {
        return one(rows("SELECT to_jsonb(r)::text FROM ab_notification_rule r WHERE tenant_id=? AND id=? AND deleted_flag=FALSE FOR SHARE", tenant, id));
    }
    @Override
    public JsonNode template(long tenant, String code) {
        return one(rows("SELECT to_jsonb(t)::text FROM ab_notification_template t WHERE tenant_id=? AND code=? FOR SHARE", tenant, code));
    }
    @Override
    public String userPid(long tenant, long id) {
        return one(rows("SELECT jsonb_build_object('pid',u.pid)::text FROM ab_user u JOIN ab_tenant_member tm ON tm.user_id=u.id WHERE tm.tenant_id=? AND u.id=? AND tm.status='active' AND tm.deleted_flag=FALSE AND u.deleted_flag=FALSE AND u.is_enabled=TRUE AND u.is_account_non_expired=TRUE AND u.is_account_non_locked=TRUE AND u.is_credentials_non_expired=TRUE AND u.deactivation_status IS NULL FOR SHARE OF u,tm", tenant, id)).path("pid").textValue();
    }
}
