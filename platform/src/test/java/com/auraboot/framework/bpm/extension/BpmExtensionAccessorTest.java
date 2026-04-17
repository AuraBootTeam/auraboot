package com.auraboot.framework.bpm.extension;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.model.WithdrawPolicy;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.constant.ExtensionElementsConstant;
import com.auraboot.smart.framework.engine.model.assembly.ExtensionElementContainer;
import com.auraboot.smart.framework.engine.model.assembly.ExtensionElements;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import com.auraboot.smart.framework.engine.model.assembly.ProcessDefinition;
import com.auraboot.smart.framework.engine.service.query.RepositoryQueryService;
import com.auraboot.smart.framework.engine.smart.PropertyCompositeKey;
import com.auraboot.smart.framework.engine.smart.PropertyCompositeValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for BpmExtensionAccessor.
 *
 * <p>SmartEngine stores <smart:properties> entries in:
 *   element.getExtensionElements().getDecorationMap().get("Properties")
 * as Map<PropertyCompositeKey, PropertyCompositeValue>.
 * These tests verify that BpmExtensionAccessor reads from that path, not from
 * IdBasedElement.getProperties() (which holds XML element attributes).
 */
@DisplayName("BpmExtensionAccessor")
class BpmExtensionAccessorTest {

    private SmartEngine smartEngine;
    private RepositoryQueryService repo;
    private BpmExtensionAccessor accessor;

    /** ProcessDefinition mock that also implements ExtensionElementContainer. */
    interface ProcessDefinitionWithExtension extends ProcessDefinition, ExtensionElementContainer {}

    /** IdBasedElement mock that also implements ExtensionElementContainer (like UserTask). */
    interface ActivityElementWithExtension extends IdBasedElement, ExtensionElementContainer {}

    private ProcessDefinitionWithExtension processDef;
    private ActivityElementWithExtension userTask;

    @BeforeEach
    void setUp() {
        smartEngine = mock(SmartEngine.class);
        repo = mock(RepositoryQueryService.class);
        when(smartEngine.getRepositoryQueryService()).thenReturn(repo);
        accessor = new BpmExtensionAccessor(smartEngine, new ObjectMapper());

        processDef = mock(ProcessDefinitionWithExtension.class);
        when(processDef.getId()).thenReturn("leave_request");
        // tenantId must match getCurrentTenantIdAsString() — MetaContext.setContext(1L, …) → "1"
        when(processDef.getTenantId()).thenReturn("1");

        userTask = mock(ActivityElementWithExtension.class);
        Map<String, IdBasedElement> activityMap = new HashMap<>();
        activityMap.put("manager_approval", userTask);
        when(processDef.getIdBasedElementMap()).thenReturn(activityMap);

        when(repo.getAllCachedProcessDefinition()).thenReturn(List.of(processDef));

        // MetaContext must be initialized so getCurrentTenantIdAsString() doesn't throw
        MetaContext.setContext(1L, 1L, null, "test-user");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    // ---- helpers --------------------------------------------------------

    /**
     * Build a mock ExtensionElements whose decorationMap["Properties"] contains
     * a single PropertyCompositeKey(null, name) → PropertyCompositeValue(value) entry.
     * NOTE: must call mock() before passing to when().thenReturn() to avoid
     * Mockito's "unfinished stubbing" error.
     */
    private static ExtensionElements buildExtensionElements(String name, String value) {
        ExtensionElements ext = mock(ExtensionElements.class);
        Map<PropertyCompositeKey, PropertyCompositeValue> props = new HashMap<>();
        props.put(new PropertyCompositeKey(null, name), new PropertyCompositeValue(value, Map.of()));
        Map<String, Object> decorationMap = new HashMap<>();
        decorationMap.put(ExtensionElementsConstant.PROPERTIES, props);
        when(ext.getDecorationMap()).thenReturn(decorationMap);
        return ext;
    }

    // ---- process-level tests --------------------------------------------

    @Test
    @DisplayName("getWithdrawPolicy returns parsed value from extensionElements")
    void getWithdrawPolicyParsed() {
        ExtensionElements ext = buildExtensionElements(BpmExtensionKeys.WITHDRAW_POLICY, "loose");
        when(processDef.getExtensionElements()).thenReturn(ext);
        assertThat(accessor.getWithdrawPolicy("leave_request")).isEqualTo(WithdrawPolicy.LOOSE);
    }

    @Test
    @DisplayName("getWithdrawPolicy defaults to STRICT when missing")
    void getWithdrawPolicyDefault() {
        when(processDef.getExtensionElements()).thenReturn(null);
        assertThat(accessor.getWithdrawPolicy("leave_request")).isEqualTo(WithdrawPolicy.STRICT);
    }

    @Test
    @DisplayName("getWithdrawPolicy returns NONE when set")
    void getWithdrawPolicyNone() {
        ExtensionElements ext = buildExtensionElements(BpmExtensionKeys.WITHDRAW_POLICY, "none");
        when(processDef.getExtensionElements()).thenReturn(ext);
        assertThat(accessor.getWithdrawPolicy("leave_request")).isEqualTo(WithdrawPolicy.NONE);
    }

    // ---- activity-level tests -------------------------------------------

    @Test
    @DisplayName("getCcPolicy uses activity override when present")
    void getCcPolicyActivityOverride() {
        ExtensionElements processExt = buildExtensionElements(BpmExtensionKeys.CC_POLICY, "all");
        ExtensionElements taskExt = buildExtensionElements(BpmExtensionKeys.CC_POLICY_OVERRIDE, "initiator");
        when(processDef.getExtensionElements()).thenReturn(processExt);
        when(userTask.getExtensionElements()).thenReturn(taskExt);
        assertThat(accessor.getCcPolicy("leave_request", "manager_approval"))
                .isEqualTo(CcPolicy.INITIATOR);
    }

    @Test
    @DisplayName("getCcPolicy falls back to process-level when no override")
    void getCcPolicyProcessLevel() {
        ExtensionElements processExt = buildExtensionElements(BpmExtensionKeys.CC_POLICY, "assignee");
        when(processDef.getExtensionElements()).thenReturn(processExt);
        when(userTask.getExtensionElements()).thenReturn(null);
        assertThat(accessor.getCcPolicy("leave_request", "manager_approval"))
                .isEqualTo(CcPolicy.ASSIGNEE);
    }

    @Test
    @DisplayName("getCcPolicy defaults to ALL when nothing set")
    void getCcPolicyDefault() {
        when(processDef.getExtensionElements()).thenReturn(null);
        when(userTask.getExtensionElements()).thenReturn(null);
        assertThat(accessor.getCcPolicy("leave_request", "manager_approval")).isEqualTo(CcPolicy.ALL);
    }

    // ---- edge cases -----------------------------------------------------

    @Test
    @DisplayName("getRequiredPermissions parses JSON array from <smart:properties>")
    void getRequiredPermissionsParsed() {
        ExtensionElements taskExt = buildExtensionElements(
                BpmExtensionKeys.REQUIRED_PERMISSIONS, "[\"hr.leave.approve\",\"hr.leave.view\"]");
        when(userTask.getExtensionElements()).thenReturn(taskExt);
        assertThat(accessor.getRequiredPermissions("leave_request", "manager_approval"))
                .containsExactly("hr.leave.approve", "hr.leave.view");
    }

    @Test
    @DisplayName("getRequiredPermissions returns empty list when unset")
    void getRequiredPermissionsEmpty() {
        when(userTask.getExtensionElements()).thenReturn(null);
        assertThat(accessor.getRequiredPermissions("leave_request", "manager_approval"))
                .isEmpty();
    }

    @Test
    @DisplayName("getRequiredPermissions throws on malformed JSON (no silent fallback)")
    void getRequiredPermissionsMalformedJson() {
        ExtensionElements taskExt = buildExtensionElements(
                BpmExtensionKeys.REQUIRED_PERMISSIONS, "not-json");
        when(userTask.getExtensionElements()).thenReturn(taskExt);
        assertThatThrownBy(() ->
                accessor.getRequiredPermissions("leave_request", "manager_approval"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed aura.requiredPermissions");
    }

    @Test
    @DisplayName("unknown processKey returns defaults")
    void unknownProcessKey() {
        assertThat(accessor.getWithdrawPolicy("nonexistent")).isEqualTo(WithdrawPolicy.STRICT);
        assertThat(accessor.getCcPolicy("nonexistent", null)).isEqualTo(CcPolicy.ALL);
    }

    @Test
    @DisplayName("findProcessDefinition throws when tenant context is missing")
    void noTenantContextThrows() {
        MetaContext.clear();
        // MetaContext.get() throws IllegalStateException when uninitialized —
        // findProcessDefinition must not swallow or bypass this.
        assertThatThrownBy(() -> accessor.getWithdrawPolicy("leave_request"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MetaContext not initialized");
    }
}
