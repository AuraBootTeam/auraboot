package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.ModelFieldRenameColumnPolicy;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.ModelFieldRenameReceipt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A declared same-model rename. No DROP, global field mutation, or audit/version rewrite. */
@Component
@RequiredArgsConstructor
public class ModelFieldRenameMigrator {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ModelFieldRenameColumnPolicy columns;
    private final MetaModelService models;

    public record Rename(String modelCode, String fromField, String toField) {}
    private record Plan(Rename rename, Map<String, Object> model, Map<String, Object> source,
                        Map<String, Object> previous, boolean required) {}

    /** Legacy mapping files are handled by the existing v1 migrator, without reinterpretation. */
    static List<Rename> parse(JsonNode root) {
        if (!root.has("schemaVersion")) {
            if (root.has("modelFieldRenames")) throw invalid("Missing field rename schemaVersion");
            return List.of();
        }
        require(root.isObject() && keys(root).equals(Set.of("schemaVersion", "modelFieldRenames")), "Unknown v2 mapping key");
        require(root.get("schemaVersion").isIntegralNumber() && root.get("schemaVersion").canConvertToInt() && root.get("schemaVersion").intValue() == 2, "Unsupported mapping version");
        JsonNode entries = root.get("modelFieldRenames");
        require(entries.isArray() && !entries.isEmpty() && entries.size() <= 100, "Invalid rename roster");
        List<Rename> result = new ArrayList<>();
        Set<String> touched = new HashSet<>();
        for (JsonNode entry : entries) {
            require(entry.isObject() && keys(entry).equals(Set.of("modelCode", "fromField", "toField")), "Unknown rename key");
            String model = token(entry.get("modelCode"));
            String from = token(entry.get("fromField"));
            String to = token(entry.get("toField"));
            require(!from.equals(to), "Source and target must differ");
            require(!SystemFieldConstants.ALL_INFRASTRUCTURE.contains(from) && !SystemFieldConstants.ALL_INFRASTRUCTURE.contains(to) && !SystemFieldConstants.SOFT_DELETE_MARKERS.contains(from) && !SystemFieldConstants.SOFT_DELETE_MARKERS.contains(to), "Infrastructure field rename denied");
            require(touched.add(model + "." + from) && touched.add(model + "." + to), "Overlapping rename");
            result.add(new Rename(model, from, to));
        }
        return List.copyOf(result);
    }

    public Session prepare(String mappingPath, PluginManifestExtended manifest, long tenant,
                           String pluginPid, String importId) {
        assertForwardOnly(manifest, tenant);
        if (mappingPath == null || mappingPath.isBlank()) return new Session(List.of(), null, tenant, pluginPid, importId);
        JsonNode root;
        try { root = json.readTree(Files.readString(Path.of(mappingPath))); }
        catch (IOException ex) { throw new IllegalArgumentException("Unreadable identifier mapping", ex); }
        List<Rename> renames = parse(root);
        if (renames.isEmpty()) return new Session(List.of(), null, tenant, pluginPid, importId);
        require(TransactionSynchronizationManager.isActualTransactionActive(), "Field rename requires import transaction");
        require(Long.valueOf(tenant).equals(MetaContext.getCurrentTenantId()), "Tenant context mismatch");
        List<Plan> plans = new ArrayList<>();
        for (Rename rename : renames) {
            validateDeclaration(manifest, rename);
            rejectIndependentPolicies(tenant, rename);
            // Serialize migrations of a shared model, including different importing tenants.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, "field-rename:" + rename.modelCode());
            Map<String, Object> model = model(tenant, rename.modelCode());
            Map<String, Object> source = null;
            Map<String, Object> previous = null;
            if (model != null) {
                require(pluginPid.equals(model.get("plugin_pid")), "Model owner mismatch");
                source = field(model, rename.fromField(), false);
                previous = previous(tenant, model, rename);
                require(source != null || previous != null || rowCount(model, tenant) == 0, "Missing source binding for existing business rows");
                if (source != null) {
                    validateField(source, rename.fromField());
                    require(Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS(SELECT 1 FROM (SELECT plugin_pid,user_modified FROM ab_plugin_resource
                             WHERE tenant_id=? AND resource_type='model_field_binding' AND resource_pid=?
                             ORDER BY id DESC LIMIT 1) owned WHERE plugin_pid=? AND coalesce(user_modified,false)=false)
                            """, Boolean.class, tenant, source.get("binding_pid"), pluginPid)), "Source binding is not owned by this import");
                }
            }
            boolean required = manifest.getModelFieldBindings().stream().filter(b -> rename.modelCode().equals(b.getModelCode()) && rename.toField().equals(b.getFieldCode())).findFirst().orElseThrow().getRequired() == Boolean.TRUE;
            plans.add(new Plan(rename, model, source, previous, required));
        }
        var scope = columns.open(renames.stream().map(r -> new ModelFieldRenameColumnPolicy.ApprovedField(tenant, pluginPid, r.modelCode(), r.toField())).toList());
        // Import publication can cache uncommitted definitions; evict on either outcome.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { plans.forEach(p -> evict(tenant, p.rename())); }
        });
        return new Session(plans, scope, tenant, pluginPid, importId);
    }

    private void assertForwardOnly(PluginManifestExtended manifest, long tenant) {
        for (var retired : jdbc.queryForList("SELECT model_code,from_field FROM ab_plugin_field_rename WHERE tenant_id=? AND state='applied'", tenant)) {
            String model = retired.get("model_code").toString();
            String field = retired.get("from_field").toString();
            rejectScopedReferences(json.valueToTree(manifest), model, field);
            if (manifest.getModelFieldBindings() != null) require(manifest.getModelFieldBindings().stream()
                    .noneMatch(b -> model.equals(b.getModelCode()) && field.equals(b.getFieldCode())), "Retired model field binding cannot be reintroduced");
            if (manifest.getModels() != null) for (var declared : manifest.getModels()) {
                if (model.equals(declared.getCode())) require(!containsText(json.valueToTree(declared), field), "Model still references retired field");
            }
            if (manifest.getCommands() != null) for (var command : manifest.getCommands()) {
                if (model.equals(command.getModelCode()) || command.getTargetModels() != null && command.getTargetModels().contains(model)) {
                    require(!containsText(json.valueToTree(command), field), "Command still references retired model field");
                }
            }
            if (manifest.getBindingRules() != null) for (var rule : manifest.getBindingRules()) {
                if (model.equals(rule.getTargetModel())) require(!containsText(json.valueToTree(rule), field), "Rule still references retired model field");
            }
        }
    }

    private static void rejectScopedReferences(JsonNode node, String model, String field) {
        if (node.isObject() && (model.equals(node.path("modelCode").asText())
                || model.equals(node.path("model").asText()) || model.equals(node.path("targetModel").asText()))) {
            require(!containsText(node, field), "Resource still references retired model field");
        }
        for (JsonNode child : node) rejectScopedReferences(child, model, field);
    }

    private void rejectIndependentPolicies(long tenant, Rename r) {
        Long audits = jdbc.queryForObject("SELECT count(*) FROM ab_field_audit_config WHERE tenant_id=? AND model_code=? AND field_code=?", Long.class, tenant, r.modelCode(), r.fromField());
        require(audits != null && audits == 0, "Legacy field has an independent audit policy; explicit policy migration is required");
        Long masks = jdbc.queryForObject("SELECT count(*) FROM ab_field_mask_config WHERE tenant_id=? AND model_code=? AND field_code=?", Long.class, tenant, r.modelCode(), r.fromField());
        require(masks != null && masks == 0, "Legacy field has an independent mask policy; explicit policy migration is required");
        Long policies = jdbc.queryForObject("""
                SELECT count(*) FROM ab_data_permission_policy WHERE tenant_id=? AND model_code=?
                 AND (field_code=? OR position(? in coalesce(scope_expression,''))>0
                      OR position(? in coalesce(condition_ast::text,''))>0 OR position(? in coalesce(mask_expression,''))>0)
                """, Long.class, tenant, r.modelCode(), r.fromField(), r.fromField(), r.fromField(), r.fromField());
        require(policies != null && policies == 0, "Legacy field has an independent access policy; explicit policy migration is required");
    }

    public void assertHistoricalRollbackSafe(Long tenant, String plugin) {
        Boolean migrated = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ab_plugin_field_rename WHERE tenant_id=? AND plugin_pid=?)", Boolean.class, tenant, plugin);
        require(!Boolean.TRUE.equals(migrated), "A field-rename import requires a forward migration; destructive resource rollback is not supported");
    }

    private void validateDeclaration(PluginManifestExtended manifest, Rename r) {
        require(manifest.getModels() != null && manifest.getModels().stream().filter(m -> r.modelCode().equals(m.getCode())).count() == 1, "Model must be declared by this import");
        var model = manifest.getModels().stream().filter(m -> r.modelCode().equals(m.getCode())).findFirst().orElseThrow();
        JsonNode declaration = json.valueToTree(model);
        require(!containsText(declaration, r.fromField()), "Model still references legacy field");
        require(manifest.getFields() != null && manifest.getFields().stream().filter(f -> r.toField().equals(f.getCode())).count() == 1, "Target field must be declared");
        var target = manifest.getFields().stream().filter(f -> r.toField().equals(f.getCode())).findFirst().orElseThrow();
        require(target.getColumnName() == null || target.getColumnName().equals(r.toField()), "Physical alias rename denied");
        require(manifest.getModelFieldBindings() != null && manifest.getModelFieldBindings().stream().filter(b -> r.modelCode().equals(b.getModelCode()) && r.toField().equals(b.getFieldCode())).count() == 1, "Target binding must be declared once");
        require(manifest.getModelFieldBindings().stream().noneMatch(b -> r.modelCode().equals(b.getModelCode()) && r.fromField().equals(b.getFieldCode())), "Legacy binding still declared");
    }

    public final class Session implements AutoCloseable {
        private final List<Plan> plans;
        private final ModelFieldRenameColumnPolicy.AutoCloseableScope scope;
        private final long tenant;
        private final String plugin;
        private final String importId;
        private boolean completed;
        private Session(List<Plan> plans, ModelFieldRenameColumnPolicy.AutoCloseableScope scope, long tenant, String plugin, String importId) {
            this.plans = plans; this.scope = scope; this.tenant = tenant; this.plugin = plugin; this.importId = importId;
        }
        public boolean isV2() { return !plans.isEmpty(); }
        public List<ModelFieldRenameReceipt> complete() {
            require(!completed, "Migration already completed");
            List<ModelFieldRenameReceipt> receipts = new ArrayList<>();
            for (Plan plan : plans) receipts.add(apply(plan, tenant, plugin, importId));
            completed = true;
            return List.copyOf(receipts);
        }
        @Override public void close() { if (scope != null) scope.close(); }
    }

    private ModelFieldRenameReceipt apply(Plan p, long tenant, String plugin, String importId) {
        Rename r = p.rename();
        Map<String, Object> model = model(tenant, r.modelCode());
        require(model != null && plugin.equals(model.get("plugin_pid")), "Imported model owner mismatch");
        if (p.model() != null) require(p.model().get("pid").equals(model.get("pid")) && p.model().get("table_name").equals(model.get("table_name")), "Model identity drift");
        String table = identifier(model.get("table_name"));
        Map<String, Object> target = field(model, r.toField(), true);
        validateField(target, r.toField());
        if (p.source() != null) {
            for (String capability : List.of("visible", "editable")) require(Objects.equals(p.source().get(capability), target.get(capability)), "Binding capability differs after rename: " + capability);
            require(!Boolean.TRUE.equals(p.source().get("required")) || Boolean.TRUE.equals(target.get("required")), "Required binding cannot be weakened by a rename");
        }
        String targetHash = digest(target);
        String sourceHash = p.source() == null ? "0".repeat(64) : digest(p.source());
        Map<String, Object> prior = p.previous();
        if (prior != null) {
            require(plugin.equals(prior.get("plugin_pid")) && table.equals(prior.get("physical_table")) && targetHash.equals(prior.get("target_definition_sha256")), "Migration definition drift");
            sourceHash = prior.get("source_definition_sha256").toString();
            if (p.source() != null) require(sourceHash.equals(digest(p.source())), "Legacy definition drift");
        }
        rejectIndependentPolicies(tenant, r);
        rejectRules(tenant, r);
        Long migration;
        if (prior == null) {
            migration = jdbc.queryForObject("""
                    INSERT INTO ab_plugin_field_rename (tenant_id,plugin_pid,model_pid,model_code,physical_table,from_field,to_field,
                      source_definition_sha256,target_definition_sha256,first_import_id,state)
                    VALUES (?,?,?,?,?,?,?,?,?,?,'applied') RETURNING id
                    """, Long.class, tenant, plugin, model.get("pid"), r.modelCode(), table, r.fromField(), r.toField(), sourceHash, targetHash, importId);
        } else migration = ((Number) prior.get("id")).longValue();
        var definition = models.getModelDefinitionFromDb(r.modelCode()).orElseThrow(() -> invalid("Imported definition is missing"));
        require(table.equals(definition.getTableName()), "Definition table mismatch");
        var targetDefinition = definition.getFields().stream().filter(f -> r.toField().equals(f.getCode())).findFirst().orElseThrow(() -> invalid("Target definition missing"));
        require(r.toField().equals(targetDefinition.getColumnName()) && !targetDefinition.isJsonbVirtual()
                && !targetDefinition.isTransientField() && !targetDefinition.isPrimaryKey(), "Target is not a physical business column");
        rejectDependencies(model, r, targetDefinition);
        int max = targetDefinition.getMaxLength() == null ? 0 : targetDefinition.getMaxLength();
        boolean required = Boolean.TRUE.equals(target.get("required"));
        require(required == p.required() && required == targetDefinition.isRequired(), "Imported logical required differs from declaration");
        long copied = 0, skipped = 0;
        // Rows of other tenants are never selected, copied, locked, or marked.
        jdbc.execute("LOCK TABLE " + quote(table) + " IN SHARE ROW EXCLUSIVE MODE");
        String lastPid = null;
        while (true) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT pid," + quote(r.toField()) + " AS target_value"
                    + (p.source() != null || prior != null && !"0".repeat(64).equals(sourceHash) ? "," + quote(r.fromField()) + " AS source_value" : ",NULL AS source_value")
                    + " FROM " + quote(table) + " WHERE tenant_id=? AND (?::text IS NULL OR pid>?) ORDER BY pid LIMIT 500 FOR UPDATE", tenant, lastPid, lastPid);
            for (Map<String, Object> row : rows) {
                Boolean applied = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ab_plugin_field_rename_row WHERE migration_id=? AND record_pid=?)", Boolean.class, migration, row.get("pid"));
                // Applied migrations are atomic. Later inserts and edits use the new
                // schema and must never be reconstructed from a retained legacy column.
                if (prior != null || Boolean.TRUE.equals(applied)) { skipped++; continue; }
                Object value = row.get("source_value");
                validateValue(value, row.get("target_value"), max, required);
                if (value instanceof String text && targetDefinition.getMinLength() != null) {
                    require(text.codePointCount(0, text.length()) >= targetDefinition.getMinLength(), "Target minimum length would be violated");
                }
                jdbc.update("UPDATE " + quote(table) + " SET " + quote(r.toField()) + "=? WHERE tenant_id=? AND pid=?", value, tenant, row.get("pid"));
                jdbc.update("INSERT INTO ab_plugin_field_rename_row (migration_id,record_pid) VALUES (?,?)", migration, row.get("pid"));
                copied++;
            }
            if (rows.size() < 500) break;
            lastPid = rows.getLast().get("pid").toString();
        }
        // New-schema inserts no longer write the retained legacy column. It must be
        // physically nullable; other tenants still enforce their own logical required.
        var legacyColumns = jdbc.queryForList("""
                SELECT data_type,is_nullable FROM information_schema.columns WHERE table_schema=current_schema()
                  AND table_name=? AND column_name=?
                """, table, r.fromField());
        require(legacyColumns.size() <= 1, "Ambiguous legacy column");
        require(legacyColumns.size() == 1 || p.source() == null && "0".repeat(64).equals(sourceHash), "Retained legacy column is missing");
        if (!legacyColumns.isEmpty()) {
            var legacy = legacyColumns.getFirst();
            require(Set.of("text", "character varying", "character").contains(legacy.get("data_type")), "Legacy physical column is not text");
            if ("NO".equals(legacy.get("is_nullable"))) jdbc.execute("ALTER TABLE " + quote(table) + " ALTER COLUMN " + quote(r.fromField()) + " DROP NOT NULL");
        }
        // Metadata retirement only. Never call unbindFieldFromModel (which drops a shared column).
        if (p.source() != null) {
            require(jdbc.update("""
                    UPDATE ab_meta_model_field_binding SET deleted_flag=true
                     WHERE model_id=? AND tenant_id=? AND field_id=? AND deleted_flag=false
                    """, model.get("id"), tenant, p.source().get("field_id")) == 1, "Source binding changed during migration");
        }
        if (p.source() != null) jdbc.update("""
                UPDATE ab_plugin_resource SET current_state=coalesce(current_state,'{}'::jsonb)
                  || jsonb_build_object('fieldRenameRetired',true,'fieldRenameMigrationId',?)
                 WHERE id=(SELECT id FROM ab_plugin_resource WHERE tenant_id=? AND plugin_pid=?
                   AND resource_type='model_field_binding' AND resource_pid=? ORDER BY id DESC LIMIT 1)
                """, migration, tenant, plugin, p.source().get("binding_pid"));
        evict(tenant, r);
        models.refreshModelCache(r.modelCode());
        return new ModelFieldRenameReceipt(r.modelCode(), model.get("pid").toString(), r.fromField(), r.toField(), sourceHash, targetHash, copied, skipped, p.source() == null && prior == null);
    }

    static void validateValue(Object source, Object target, int max, boolean required) {
        require(source == null || source instanceof String, "Non-text migration value");
        require(target == null || target instanceof String, "Non-text target value");
        require(!required || source instanceof String text && !text.trim().isEmpty(), "Required target would be empty");
        if (source instanceof String text) require(max <= 0 || text.codePointCount(0, text.length()) <= max, "Target length would truncate source");
        require(target == null || Objects.equals(source, target), "Conflicting target value");
    }

    private Map<String, Object> model(long tenant, String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,pid,plugin_pid,table_name,source_type FROM ab_meta_model WHERE tenant_id=? AND code=? AND is_current=true AND deleted_flag=false FOR UPDATE", tenant, code);
        require(rows.size() <= 1, "Ambiguous model metadata");
        if (rows.isEmpty()) return null;
        var model = new LinkedHashMap<>(rows.getFirst());
        require(model.get("source_type") == null || "physical".equals(model.get("source_type")), "Only physical models can migrate");
        var definition = models.getModelDefinitionFromDb(code).orElseThrow(() -> invalid("Effective model definition is missing"));
        String effective = identifier(definition.getTableName());
        Object declared = model.get("table_name");
        require(declared == null || declared instanceof String text && (text.isBlank() || text.equals(effective)), "Raw/effective table mismatch");
        model.put("table_name", effective);
        return model;
    }

    private Map<String, Object> field(Map<String, Object> model, String code, boolean required) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.pid AS binding_pid,f.id AS field_id,f.pid AS field_pid,f.code,f.data_type,f.ref_target::text AS ref_target,
                  f.feature::text AS feature,f.extension::text AS extension,f.rule_schema::text AS rule_schema,
                  b.required,b.visible,b.editable,b.validation_rules::text AS binding_validation,b.validation_override,b.is_system_binding,b.alias_code
                 FROM ab_meta_model_field_binding b JOIN ab_meta_field f ON f.id=b.field_id
                 WHERE b.model_id=? AND b.tenant_id=? AND b.deleted_flag=false AND f.code=? AND f.deleted_flag=false
                """, model.get("id"), MetaContext.getCurrentTenantId(), code);
        require(rows.size() <= 1 && (!required || rows.size() == 1), "Missing or ambiguous model binding");
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void validateField(Map<String, Object> field, String code) {
        require(field != null && code.equals(field.get("code")), "Field identity mismatch");
        require(Set.of("string", "text").contains(Objects.toString(field.get("data_type"), "").toLowerCase(java.util.Locale.ROOT)), "Only text field renames are supported");
        require(!Boolean.TRUE.equals(field.get("is_system_binding")) && (field.get("alias_code") == null || field.get("alias_code").toString().isBlank()), "System/alias binding rename denied");
        JsonNode feature = tree(field.get("feature"));
        for (String key : List.of("unique", "readonly", "hidden", "disabled", "i18nEnabled")) require(!feature.path(key).asBoolean(false), "Unsupported source field capability: " + key);
        for (String key : List.of("validationRules", "permission", "computeExpression", "computeDependencies", "rollUp")) {
            JsonNode value=feature.get(key);require(value==null || value.isNull() || value.isContainerNode() && value.isEmpty() || value.isTextual() && value.textValue().isEmpty(), "Customized field behavior requires explicit migration");
        }
        JsonNode validation=feature.get("validation");
        if(validation!=null && !validation.isNull()) {
            require(validation.isObject(), "Text validation must be an object");
            validation.fields().forEachRemaining(entry -> {
                require(Set.of("minLength","maxLength").contains(entry.getKey()) && entry.getValue().isIntegralNumber()
                        && entry.getValue().canConvertToInt() && entry.getValue().intValue()>=0,
                        "Custom text validation requires explicit migration");
            });
        }
        require(field.get("rule_schema") == null || tree(field.get("rule_schema")).isEmpty(), "Custom field validation requires explicit migration");
        require(field.get("binding_validation") == null || tree(field.get("binding_validation")).isEmpty(), "Custom binding validation requires explicit migration");
        require(field.get("validation_override") == null || field.get("validation_override").toString().isBlank(), "Binding validation override requires explicit migration");
        JsonNode virtualType = feature.get("virtualType");
        require(virtualType == null || virtualType.isNull() || virtualType.isTextual() && virtualType.textValue().isEmpty(), "Virtual field rename denied");
        JsonNode extension = tree(field.get("extension"));
        for (String key : List.of("immutable", "immutableWhen", "allowedWriterCommands")) for (JsonNode value : extension.findValues(key)) {
            require(value.isNull() || value.isBoolean() && !value.booleanValue() || value.isContainerNode() && value.isEmpty(), "Customized field writer/immutability requires explicit migration");
        }
        for (JsonNode permission : extension.findValues("fieldPermission")) require(permission.isNull() || permission.isEmpty(), "Customized field permission requires explicit policy migration");
        for (JsonNode column : extension.findValues("columnName")) require(column.isNull() || column.isTextual() && code.equals(column.textValue()), "Physical alias rename denied");
        for (String key : List.of("storage", "jsonbPath", "jsonbHostField", "transient", "transientField", "virtual", "primaryKey", "isPrimaryKey")) {
            require(!extension.hasNonNull(key), "Virtual/aliased/system field rename denied");
        }
        require(field.get("ref_target") == null || tree(field.get("ref_target")).isEmpty(), "Reference field rename denied");
    }

    private void rejectDependencies(Map<String,Object> model, Rename r, com.auraboot.framework.meta.dto.FieldDefinition target) {
        String table=identifier(model.get("table_name"));
        for (var other : jdbc.queryForList("""
                SELECT f.feature::text AS feature,f.extension::text AS extension FROM ab_meta_model_field_binding b
                 JOIN ab_meta_field f ON f.id=b.field_id WHERE b.model_id=? AND b.tenant_id=?
                  AND b.deleted_flag=false AND f.deleted_flag=false AND f.code<>?
                """,model.get("id"),MetaContext.getCurrentTenantId(),r.fromField())) {
            for (String property : List.of("computeExpression","computeDependencies","immutableWhen","rollUp")) {
                for (String document : List.of("feature","extension")) for (JsonNode value : tree(other.get(document)).findValues(property)) {
                    require(!containsText(value,r.fromField()), "Another field depends on the retired field");
                }
            }
        }
        Long constraints=jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.relname=?
                 JOIN pg_namespace n ON n.oid=t.relnamespace AND n.nspname=current_schema()
                 JOIN pg_attribute a ON a.attrelid=t.oid AND a.attname=? AND NOT a.attisdropped
                 WHERE (c.conrelid=t.oid AND a.attnum=ANY(c.conkey))
                    OR (c.confrelid=t.oid AND a.attnum=ANY(c.confkey))
                """,Long.class,table,r.fromField());
        require(constraints!=null && constraints==0,"Legacy field has a physical business constraint; explicit constraint migration is required");
        var indexes=jdbc.queryForList("""
                SELECT ci.relname AS name,am.amname AS method,i.indisunique AS unique_index,
                  i.indpred IS NOT NULL AS partial_index,i.indexprs IS NOT NULL AS expression_index,
                  i.indnatts=i.indnkeyatts AS no_includes,
                  NOT EXISTS(SELECT 1 FROM unnest(i.indoption) v WHERE v<>0) AS default_order,
                  array_to_json(ARRAY(SELECT a.attname FROM unnest(i.indkey) WITH ORDINALITY k(attnum,ord)
                     JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum ORDER BY k.ord))::text AS columns,
                  array_to_json(ARRAY(SELECT opc.opcname FROM unnest(i.indclass) WITH ORDINALITY k(opclass,ord)
                     JOIN pg_opclass opc ON opc.oid=k.opclass ORDER BY k.ord))::text AS opclasses
                 FROM pg_index i JOIN pg_class t ON t.oid=i.indrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                  JOIN pg_class ci ON ci.oid=i.indexrelid JOIN pg_am am ON am.oid=ci.relam
                  JOIN pg_attribute source ON source.attrelid=t.oid AND source.attname=? AND NOT source.attisdropped
                 WHERE t.relname=? AND n.nspname=current_schema() AND (source.attnum=ANY(i.indkey)
                   OR EXISTS(SELECT 1 FROM pg_depend d WHERE d.classid='pg_class'::regclass AND d.objid=i.indexrelid
                     AND d.refobjid=t.oid AND d.refobjsubid=source.attnum))
                """,r.fromField(),table);
        for (var index : indexes) {
            require(Boolean.FALSE.equals(index.get("unique_index")) && Boolean.FALSE.equals(index.get("partial_index"))
                    && Boolean.FALSE.equals(index.get("expression_index")) && Boolean.TRUE.equals(index.get("no_includes"))
                    && Boolean.TRUE.equals(index.get("default_order")),"Unsupported legacy index semantics");
            String expected="idx_"+table+"_"+r.fromField()+"_trgm";
            expected=expected.substring(0,Math.min(expected.length(),63));
            require(expected.equals(index.get("name")) && "gin".equals(index.get("method")) && target.isSearchable()
                    && tree(index.get("columns")).equals(json.valueToTree(List.of(r.fromField())))
                    && tree(index.get("opclasses")).equals(json.valueToTree(List.of("gin_trgm_ops"))), "Custom legacy index requires explicit migration");
            String targetIndex="idx_"+table+"_"+r.toField()+"_trgm";
            targetIndex=targetIndex.substring(0,Math.min(targetIndex.length(),63));
            Boolean present=jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_index i JOIN pg_class t ON t.oid=i.indrelid
                     JOIN pg_class x ON x.oid=i.indexrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                     JOIN pg_am am ON am.oid=x.relam JOIN pg_attribute a ON a.attrelid=t.oid AND a.attname=?
                     WHERE t.relname=? AND n.nspname=current_schema() AND x.relname=? AND am.amname='gin'
                      AND NOT i.indisunique AND i.indpred IS NULL AND i.indexprs IS NULL
                      AND i.indnatts=1 AND i.indnkeyatts=1 AND a.attnum=ANY(i.indkey)
                      AND EXISTS(SELECT 1 FROM unnest(i.indclass) k JOIN pg_opclass o ON o.oid=k WHERE o.opcname='gin_trgm_ops'))
                    """,Boolean.class,r.toField(),table,targetIndex);
            require(Boolean.TRUE.equals(present),"Equivalent target search index is missing");
        }
    }

    private Map<String, Object> previous(long tenant, Map<String, Object> model, Rename r) {
        var rows = jdbc.queryForList("SELECT * FROM ab_plugin_field_rename WHERE tenant_id=? AND model_pid=? AND from_field=? AND to_field=? FOR UPDATE", tenant, model.get("pid"), r.fromField(), r.toField());
        require(rows.size() <= 1, "Ambiguous migration ledger");
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private long rowCount(Map<String, Object> model, long tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM " + quote(identifier(model.get("table_name"))) + " WHERE tenant_id=?", Long.class, tenant);
    }
    private void rejectRules(long tenant, Rename r) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM ab_binding_rule br JOIN ab_command_definition c ON c.id=br.command_id
                 WHERE c.tenant_id=? AND c.deleted_flag=false AND c.model_code=? AND br.deleted_flag=false
                 AND (br.target_field=? OR br.source_field=? OR position(? in coalesce(br.expression,''))>0
                      OR position(? in coalesce(br.config::text,''))>0)
                """, Long.class, tenant, r.modelCode(), r.fromField(), r.fromField(), r.fromField(), r.fromField());
        require(count != null && count == 0, "Legacy field still used by model command rule");
    }
    private void evict(long tenant, Rename r) {
        // Use the existing metadata invalidation contract, including both binding-key
        // forms and derived view definitions; afterCompletion also clears rollback caches.
        models.clearAllCache();
    }
    private String digest(Map<String, Object> field) {
        var semantic = new java.util.TreeMap<String, Object>(field);
        semantic.remove("field_id"); semantic.remove("field_pid"); semantic.remove("binding_pid");
        for (String key : List.of("ref_target", "feature", "extension", "rule_schema")) semantic.put(key, tree(semantic.get(key)));
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(semantic))); }
        catch (NoSuchAlgorithmException | JsonProcessingException ex) { throw new IllegalStateException("Cannot seal field definition", ex); }
    }
    private JsonNode tree(Object value) {
        if (value == null) return json.nullNode();
        try { return json.readTree(value.toString()); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("Invalid stored field metadata", ex); }
    }
    private static boolean containsText(JsonNode node, String value) {
        if (node.isTextual()) return java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(value) + "(?![A-Za-z0-9_])").matcher(node.textValue()).find();
        if (node.isObject() && keys(node).contains(value)) return true;
        for (JsonNode child : node) if (containsText(child, value)) return true;
        return false;
    }
    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new HashSet<>(); node.fieldNames().forEachRemaining(keys::add); return keys;
    }
    private static String token(JsonNode node) {
        require(node != null && node.isTextual(), "Identifier must be text"); return identifier(node.textValue());
    }
    private static String identifier(Object value) {
        require(value instanceof String && ((String) value).matches("[a-z][a-z0-9_]{0,62}"), "Unsafe physical identifier"); return (String) value;
    }
    private static String quote(String value) { return '"' + identifier(value) + '"'; }
    private static void require(boolean condition, String message) { if (!condition) throw invalid(message); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
