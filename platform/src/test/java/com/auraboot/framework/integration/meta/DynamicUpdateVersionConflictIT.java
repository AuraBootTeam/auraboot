package com.auraboot.framework.integration.meta;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Direct dynamic-record updates that carry {@code _expectedVersion} must answer HTTP 409
 * (ConflictException / CAS_VERSION_CONFLICT) on an optimistic-lock miss — not a 400
 * business error. Mobile offline replay only surfaces user-visible conflicts from 409,
 * so a 400 here silently downgrades an edit conflict into a retry-then-drop.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles({"integration-test", "test"})
@DisplayName("Dynamic update optimistic-lock conflict returns HTTP 409")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DynamicUpdateVersionConflictIT extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantMemberService tenantMemberService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PluginImportService pluginImportService;

    private MockMvc mockMvc;
    private MockMvc authenticatedMvc;

    private Long tenantId;
    private Long userId;
    private String recordPid;
    private long rowVersion;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        MvcResult seed = mockMvc.perform(post("/api/test/seed")
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        String seedJson = seed.getResponse().getContentAsString();
        JsonNode seedBody = objectMapper.readTree(seedJson);
        String firstSeedError = seedBody.path("context").path("detail").asText("");
        Assertions.assertTrue(seed.getResponse().getStatus() == 200 || firstSeedError.contains("showcase"),
                "seed must succeed or fail only on the showcase precondition; body=" + seedJson);

        // The shared developer database may lack the showcase plugin pages that
        // /api/test/seed requires for the mobile-E2E fixture guarantee. Import them
        // explicitly (the same path the seed controller uses) and re-seed, so this IT
        // does not depend on whoever ran the mobile E2E stack last.
        if (seed.getResponse().getStatus() != 200) {
            tenantId = seedBody.path("data").asLong(0);
            tenantId = jdbcTemplate.queryForObject(
                    "SELECT id FROM ab_tenant WHERE name = 'e2e_test' ORDER BY id DESC LIMIT 1", Long.class);
            userId = jdbcTemplate.queryForObject(
                    "SELECT id FROM ab_user WHERE email = 'e2e@test.local' ORDER BY id DESC LIMIT 1", Long.class);
            importShowcasePlugin(tenantId, userId);
            MvcResult reseed = mockMvc.perform(post("/api/test/seed")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(result -> Assertions.assertEquals(200,
                            result.getResponse().getStatus(),
                            "re-seed must return 200; body=" + result.getResponse().getContentAsString()))
                    .andReturn();
            seedBody = objectMapper.readTree(reseed.getResponse().getContentAsString());
        }
        tenantId = seedBody.get("tenantId").asLong();
        userId = seedBody.get("userId").asLong();
        authenticatedMvc = authenticatedMvc(tenantId, userId);

        String orderNo = "CAS409_" + System.nanoTime();
        MvcResult create = authenticatedMvc.perform(post("/api/dynamic/e2et_order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "e2et_order_no", orderNo,
                                "e2et_order_title", "CAS 409 conflict fixture",
                                "e2et_order_status", "draft"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode createBody = objectMapper.readTree(create.getResponse().getContentAsString());
        recordPid = createBody.path("data").path("pid").asText();
        Assertions.assertFalse(recordPid.isBlank(), "created record must expose pid");

        rowVersion = jdbcTemplate.queryForObject(
                "SELECT row_version FROM mt_e2et_order WHERE tenant_id = ? AND pid = ?",
                Long.class, tenantId, recordPid);
    }

    @Test
    @Order(1)
    @DisplayName("update with current _expectedVersion succeeds (200)")
    void updateWithCurrentVersionSucceeds() throws Exception {
        authenticatedMvc.perform(put("/api/dynamic/e2et_order/" + recordPid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "e2et_order_title", "CAS 409 first write",
                                "_expectedVersion", rowVersion))))
                .andExpect(status().isOk());
    }

    @Test
    @Order(2)
    @DisplayName("update with stale _expectedVersion returns 409 + CAS_VERSION_CONFLICT")
    void updateWithStaleVersionReturns409() throws Exception {
        // Simulate another writer landing first: the row moves ahead of the caller's version.
        jdbcTemplate.update(
                "UPDATE mt_e2et_order SET row_version = row_version + 1, e2et_order_title = 'written by another device' "
                        + "WHERE tenant_id = ? AND pid = ?",
                tenantId, recordPid);

        authenticatedMvc.perform(put("/api/dynamic/e2et_order/" + recordPid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "e2et_order_title", "CAS 409 stale write",
                                "_expectedVersion", rowVersion))))
                .andExpect(status().isConflict())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    JsonNode json = objectMapper.readTree(body);
                    Assertions.assertEquals("41001", json.path("code").asText(),
                            "conflict body must carry the CasVersionConflict code; body=" + body);
                    Assertions.assertEquals("CAS_VERSION_CONFLICT",
                            json.path("context").path("errorCode").asText(),
                            "conflict context must carry errorCode=CAS_VERSION_CONFLICT; body=" + body);
                });
    }

    @Test
    @Order(3)
    @DisplayName("update without _expectedVersion keeps legacy no-CAS behavior (200)")
    void updateWithoutVersionKeepsLegacyBehavior() throws Exception {
        authenticatedMvc.perform(put("/api/dynamic/e2et_order/" + recordPid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "e2et_order_title", "CAS 409 legacy write"))))
                .andExpect(status().isOk());
    }

    /**
     * Import the OSS showcase plugin (list/form/detail showcase_all_fields pages) for the
     * test tenant — the precondition /api/test/seed enforces for the mobile-E2E fixtures.
     */
    private void importShowcasePlugin(long tenantId, long userId) {
        Path pluginDir = java.nio.file.Path.of(System.getProperty("user.dir"))
                .resolve("../plugins/showcase").normalize();
        Assertions.assertTrue(java.nio.file.Files.exists(pluginDir),
                "showcase plugin directory must exist at " + pluginDir);
        MetaContext.setContext(tenantId, userId, "e2e-test-user", "e2e@test.local");
        try {
            ImportPreviewResult preview = pluginImportService.parseDirectory(pluginDir.toString());
            Assertions.assertTrue(preview.isValid(), "showcase plugin parse must be valid: " + preview.getErrors());
            ImportRequest request = ImportRequest.builder()
                    .importId(preview.getImportId())
                    .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                    .autoPublishModels(true)
                    .autoPublishFields(true)
                    .autoPublishCommands(true)
                    .autoPublishPages(true)
                    .autoDeployProcesses(true)
                    .build();
            var result = pluginImportService.execute(preview.getImportId(), request);
            Assertions.assertTrue(result.isSuccess(), "showcase plugin import must succeed: " + result.getErrorMessage());
        } finally {
            MetaContext.clear();
        }
    }

    private MockMvc authenticatedMvc(long tenantId, long userId) {
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(tenantId, userId);
        Assertions.assertNotNull(member, "seeded user must have tenant member before authenticated API calls");

        Filter contextFilter = (request, response, chain) -> {
            try {
                MetaContext.setContext(tenantId, userId, "e2e-test-user", "e2e@test.local");
                MetaContext.setMemberId(member.getId());
                CustomUserDetails userDetails = new CustomUserDetails(
                        "e2e@test.local",
                        "test-password",
                        userId,
                        "e2e-test-user",
                        AuthorityUtils.createAuthorityList("role_admin"),
                        true,
                        true,
                        true,
                        true
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

        return MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(contextFilter, "/*")
                .build();
    }
}
