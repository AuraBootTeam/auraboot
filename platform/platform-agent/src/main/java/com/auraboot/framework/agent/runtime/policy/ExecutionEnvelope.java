package com.auraboot.framework.agent.runtime.policy;

public record ExecutionEnvelope(
        LifecycleEntry lifecycleEntry,
        InitialExecutionMode initialMode,
        ToolCapabilityCeiling capabilityCeiling,
        ToolExposure toolExposure,
        DurabilityPreference durabilityPreference) {

    public ExecutionEnvelope {
        lifecycleEntry = lifecycleEntry == null ? LifecycleEntry.NEW_TURN : lifecycleEntry;
        initialMode = initialMode == null ? InitialExecutionMode.SYNC_AGENT_TURN : initialMode;
        capabilityCeiling = capabilityCeiling == null ? ToolCapabilityCeiling.NO_TOOLS : capabilityCeiling;
        toolExposure = toolExposure == null ? ToolExposure.ANSWER_ONLY : toolExposure;
        durabilityPreference = durabilityPreference == null ? DurabilityPreference.NONE : durabilityPreference;
    }

    public static ExecutionEnvelope answerOnly() {
        return new ExecutionEnvelope(
                LifecycleEntry.NEW_TURN,
                InitialExecutionMode.SYNC_AGENT_TURN,
                ToolCapabilityCeiling.NO_TOOLS,
                ToolExposure.ANSWER_ONLY,
                DurabilityPreference.NONE);
    }

    public static ExecutionEnvelope readOnlyCatalog() {
        return new ExecutionEnvelope(
                LifecycleEntry.NEW_TURN,
                InitialExecutionMode.SYNC_AGENT_TURN,
                ToolCapabilityCeiling.READ_ONLY,
                ToolExposure.READ_ONLY_CATALOG,
                DurabilityPreference.NONE);
    }

    public static ExecutionEnvelope writeCatalogWithGate() {
        return new ExecutionEnvelope(
                LifecycleEntry.NEW_TURN,
                InitialExecutionMode.SYNC_AGENT_TURN,
                ToolCapabilityCeiling.WRITE_CAPABLE,
                ToolExposure.WRITE_CATALOG_WITH_GATE,
                DurabilityPreference.ALLOWED);
    }
}
