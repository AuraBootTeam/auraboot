package com.auraboot.framework.meta.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.entity.PageSchemaHistory;
import com.auraboot.framework.meta.mapper.PageSchemaHistoryMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack real-DB round-trip IT for the PageSchema publish / unpublish /
 * version / rollback / compare REST contract, plus the structural integrity
 * guard ({@link com.auraboot.framework.meta.validator.PageSchemaBlockStructureValidator}).
 *
 * <p>Extends {@link BaseIntegrationTest} so the complete Spring pipeline runs:
 * real PostgreSQL, real Redis, PermissionInterceptor, and Spring Security. The
 * auth pattern mirrors {@code PageSchemaKindFullStackIntegrationTest}: a
 * per-request servlet filter injects both {@link MetaContext} and the
 * {@link SecurityContextHolder}, and the test role is granted
 * {@code page.page.manage}.</p>
 *
 * <p>Assertions read the persisted {@link PageSchema} / {@link PageSchemaHistory}
 * rows directly through the mappers (not just HTTP 200 / toasts), so this proves
 * the real persistence side-effects of each endpoint. Direct mapper reads run
 * inside {@link #withMetaContext} because the test-thread MetaContext is only
 * set during the MockMvc request (by the filter), then cleared.</p>
 *
 * <p>Note: {@code ValidationException} maps to HTTP 422 (Unprocessable Entity)
 * via {@code GlobalExceptionHandler}, so reject-path assertions expect 422.</p>
 */
@DisplayName("PageSchema publish/version/rollback/compare + structure guard - Full-stack IT")
class PageSchemaPublishVersionIntegrationTest extends BaseIntegrationTest {

    private static final String PERMISSION_CODE = "page.page.manage";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private UserPermissionService userPermissionService;

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @Autowired
    private PageSchemaHistoryMapper pageSchemaHistoryMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        grantPermissionToTestRole(PERMISSION_CODE, "page", "page", "manage", "Page Manage");
        // The compare endpoint is gated by PAGE_SCHEMA_READ (page.page.read).
        grantPermissionToTestRole("page.page.read", "page", "page", "read", "Page Read");
        userPermissionService.evictUserPermissions(getTestUser().getId());

        Filter contextFilter = (request, response, chain) -> {
            try {
                applyTestMetaContext();
                CustomUserDetails userDetails = new CustomUserDetails(
                        getTestUser().getUserName(),
                        "test-password",
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        AuthorityUtils.createAuthorityList("role_admin"),
                        true, true, true, true
                );
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
                SecurityContextHolder.clearContext();
            }
        };

        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(contextFilter, "/*")
                .build();
    }

    // ── PV-01: publish → unpublish real status round-trip ─────────────────────

    @Test
    @DisplayName("PV-01: POST /publish then /unpublish flips persisted status + published_at")
    void publishThenUnpublish_persistsStatusTransition() throws Exception {
        String pid = createPage("list", List.of(
                Map.of("id", "blk_table_1", "blockType", "table")));

        PageSchema afterCreate = readByPid(pid);
        assertThat(afterCreate).isNotNull();
        assertThat(afterCreate.getStatus()).isEqualTo("draft");
        assertThat(afterCreate.getPublishedAt()).isNull();

        // publish
        mockMvc.perform(post("/api/pages/{pid}/publish", pid))
                .andExpect(status().is2xxSuccessful());

        PageSchema afterPublish = readByPid(pid);
        assertThat(afterPublish.getStatus()).isEqualTo("published");
        assertThat(afterPublish.getPublishedAt()).isNotNull();

        // unpublish
        mockMvc.perform(post("/api/pages/{pid}/unpublish", pid))
                .andExpect(status().is2xxSuccessful());

        PageSchema afterUnpublish = readByPid(pid);
        assertThat(afterUnpublish.getStatus()).isEqualTo("draft");
        assertThat(afterUnpublish.getPublishedAt()).isNull();
    }

    // ── PV-02: version snapshot → rollback restores schema + bumps version ─────

    @Test
    @DisplayName("PV-02: POST /versions snapshot then /rollback restores blocks and increments version")
    void createVersionThenRollback_restoresSnapshotAndBumpsVersion() throws Exception {
        // Create the page with an initial table block.
        String pid = createPage("list", List.of(
                Map.of("id", "blk_orig", "blockType", "table")));

        PageSchema beforeVersion = readByPid(pid);
        int versionBefore = beforeVersion.getVersion();
        String blocksAtSnapshot = beforeVersion.getBlocks();

        // Snapshot the current page state as a version.
        MvcResult versionResult = mockMvc.perform(post("/api/pages/{pid}/versions", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "operation", "update",
                                "description", "snapshot before edit"))))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        long historyId = readDataLong(versionResult, "id");

        // Confirm the history snapshot row really landed in the DB.
        PageSchemaHistory history = withMetaContext(() -> pageSchemaHistoryMapper.selectById(historyId));
        assertThat(history).isNotNull();
        assertThat(history.getPid()).isEqualTo(pid);
        assertThat(history.getSnapshot()).isNotNull();
        assertThat(history.getSnapshot()).containsKey("blocks");

        // Mutate the live page so rollback has something to restore.
        withMetaContext(() -> {
            PageSchema toMutate = pageSchemaMapper.selectByPid(pid);
            toMutate.setBlocks("[{\"id\":\"blk_changed\",\"blockType\":\"chart\"}]");
            toMutate.setUpdatedAt(Instant.now());
            pageSchemaMapper.updateById(toMutate);
            return null;
        });

        PageSchema afterMutate = readByPid(pid);
        assertThat(afterMutate.getBlocks()).contains("blk_changed");

        // Rollback to the snapshot.
        mockMvc.perform(post("/api/pages/{pid}/rollback/{historyId}", pid, historyId)
                        .param("reason", "restore original"))
                .andExpect(status().is2xxSuccessful());

        PageSchema afterRollback = readByPid(pid);
        // schema restored from snapshot
        assertThat(afterRollback.getBlocks()).isEqualTo(blocksAtSnapshot);
        assertThat(afterRollback.getBlocks()).contains("blk_orig");
        // version incremented by the restore (restoreSchemaFromSnapshot does version + 1)
        assertThat(afterRollback.getVersion()).isEqualTo(versionBefore + 1);
    }

    // ── PV-03: compare returns a structured comparison DTO ─────────────────────

    @Test
    @DisplayName("PV-03: GET /versions/{from}/compare/{to} returns differences + summary")
    void compareVersions_returnsComparisonStructure() throws Exception {
        String pid = createPage("list", List.of(
                Map.of("id", "blk_v1", "blockType", "table")));

        // First snapshot (table block).
        long fromId = readDataLong(mockMvc.perform(post("/api/pages/{pid}/versions", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "operation", "update", "description", "v1"))))
                .andExpect(status().is2xxSuccessful())
                .andReturn(), "id");

        // Mutate, then take a second snapshot (chart block) so there is a real diff.
        withMetaContext(() -> {
            PageSchema toMutate = pageSchemaMapper.selectByPid(pid);
            toMutate.setBlocks("[{\"id\":\"blk_v2\",\"blockType\":\"chart\"}]");
            toMutate.setTitle("\"Renamed Title\"");
            toMutate.setUpdatedAt(Instant.now());
            pageSchemaMapper.updateById(toMutate);
            return null;
        });

        long toId = readDataLong(mockMvc.perform(post("/api/pages/{pid}/versions", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "operation", "update", "description", "v2"))))
                .andExpect(status().is2xxSuccessful())
                .andReturn(), "id");

        mockMvc.perform(get("/api/pages/{pid}/versions/{from}/compare/{to}", pid, fromId, toId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.differences").isArray())
                .andExpect(jsonPath("$.data.summary").exists())
                .andExpect(jsonPath("$.data.summary.totalDifferences").isNumber())
                .andExpect(jsonPath("$.data.sourceVersion.historyId").value((int) fromId))
                .andExpect(jsonPath("$.data.targetVersion.historyId").value((int) toId));
    }

    // ── PV-04: structure guard — legal v4 blockType succeeds ───────────────────

    @Test
    @DisplayName("PV-04: legal v4 blockTypes (list/detail/widget) with ids succeed")
    void create_withLegalV4BlockTypes_succeeds() throws Exception {
        String pageKey = "pv_v4_ok_" + System.currentTimeMillis();
        Map<String, Object> payload = Map.of(
                "pageKey", pageKey,
                "name", "PV V4 OK " + System.currentTimeMillis(),
                "title", "PV V4 OK",
                "kind", "detail",
                "blocks", List.of(
                        Map.of("id", "b1", "blockType", "list"),
                        Map.of("id", "b2", "blockType", "detail"),
                        Map.of("id", "b3", "blockType", "widget")));

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is2xxSuccessful());

        assertThat(readByPageKey(pageKey)).isNotNull();
    }

    // ── PV-05: structure guard — missing id is rejected ────────────────────────

    @Test
    @DisplayName("PV-05: a block missing id is rejected with 422")
    void create_withBlockMissingId_isRejected() throws Exception {
        String pageKey = "pv_no_id_" + System.currentTimeMillis();
        Map<String, Object> payload = Map.of(
                "pageKey", pageKey,
                "name", "PV No Id " + System.currentTimeMillis(),
                "title", "PV No Id",
                "kind", "list",
                // table block has NO id → hard reject
                "blocks", List.of(Map.of("blockType", "table")));

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnprocessableEntity());

        // Nothing persisted.
        assertThat(readByPageKey(pageKey)).isNull();
    }

    // ── PV-06: structure guard — blank blockType is rejected ───────────────────

    @Test
    @DisplayName("PV-06: a block with blank blockType is rejected with 422")
    void create_withBlankBlockType_isRejected() throws Exception {
        String pageKey = "pv_blank_type_" + System.currentTimeMillis();
        Map<String, Object> payload = Map.of(
                "pageKey", pageKey,
                "name", "PV Blank Type " + System.currentTimeMillis(),
                "title", "PV Blank Type",
                "kind", "list",
                "blocks", List.of(Map.of("id", "b1", "blockType", "   ")));

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(readByPageKey(pageKey)).isNull();
    }

    // ── PV-07: structure guard — duplicate block id is rejected ────────────────

    @Test
    @DisplayName("PV-07: duplicate block ids are rejected with 422")
    void create_withDuplicateBlockIds_isRejected() throws Exception {
        String pageKey = "pv_dup_id_" + System.currentTimeMillis();
        Map<String, Object> payload = Map.of(
                "pageKey", pageKey,
                "name", "PV Dup Id " + System.currentTimeMillis(),
                "title", "PV Dup Id",
                "kind", "list",
                "blocks", List.of(
                        Map.of("id", "dup", "blockType", "table"),
                        Map.of("id", "dup", "blockType", "chart")));

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(readByPageKey(pageKey)).isNull();
    }

    // ── PV-08: structure guard — unknown blockType succeeds (forward-compat) ───

    @Test
    @DisplayName("PV-08: unknown blockType is accepted (warns only) and persists — forward-compat")
    void create_withUnknownBlockType_succeedsWithWarning() throws Exception {
        String pageKey = "pv_unknown_type_" + System.currentTimeMillis();
        Map<String, Object> payload = Map.of(
                "pageKey", pageKey,
                "name", "PV Unknown Type " + System.currentTimeMillis(),
                "title", "PV Unknown Type",
                "kind", "list",
                // a totally made-up custom block type — must NOT be hard-rejected
                "blocks", List.of(Map.of("id", "b1", "blockType", "totally-made-up-block")));

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is2xxSuccessful());

        PageSchema persisted = readByPageKey(pageKey);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getBlocks()).contains("totally-made-up-block");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Runs a DB-touching action with the test MetaContext applied on the current
     * (test) thread. The servlet filter only sets MetaContext during the MockMvc
     * request and clears it afterward, so direct mapper reads from the test body
     * must re-apply it.
     */
    private <T> T withMetaContext(Supplier<T> action) {
        try {
            applyTestMetaContext();
            return action.get();
        } finally {
            MetaContext.clear();
        }
    }

    private PageSchema readByPid(String pid) {
        return withMetaContext(() -> pageSchemaMapper.selectByPid(pid));
    }

    /**
     * Existence check that does NOT require the page to be published —
     * {@code selectByPageKey} filters {@code status = 'published'}, but pages are
     * created as drafts, so existence checks use {@code selectAnyByPageKey}.
     */
    private PageSchema readByPageKey(String pageKey) {
        return withMetaContext(() -> pageSchemaMapper.selectAnyByPageKey(pageKey));
    }

    /** Creates a page via the real REST endpoint and returns its persisted pid. */
    private String createPage(String kind, List<Map<String, Object>> blocks) throws Exception {
        String pageKey = "pv_page_" + kind + "_" + System.currentTimeMillis() + "_" + System.nanoTime();
        Map<String, Object> payload = Map.of(
                "pageKey", pageKey,
                "name", "PV Page " + System.nanoTime(),
                "title", "PV Page Title",
                "kind", kind,
                "blocks", blocks);

        MvcResult result = mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is2xxSuccessful())
                .andReturn();

        // The create response carries the persisted pid (data.pid); read it from
        // there rather than re-querying, since the page is a draft.
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        String pid = root.path("data").path("pid").asText(null);
        assertThat(pid).as("create response should carry a pid").isNotBlank();
        return pid;
    }

    private long readDataLong(MvcResult result, String dataField) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode node = root.path("data").path(dataField);
        assertThat(node.isMissingNode()).as("data." + dataField + " present in response").isFalse();
        return node.asLong();
    }

    private void grantPermissionToTestRole(String code, String resourceType,
                                           String resourceCode, String action, String name) {
        Permission permission = permissionMapper.findByCode(code);
        if (permission == null) {
            permission = new Permission();
            permission.setPid(UniqueIdGenerator.generate());
            permission.setCode(code);
            permission.setName(name);
            permission.setResourceType(resourceType);
            permission.setResourceCode(resourceCode);
            permission.setAction(action);
            permission.setSource("manual");
            permission.setStatus("active");
            permission.setDeletedFlag(false);
            permission.setTenantId(getTestTenant().getId());
            permission.setCreatedAt(Instant.now());
            permission.setUpdatedAt(Instant.now());
            permissionMapper.insert(permission);
        }

        boolean notAssigned = rolePermissionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRoleId, getTestRole().getId())
                        .eq(RolePermission::getPermissionId, permission.getId())
                        .eq(RolePermission::getDeletedFlag, false)
        ).isEmpty();

        if (notAssigned) {
            RolePermission rp = new RolePermission();
            rp.setPid(UniqueIdGenerator.generate());
            rp.setRoleId(getTestRole().getId());
            rp.setPermissionId(permission.getId());
            rp.setGrantType("grant");
            rp.setStatus("active");
            rp.setDeletedFlag(false);
            rp.setTenantId(getTestTenant().getId());
            rp.setCreatedAt(Instant.now());
            rp.setUpdatedAt(Instant.now());
            rolePermissionMapper.insert(rp);
        }
    }
}
