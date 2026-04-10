package com.auraboot.framework.meta.service.impl.pipeline;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared mutable state that flows through every phase.
 * Created once per command execution, passed to each {@link CommandPhase}.
 *
 * @author AuraBoot Team
 * @since 8.0.0
 */
@Data
@Builder
public class CommandPipelineContext {

    // ==================== Immutable inputs ====================

    private final String commandCode;
    private final CommandExecuteRequest request;
    private final Long tenantId;
    private final Long userId;
    private final long startTime;

    // ==================== Populated during pipeline ====================

    /** Loaded command definition (set by LoadPhase) */
    private CommandDefinition command;

    /** Request payload — may be mutated by normalize/autoSet/computed phases */
    @Builder.Default
    private Map<String, Object> payload = new HashMap<>();

    /** Parsed executionConfig JSON */
    @Builder.Default
    private Map<String, Object> execConfig = new HashMap<>();

    /** Binding rules grouped by type (set by LoadPhase) */
    @Builder.Default
    private Map<String, List<BindingRule>> rulesByType = new HashMap<>();

    /** Results from the FIELD_MAP phase (recordId, etc.) */
    @Builder.Default
    private Map<String, Object> fieldMapResults = new HashMap<>();

    /** Results from the HANDLER phase */
    @Builder.Default
    private Map<String, Object> handlerResults = new HashMap<>();

    /** Target state resolved by STATE_CHECK phase */
    private String targetState;

    /** Before-snapshot for change tracking */
    private Map<String, Object> beforeSnapshot;

    /** Whether a plugin handler exists for this command */
    private boolean hasPluginHandler;

    /** Whether plugin handler still needs DSL persistence */
    private boolean pluginRequiresDslPersistence;

    // ==================== Concurrency ====================

    /** Resolved concurrency key (null = no lock) */
    private String concurrencyKey;

    /** Lock timeout in ms */
    @Builder.Default
    private long lockTimeoutMs = 5000L;

    // ==================== Short-circuit ====================

    /** If set, pipeline stops and returns this result (e.g., idempotent replay) */
    private CommandExecuteResult shortCircuitResult;

    public boolean isShortCircuited() {
        return shortCircuitResult != null;
    }

    // ==================== Phase timing ====================

    @Builder.Default
    private Map<String, Long> phaseTimings = new LinkedHashMap<>();

    private String currentPhase;
    private long currentPhaseStart;

    public void transitionTo(String newPhase) {
        long now = System.currentTimeMillis();
        if (currentPhase != null) {
            phaseTimings.put(currentPhase, now - currentPhaseStart);
        }
        currentPhase = newPhase;
        currentPhaseStart = now;
    }
}
