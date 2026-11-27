package com.auraboot.framework.agent.integration.plugin;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Versioned before/after images in the existing resource ledger; no second history store. */
final class AgentDefinitionRecovery {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PluginResourceMapper tracking;
    private static final String AGENT_COLUMNS = String.join(",",
            "name", "description", "avatar_url", "agent_type", "model", "system_prompt", "tools", "skills", "guardrails", "soul_profile",
            "personality", "expertise", "communication_style", "boundaries", "soul_goals", "system_user_id", "service_account_id",
            "allowed_models", "allowed_operations", "max_tools", "max_concurrent_runs", "execution_timeout_seconds", "event_triggers",
            "execution_config", "employee_id", "auto_reply_mode", "status", "stats", "visibility", "created_at", "updated_at",
            "created_by", "updated_by", "deleted_flag", "knowledge_base_ids", "proactive_policy");

    AgentDefinitionRecovery(JdbcTemplate jdbc, ObjectMapper json, PluginResourceMapper tracking) {
        this.jdbc = jdbc; this.json = json; this.tracking = tracking;
    }

    boolean preserve(Long tenant, String code, String incomingPid, ImportRequest.ConflictStrategy strategy) {
        if (strategy != ImportRequest.ConflictStrategy.OVERWRITE_SAFE) return false;
        PluginResourceMapper.ModificationState state;
        try { state = tracking.lockModificationState(tenant, ResourceType.AGENT_DEFINITION.code(), code); }
        // CATCH: an unreadable protection marker must abort before any resource write.
        catch (Exception failure) { throw new PluginException("Cannot verify Agent customization protection", failure); }
        if (state == null) return true;
        if (state.modified() == null) throw new PluginException("Agent customization protection state is missing");
        if (Boolean.TRUE.equals(state.modified())) return true;
        var owner = tracking.lockByTypeAndCode(tenant, ResourceType.AGENT_DEFINITION.code(), code);
        if (owner == null) throw new PluginException("Agent protection owner changed");
        if (!"shared".equals(owner.getOwnershipType()) && !"plugin_owned".equals(owner.getOwnershipType())) return true;
        Integer sameFamily = jdbc.queryForObject("""
                SELECT count(*) FROM ab_plugin current_owner
                JOIN ab_plugin incoming ON incoming.plugin_id=current_owner.plugin_id
                                       AND incoming.tenant_id=current_owner.tenant_id
                WHERE current_owner.pid=? AND incoming.pid=? AND current_owner.tenant_id=?
                """, Integer.class, owner.getPluginPid(), incomingPid, tenant);
        return sameFamily == null || sameFamily != 1;
    }

    Map<String, Object> before(Long tenant, String code) {
        tracking.lockByTypeAndCode(tenant, ResourceType.AGENT_DEFINITION.code(), code);
        Map<String, Object> state = content(tenant, code);
        List<String> rows = jdbc.queryForList("""
                SELECT to_jsonb(r)::text FROM ab_plugin_resource r
                WHERE tenant_id=? AND resource_type='agent_definition' AND resource_code=?
                """, String.class, tenant, code);
        state.put("tracking", rows.isEmpty() ? null : decode(rows.getFirst()));
        return state;
    }

    Map<String, Object> content(Long tenant, String code) {
        List<String> agents = jdbc.queryForList("""
                SELECT to_jsonb(a)::text FROM ab_agent_definition a
                WHERE tenant_id=? AND agent_code=? FOR UPDATE
                """, String.class, tenant, code);
        if (agents.size() != 1) throw new PluginException("Agent recovery identity is missing or ambiguous");
        List<Map<String, Object>> cases = jdbc.queryForList("""
                SELECT to_jsonb(e)::text FROM ab_agent_eval_case e
                WHERE tenant_id=? AND agent_code=? ORDER BY id FOR UPDATE
                """, String.class, tenant, code).stream().map(this::decode).toList();
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("schemaVersion", 1); image.put("agent", decode(agents.getFirst())); image.put("evalCases", cases);
        return image;
    }

    void withdraw(PluginResource resource) {
        requireCurrent(resource, false);
        if (jdbc.update("UPDATE ab_agent_definition SET deleted_flag=TRUE,updated_at=NOW() WHERE tenant_id=? AND agent_code=? AND pid=?",
                resource.getTenantId(), resource.getResourceCode(), resource.getResourcePid()) != 1)
            throw new PluginException("Agent exit owner changed");
        jdbc.update("UPDATE ab_agent_eval_case SET deleted_flag=TRUE,updated_at=NOW() WHERE tenant_id=? AND agent_code=?",
                resource.getTenantId(), resource.getResourceCode());
    }

    void restore(PluginResource resource) {
        PluginResource current = requireCurrent(resource, true);
        Map<String, Object> before = current.getPreviousState();
        requireImage(before, resource);
        Map<String, Object> priorAgent = asMap(before.get("agent"));
        List<Map<String, Object>> priorCases = asCases(before.get("evalCases"));
        Map<String, Object> priorTracking = before.get("tracking") == null ? null : asMap(before.get("tracking"));
        if (priorTracking != null) {
            if (!Objects.equals(priorTracking.get("resource_code"), resource.getResourceCode())
                    || !Objects.equals(priorTracking.get("resource_type"), ResourceType.AGENT_DEFINITION.code())
                    || number(priorTracking.get("tenant_id")) != resource.getTenantId()
                    || number(priorTracking.get("id")) != current.getId()
                    || !Objects.equals(priorTracking.get("pid"), current.getPid())
                    || !Objects.equals(priorTracking.get("resource_pid"), resource.getResourcePid()))
                throw new PluginException("Agent recovery tracking snapshot identity mismatch");
            Integer activeOwner = jdbc.queryForObject("SELECT count(*) FROM ab_plugin WHERE tenant_id=? AND pid=? AND deleted_flag=FALSE",
                    Integer.class, resource.getTenantId(), priorTracking.get("plugin_pid"));
            if (activeOwner == null || activeOwner != 1) throw new PluginException("Agent recovery prior owner is retired or missing");
        }
        for (Map<String, Object> c : priorCases) {
            if (number(c.get("tenant_id")) != resource.getTenantId() || !Objects.equals(c.get("agent_code"), resource.getResourceCode()))
                throw new PluginException("Agent recovery eval snapshot identity mismatch");
        }
        // Column identifiers are fixed platform constants; row values stay bound JSON parameters.
        int updated = jdbc.update("UPDATE ab_agent_definition SET (" + AGENT_COLUMNS + ")=(SELECT " + AGENT_COLUMNS
                + " FROM jsonb_populate_record(NULL::ab_agent_definition,?::jsonb)) WHERE tenant_id=? AND agent_code=? AND pid=?",
                encode(priorAgent), resource.getTenantId(), resource.getResourceCode(), resource.getResourcePid());
        if (updated != 1) throw new PluginException("Agent recovery row changed");
        jdbc.update("DELETE FROM ab_agent_eval_case WHERE tenant_id=? AND agent_code=?", resource.getTenantId(), resource.getResourceCode());
        jdbc.update("INSERT INTO ab_agent_eval_case SELECT * FROM jsonb_populate_recordset(NULL::ab_agent_eval_case,?::jsonb)", encode(priorCases));
        if (priorTracking == null) tracking.deleteById(current.getId());
        else if (tracking.restoreAgentTrackingSnapshot(current.getId(), resource.getTenantId(), encode(priorTracking)) != 1)
            throw new PluginException("Agent recovery tracking changed");
        Map<String, Object> expected = new LinkedHashMap<>(before); expected.remove("tracking");
        if (!same(expected, content(resource.getTenantId(), resource.getResourceCode())))
            throw new PluginException("Agent recovery snapshot was not fully restored");
    }

    private PluginResource requireCurrent(PluginResource resource, boolean restoring) {
        if (resource.getResourceTypeEnum() != ResourceType.AGENT_DEFINITION || resource.getId() == null
                || resource.getResourceId() == null || resource.getResourcePid() == null)
            throw new PluginException("Agent recovery resource identity is missing");
        if (!Objects.equals(MetaContext.getCurrentTenantId(), resource.getTenantId()))
            throw new PluginException("Agent recovery tenant mismatch");
        var current = tracking.lockByTypeAndCode(resource.getTenantId(), ResourceType.AGENT_DEFINITION.code(), resource.getResourceCode());
        if (current == null || !Objects.equals(current.getId(), resource.getId())
                || !Objects.equals(current.getPid(), resource.getPid())
                || !Objects.equals(current.getResourceId(), resource.getResourceId())
                || !Objects.equals(current.getResourcePid(), resource.getResourcePid())
                || !Objects.equals(current.getPluginPid(), resource.getPluginPid())
                || !Objects.equals(current.getImportId(), resource.getImportId()))
            throw new PluginException("Agent recovery owner changed");
        boolean inheritedClaim = restoring && "user_claimed".equals(current.getOwnershipType())
                && current.getPreviousState() != null
                && current.getPreviousState().get("tracking") instanceof Map<?, ?> prior
                && "user_claimed".equals(prior.get("ownership_type"));
        if (!Boolean.FALSE.equals(current.getUserModified())
                || (!"shared".equals(current.getOwnershipType()) && !"plugin_owned".equals(current.getOwnershipType()) && !inheritedClaim))
            throw new PluginException("Agent recovery resource is claimed, modified or has unknown protection");
        requireImage(current.getCurrentState(), current);
        if (!same(current.getCurrentState(), content(resource.getTenantId(), resource.getResourceCode())))
            throw new PluginException("Agent content or eval cases changed after import");
        return current;
    }

    private void requireImage(Map<String, Object> image, PluginResource resource) {
        if (image == null || !(image.get("schemaVersion") instanceof Number version) || version.intValue() != 1
                || !(image.get("agent") instanceof Map<?, ?>) || !(image.get("evalCases") instanceof List<?>))
            throw new PluginException("Agent recovery snapshot is missing or unsupported");
        Map<String, Object> agent = asMap(image.get("agent"));
        if (number(agent.get("tenant_id")) != resource.getTenantId()
                || !Objects.equals(agent.get("pid"), resource.getResourcePid())
                || !Objects.equals(agent.get("agent_code"), resource.getResourceCode())
                || number(agent.get("id")) != resource.getResourceId())
            throw new PluginException("Agent recovery snapshot identity mismatch");
    }
    private long number(Object value) { return value instanceof Number n ? n.longValue() : Long.MIN_VALUE; }
    private boolean same(Object first, Object second) { return json.valueToTree(first).equals(json.valueToTree(second)); }
    private Map<String, Object> asMap(Object value) { return json.convertValue(value, new TypeReference<Map<String, Object>>() {}); }
    private List<Map<String, Object>> asCases(Object value) { return json.convertValue(value, new TypeReference<List<Map<String, Object>>>() {}); }
    private Map<String, Object> decode(String value) {
        try { return json.readValue(value, new TypeReference<Map<String, Object>>() {}); }
        // CATCH: malformed stored images are not a reason to synthesize recovery defaults.
        catch (java.io.IOException failure) { throw new PluginException("Invalid Agent recovery snapshot", failure); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException failure) { throw new PluginException("Cannot encode Agent recovery snapshot", failure); }
    }
}
