package com.auraboot.framework.integration.test;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
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

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for TestSeedController.
 * Verifies seed, reset, and context endpoints for E2E test environment setup.
 * <p>
 * Requires both "integration-test" and "test" profiles since the controller
 * is annotated with @Profile("test").
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles({"integration-test", "test"})
@DisplayName("TestSeedController Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TestSeedControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TenantMemberService tenantMemberService;

    @Autowired
    private UserPermissionService userPermissionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    // Cross-test state captured from seed response
    private Long seededTenantId;
    private Long seededUserId;

    @BeforeEach
    void setup() {
        // No auth filter needed — /api/test/** is whitelisted in SecurityConfig
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .build();
    }

    @Test
    @Order(1)
    @DisplayName("TS-01: POST /api/test/seed creates test tenant and user, returns valid SeedResult")
    void seed_createsTestTenantAndUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/test/seed")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").isNumber())
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.jwt").isString())
                .andExpect(jsonPath("$.jwt", not(emptyString())))
                .andExpect(jsonPath("$.email").value("e2e@test.local"))
                .andExpect(jsonPath("$.tenantName").value("e2e_test"))
                .andReturn();

        // Capture IDs for subsequent tests using Jackson
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        seededTenantId = body.get("tenantId").asLong();
        seededUserId = body.get("userId").asLong();
        assertJwtIncludesMemberId(body);

        log.info("TS-01: seed returned tenantId={}, userId={}", seededTenantId, seededUserId);
    }

    @Test
    @Order(2)
    @DisplayName("TS-02: POST /api/test/seed is idempotent — returns same tenantId and userId")
    void seed_idempotent_returnsSameUser() throws Exception {
        Assertions.assertNotNull(seededTenantId, "seededTenantId must be set by TS-01");
        Assertions.assertNotNull(seededUserId, "seededUserId must be set by TS-01");

        MvcResult result = mockMvc.perform(post("/api/test/seed")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("e2e@test.local"))
                .andReturn();

        // Use Jackson's asLong() to avoid Long vs Integer type mismatch in jsonPath().value()
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Assertions.assertEquals(seededTenantId, body.get("tenantId").asLong(),
                "Second seed must return same tenantId");
        Assertions.assertEquals(seededUserId, body.get("userId").asLong(),
                "Second seed must return same userId");
        assertJwtIncludesMemberId(body);

        log.info("TS-02: second seed returned same tenantId={}, userId={}", seededTenantId, seededUserId);
    }

    @Test
    @Order(3)
    @DisplayName("TS-03: GET /api/test/context returns seeded=true with matching IDs and a valid JWT")
    void context_returnsCurrentState() throws Exception {
        Assertions.assertNotNull(seededTenantId, "seededTenantId must be set by TS-01");

        MvcResult result = mockMvc.perform(get("/api/test/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(true))
                .andExpect(jsonPath("$.email").value("e2e@test.local"))
                .andExpect(jsonPath("$.jwt").isString())
                .andExpect(jsonPath("$.jwt", not(emptyString())))
                .andReturn();

        // Use Jackson's asLong() to avoid Long vs Integer type mismatch in jsonPath().value()
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Assertions.assertEquals(seededTenantId, body.get("tenantId").asLong(),
                "context must return same tenantId as seed");
        Assertions.assertEquals(seededUserId, body.get("userId").asLong(),
                "context must return same userId as seed");
        assertJwtIncludesMemberId(body);

        log.info("TS-03: context returned seeded=true, tenantId={}", seededTenantId);
    }

    @Test
    @Order(4)
    @DisplayName("TS-04: POST /api/test/reset destroys and recreates, returns valid SeedResult with JWT")
    void reset_clearsAndRecreates() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/test/reset")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").isNumber())
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.jwt").isString())
                .andExpect(jsonPath("$.jwt", not(emptyString())))
                .andExpect(jsonPath("$.email").value("e2e@test.local"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Long newTenantId = body.get("tenantId").asLong();

        // After reset, tenantId may differ (old tenant deleted, new one created)
        Assertions.assertNotNull(newTenantId);
        assertJwtIncludesMemberId(body);
        log.info("TS-04: reset returned tenantId={} (previous was {})", newTenantId, seededTenantId);
    }

    @Test
    @Order(5)
    @DisplayName("TS-05: GET /api/test/context after reset still shows seeded=true")
    void context_afterReset_stillSeeded() throws Exception {
        mockMvc.perform(get("/api/test/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(true))
                .andExpect(jsonPath("$.jwt").isString())
                .andExpect(jsonPath("$.jwt", not(emptyString())));

        log.info("TS-05: context after reset shows seeded=true");
    }

    @Test
    @Order(6)
    @DisplayName("TS-06: POST /api/test/seed with testRunId param echoes the same ID in response")
    void seed_withTestRunId_echoesInResponse() throws Exception {
        String customRunId = "xp_1234567890_abcd";
        MvcResult result = mockMvc.perform(post("/api/test/seed")
                        .param("testRunId", customRunId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testRunId").value(customRunId))
                .andExpect(jsonPath("$.jwt").isString())
                .andReturn();

        log.info("TS-06: seed with testRunId={} returned matching ID", customRunId);
    }

    @Test
    @Order(7)
    @DisplayName("TS-07: POST /api/test/seed without testRunId auto-generates one")
    void seed_withoutTestRunId_generatesOne() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/test/seed")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testRunId").isString())
                .andExpect(jsonPath("$.testRunId", not(emptyString())))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String generatedRunId = body.get("testRunId").asText();
        // Auto-generated format: api_{unixSeconds}_{4-hex}
        Assertions.assertTrue(generatedRunId.startsWith("api_"),
                "Auto-generated testRunId should start with 'api_' prefix, got: " + generatedRunId);

        log.info("TS-07: seed without testRunId generated: {}", generatedRunId);
    }

    @Test
    @Order(8)
    @DisplayName("TS-08: GET /api/test/run-id returns a valid testRunId with platform prefix")
    void runId_endpoint_returnsValidFormat() throws Exception {
        mockMvc.perform(get("/api/test/run-id")
                        .param("platform", "xp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testRunId").isString())
                .andExpect(jsonPath("$.testRunId", org.hamcrest.Matchers.startsWith("xp_")));

        // Default platform
        mockMvc.perform(get("/api/test/run-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testRunId", org.hamcrest.Matchers.startsWith("api_")));

        log.info("TS-08: run-id endpoint returns valid format");
    }

    @Test
    @Order(9)
    @DisplayName("TS-09: POST /api/test/seed grants dynamic model permissions required by mobile E2E")
    void seed_grantsDynamicModelPermissionsForMobileE2e() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/test/seed")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        long tenantId = body.get("tenantId").asLong();
        long userId = body.get("userId").asLong();
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(tenantId, userId);
        Assertions.assertNotNull(member, "seeded user must have a tenant member");

        MetaContext.setContext(tenantId, userId, "test-user-pid", "e2e@test.local");
        MetaContext.setMemberId(member.getId());
        try {
            Integer permissionCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM ab_permission
                    WHERE tenant_id = ?
                      AND code = 'model.e2et_order.create'
                      AND deleted_flag = FALSE
                    """, Integer.class, tenantId);
            Integer globalPermissionCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM ab_permission
                    WHERE code = 'model.e2et_order.create'
                    """, Integer.class);
            Integer userRoleCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM ab_user_role ur
                    JOIN ab_role r ON r.id = ur.role_id
                    WHERE ur.tenant_id = ?
                      AND ur.member_id = ?
                      AND r.code = 'tenant_admin'
                      AND ur.status = 'active'
                      AND ur.deleted_flag = FALSE
                    """, Integer.class, tenantId, member.getId());
            Integer bindingCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM ab_role_permission rp
                    JOIN ab_permission p ON p.id = rp.permission_id
                    JOIN ab_role r ON r.id = rp.role_id
                    WHERE rp.tenant_id = ?
                      AND r.code = 'tenant_admin'
                      AND p.code = 'model.e2et_order.create'
                      AND rp.status = 'active'
                      AND rp.deleted_flag = FALSE
                    """, Integer.class, tenantId);
            Assertions.assertTrue(
                    userPermissionService.hasPermission(userId, "model.e2et_order.create"),
                    "mobile E2E seed user must be able to create e2et_order records; " +
                            "permissionCount=" + permissionCount +
                            ", globalPermissionCount=" + globalPermissionCount +
                            ", userRoleCount=" + userRoleCount +
                            ", bindingCount=" + bindingCount
            );
            Assertions.assertTrue(
                    userPermissionService.hasPermission(userId, "model.e2et_order.read"),
                    "mobile E2E seed user must be able to read e2et_order records"
            );
            Integer showcaseMenuCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM ab_menu
                    WHERE tenant_id = ?
                      AND page_key = 'showcase_all_fields_list'
                      AND deleted_flag = FALSE
                    """, Integer.class, tenantId);
            Assertions.assertTrue(
                    showcaseMenuCount != null && showcaseMenuCount > 0,
                    "mobile showcase E2E seed must expose showcase_all_fields in the tenant menu"
            );
            Assertions.assertTrue(
                    userPermissionService.hasPermission(userId, "sc.showcase.read"),
                    "mobile showcase E2E seed user must be able to read showcase_all_fields menu"
            );
            Integer showcasePageCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(DISTINCT page_key)
                    FROM ab_page_schema
                    WHERE tenant_id = ?
                      AND page_key IN (
                        'showcase_all_fields_list',
                        'showcase_all_fields_form',
                        'showcase_all_fields_detail'
                    )
                      AND model_code = 'showcase_all_fields'
                      AND status = 'published'
                      AND deleted_flag = FALSE
                    """, Integer.class, tenantId);
            Assertions.assertEquals(
                    3,
                    showcasePageCount,
                    "mobile showcase E2E seed must publish list/form/detail PageSchemas"
            );
            Integer showcaseRecordCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM mt_showcase_all_fields
                    WHERE tenant_id = ?
                    """, Integer.class, tenantId);
            Assertions.assertTrue(
                    showcaseRecordCount != null && showcaseRecordCount >= 4,
                    "mobile showcase E2E seed must include real showcase_all_fields records"
            );
            Integer showcaseAttachmentCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM mt_showcase_all_fields
                    WHERE tenant_id = ?
                      AND sc_attachment IS NOT NULL
                      AND jsonb_typeof(sc_attachment) = 'array'
                      AND jsonb_array_length(sc_attachment) > 0
                    """, Integer.class, tenantId);
            Assertions.assertTrue(
                    showcaseAttachmentCount != null && showcaseAttachmentCount > 0,
                    "mobile showcase E2E seed must include attachment JSON to verify safe mobile rendering"
            );
            Integer showcaseRichTextCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM mt_showcase_all_fields
                    WHERE tenant_id = ?
                      AND sc_richtext_content LIKE '<%'
                    """, Integer.class, tenantId);
            Assertions.assertTrue(
                    showcaseRichTextCount != null && showcaseRichTextCount > 0,
                    "mobile showcase E2E seed must include rich text to verify HTML-safe rendering"
            );
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @Order(10)
    @DisplayName("TS-10: POST /api/test/seed repairs dynamic table identity sequences for mobile E2E creates")
    void seed_repairsDynamicTableIdentitySequenceForMobileE2eCreate() throws Exception {
        MvcResult resetResult = mockMvc.perform(post("/api/test/reset")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode resetBody = objectMapper.readTree(resetResult.getResponse().getContentAsString());
        long tenantId = resetBody.get("tenantId").asLong();
        long userId = resetBody.get("userId").asLong();

        String sequenceName = jdbcTemplate.queryForObject(
                "SELECT pg_get_serial_sequence('public.mt_e2et_order', 'id')",
                String.class
        );
        Assertions.assertNotNull(sequenceName, "mt_e2et_order.id must have a PostgreSQL identity sequence");

        Long maxId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM mt_e2et_order",
                Long.class
        );
        long explicitId = maxId + 1000;
        String explicitOrderNo = "SEQ_STALE_" + explicitId;
        jdbcTemplate.update("""
                INSERT INTO mt_e2et_order (
                    id, pid, tenant_id, created_at, updated_at, created_by, updated_by,
                    e2et_order_no, e2et_order_title, e2et_order_status
                )
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?, ?, 'draft')
                """,
                explicitId,
                UniqueIdGenerator.generate(),
                tenantId,
                userId,
                userId,
                explicitOrderNo,
                "Stale sequence fixture " + explicitId
        );
        jdbcTemplate.queryForObject(
                "SELECT setval(to_regclass(?), 1, false)",
                Long.class,
                sequenceName
        );

        MvcResult repairedSeed = mockMvc.perform(post("/api/test/seed")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode repairedSeedBody = objectMapper.readTree(repairedSeed.getResponse().getContentAsString());
        tenantId = repairedSeedBody.get("tenantId").asLong();
        userId = repairedSeedBody.get("userId").asLong();

        String createdOrderNo = "SEQ_REPAIR_" + UniqueIdGenerator.generate();
        String payload = objectMapper.writeValueAsString(Map.of(
                "e2et_order_no", createdOrderNo,
                "e2et_order_title", "Sequence repair regression order",
                "e2et_order_status", "draft"
        ));

        MvcResult createResult = authenticatedMvc(tenantId, userId).perform(post("/api/dynamic/e2et_order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
        String createResponse = createResult.getResponse().getContentAsString();
        Assertions.assertEquals(200, createResult.getResponse().getStatus(),
                "dynamic create response status must be 200; body=" + createResponse);
        JsonNode createBody = objectMapper.readTree(createResponse);
        Assertions.assertEquals("0", createBody.path("code").asText(),
                "dynamic create must return ApiResponse OK; body=" + createResponse);
        Assertions.assertEquals(createdOrderNo, createBody.path("data").path("e2et_order_no").asText(),
                "dynamic create response must include the created order number; body=" + createResponse);
        Assertions.assertTrue(createBody.path("data").path("id").isNumber(),
                "dynamic create response must include a numeric id; body=" + createResponse);
        long createdId = createBody.path("data").path("id").asLong();
        Assertions.assertTrue(createdId > explicitId,
                "dynamic create must allocate an id after the explicit high-id fixture; createdId=" +
                        createdId + ", explicitId=" + explicitId);

        Integer persistedRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM mt_e2et_order
                WHERE tenant_id = ?
                  AND e2et_order_no = ?
                """, Integer.class, tenantId, createdOrderNo);
        Assertions.assertEquals(1, persistedRows,
                "dynamic create after seed repair must persist exactly one E2E order row");
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

    private void assertJwtIncludesMemberId(JsonNode body) {
        Long tenantId = body.get("tenantId").asLong();
        Long userId = body.get("userId").asLong();
        String jwt = body.get("jwt").asText();
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(tenantId, userId);

        Assertions.assertNotNull(member, "seeded user must have tenant member");
        Assertions.assertEquals(member.getId(), jwtUtil.extractMemberId(jwt),
                "seed JWT must include memberId for permission resolution");
    }
}
