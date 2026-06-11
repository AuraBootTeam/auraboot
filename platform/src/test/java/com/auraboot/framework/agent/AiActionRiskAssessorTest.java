package com.auraboot.framework.agent;

import com.auraboot.framework.agent.dto.AiActionRiskLevel;
import com.auraboot.framework.agent.service.AiActionRiskAssessor;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for the JSONB-PGobject read-side fix in {@link AiActionRiskAssessor}.
 *
 * <p>{@code execution_config} is a JSONB column. Read via the generic
 * {@code DynamicDataMapper.selectByQuery}, the PostgreSQL driver returns a
 * {@code PGobject} — not a String, not a Map. The pre-fix code handled only
 * {@code instanceof Map} / {@code instanceof String}, so a PGobject fell through to
 * the {@code "update"} default — which silently DOWNGRADED the AI-action safety
 * gating: a {@code delete} command that must be BLOCKED was assessed as MEDIUM, and a
 * {@code state_transition} that must be HIGH became MEDIUM. These tests feed a real
 * PGobject and assert the gating is correct; they fail against the pre-fix code.
 */
class AiActionRiskAssessorTest {

    private final DynamicDataMapper mapper = mock(DynamicDataMapper.class);
    private final AiActionRiskAssessor assessor = new AiActionRiskAssessor(mapper, new ObjectMapper());

    private static PGobject jsonb(String value) {
        PGobject pg = new PGobject();
        pg.setType("jsonb");
        try {
            pg.setValue(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return pg;
    }

    private void stubExecConfig(Object execConfigValue) {
        when(mapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("execution_config", execConfigValue)));
    }

    @Test
    void pgobjectDeleteConfig_isBlocked_notDowngradedToMedium() {
        stubExecConfig(jsonb("{\"type\":\"delete\"}"));
        assertEquals(AiActionRiskLevel.BLOCKED,
                assessor.assess("execute_command", "order:delete", 1L),
                "delete execution_config returned as PGobject must still gate BLOCKED");
    }

    @Test
    void pgobjectStateTransitionConfig_isHigh_notDowngradedToMedium() {
        stubExecConfig(jsonb("{\"type\":\"state_transition\"}"));
        assertEquals(AiActionRiskLevel.HIGH,
                assessor.assess("execute_command", "order:approve", 1L),
                "state_transition execution_config returned as PGobject must gate HIGH");
    }

    @Test
    void pgobjectCreateConfig_isMedium() {
        stubExecConfig(jsonb("{\"type\":\"create\"}"));
        assertEquals(AiActionRiskLevel.MEDIUM,
                assessor.assess("execute_command", "order:create", 1L));
    }

    @Test
    void stringExecConfig_stillParsed_regression() {
        stubExecConfig("{\"type\":\"state_transition\"}");
        assertEquals(AiActionRiskLevel.HIGH,
                assessor.assess("execute_command", "order:approve", 1L));
    }

    @Test
    void mapExecConfig_stillParsed_regression() {
        stubExecConfig(Map.of("type", "delete"));
        assertEquals(AiActionRiskLevel.BLOCKED,
                assessor.assess("execute_command", "order:delete", 1L));
    }
}
