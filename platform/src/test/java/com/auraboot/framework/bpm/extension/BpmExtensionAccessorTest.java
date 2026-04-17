package com.auraboot.framework.bpm.extension;

import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.model.WithdrawPolicy;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import com.auraboot.smart.framework.engine.model.assembly.ProcessDefinition;
import com.auraboot.smart.framework.engine.service.query.RepositoryQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BpmExtensionAccessor")
class BpmExtensionAccessorTest {

    private SmartEngine smartEngine;
    private RepositoryQueryService repo;
    private BpmExtensionAccessor accessor;
    private ProcessDefinition processDef;
    private IdBasedElement userTask;

    @BeforeEach
    void setUp() {
        smartEngine = mock(SmartEngine.class);
        repo = mock(RepositoryQueryService.class);
        when(smartEngine.getRepositoryQueryService()).thenReturn(repo);
        accessor = new BpmExtensionAccessor(smartEngine);

        processDef = mock(ProcessDefinition.class);
        when(processDef.getId()).thenReturn("leave_request");

        userTask = mock(IdBasedElement.class);
        Map<String, IdBasedElement> activityMap = new HashMap<>();
        activityMap.put("manager_approval", userTask);
        when(processDef.getIdBasedElementMap()).thenReturn(activityMap);

        when(repo.getAllCachedProcessDefinition()).thenReturn(List.of(processDef));
    }

    @Test
    @DisplayName("getWithdrawPolicy returns parsed value")
    void getWithdrawPolicyParsed() {
        when(processDef.getProperties()).thenReturn(Map.of("aura.withdrawPolicy", "loose"));
        assertThat(accessor.getWithdrawPolicy("leave_request")).isEqualTo(WithdrawPolicy.LOOSE);
    }

    @Test
    @DisplayName("getWithdrawPolicy defaults to STRICT when missing")
    void getWithdrawPolicyDefault() {
        when(processDef.getProperties()).thenReturn(Map.of());
        assertThat(accessor.getWithdrawPolicy("leave_request")).isEqualTo(WithdrawPolicy.STRICT);
    }

    @Test
    @DisplayName("getCcPolicy uses activity override when present")
    void getCcPolicyActivityOverride() {
        when(processDef.getProperties()).thenReturn(Map.of("aura.ccPolicy", "all"));
        when(userTask.getProperties()).thenReturn(Map.of("aura.ccPolicyOverride", "initiator"));
        assertThat(accessor.getCcPolicy("leave_request", "manager_approval"))
                .isEqualTo(CcPolicy.INITIATOR);
    }

    @Test
    @DisplayName("getCcPolicy falls back to process-level when no override")
    void getCcPolicyProcessLevel() {
        when(processDef.getProperties()).thenReturn(Map.of("aura.ccPolicy", "assignee"));
        when(userTask.getProperties()).thenReturn(Map.of());
        assertThat(accessor.getCcPolicy("leave_request", "manager_approval"))
                .isEqualTo(CcPolicy.ASSIGNEE);
    }

    @Test
    @DisplayName("getCcPolicy defaults to ALL when nothing set")
    void getCcPolicyDefault() {
        when(processDef.getProperties()).thenReturn(Map.of());
        when(userTask.getProperties()).thenReturn(Map.of());
        assertThat(accessor.getCcPolicy("leave_request", "manager_approval")).isEqualTo(CcPolicy.ALL);
    }

    @Test
    @DisplayName("unknown processKey returns defaults")
    void unknownProcessKey() {
        assertThat(accessor.getWithdrawPolicy("nonexistent")).isEqualTo(WithdrawPolicy.STRICT);
        assertThat(accessor.getCcPolicy("nonexistent", null)).isEqualTo(CcPolicy.ALL);
    }
}
