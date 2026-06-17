package com.auraboot.framework.meta.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-link i18n compliance IT: drives {@code POST /api/pages} through the
 * complete pipeline (controller → {@code PageSchemaServiceImpl} → real
 * {@link com.auraboot.framework.meta.validator.PageSchemaDslI18nValidator}) with
 * a real DB.
 *
 * <p>Unlike {@code PageSchemaI18nValidationControllerTest} — which mocks the
 * service and {@code thenThrow}s a hand-built {@link com.auraboot.framework.exception.ValidationException}
 * (proving only controller wiring, not the validator itself) — this test does
 * <b>NOT</b> mock the service, so a hardcoded Chinese title is rejected by the
 * real validator (422), while a compliant {@code $i18n:} key or LocalizedText
 * map passes and persists.</p>
 */
@DisplayName("PageSchema i18n compliance - real validator full-stack IT")
class PageSchemaI18nValidationFullStackIntegrationTest extends BaseIntegrationTest {

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

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        grantPermissionToTestRole(PERMISSION_CODE, "page", "page", "manage", "Page Manage");
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

    // ── I18N-FS-01: hardcoded Chinese title rejected by the REAL validator ─────

    @Test
    @DisplayName("I18N-FS-01: POST with hardcoded Chinese title → real validator rejects with 422")
    void create_hardcodedChineseTitle_realValidatorRejects() throws Exception {
        String pageKey = "i18n_zh_reject_" + System.currentTimeMillis();
        Map<String, Object> body = baseBody(pageKey);
        body.put("title", "合同管理"); // not $i18n:, not LocalizedText → violation

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(readByPageKey(pageKey))
                .as("page with non-compliant title must NOT persist")
                .isNull();
    }

    // ── I18N-FS-02: $i18n: key title passes the REAL validator and persists ────

    @Test
    @DisplayName("I18N-FS-02: POST with $i18n: title → passes real validator, persists")
    void create_i18nKeyTitle_realValidatorPasses() throws Exception {
        String pageKey = "i18n_key_ok_" + System.currentTimeMillis();
        Map<String, Object> body = baseBody(pageKey);
        body.put("title", "$i18n:page.contract.title");

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is2xxSuccessful());

        assertThat(readByPageKey(pageKey)).isNotNull();
    }

    // ── I18N-FS-03: LocalizedText map title passes the REAL validator ──────────

    @Test
    @DisplayName("I18N-FS-03: POST with LocalizedText map title → passes real validator, persists")
    void create_localizedTextTitle_realValidatorPasses() throws Exception {
        String pageKey = "i18n_localized_ok_" + System.currentTimeMillis();
        Map<String, Object> body = baseBody(pageKey);
        // LocalizedText map is encoded as a JSON object string in the title field;
        // PageSchemaServiceImpl.normalizeTextFieldForI18nValidation parses it back
        // to a Map so the validator treats it as compliant LocalizedText.
        body.put("title", objectMapper.writeValueAsString(Map.of(
                "zh-CN", "合同管理",
                "en-US", "Contract Management")));

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is2xxSuccessful());

        PageSchema persisted = readByPageKey(pageKey);
        assertThat(persisted).isNotNull();
    }

    // ── I18N-FS-04: hardcoded Chinese inside a block label rejected ────────────

    @Test
    @DisplayName("I18N-FS-04: POST with Chinese block label → real validator rejects with 422")
    void create_chineseBlockLabel_realValidatorRejects() throws Exception {
        String pageKey = "i18n_block_zh_" + System.currentTimeMillis();
        Map<String, Object> body = baseBody(pageKey);
        body.put("title", "Contract List"); // page title compliant
        // but a block carries a hardcoded Chinese label
        body.put("blocks", List.of(Map.of(
                "id", "blk_toolbar_1",
                "blockType", "toolbar",
                "label", "工具栏")));

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(readByPageKey(pageKey)).isNull();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Reads a page by pageKey with the test MetaContext applied on the current
     * (test) thread. The servlet filter only sets MetaContext during the MockMvc
     * request and clears it afterward, so direct mapper reads must re-apply it.
     */
    private PageSchema readByPageKey(String pageKey) {
        // selectAnyByPageKey: no status filter — created pages are drafts.
        return withMetaContext(() -> pageSchemaMapper.selectAnyByPageKey(pageKey));
    }

    private <T> T withMetaContext(Supplier<T> action) {
        try {
            applyTestMetaContext();
            return action.get();
        } finally {
            MetaContext.clear();
        }
    }

    private Map<String, Object> baseBody(String pageKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("pageKey", pageKey);
        body.put("name", "I18n FS Page " + System.nanoTime());
        body.put("title", "Contract List");
        body.put("kind", "list");
        body.put("blocks", List.of()); // empty by default; structural guard skips empty
        return body;
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
