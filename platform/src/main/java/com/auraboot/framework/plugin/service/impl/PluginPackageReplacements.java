package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.exception.PluginException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/** Edition-owned retirement contracts, applied only in explicit plugin import transactions. */
@Component
public class PluginPackageReplacements {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final com.auraboot.framework.plugin.mapper.PluginResourceMapper tracking;
    private final List<JsonNode> contracts;

    public PluginPackageReplacements(JdbcTemplate jdbc, ObjectMapper json,
                                     com.auraboot.framework.plugin.mapper.PluginResourceMapper tracking) {
        this.jdbc = jdbc;
        this.json = json;
        this.tracking = tracking;
        try {
            var resources = new PathMatchingResourcePatternResolver().getResources(
                    "classpath*:META-INF/auraboot/package-replacements/*.json");
            List<JsonNode> found = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (var resource : resources) {
                try (var input = resource.getInputStream()) {
                    for (var contract : json.readTree(input)) {
                        String old = contract.path("oldPluginId").asText();
                        String receiver = contract.path("receiverPluginId").asText();
                        if (old.isBlank() || receiver.isBlank() || old.equals(receiver) || !ids.add(old)) {
                            throw new PluginException("Invalid or duplicate package replacement: " + old);
                        }
                        found.add(contract);
                    }
                }
            }
            this.contracts = List.copyOf(found);
        } catch (Exception failure) {
            throw new PluginException("Cannot load package replacement contracts", failure);
        }
    }

    public void requireImportable(String pluginId) {
        for (var c : contracts) if (c.path("oldPluginId").asText().equals(pluginId)) {
            throw new PluginException("Retired plugin " + pluginId + "; import receiver "
                    + c.path("receiverPluginId").asText() + " before exiting the old package");
        }
    }

    public void selectAgentTemplates(PluginManifestExtended manifest, List<String> selected) {
        if (selected == null || selected.isEmpty()) return;
        var definitions = new ArrayList<>(manifest.getAgentDefinitions() == null ? List.<com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO>of() : manifest.getAgentDefinitions());
        Set<String> codes = new HashSet<>();
        for (var definition : definitions) codes.add(definition.getAgentCode());
        for (String code : selected) {
            var template = manifest.getAgentTemplates() == null ? Optional.<com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO>empty()
                    : manifest.getAgentTemplates().stream().filter(t -> code.equals(t.getAgentCode())).findFirst();
            if (template.isEmpty() || !codes.add(code)) throw new PluginException("Unknown or duplicate Agent template: " + code);
            definitions.add(template.get());
        }
        manifest.setAgentDefinitions(definitions);
    }

    /** Add edition resources to an existing installation unit, without a new plugin identity. */
    public PluginManifestExtended contribute(PluginManifestExtended manifest) {
        ObjectNode node = json.valueToTree(manifest);
        for (var c : contracts) if (c.path("receiverPluginId").asText().equals(manifest.getPluginId())) {
            c.path("declarations").fields().forEachRemaining(field -> {
                var values = node.path(field.getKey()).isArray() ? node.withArray(field.getKey()) : node.putArray(field.getKey());
                for (var value : field.getValue()) {
                    boolean exists = false;
                    for (var existing : values) {
                        String identity = field.getKey().equals("i18nResources") ? "key" : "code";
                        if (existing.path(identity).equals(value.path(identity))) {
                            if (!containsDeclaration(existing, value)) throw new PluginException("Conflicting replacement declaration: " + field.getKey());
                            exists = true;
                        }
                    }
                    if (!exists) values.add(value);
                }
            });
        }
        return json.convertValue(node, PluginManifestExtended.class);
    }

    private boolean containsDeclaration(JsonNode existing, JsonNode declaration) {
        var fields = declaration.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!existing.path(field.getKey()).equals(field.getValue())) return false;
        }
        return true;
    }

    /** Move ownership only: preserve live content, identities, baselines, claims and grants. */
    public void requireSafeAdoption(String receiverId, Long tenant,
                                    com.auraboot.framework.plugin.dto.imports.ImportRequest.ConflictStrategy strategy) {
        for (var c : contracts) if (c.path("receiverPluginId").asText().equals(receiverId)) {
            requireTrackedInventory(c, tenant);
            if (strategy == com.auraboot.framework.plugin.dto.imports.ImportRequest.ConflictStrategy.OVERWRITE_SAFE) continue;
            Integer pending = jdbc.queryForObject("""
                    SELECT count(*) FROM ab_plugin p WHERE p.tenant_id=? AND p.plugin_id=?
                    AND (p.deleted_flag=false OR p.deleted_flag IS NULL)
                    """, Integer.class, tenant, c.path("oldPluginId").asText());
            if (pending == null || pending > 0) throw new PluginException("Package adoption requires OVERWRITE_SAFE");
        }
    }

    public int adopt(String receiverId, String receiverPid, Long tenant) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !Objects.equals(tenant, com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId())) {
            throw new PluginException("Package adoption requires the importing tenant transaction");
        }
        int count = 0;
        for (var c : contracts) if (c.path("receiverPluginId").asText().equals(receiverId)) {
            String sourceId = c.path("oldPluginId").asText();
            requireTrackedInventory(c, tenant);
            var rows = jdbc.queryForList("""
                    SELECT r.id,r.resource_type,r.resource_code,r.resource_pid,r.plugin_pid,r.user_modified,r.ownership_type
                    FROM ab_plugin_resource r JOIN ab_plugin p ON p.pid=r.plugin_pid AND p.tenant_id=r.tenant_id
                    WHERE r.tenant_id=? AND p.plugin_id=? ORDER BY r.id FOR UPDATE OF r,p
                    """, tenant, sourceId);
            for (var row : rows) {
                String type = row.get("resource_type").toString();
                String code = row.get("resource_code").toString();
                boolean known = false;
                for (var ref : c.path("resources")) {
                    if (type.equals(ref.path("type").asText()) && code.equals(ref.path("code").asText())) known = true;
                }
                if (!known || row.get("user_modified") == null || !Set.of("shared", "plugin_owned", "user_claimed").contains(row.get("ownership_type"))) {
                    throw new PluginException("Cannot adopt unknown replacement resource: " + type + ":" + code);
                }
                String table = resourceTable(type);
                String column = type.equals("agent_definition") ? "agent_code" : "code";
                var live = jdbc.queryForList("SELECT pid" + (type.equals("agent_definition") ? "" : ",plugin_pid")
                        + " FROM " + table + " WHERE tenant_id=? AND " + column + "=? AND deleted_flag=false FOR UPDATE", tenant, code);
                if (live.size() != 1 || !Objects.equals(live.getFirst().get("pid"), row.get("resource_pid"))
                        || (!type.equals("agent_definition") && !Objects.equals(live.getFirst().get("plugin_pid"), row.get("plugin_pid")))) {
                    throw new PluginException("Replacement resource identity/owner changed: " + type + ":" + code);
                }
                if (!type.equals("agent_definition")) {
                    int changed = jdbc.update("UPDATE " + table
                            + " SET plugin_pid=? WHERE tenant_id=? AND pid=? AND plugin_pid=?",
                            receiverPid, tenant, row.get("resource_pid"), row.get("plugin_pid"));
                    if (changed != 1) throw new PluginException("Replacement resource owner changed during adoption");
                }
                int moved = tracking.adoptOwner(tenant, ((Number) row.get("id")).longValue(), row.get("plugin_pid").toString(), receiverPid);
                if (moved != 1) throw new PluginException("Replacement tracking changed during adoption");
                count += moved;
            }
        }
        return count;
    }

    private String resourceTable(String type) {
        return switch (type) {
            case "menu" -> "ab_menu";
            case "permission" -> "ab_permission";
            case "role" -> "ab_role";
            case "agent_definition" -> "ab_agent_definition";
            default -> throw new PluginException("Unsupported replacement resource: " + type);
        };
    }

    private void requireTrackedInventory(JsonNode contract, Long tenant) {
        Integer installed = jdbc.queryForObject("SELECT count(*) FROM ab_plugin WHERE tenant_id=? AND plugin_id=?",
                Integer.class, tenant, contract.path("oldPluginId").asText());
        if (installed == null) throw new PluginException("Cannot verify replacement installation");
        if (installed == 0) return;
        for (var ref : contract.path("resources")) {
            String type = ref.path("type").asText();
            String table = resourceTable(type);
            String column = type.equals("agent_definition") ? "agent_code" : "code";
            String ownerMismatch = type.equals("agent_definition") ? "" : " OR live.plugin_pid IS DISTINCT FROM r.plugin_pid";
            Integer invalid = jdbc.queryForObject("SELECT count(*) FROM " + table
                    + " live LEFT JOIN ab_plugin_resource r ON r.tenant_id=live.tenant_id AND r.resource_type=? AND r.resource_code=?"
                    + " WHERE live.tenant_id=? AND live." + column + "=? AND live.deleted_flag=false"
                    + " AND (r.id IS NULL OR live.pid IS DISTINCT FROM r.resource_pid" + ownerMismatch + ")",
                    Integer.class, type, ref.path("code").asText(), tenant, ref.path("code").asText());
            if (invalid == null || invalid > 0) throw new PluginException("Replacement resource tracking is missing or identity/owner changed: " + ref.path("code").asText());
        }
    }

    /** Never let the legacy uninstall path drop resources before their receiver has adopted them. */
    public void requireReadyToExit(String oldId, Long tenant) {
        for (var c : contracts) if (c.path("oldPluginId").asText().equals(oldId)) {
            requireTrackedInventory(c, tenant);
            Integer remaining = jdbc.queryForObject("""
                    SELECT count(*) FROM ab_plugin_resource r JOIN ab_plugin p ON p.pid=r.plugin_pid AND p.tenant_id=r.tenant_id
                    WHERE r.tenant_id=? AND p.plugin_id=?
                    """, Integer.class, tenant, oldId);
            if (remaining == null || remaining != 0) throw new PluginException("Import receiver before exiting retired plugin " + oldId);
        }
    }
}
