package com.auraboot.framework.aurabot.skill;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Case 4 + the MEDIUM-risk slice of case 5 (Plan §Step 10).
 *
 * <p>Bumps {@code aurabot.skill.echo.risk-level=medium} via
 * {@link TestPropertySource} so EchoSkill reports {@code MEDIUM} risk on
 * registry bootstrap. With MEDIUM risk:
 * <ul>
 *     <li>execute without preview-token → 422 {@code CONFIRM_REQUIRED}.</li>
 *     <li>dry-run → execute(token) → 200 (token consumed).</li>
 *     <li>second execute(same token) → 422 {@code PREVIEW_TOKEN_INVALID}.</li>
 * </ul>
 *
 * <p>Lives in a separate class because the property is read at bean
 * construction time; sharing the main IT context would either freeze the
 * skill at one risk level for the whole suite or require a per-test
 * {@code DirtiesContext}.
 */
@ActiveProfiles({"integration-test", "skills-c2-test"})
@TestPropertySource(properties = "aurabot.skill.echo.risk-level=medium")
@DisplayName("AuraBotSkillController — MEDIUM-risk echo IT (case 4 + case 5)")
class SkillExecuteMediumRiskIT extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockBean
    private UserPermissionService userPermissionService;

    @MockBean
    private PermissionMapper permissionMapper;

    private MockMvc mockMvc;
    private final Set<String> currentPermissions = new HashSet<>();

    @BeforeEach
    void setUp() {
        currentPermissions.clear();

        Long userId = getTestUser().getId();
        when(userPermissionService.getUserPermissionIds(eq(userId)))
                .thenAnswer(inv -> Set.of(1L));
        when(permissionMapper.findByIds(any())).thenAnswer(inv ->
                currentPermissions.stream().map(code -> {
                    Permission p = new Permission();
                    p.setCode(code);
                    return p;
                }).toList());

        // See AuraBotSkillControllerIntegrationTest for rationale: do not
        // finally-clear the ThreadLocal — MockMvc runs filters on the test
        // thread, and clearing would wipe the parent context BaseIntegrationTest
        // installed in @BeforeEach.
        Filter metaContextFilter = (request, response, chain) -> {
            MetaContext.setContext(
                    getTestTenant().getId(),
                    getTestUser().getId(),
                    getTestUser().getPid(),
                    getTestUser().getUserName()
            );
            MetaContext.setMemberId(getTestTenantMember().getId());
            chain.doFilter(request, response);
        };
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(metaContextFilter, "/*")
                .build();
    }

    @AfterEach
    void wipeRedis() {
        Set<String> idem = redisTemplate.keys(SkillIdempotencyStore.KEY_PREFIX + "*");
        if (idem != null && !idem.isEmpty()) {
            redisTemplate.delete(idem);
        }
        Set<String> preview = redisTemplate.keys(PreviewTokenStore.KEY_PREFIX + "*");
        if (preview != null && !preview.isEmpty()) {
            redisTemplate.delete(preview);
        }
    }

    private ObjectNode body(Map<String, Object> kv) {
        ObjectNode n = objectMapper.createObjectNode();
        kv.forEach((k, v) -> {
            if (v instanceof JsonNode jn) {
                n.set(k, jn);
            } else if (v instanceof String s) {
                n.put(k, s);
            } else {
                n.set(k, objectMapper.valueToTree(v));
            }
        });
        return n;
    }

    @Test
    @DisplayName("Case 4: execute MEDIUM-risk echo without previewToken → 422 CONFIRM_REQUIRED")
    void case4_mediumWithoutToken_422() throws Exception {
        ObjectNode params = objectMapper.createObjectNode().put("text", "case4");
        ObjectNode req = body(Map.of(
                "skillName", "echo",
                "params", params,
                "idempotencyKey", "it-aurabot-idem-" + UniqueIdGenerator.generate()
        ));

        mockMvc.perform(post("/api/aurabot/v2/skill/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONFIRM_REQUIRED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("Case 4 happy path: dry-run → execute(token) → 200 SUCCESS")
    void case4_dryRunThenExecute_success() throws Exception {
        ObjectNode params = objectMapper.createObjectNode().put("text", "case4-happy");

        ObjectNode dryReq = body(Map.of(
                "skillName", "echo",
                "params", params
        ));
        MvcResult dry = mockMvc.perform(post("/api/aurabot/v2/skill/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dryReq.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NEEDS_CONFIRM"))
                .andExpect(jsonPath("$.data.previewToken").isNotEmpty())
                .andReturn();

        String token = objectMapper.readTree(dry.getResponse().getContentAsString())
                .path("data").path("previewToken").asText();
        assertThat(token).isNotBlank();

        ObjectNode execReq = body(Map.of(
                "skillName", "echo",
                "params", params,
                "previewToken", token,
                "idempotencyKey", "it-aurabot-idem-" + UniqueIdGenerator.generate()
        ));

        mockMvc.perform(post("/api/aurabot/v2/skill/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(execReq.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.payload.echo").value("case4-happy"));
    }

    @Test
    @DisplayName("Case 5: stale token after first execute → 422 PREVIEW_TOKEN_INVALID")
    void case5_staleToken_422() throws Exception {
        ObjectNode params = objectMapper.createObjectNode().put("text", "case5-stale");

        ObjectNode dryReq = body(Map.of(
                "skillName", "echo",
                "params", params
        ));
        MvcResult dry = mockMvc.perform(post("/api/aurabot/v2/skill/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dryReq.toString()))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(dry.getResponse().getContentAsString())
                .path("data").path("previewToken").asText();
        assertThat(token).isNotBlank();

        // First execute consumes the token (one-shot).
        ObjectNode firstExec = body(Map.of(
                "skillName", "echo",
                "params", params,
                "previewToken", token,
                "idempotencyKey", "it-aurabot-idem-" + UniqueIdGenerator.generate()
        ));
        mockMvc.perform(post("/api/aurabot/v2/skill/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstExec.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        // Second execute with the now-burnt token → PREVIEW_TOKEN_INVALID (422).
        ObjectNode replay = body(Map.of(
                "skillName", "echo",
                "params", params,
                "previewToken", token,
                "idempotencyKey", "it-aurabot-idem-" + UniqueIdGenerator.generate()
        ));
        mockMvc.perform(post("/api/aurabot/v2/skill/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replay.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PREVIEW_TOKEN_INVALID"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
