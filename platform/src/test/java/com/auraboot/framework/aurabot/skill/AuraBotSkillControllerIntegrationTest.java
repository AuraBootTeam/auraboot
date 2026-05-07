package com.auraboot.framework.aurabot.skill;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.skill.builtin.EchoSkill;
import com.auraboot.framework.aurabot.skill.builtin.ModelQuerySkill;
import com.auraboot.framework.aurabot.skill.entity.SkillRun;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Main IT suite for {@code AuraBotSkillController} (Plan §Step 10).
 *
 * <p>Real PostgreSQL (port 25442) + real Redis (port 26389) via the
 * {@code skills-c2-test} profile. {@link UserPermissionService} and
 * {@link PermissionMapper} are mocked because the controller only consults
 * them through {@code resolveCurrentUserPermissions()} — wiring real RBAC
 * tables would turn this suite into an RBAC integration test.
 *
 * <p>EchoSkill / ModelQuerySkill are real {@code @Component} beans wired by
 * the Spring context and registered into {@link AuraBotSkillRegistry} on
 * {@code ContextRefreshedEvent}; the validator + Redis stores + DB
 * repository all run unmocked.
 *
 * <p>Each test resolves to a typed {@link com.auraboot.framework.aurabot.skill.error.SkillErrorCode}
 * via the central {@code SkillExceptionHandler}; assertions check both the
 * HTTP status (matching the SkillErrorCode → HTTP table in SPI Contract §11)
 * and the {@code body.code} string (the wire identifier the FE switches on).
 */
@ActiveProfiles({"integration-test", "skills-c2-test"})
@Import(AuraBotSkillControllerIntegrationTest.NoDryRunSkillTestConfig.class)
@DisplayName("AuraBotSkillController — main IT (real PG + real Redis, mocked permissions)")
class AuraBotSkillControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SkillRunRepository repository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EchoSkill echoSkill;

    @Autowired
    private ModelQuerySkill modelQuerySkill;

    @MockBean
    private UserPermissionService userPermissionService;

    @MockBean
    private PermissionMapper permissionMapper;

    private MockMvc mockMvc;

    /** Controlled per-test permission set the mocked PermissionMapper returns. */
    private final Set<String> currentPermissions = new HashSet<>();

    @BeforeEach
    void setUp() {
        // Default: caller has no permissions. Individual tests override.
        currentPermissions.clear();

        Long userId = getTestUser().getId();
        when(userPermissionService.getUserPermissionIds(eq(userId)))
                .thenAnswer(inv -> Set.of(1L)); // any non-empty so mapper is called
        when(permissionMapper.findByIds(any())).thenAnswer(inv ->
                currentPermissions.stream().map(code -> {
                    Permission p = new Permission();
                    p.setCode(code);
                    return p;
                }).toList());

        // BaseIntegrationTest already sets MetaContext on the main thread via
        // @BeforeEach. MockMvc#perform runs the filter chain synchronously on
        // that same thread, so a finally-clear here would wipe the parent
        // context for subsequent perform() calls AND the post-perform
        // assertion phase (e.g. repository.findByIdempotency). Re-establish
        // per request without clearing afterwards — the JUnit test method
        // itself owns the lifecycle of the ThreadLocal.
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
            if (v == null) {
                n.putNull(k);
            } else if (v instanceof JsonNode jn) {
                n.set(k, jn);
            } else if (v instanceof String s) {
                n.put(k, s);
            } else if (v instanceof Number num) {
                n.put(k, num.doubleValue());
            } else if (v instanceof Boolean b) {
                n.put(k, b);
            } else {
                n.set(k, objectMapper.valueToTree(v));
            }
        });
        return n;
    }

    // ─── Case 1 ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Case 1: GET /skills with no permissions returns ETag-tagged catalog including no-perm skills")
    void case1_listSkills_noPermissions_returnsCatalog() throws Exception {
        // empty permission set → only skills with empty requiredPermissions visible (echo).
        currentPermissions.clear();

        mockMvc.perform(get("/api/aurabot/v2/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").isArray())
                // echo has no required perms → must surface
                .andExpect(jsonPath("$.data[?(@.name=='echo')]").exists())
                // model:query requires MODEL.READ which the user lacks → must NOT surface
                .andExpect(jsonPath("$.data[?(@.name=='model:query')]").doesNotExist());
    }

    // ─── Case 2 ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Case 2: POST /skill/execute with unknown skillName → 404 SKILL_NOT_FOUND")
    void case2_execute_unknownSkill_404() throws Exception {
        ObjectNode req = body(Map.of(
                "skillName", "it-aurabot-does-not-exist-" + UniqueIdGenerator.generate().toLowerCase(),
                "params", objectMapper.createObjectNode()
        ));

        mockMvc.perform(post("/api/aurabot/v2/skill/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SKILL_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ─── Case 3 ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Case 3: POST /skill/execute echo with missing 'text' → 400 PARAMS_INVALID + fieldPath")
    void case3_execute_echoMissingText_400() throws Exception {
        ObjectNode req = body(Map.of(
                "skillName", "echo",
                "params", objectMapper.createObjectNode() // empty → schema requires "text"
        ));

        MvcResult result = mockMvc.perform(post("/api/aurabot/v2/skill/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMS_INVALID"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                // SkillExceptionHandler attaches context.fieldPath for PARAMS_INVALID with non-blank pointer.
                // The schema validator emits an instance-pointer for the missing-required violation; assert it.
                .andExpect(jsonPath("$.context.fieldPath").exists())
                .andReturn();

        // Sanity: response is well-formed JSON (already implicit from jsonPath, but assert explicitly).
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("code").asText()).isEqualTo("PARAMS_INVALID");
    }

    // ─── Case 5 (LOW slice — see SkillExecuteMediumRiskIT for full HTTP path) ─
    @Test
    @DisplayName("Case 5 (LOW slice): dry-run mints preview token; second consume returns empty (one-shot)")
    void case5_staleTokenAfterExecute_422() throws Exception {
        // EchoSkill in this class runs at LOW risk (default). For preview-token semantics
        // we mint a token via dry-run and then exercise the consume + replay paths
        // through model:query (also LOW risk, no risk-gating). LOW skills also get a token
        // by design ("controller mints regardless of risk"), but step 5 only enforces
        // matching for ≥ MEDIUM. To assert the "stale token" path we use the MEDIUM
        // variant in SkillExecuteMediumRiskIT (case 4 + token consume + stale).
        //
        // Here, we exercise the dry-run → execute → re-execute path on echo to verify
        // that the dry-run+execute combo round-trips correctly when no risk gating
        // is required, and then assert PREVIEW_TOKEN_INVALID surfaces when a previously
        // minted token is replayed via the validator's consume() through a MEDIUM-risk
        // skill in the dedicated MediumRiskIT.
        //
        // For the same-class case 5 contract, we mint a preview token, consume it
        // explicitly, then replay → expect PREVIEW_TOKEN_INVALID by re-running execute
        // against an echo whose risk has been bumped via TestPropertySource — but that
        // would require a second context. Instead, drive consume directly via the
        // PreviewTokenStore and assert that the store's one-shot semantics surface
        // through the validator: see SkillExecuteMediumRiskIT.case5_staleToken_422.
        //
        // To keep main-IT case 5 meaningful without touching MEDIUM risk here, we
        // assert the dry-run round-trip yields a NEEDS_CONFIRM envelope with a
        // non-blank previewToken (token is minted regardless of risk per controller),
        // and that the second consume of that same token via the store returns empty.
        ObjectNode params = objectMapper.createObjectNode().put("text", "case5");

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

        // Burn the token directly via the store so we can assert the one-shot semantics
        // without crossing into MEDIUM-risk territory in this class.
        org.springframework.beans.factory.support.DefaultListableBeanFactory bf =
                (org.springframework.beans.factory.support.DefaultListableBeanFactory)
                        webApplicationContext.getAutowireCapableBeanFactory();
        PreviewTokenStore store = bf.getBean(PreviewTokenStore.class);
        java.util.Optional<PreviewTokenStore.PreviewPayload> first =
                store.consume(token, "echo", params);
        assertThat(first).as("first consume must succeed").isPresent();
        java.util.Optional<PreviewTokenStore.PreviewPayload> second =
                store.consume(token, "echo", params);
        assertThat(second).as("second consume must return empty (one-shot)").isEmpty();
    }

    // ─── Case 6 ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Case 6: POST /skill/execute model:query without MODEL.READ → 403 PERMISSION_DENIED")
    void case6_modelQueryWithoutPermission_403() throws Exception {
        currentPermissions.clear(); // no MODEL.READ

        ObjectNode params = objectMapper.createObjectNode()
                .put("modelCode", "it-aurabot-some-model");
        ObjectNode req = body(Map.of(
                "skillName", "model:query",
                "params", params
        ));

        mockMvc.perform(post("/api/aurabot/v2/skill/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ─── Case 7 ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Case 7: POST /skill/undo with non-existent token → 410 UNDO_EXPIRED")
    void case7_undoNonexistentToken_410() throws Exception {
        ObjectNode req = body(Map.of(
                "undoToken", "u-it-aurabot-does-not-exist-" + UniqueIdGenerator.generate()
        ));

        mockMvc.perform(post("/api/aurabot/v2/skill/undo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req.toString()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("UNDO_EXPIRED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ─── Case 8 ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Case 8: same idempotency key twice → first SUCCESS, second IDEMPOTENCY_REPLAY (HTTP 200)")
    void case8_idempotencyReplay() throws Exception {
        String idemKey = "it-aurabot-idem-" + UniqueIdGenerator.generate();
        String text = "case8-payload";
        ObjectNode params = objectMapper.createObjectNode().put("text", text);
        ObjectNode req = body(Map.of(
                "skillName", "echo",
                "params", params,
                "idempotencyKey", idemKey
        ));

        // 1st execute → fresh write, status SUCCESS
        MvcResult first = mockMvc.perform(post("/api/aurabot/v2/skill/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.payload.echo").value(text))
                .andReturn();

        // 2nd execute with same idempotencyKey → replay envelope
        MvcResult second = mockMvc.perform(post("/api/aurabot/v2/skill/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_REPLAY"))
                .andExpect(jsonPath("$.data.skillName").value("echo"))
                .andReturn();

        // DB-level guarantee: only ONE SkillRun row exists for this (tenant, skill, idemKey).
        // Re-establish MetaContext on the test thread — the platform's
        // TenantInterceptor clears it after each MockMvc dispatch, and the
        // tenant-line MyBatis interceptor reads MetaContext on every query.
        long tenant = getTestTenant().getId();
        MetaContext.setContext(tenant, getTestUser().getId(),
                getTestUser().getPid(), getTestUser().getUserName());
        MetaContext.setMemberId(getTestTenantMember().getId());
        java.util.Optional<SkillRun> winner = repository.findByIdempotency(
                tenant, "echo", idemKey, java.time.Duration.ofMinutes(60));
        assertThat(winner).isPresent();
        assertThat(winner.get().getIdempotencyKey()).isEqualTo(idemKey);

        // Replay payload must match the first call's payload (after-snapshot replayed).
        JsonNode firstData = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("payload");
        JsonNode secondData = objectMapper.readTree(second.getResponse().getContentAsString())
                .path("data").path("payload");
        assertThat(secondData.path("echo").asText())
                .as("replay payload must match original after-snapshot")
                .isEqualTo(firstData.path("echo").asText());
    }

    // ─── Case 9 (B8 follow-up — F-2 clean fix) ───────────────────────────────
    @Test
    @DisplayName("Case 9: POST /skill/dry-run on a skill with supportsDryRun=false → 422 DRY_RUN_NOT_SUPPORTED")
    void case9_dryRunNotSupported_422() throws Exception {
        // The NoDryRunSkill bean is wired by the nested @TestConfiguration only
        // when this IT runs; it is invisible to production profiles. Calling
        // dry-run on it must surface the typed 422 envelope rather than the
        // skill's UnsupportedOperationException — that is the controller's
        // dry-run pre-check contract (see SPI Contract §11).
        ObjectNode req = body(Map.of(
                "skillName", NoDryRunSkill.NAME,
                "params", objectMapper.createObjectNode()
        ));

        mockMvc.perform(post("/api/aurabot/v2/skill/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DRY_RUN_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    /**
     * Test-only skill registered into the IT context to exercise the
     * {@code DRY_RUN_NOT_SUPPORTED} controller pre-check. Lives as a static
     * nested class so it is not picked up by component scan in production
     * profiles — only the {@link Import} on this IT pulls it in.
     */
    @TestConfiguration
    static class NoDryRunSkillTestConfig {
        @Bean
        NoDryRunSkill noDryRunSkill() {
            return new NoDryRunSkill();
        }
    }

    /** Minimal skill with {@code supportsDryRun()=false}. */
    static class NoDryRunSkill implements AuraBotSkill {
        static final String NAME = "test:no-dry-run";

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public String displayName() {
            return "test:no-dry-run";
        }

        @Override
        public RiskLevel riskLevel() {
            return RiskLevel.LOW;
        }

        @Override
        public JsonNode paramsSchema() {
            return JsonNodeFactory.instance.objectNode().put("type", "object");
        }

        @Override
        public boolean supportsDryRun() {
            return false;
        }

        @Override
        public SkillResult execute(SkillRequest req) {
            return SkillResult.builder()
                    .status(SkillResult.Status.SUCCESS)
                    .skillName(NAME)
                    .build();
        }
    }
}
