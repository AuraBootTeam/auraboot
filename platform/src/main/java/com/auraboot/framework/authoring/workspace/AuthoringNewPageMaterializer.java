package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.database.snowflake.SnowflakeIdGeneratorConfig;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.GovernanceRow;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.NewPageOption;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.NewPageWorkspaceOptions;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/** Materializes a reviewed new PageSchema and its menu mount at the publish transaction seam. */
@Component
public class AuthoringNewPageMaterializer {

    static final String RESOURCE_METADATA = "_authoringResource";
    static final String NEW_LIFECYCLE = "NEW";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SnowflakeIdGeneratorConfig idGenerator;

    public AuthoringNewPageMaterializer(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            SnowflakeIdGeneratorConfig idGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
    }

    public void requireAvailable(
            long tenantId,
            long envId,
            String pageKey,
            String menuCode,
            String menuPath,
            String modelCode,
            String parentMenuCode,
            String permissionCode) {
        if (exists("""
                SELECT COUNT(*) FROM ab_page_schema
                WHERE tenant_id = ? AND env_id = ? AND page_key = ?
                  AND is_current = TRUE AND deleted_flag = FALSE
                """, tenantId, envId, pageKey)) {
            throw conflict("authoring.new-page.page-key-conflict");
        }
        if (exists("""
                SELECT COUNT(*) FROM ab_authoring_resource_draft draft
                JOIN ab_authoring_change_set change_set ON change_set.id = draft.change_set_id
                WHERE draft.tenant_id = ? AND draft.env_id = ?
                  AND draft.snapshot ->> 'pageKey' = ?
                  AND draft.snapshot #>> '{_authoringResource,lifecycle}' = 'NEW'
                  AND change_set.status IN ('DRAFT', 'REJECTED', 'IN_REVIEW', 'APPROVED')
                  AND change_set.deleted_flag = FALSE
                """, tenantId, envId, pageKey)) {
            throw conflict("authoring.new-page.page-key-reserved");
        }
        requireMenuAvailable(tenantId, menuCode, menuPath);
        requireModel(tenantId, modelCode);
        requireParentMenu(tenantId, envId, parentMenuCode);
        requirePermission(tenantId, permissionCode);
    }

    public NewPageWorkspaceOptions options(long tenantId, long envId) {
        java.util.List<NewPageOption> models = jdbcTemplate.query("""
                        SELECT code,
                               COALESCE(NULLIF(extension ->> 'displayName', ''),
                                        NULLIF(extension #>> '{extension,displayName}', ''),
                                        code) AS label
                        FROM ab_meta_model
                        WHERE tenant_id = ? AND status = 'published' AND is_current = TRUE
                          AND deleted_flag = FALSE
                        ORDER BY label, code LIMIT 1000
                        """,
                (resultSet, rowNumber) -> new NewPageOption(
                        resultSet.getString("code"), resultSet.getString("label")),
                tenantId);
        java.util.List<NewPageOption> parentMenus = jdbcTemplate.query("""
                        SELECT code, name FROM ab_menu
                        WHERE tenant_id = ? AND type = 0 AND status = 'active'
                          AND deleted_flag = FALSE AND code IS NOT NULL
                          AND (extension -> 'authoringEnvironmentIds' IS NULL
                               OR extension -> 'authoringEnvironmentIds'
                                  @> jsonb_build_array(CAST(? AS BIGINT)))
                        ORDER BY order_no, name LIMIT 500
                        """,
                (resultSet, rowNumber) -> new NewPageOption(
                        resultSet.getString("code"), resultSet.getString("name")),
                tenantId, envId);
        java.util.List<NewPageOption> permissions = jdbcTemplate.query("""
                        SELECT code, name FROM ab_permission
                        WHERE tenant_id = ? AND status = 'active' AND deleted_flag = FALSE
                        ORDER BY code
                        """,
                (resultSet, rowNumber) -> new NewPageOption(
                        resultSet.getString("code"), resultSet.getString("name")),
                tenantId);
        return new NewPageWorkspaceOptions(models, parentMenus, permissions);
    }

    public boolean isNewResource(JsonNode snapshot) {
        return NEW_LIFECYCLE.equals(
                snapshot.path(RESOURCE_METADATA).path("lifecycle").asText());
    }

    public void materialize(
            GovernanceRow row,
            ObjectNode sourceSnapshot,
            ObjectNode runtimeSnapshot,
            long actorUserId) {
        if (!isNewResource(sourceSnapshot)) {
            return;
        }
        requireStillAvailable(row, sourceSnapshot);
        JsonNode metadata = sourceSnapshot.path(RESOURCE_METADATA);
        JsonNode menu = metadata.path("menu");
        String pageKey = requiredText(sourceSnapshot, "pageKey");
        String menuCode = requiredText(menu, "code");
        String menuPath = requiredText(menu, "path");
        String parentMenuCode = requiredText(menu, "parentCode");
        String permissionCode = requiredText(menu, "permissionCode");

        long parentMenuId = requireParentMenu(row.tenantId(), row.envId(), parentMenuCode);

        int pageInserted = jdbcTemplate.update("""
                INSERT INTO ab_page_schema (
                    pid, tenant_id, env_id, namespace, is_current, status, extension,
                    page_key, model_code, name, description, kind, schema_version, profile,
                    title, layout, blocks, is_template, version, row_version, sort_weight,
                    published_at, ownership_scope, ownership_ref,
                    created_at, updated_at, created_by, updated_by, deleted_flag)
                VALUES (?, ?, ?, 'default', TRUE, 'published', '{}'::jsonb,
                        ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
                        FALSE, 1, 1, 0, CURRENT_TIMESTAMP, 'TENANT', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, FALSE)
                """,
                row.resourcePid(), row.tenantId(), row.envId(), pageKey,
                requiredText(runtimeSnapshot, "modelCode"),
                requiredText(runtimeSnapshot, "name"), nullableText(runtimeSnapshot, "description"),
                requiredText(runtimeSnapshot, "kind"), runtimeSnapshot.path("schemaVersion").asInt(4),
                runtimeSnapshot.path("profile").asText("admin"), json(runtimeSnapshot.path("title")),
                json(runtimeSnapshot.path("layout")), json(runtimeSnapshot.path("blocks")),
                "tenant:" + row.tenantId(), actorUserId, actorUserId);
        if (pageInserted != 1) {
            throw conflict("authoring.new-page.materialize-conflict");
        }

        int menuInserted = jdbcTemplate.update("""
                INSERT INTO ab_menu (
                    id, pid, tenant_id, parent_id, code, name, path, icon, type,
                    permission_code, visible, order_no, page_pid, page_key, status,
                    extension, deleted_flag, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, TRUE, ?, ?, ?, 'active', ?::jsonb, FALSE,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                idGenerator.nextId(sourceSnapshot), UniqueIdGenerator.generate(), row.tenantId(),
                parentMenuId, menuCode, requiredText(menu, "name"), menuPath,
                nullableText(menu, "icon"), permissionCode, menu.path("orderNo").asInt(0),
                row.resourcePid(), pageKey,
                "{\"authoringManaged\":true,\"authoringEnvironmentIds\":[" + row.envId() + "]}",
                actorUserId, actorUserId);
        if (menuInserted != 1) {
            throw conflict("authoring.new-page.menu-materialize-conflict");
        }
    }

    public void requireStillAvailable(GovernanceRow row, ObjectNode snapshot) {
        requireNewSnapshot(row, snapshot);
        JsonNode menu = snapshot.path(RESOURCE_METADATA).path("menu");
        requireMenuAvailable(
                row.tenantId(), requiredText(menu, "code"), requiredText(menu, "path"));
        requireModel(row.tenantId(), requiredText(snapshot, "modelCode"));
        requireParentMenu(row.tenantId(), row.envId(), requiredText(menu, "parentCode"));
        requirePermission(row.tenantId(), requiredText(menu, "permissionCode"));
    }

    private void requireNewSnapshot(GovernanceRow row, ObjectNode snapshot) {
        if (!row.resourcePid().equals(requiredText(snapshot, "pid"))
                || !"TENANT".equals(snapshot.path("ownershipScope").asText())
                || !snapshot.path("blocks").isArray()
                || !snapshot.path("title").isObject()
                || !snapshot.path("layout").isObject()) {
            throw invalid("authoring.new-page.snapshot-invalid");
        }
        if (exists("""
                SELECT COUNT(*) FROM ab_page_schema
                WHERE tenant_id = ? AND env_id = ? AND page_key = ?
                  AND is_current = TRUE AND deleted_flag = FALSE
                """, row.tenantId(), row.envId(), requiredText(snapshot, "pageKey"))) {
            throw conflict("authoring.new-page.page-key-conflict");
        }
    }

    private void requireMenuAvailable(long tenantId, String menuCode, String menuPath) {
        if (exists("""
                SELECT COUNT(*) FROM ab_menu
                WHERE tenant_id = ? AND deleted_flag = FALSE AND (code = ? OR path = ?)
                """, tenantId, menuCode, menuPath)) {
            throw conflict("authoring.new-page.menu-conflict");
        }
    }

    private long requireParentMenu(long tenantId, long envId, String parentMenuCode) {
        Long parentId = jdbcTemplate.query("""
                        SELECT id FROM ab_menu
                        WHERE tenant_id = ? AND code = ? AND type = 0
                          AND status = 'active' AND deleted_flag = FALSE
                          AND (extension -> 'authoringEnvironmentIds' IS NULL
                               OR extension -> 'authoringEnvironmentIds'
                                  @> jsonb_build_array(CAST(? AS BIGINT)))
                        LIMIT 1
                        """,
                resultSet -> resultSet.next() ? resultSet.getLong("id") : null,
                tenantId, parentMenuCode, envId);
        if (parentId == null) {
            throw invalid("authoring.new-page.parent-menu-invalid");
        }
        return parentId;
    }

    private void requireModel(long tenantId, String modelCode) {
        if (!exists("""
                SELECT COUNT(*) FROM ab_meta_model
                WHERE tenant_id = ? AND code = ? AND status = 'published'
                  AND is_current = TRUE AND deleted_flag = FALSE
                """, tenantId, modelCode)) {
            throw invalid("authoring.new-page.model-invalid");
        }
    }

    private void requirePermission(long tenantId, String permissionCode) {
        if (!exists("""
                SELECT COUNT(*) FROM ab_permission
                WHERE tenant_id = ? AND code = ? AND status = 'active' AND deleted_flag = FALSE
                """, tenantId, permissionCode)) {
            throw invalid("authoring.new-page.permission-invalid");
        }
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private String requiredText(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null) {
            throw invalid("authoring.new-page.snapshot-invalid");
        }
        return value;
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("authoring.json.serialize-failed", exception);
        }
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(CONFLICT, reason);
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(UNPROCESSABLE_ENTITY, reason);
    }
}
