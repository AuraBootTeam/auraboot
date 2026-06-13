package com.auraboot.framework.rag.controller;

import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rag.dto.CreateKnowledgeBaseRequest;
import com.auraboot.framework.rag.dto.KnowledgeBaseDTO;
import com.auraboot.framework.rag.service.EmbeddingService;
import com.auraboot.framework.rag.service.KbTextIngestService;
import com.auraboot.framework.rag.service.KnowledgeBaseService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-layer guard + CJK retrieval golden for the RAG knowledge base API (G1, G2).
 *
 * <p>G1: every endpoint carries {@code @RequirePermission}; without a grant the
 * request must be 403, with the grant it must succeed.
 *
 * <p>G2: Chinese content ingested through the shared pipeline must be retrievable
 * by a Chinese query over the keyword (BM25) leg alone — embeddings are mocked to
 * fail, so any hit proves the CJK bigram tsv/tsquery alignment. The reindex
 * endpoint must upgrade rows indexed with the old unsegmented tsv.
 */
class KnowledgeBaseControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private PermissionMapper permissionMapper;
    @Autowired private RolePermissionMapper rolePermissionMapper;
    @Autowired private UserPermissionService userPermissionService;
    @Autowired private KnowledgeBaseService kbService;
    @Autowired private KbTextIngestService kbTextIngestService;
    @Autowired private com.auraboot.framework.rag.service.RagRetrievalService ragRetrievalService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private EmbeddingService embeddingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setupMockMvc() {
        // Force the keyword-only retrieval leg: embedding always fails (G2 proof).
        when(embeddingService.embed(anyLong(), anyString(), anyString()))
                .thenThrow(new RuntimeException("embedding stubbed off"));
        when(embeddingService.embedBatch(anyLong(), anyList(), anyString()))
                .thenThrow(new RuntimeException("embedding stubbed off"));

        Filter contextFilter = (request, response, chain) -> {
            try {
                applyTestMetaContext();
                CustomUserDetails ud = new CustomUserDetails(
                        getTestUser().getUserName(), "test-password",
                        getTestUser().getId(), getTestUser().getPid(),
                        AuthorityUtils.createAuthorityList("role_admin"), true, true, true, true);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities()));
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(contextFilter, "/*").build();
    }

    private void grantAll() {
        grant(MetaPermission.AI_KNOWLEDGE_READ, "function", "ai:knowledge", "read", "KB Read");
        grant(MetaPermission.AI_KNOWLEDGE_MANAGE, "function", "ai:knowledge", "manage", "KB Manage");
        grant(MetaPermission.AI_KNOWLEDGE_RETRIEVE, "function", "ai:knowledge", "retrieve", "KB Retrieve");
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }

    // ---- G1: guard ----

    @Test
    @DisplayName("G1: without grants every endpoint class is 403 (read, manage, retrieve)")
    void withoutGrants_allEndpointsForbidden() throws Exception {
        userPermissionService.evictUserPermissions(getTestUser().getId());
        mockMvc.perform(get("/api/ai/knowledge")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai/knowledge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai/knowledge/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"q\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai/knowledge/some-kb/reindex")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("G1: with grants list/create/retrieve succeed")
    void withGrants_endpointsSucceed() throws Exception {
        grantAll();
        mockMvc.perform(get("/api/ai/knowledge")).andExpect(status().isOk());
        mockMvc.perform(post("/api/ai/knowledge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"guard-kb\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("guard-kb"));
        mockMvc.perform(post("/api/ai/knowledge/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"anything\"}"))
                .andExpect(status().isOk());
    }

    // ---- G2: CJK keyword retrieval over the real stack ----

    private KnowledgeBaseDTO createKb(String name) {
        CreateKnowledgeBaseRequest req = new CreateKnowledgeBaseRequest();
        req.setName(name);
        return kbService.createKnowledgeBase(getTestTenant().getId(), getTestUser().getId(), req);
    }

    @Test
    @DisplayName("G2: Chinese query hits Chinese content via BM25 leg alone (bigram tsv)")
    void chineseQuery_hitsChineseContent_keywordOnly() {
        KnowledgeBaseDTO kb = createKb("cjk-kb");
        String docPid = kbTextIngestService.ingestText(getTestTenant().getId(), kb.getPid(),
                "internal_doc", "cjk-doc-1", "权限文档",
                "平台的权限管理采用基于角色的访问控制。管理员可以为每个角色配置菜单权限和数据权限。");
        assertThat(docPid).isNotNull();

        var results = ragRetrievalService.retrieve(getTestTenant().getId(), "权限管理",
                java.util.List.of(kb.getPid()), 5, null);

        assertThat(results).as("CJK bigram BM25 must match Chinese content with embeddings down")
                .isNotEmpty();
        assertThat(results.get(0).getContent()).contains("权限管理");
    }

    @Test
    @DisplayName("G2: reindex endpoint upgrades old unsegmented tsv rows to bigram tsv")
    void reindex_upgradesLegacyTsvRows() throws Exception {
        grantAll();
        KnowledgeBaseDTO kb = createKb("legacy-kb");
        String docPid = kbTextIngestService.ingestText(getTestTenant().getId(), kb.getPid(),
                "internal_doc", "legacy-doc-1", "旧文档",
                "工作流引擎支持串行和并行审批节点的自由编排。");
        assertThat(docPid).isNotNull();

        // Simulate a legacy row: recompute tsv WITHOUT segmentation (pre-G2 behavior).
        int downgraded = jdbcTemplate.update(
                "UPDATE ab_kb_chunk SET tsv = to_tsvector('simple', content) WHERE doc_id = ?", docPid);
        assertThat(downgraded).isGreaterThan(0);

        var missed = ragRetrievalService.retrieve(getTestTenant().getId(), "审批节点",
                java.util.List.of(kb.getPid()), 5, null);
        assertThat(missed).as("legacy unsegmented tsv must NOT match a Chinese query (the G2 bug)")
                .isEmpty();

        mockMvc.perform(post("/api/ai/knowledge/" + kb.getPid() + "/reindex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reindexedChunks").value(org.hamcrest.Matchers.greaterThan(0)));

        // request lifecycle clears the thread-local MetaContext — restore for direct service calls
        applyTestMetaContext();
        var hit = ragRetrievalService.retrieve(getTestTenant().getId(), "审批节点",
                java.util.List.of(kb.getPid()), 5, null);
        assertThat(hit).as("after reindex the same Chinese query must hit").isNotEmpty();
    }

    @Test
    @DisplayName("G1: reindex on unknown KB returns business error, not 500")
    void reindex_unknownKb_returnsError() throws Exception {
        grantAll();
        mockMvc.perform(post("/api/ai/knowledge/NOPE/reindex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Knowledge base not found"));
    }

    private void grant(String code, String resourceType, String resourceCode, String action, String name) {
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
