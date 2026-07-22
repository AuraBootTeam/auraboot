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

    /**
     * The two risk scales finally meet.
     *
     * <p>A bridge between them was written and tested, and nothing in
     * production ever crossed it: the assessor kept deriving its own level
     * from {@code execution_config.type} while {@code cmd_risk_level} — the
     * platform's declared L0–L4, sitting in the same row the assessor was
     * already reading — went unread. A tested mapping that no caller uses is
     * a mapping that has not connected anything.
     *
     * <p>Composed strictest-wins, on purpose. Reading the declared level
     * naively would let an L1 default downgrade a delete from HIGH to MEDIUM,
     * which is the exact silent downgrade this class was fixed for once
     * already. Connecting the scales can tighten a confirmation and must
     * never loosen one.
     */
    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("The declared platform risk level is read, and can only tighten")
    class DeclaredPlatformLevel {

        private void stubRow(String execType, String declaredLevel) {
            java.util.Map<String, Object> row = new java.util.HashMap<>();
            row.put("execution_config", jsonb("{\"type\":\"" + execType + "\"}"));
            row.put("cmd_risk_level", declaredLevel);
            when(mapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of(row));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("a declared L3 raises a command the heuristic would call MEDIUM")
        void declaredLevelRaisesRisk() {
            stubRow("update", "L3");
            assertEquals(AiActionRiskLevel.HIGH, assessor.assess("execute_command", "order:update", 1L),
                    "the platform said L3; the client must not show a plain dialog for it");
        }

        @Test
        @org.junit.jupiter.api.DisplayName("a default L1 does not lower a delete out of BLOCKED")
        void declaredLevelNeverLowersRisk() {
            // The direction that matters. cmd_risk_level defaults to l1, so a
            // naive read would downgrade every unlabelled destructive command.
            stubRow("delete", "l1");
            assertEquals(AiActionRiskLevel.BLOCKED, assessor.assess("execute_command", "order:delete", 1L));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("a declared L0 does not drop an execute_command below the heuristic floor")
        void declaredLevelCannotLowerBelowTheFloor() {
            // Strictest-wins cuts both ways, and this is the edge where it
            // costs something: an explicitly declared L0 still confirms,
            // because "this command creates or changes data" is an assessment
            // the heuristic made rather than a gap it was filling. Letting a
            // column lower it would reopen exactly the downgrade class this
            // assessor was fixed for.
            //
            // It doubles as the proof that lowercase parses. An unreadable
            // level falls to HIGH, so 'l0' landing on MEDIUM can only happen
            // if it was read as L0 — asserting HIGH-vs-MEDIUM here is what a
            // case-sensitivity bug would break.
            stubRow("query", "l0");
            assertEquals(AiActionRiskLevel.MEDIUM, assessor.assess("execute_command", "order:query", 1L));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("a missing declared level leaves the previous behaviour intact")
        void absentDeclaredLevelChangesNothing() {
            stubRow("state_transition", null);
            assertEquals(AiActionRiskLevel.HIGH, assessor.assess("execute_command", "order:ship", 1L));
        }
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
