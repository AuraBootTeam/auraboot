package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.AffectedModel;
import com.auraboot.framework.meta.dto.FieldModification;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.ModificationImpact;
import com.auraboot.framework.meta.dto.ModificationType;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.service.FieldImpactAnalysisService;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack integration test for {@link FieldImpactAnalysisServiceImpl}.
 *
 * <p>Part of OSS coverage initiative #8/#9 (tracker:
 * {@code docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md}). Lifts
 * {@code FieldImpactAnalysisServiceImpl} from ~1% line coverage to ≥70%.
 *
 * <p>Strategy:
 * <ol>
 *   <li><b>Pure-logic methods</b> ({@code isBreakingChange}, {@code classifyModification}):
 *       exercised exhaustively with crafted {@link FieldModification} objects — no DB needed.</li>
 *   <li><b>DB-backed methods</b> ({@code analyzeModificationImpact}, {@code getAffectedModels},
 *       {@code validateModificationSafety}): require a real {@code MetaField} fixture, and for
 *       "affected models" branches an {@code ab_meta_model_field_binding} row inserted directly
 *       via JdbcTemplate.</li>
 * </ol>
 *
 * <p>Uses the {@code integration-test} profile (shared Postgres on :5432). All fixtures are
 * created under a dedicated tenant (prefix {@code covimpact}) and hard-deleted in
 * {@link #tearDown()} to keep the shared DB clean. Fields have {@code @TableLogic} so
 * teardown uses raw SQL; bindings have no soft-delete so are deleted via mapper.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("FieldImpactAnalysisServiceImpl Real-Stack Integration Test")
class FieldImpactAnalysisServiceImplIntegrationTest {

    private static final String CODE_PREFIX = "covimpact";
    /** Stable per-class-run nonce; alnum only, LIKE-safe. */
    private static final String RUN = Long.toString(Math.abs(System.nanoTime()), 36);

    @Autowired
    private FieldImpactAnalysisService fieldImpactAnalysisService;

    @Autowired
    private MetaFieldService metaFieldService;

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private MetaModelFieldBindingMapper modelFieldBindingMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantMemberService tenantMemberService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicInteger seq = new AtomicInteger();
    private User testUser;
    private Tenant testTenant;

    /** Created field PIDs — hard-deleted in tearDown. */
    private final java.util.List<String> createdFieldPids = new java.util.ArrayList<>();
    /** Created binding IDs — deleted in tearDown. */
    private final java.util.List<Long> createdBindingIds = new java.util.ArrayList<>();
    /** Created model PIDs — hard-deleted in tearDown. */
    private final java.util.List<String> createdModelPids = new java.util.ArrayList<>();

    private String uniqueCode(String label) {
        return CODE_PREFIX + RUN + "_" + seq.incrementAndGet() + "_" + label;
    }

    // ==================== Setup / Teardown ====================

    @BeforeEach
    void setUp() {
        String testEmail = "covimpact-test@auraboot.com";
        testUser = userService.findByEmail(testEmail);
        if (testUser == null) {
            testUser = userService.signUp(testEmail, "test-password-123");
        }

        String testTenantName = "covimpact-test-tenant";
        testTenant = tenantService.findByName(testTenantName);
        if (testTenant == null) {
            Tenant tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(testTenantName);
            tenant.setDisplayName("FieldImpact Coverage Test Tenant");
            tenant.setStatus("active");
            tenant.setContactEmail("admin@covimpact-test.com");
            tenant.setDescription("Test tenant for FieldImpactAnalysis IT");
            tenant.setDeletedFlag(false);
            tenant.setCreatedAt(Instant.now());
            tenant.setUpdatedAt(Instant.now());
            testTenant = tenantService.createTenant(tenant);
        }

        TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
        if (member == null) {
            tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }

        MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void tearDown() {
        try {
            // Delete bindings first (no soft-delete)
            for (Long bindingId : createdBindingIds) {
                try {
                    modelFieldBindingMapper.deleteById(bindingId);
                } catch (Exception e) {
                    log.warn("binding cleanup failed id={}: {}", bindingId, e.getMessage());
                }
            }
            createdBindingIds.clear();

            // Hard-delete fields (TableLogic soft-delete — raw SQL required)
            for (String pid : createdFieldPids) {
                try {
                    jdbcTemplate.update(
                        "DELETE FROM ab_meta_field WHERE pid = ?", pid);
                } catch (Exception e) {
                    log.warn("field cleanup failed pid={}: {}", pid, e.getMessage());
                }
            }
            createdFieldPids.clear();

            // Hard-delete models (TableLogic soft-delete — raw SQL required)
            for (String pid : createdModelPids) {
                try {
                    jdbcTemplate.update(
                        "DELETE FROM ab_meta_model WHERE pid = ?", pid);
                } catch (Exception e) {
                    log.warn("model cleanup failed pid={}: {}", pid, e.getMessage());
                }
            }
            createdModelPids.clear();
        } finally {
            MetaContext.clear();
        }
    }

    // ==================== Helpers ====================

    private MetaFieldDTO createField(String label, String dataType) {
        MetaFieldCreateRequest req = new MetaFieldCreateRequest();
        req.setCode(uniqueCode(label));
        req.setDataType(dataType);
        MetaFieldDTO dto = metaFieldService.create(req);
        assertNotNull(dto, "field creation must succeed");
        createdFieldPids.add(dto.getPid());
        return dto;
    }

    /** Creates a minimal real model and tracks it for teardown. */
    private MetaModelDTO createModel(String label) {
        MetaModelCreateRequest req = new MetaModelCreateRequest();
        req.setCode(uniqueCode(label));
        req.setDisplayName("Impact Test Model " + label);
        req.setModelType("entity");
        MetaModelDTO dto = metaModelService.create(req);
        assertNotNull(dto, "model creation must succeed");
        createdModelPids.add(dto.getPid());
        return dto;
    }

    /**
     * Insert a ModelFieldBinding using a real model (satisfies FK fk_model_binding_model).
     * Tracks the binding id for teardown.
     */
    private ModelFieldBinding createBinding(Long modelId, Long fieldId) {
        ModelFieldBinding binding = new ModelFieldBinding(
            testTenant.getId(),
            modelId,
            fieldId,
            1
        );
        modelFieldBindingMapper.insert(binding);
        createdBindingIds.add(binding.getId());
        return binding;
    }

    // ==================== Pure-logic: isBreakingChange ====================

    @Test
    @DisplayName("isBreakingChange: newDataType set → true")
    void isBreakingChange_newDataType_returnsTrue() {
        FieldModification mod = FieldModification.builder()
            .newDataType("integer")
            .build();
        assertTrue(fieldImpactAnalysisService.isBreakingChange(mod));
    }

    @Test
    @DisplayName("isBreakingChange: newSemanticType set → true")
    void isBreakingChange_newSemanticType_returnsTrue() {
        FieldModification mod = FieldModification.builder()
            .newSemanticType("email")
            .build();
        assertTrue(fieldImpactAnalysisService.isBreakingChange(mod));
    }

    @Test
    @DisplayName("isBreakingChange: newRefTarget set → true")
    void isBreakingChange_newRefTarget_returnsTrue() {
        FieldModification mod = FieldModification.builder()
            .newRefTarget(Map.of("modelCode", "some_model"))
            .build();
        assertTrue(fieldImpactAnalysisService.isBreakingChange(mod));
    }

    @Test
    @DisplayName("isBreakingChange: none of the breaking fields set → false")
    void isBreakingChange_noBreakingField_returnsFalse() {
        FieldModification mod = FieldModification.builder()
            .newCode("new_name")
            .newFeature(Map.of("required", true))
            .newRuleSchema(Map.of("rule", "x"))
            .build();
        assertFalse(fieldImpactAnalysisService.isBreakingChange(mod));
    }

    @Test
    @DisplayName("isBreakingChange: empty modification → false")
    void isBreakingChange_emptyModification_returnsFalse() {
        FieldModification mod = FieldModification.builder().build();
        assertFalse(fieldImpactAnalysisService.isBreakingChange(mod));
    }

    // ==================== Pure-logic: classifyModification ====================

    @Test
    @DisplayName("classifyModification: data type change → BREAKING")
    void classifyModification_dataTypeChange_returnsBreaking() {
        FieldModification mod = FieldModification.builder()
            .newDataType("text")
            .build();
        assertEquals(ModificationType.BREAKING, fieldImpactAnalysisService.classifyModification(mod));
    }

    @Test
    @DisplayName("classifyModification: semantic type change → BREAKING")
    void classifyModification_semanticTypeChange_returnsBreaking() {
        FieldModification mod = FieldModification.builder()
            .newSemanticType("url")
            .build();
        assertEquals(ModificationType.BREAKING, fieldImpactAnalysisService.classifyModification(mod));
    }

    @Test
    @DisplayName("classifyModification: ref target change → BREAKING")
    void classifyModification_refTargetChange_returnsBreaking() {
        FieldModification mod = FieldModification.builder()
            .newRefTarget(Map.of("modelCode", "other"))
            .build();
        assertEquals(ModificationType.BREAKING, fieldImpactAnalysisService.classifyModification(mod));
    }

    @Test
    @DisplayName("classifyModification: newFeature only → WARNING")
    void classifyModification_newFeature_returnsWarning() {
        FieldModification mod = FieldModification.builder()
            .newFeature(Map.of("required", false))
            .build();
        assertEquals(ModificationType.WARNING, fieldImpactAnalysisService.classifyModification(mod));
    }

    @Test
    @DisplayName("classifyModification: newRuleSchema only → WARNING")
    void classifyModification_newRuleSchema_returnsWarning() {
        FieldModification mod = FieldModification.builder()
            .newRuleSchema(Map.of("validator", "email"))
            .build();
        assertEquals(ModificationType.WARNING, fieldImpactAnalysisService.classifyModification(mod));
    }

    @Test
    @DisplayName("classifyModification: newFeature AND newRuleSchema → WARNING")
    void classifyModification_featureAndRule_returnsWarning() {
        FieldModification mod = FieldModification.builder()
            .newFeature(Map.of("required", true))
            .newRuleSchema(Map.of("pattern", ".*"))
            .build();
        assertEquals(ModificationType.WARNING, fieldImpactAnalysisService.classifyModification(mod));
    }

    @Test
    @DisplayName("classifyModification: only safe fields (uiSchema, extension) → SAFE")
    void classifyModification_safeFields_returnsSafe() {
        FieldModification mod = FieldModification.builder()
            .newUiSchema(Map.of("placeholder", "enter value"))
            .newExtension(Map.of("hint", "some hint"))
            .build();
        assertEquals(ModificationType.SAFE, fieldImpactAnalysisService.classifyModification(mod));
    }

    @Test
    @DisplayName("classifyModification: empty modification → SAFE")
    void classifyModification_empty_returnsSafe() {
        FieldModification mod = FieldModification.builder().build();
        assertEquals(ModificationType.SAFE, fieldImpactAnalysisService.classifyModification(mod));
    }

    // ==================== analyzeModificationImpact — field-not-found ====================

    @Test
    @DisplayName("analyzeModificationImpact: non-existent fieldPid → canProceed=false, SAFE type")
    void analyzeModificationImpact_fieldNotFound_returnsCannotProceed() {
        FieldModification mod = FieldModification.builder().build();
        ModificationImpact impact = fieldImpactAnalysisService.analyzeModificationImpact(
            "non-existent-pid-xyz", mod);

        assertNotNull(impact);
        assertFalse(impact.getCanProceed(), "canProceed must be false when field not found");
        assertEquals("non-existent-pid-xyz", impact.getFieldPid());
        assertEquals(ModificationType.SAFE, impact.getModificationType());
        assertEquals(0, impact.getTotalAffectedModels());
        assertNotNull(impact.getAffectedModels());
        assertTrue(impact.getAffectedModels().isEmpty());
        assertNotNull(impact.getImpactDescription());
    }

    // ==================== analyzeModificationImpact — real field, no bindings ====================

    @Test
    @DisplayName("analyzeModificationImpact: SAFE change, no bindings → canProceed=true, 0 affected")
    void analyzeModificationImpact_safeChange_noBindings() {
        MetaFieldDTO field = createField("safe_nomod", "string");
        FieldModification mod = FieldModification.builder()
            .newUiSchema(Map.of("placeholder", "test"))
            .build();

        ModificationImpact impact = fieldImpactAnalysisService.analyzeModificationImpact(field.getPid(), mod);

        assertNotNull(impact);
        assertEquals(field.getPid(), impact.getFieldPid());
        assertEquals(ModificationType.SAFE, impact.getModificationType());
        assertEquals(0, impact.getTotalAffectedModels());
        assertTrue(impact.getCanProceed(), "safe change with no models should canProceed");
        assertNotNull(impact.getRecommendations());
        assertFalse(impact.getRecommendations().isEmpty());
        // Safe recommendation
        assertTrue(impact.getRecommendations().get(0).contains("Safe") ||
                   impact.getRecommendations().get(0).toLowerCase().contains("safe"));
    }

    @Test
    @DisplayName("analyzeModificationImpact: WARNING change, no bindings → canProceed=true")
    void analyzeModificationImpact_warningChange_noBindings() {
        MetaFieldDTO field = createField("warn_nomod", "string");
        FieldModification mod = FieldModification.builder()
            .newFeature(Map.of("required", true))
            .build();

        ModificationImpact impact = fieldImpactAnalysisService.analyzeModificationImpact(field.getPid(), mod);

        assertNotNull(impact);
        assertEquals(ModificationType.WARNING, impact.getModificationType());
        assertEquals(0, impact.getTotalAffectedModels());
        assertTrue(impact.getCanProceed(), "warning with no models should canProceed");
        // Impact description for 0 affected
        assertNotNull(impact.getImpactDescription());
        assertTrue(impact.getImpactDescription().contains("No models"));
    }

    @Test
    @DisplayName("analyzeModificationImpact: BREAKING change, no bindings → canProceed=true (no affected models)")
    void analyzeModificationImpact_breakingChange_noBindings_canProceed() {
        MetaFieldDTO field = createField("break_nomod", "string");
        FieldModification mod = FieldModification.builder()
            .newDataType("integer")
            .build();

        ModificationImpact impact = fieldImpactAnalysisService.analyzeModificationImpact(field.getPid(), mod);

        assertNotNull(impact);
        assertEquals(ModificationType.BREAKING, impact.getModificationType());
        assertEquals(0, impact.getTotalAffectedModels());
        // BREAKING but no affected models → canProceed = true
        assertTrue(impact.getCanProceed(),
            "breaking with empty affectedModels should canProceed per: type != BREAKING || isEmpty()");
        // recommendations for BREAKING with 0 affected: hits buildRecommendations else branch
        assertFalse(impact.getRecommendations().isEmpty());
    }

    // ==================== analyzeModificationImpact — BREAKING change WITH affected models ====================

    @Test
    @DisplayName("analyzeModificationImpact: BREAKING change WITH binding → canProceed=false, affected list populated")
    void analyzeModificationImpact_breakingChange_withBinding() {
        MetaFieldDTO field = createField("break_withmod", "string");
        MetaModelDTO model = createModel("brk_mod1");
        createBinding(model.getId(), field.getId());

        FieldModification mod = FieldModification.builder()
            .newDataType("integer")
            .build();

        ModificationImpact impact = fieldImpactAnalysisService.analyzeModificationImpact(field.getPid(), mod);

        assertNotNull(impact);
        assertEquals(ModificationType.BREAKING, impact.getModificationType());
        assertEquals(1, impact.getTotalAffectedModels());
        assertFalse(impact.getCanProceed(), "BREAKING with affected models → canProceed=false");
        assertNotNull(impact.getAffectedModels());
        assertEquals(1, impact.getAffectedModels().size());

        AffectedModel affected = impact.getAffectedModels().get(0);
        assertEquals("high", affected.getImpactLevel());
        assertFalse(affected.getPotentialIssues().isEmpty());
        assertTrue(affected.getPotentialIssues().stream()
            .anyMatch(s -> s.contains("incompatibility") || s.contains("invalid") || s.contains("break")));

        // Impact description for N>0 affected + BREAKING
        assertNotNull(impact.getImpactDescription());
        assertTrue(impact.getImpactDescription().contains("1") || impact.getImpactDescription().contains("Breaking"));

        // Recommendations for BREAKING + N>0
        assertFalse(impact.getRecommendations().isEmpty());
        assertTrue(impact.getRecommendations().stream().anyMatch(r -> r.contains("Fork") || r.contains("fork")));
    }

    @Test
    @DisplayName("analyzeModificationImpact: WARNING change WITH binding → canProceed=true, medium impact")
    void analyzeModificationImpact_warningChange_withBinding() {
        MetaFieldDTO field = createField("warn_withmod", "string");
        MetaModelDTO model = createModel("warn_mod1");
        createBinding(model.getId(), field.getId());

        FieldModification mod = FieldModification.builder()
            .newRuleSchema(Map.of("pattern", "[A-Z]+"))
            .build();

        ModificationImpact impact = fieldImpactAnalysisService.analyzeModificationImpact(field.getPid(), mod);

        assertNotNull(impact);
        assertEquals(ModificationType.WARNING, impact.getModificationType());
        assertEquals(1, impact.getTotalAffectedModels());
        assertTrue(impact.getCanProceed(), "WARNING is not BREAKING → canProceed=true");

        AffectedModel affected = impact.getAffectedModels().get(0);
        assertEquals("medium", affected.getImpactLevel());
    }

    @Test
    @DisplayName("analyzeModificationImpact: SAFE change WITH binding → canProceed=true, low impact")
    void analyzeModificationImpact_safeChange_withBinding() {
        MetaFieldDTO field = createField("safe_withmod", "string");
        MetaModelDTO model = createModel("safe_mod1");
        createBinding(model.getId(), field.getId());

        FieldModification mod = FieldModification.builder()
            .newUiSchema(Map.of("component", "textarea"))
            .build();

        ModificationImpact impact = fieldImpactAnalysisService.analyzeModificationImpact(field.getPid(), mod);

        assertNotNull(impact);
        assertEquals(ModificationType.SAFE, impact.getModificationType());
        assertEquals(1, impact.getTotalAffectedModels());
        assertTrue(impact.getCanProceed());

        AffectedModel affected = impact.getAffectedModels().get(0);
        assertEquals("low", affected.getImpactLevel());
        // SAFE type → no potential issues
        assertTrue(affected.getPotentialIssues().isEmpty());
    }

    // ==================== getAffectedModels ====================

    @Test
    @DisplayName("getAffectedModels: non-existent fieldPid → empty list")
    void getAffectedModels_fieldNotFound_returnsEmpty() {
        FieldModification mod = FieldModification.builder().build();
        List<AffectedModel> result = fieldImpactAnalysisService.getAffectedModels(
            "non-existent-pid", mod);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getAffectedModels: field with no bindings → empty list")
    void getAffectedModels_noBindings_returnsEmpty() {
        MetaFieldDTO field = createField("affected_none", "integer");
        FieldModification mod = FieldModification.builder().newDataType("string").build();

        List<AffectedModel> result = fieldImpactAnalysisService.getAffectedModels(field.getPid(), mod);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getAffectedModels: field with two bindings → list with 2 items")
    void getAffectedModels_twoBindings_returnsList() {
        MetaFieldDTO field = createField("affected_two", "string");
        MetaModelDTO model1 = createModel("aff_mod1");
        MetaModelDTO model2 = createModel("aff_mod2");
        createBinding(model1.getId(), field.getId());
        createBinding(model2.getId(), field.getId());

        FieldModification mod = FieldModification.builder().newSemanticType("email").build();

        List<AffectedModel> result = fieldImpactAnalysisService.getAffectedModels(field.getPid(), mod);
        assertNotNull(result);
        assertEquals(2, result.size());
        result.forEach(am -> {
            assertNotNull(am.getModelPid());
            assertNotNull(am.getModelCode());
            assertEquals("high", am.getImpactLevel()); // newSemanticType → BREAKING
        });
    }

    // ==================== validateModificationSafety ====================

    @Test
    @DisplayName("validateModificationSafety: non-existent field → errors list contains message, canProceed=false")
    void validateModificationSafety_fieldNotFound_returnsErrors() {
        FieldModification mod = FieldModification.builder().build();
        FieldImpactAnalysisService.ValidationResult result =
            fieldImpactAnalysisService.validateModificationSafety("bad-pid-xyz", mod);

        assertNotNull(result);
        assertFalse(result.isCanProceed());
        assertFalse(result.getErrors().isEmpty());
        assertTrue(result.getErrors().get(0).contains("bad-pid-xyz") ||
                   result.getErrors().get(0).toLowerCase().contains("not found"));
    }

    @Test
    @DisplayName("validateModificationSafety: SAFE change on existing field → canProceed=true, no errors")
    void validateModificationSafety_safeChange_canProceed() {
        MetaFieldDTO field = createField("valid_safe", "string");
        FieldModification mod = FieldModification.builder()
            .newUiSchema(Map.of("component", "input"))
            .build();

        FieldImpactAnalysisService.ValidationResult result =
            fieldImpactAnalysisService.validateModificationSafety(field.getPid(), mod);

        assertNotNull(result);
        assertTrue(result.isCanProceed());
        assertTrue(result.getErrors().isEmpty());
        // SAFE → no warnings from this method
        assertTrue(result.getWarnings().isEmpty());
        assertTrue(result.getSuggestions().isEmpty());
    }

    @Test
    @DisplayName("validateModificationSafety: WARNING change on existing field → canProceed=true, warning added")
    void validateModificationSafety_warningChange_canProceedWithWarning() {
        MetaFieldDTO field = createField("valid_warn", "string");
        FieldModification mod = FieldModification.builder()
            .newFeature(Map.of("required", true))
            .build();

        FieldImpactAnalysisService.ValidationResult result =
            fieldImpactAnalysisService.validateModificationSafety(field.getPid(), mod);

        assertNotNull(result);
        assertTrue(result.isCanProceed());
        assertTrue(result.getErrors().isEmpty());
        assertFalse(result.getWarnings().isEmpty(), "WARNING type should produce a warning message");
        assertFalse(result.getSuggestions().isEmpty(), "WARNING type should produce a suggestion");
    }

    @Test
    @DisplayName("validateModificationSafety: BREAKING change, no bindings → canProceed=true (no affected models)")
    void validateModificationSafety_breakingChange_noBindings_canProceed() {
        MetaFieldDTO field = createField("valid_break_none", "string");
        FieldModification mod = FieldModification.builder()
            .newDataType("text")
            .build();

        FieldImpactAnalysisService.ValidationResult result =
            fieldImpactAnalysisService.validateModificationSafety(field.getPid(), mod);

        assertNotNull(result);
        // BREAKING but no affected models → canProceed=true (the affected.isEmpty() check passes the guard)
        assertTrue(result.isCanProceed());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    @DisplayName("validateModificationSafety: BREAKING change WITH binding → canProceed=false, errors and suggestions")
    void validateModificationSafety_breakingChange_withBindings_cannotProceed() {
        MetaFieldDTO field = createField("valid_break_bound", "string");
        MetaModelDTO model = createModel("vld_brk_mod");
        createBinding(model.getId(), field.getId());

        FieldModification mod = FieldModification.builder()
            .newDataType("integer")
            .build();

        FieldImpactAnalysisService.ValidationResult result =
            fieldImpactAnalysisService.validateModificationSafety(field.getPid(), mod);

        assertNotNull(result);
        assertFalse(result.isCanProceed());
        assertFalse(result.getErrors().isEmpty());
        assertTrue(result.getErrors().get(0).contains("1") || result.getErrors().get(0).contains("Breaking"));
        assertFalse(result.getSuggestions().isEmpty());
        assertTrue(result.getSuggestions().get(0).toLowerCase().contains("fork") ||
                   result.getSuggestions().get(0).toLowerCase().contains("forking"));
    }
}
