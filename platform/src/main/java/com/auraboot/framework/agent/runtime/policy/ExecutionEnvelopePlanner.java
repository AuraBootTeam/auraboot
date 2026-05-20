package com.auraboot.framework.agent.runtime.policy;

import org.springframework.stereotype.Component;

/**
 * Plans the maximum execution envelope for a turn without deciding a domain
 * action's fate. Tool calls are still evaluated later by {@link ToolPolicyEngine}.
 */
@Component
public class ExecutionEnvelopePlanner {

    public record Request(ExecutionEnvelope explicitEnvelope,
                          boolean toolsAvailable,
                          boolean readOnlyContext,
                          boolean durableRequired,
                          AgentProfile agentProfile,
                          AgentTenantPolicy tenantPolicy) {
        public Request(ExecutionEnvelope explicitEnvelope,
                       boolean toolsAvailable,
                       boolean readOnlyContext,
                       boolean durableRequired) {
            this(explicitEnvelope, toolsAvailable, readOnlyContext, durableRequired, null, null);
        }

        public Request(ExecutionEnvelope explicitEnvelope,
                       boolean toolsAvailable,
                       boolean readOnlyContext,
                       boolean durableRequired,
                       AgentProfile agentProfile) {
            this(explicitEnvelope, toolsAvailable, readOnlyContext, durableRequired, agentProfile, null);
        }
    }

    public ExecutionEnvelope plan(Request request) {
        Request effective = request != null
                ? request
                : new Request(null, false, false, false, null, null);
        AgentContextPolicy contextPolicy = effective.agentProfile() != null
                ? effective.agentProfile().contextPolicy()
                : AgentContextPolicy.defaults();
        AgentTenantPolicy tenantPolicy = effective.tenantPolicy() != null
                ? effective.tenantPolicy()
                : AgentTenantPolicy.defaults();
        if (effective.explicitEnvelope() != null) {
            if (!hasPolicyBounds(contextPolicy, tenantPolicy)) {
                return effective.explicitEnvelope();
            }
            return applyPolicyBounds(effective.explicitEnvelope(), contextPolicy, tenantPolicy);
        }
        DurabilityPreference durabilityPreference = durabilityPreference(
                effective.durableRequired(), contextPolicy, tenantPolicy, DurabilityPreference.NONE);
        if (effective.durableRequired()
                || contextPolicy.durabilityPreference() == DurabilityPreference.REQUIRED
                || tenantPolicy.durabilityPreference() == DurabilityPreference.REQUIRED) {
            return applyPolicyBounds(new ExecutionEnvelope(
                    LifecycleEntry.NEW_TURN,
                    InitialExecutionMode.DURABLE_WORKFLOW_ENTRY,
                    ToolCapabilityCeiling.WRITE_CAPABLE,
                    ToolExposure.ACTION_PROPOSAL,
                    DurabilityPreference.REQUIRED), contextPolicy, tenantPolicy);
        }
        ToolCapabilityCeiling policyCeiling = strictestCapability(
                contextPolicy.capabilityCeiling(), tenantPolicy.capabilityCeiling());
        ToolExposure policyExposure = strictestExposure(
                contextPolicy.toolExposure(), tenantPolicy.toolExposure());
        if (policyCeiling == ToolCapabilityCeiling.NO_TOOLS
                || policyExposure == ToolExposure.ANSWER_ONLY) {
            return applyPolicyBounds(new ExecutionEnvelope(
                    LifecycleEntry.NEW_TURN,
                    InitialExecutionMode.SYNC_AGENT_TURN,
                    ToolCapabilityCeiling.NO_TOOLS,
                    ToolExposure.ANSWER_ONLY,
                    durabilityPreference), contextPolicy, tenantPolicy);
        }
        if (policyCeiling == ToolCapabilityCeiling.READ_ONLY
                || policyExposure == ToolExposure.READ_ONLY_CATALOG) {
            return applyPolicyBounds(new ExecutionEnvelope(
                    LifecycleEntry.NEW_TURN,
                    InitialExecutionMode.SYNC_AGENT_TURN,
                    ToolCapabilityCeiling.READ_ONLY,
                    ToolExposure.READ_ONLY_CATALOG,
                    durabilityPreference), contextPolicy, tenantPolicy);
        }
        if (effective.readOnlyContext()) {
            return applyPolicyBounds(new ExecutionEnvelope(
                    LifecycleEntry.NEW_TURN,
                    InitialExecutionMode.SYNC_AGENT_TURN,
                    ToolCapabilityCeiling.READ_ONLY,
                    ToolExposure.READ_ONLY_CATALOG,
                    durabilityPreference), contextPolicy, tenantPolicy);
        }
        if (!effective.toolsAvailable()) {
            return applyPolicyBounds(new ExecutionEnvelope(
                    LifecycleEntry.NEW_TURN,
                    InitialExecutionMode.SYNC_AGENT_TURN,
                    ToolCapabilityCeiling.NO_TOOLS,
                    ToolExposure.ANSWER_ONLY,
                    durabilityPreference), contextPolicy, tenantPolicy);
        }
        if (policyCeiling == ToolCapabilityCeiling.PROPOSE_ONLY
                || policyExposure == ToolExposure.ACTION_PROPOSAL) {
            return applyPolicyBounds(new ExecutionEnvelope(
                    LifecycleEntry.NEW_TURN,
                    InitialExecutionMode.SYNC_AGENT_TURN,
                    ToolCapabilityCeiling.PROPOSE_ONLY,
                    ToolExposure.ACTION_PROPOSAL,
                    durabilityPreference), contextPolicy, tenantPolicy);
        }
        return applyPolicyBounds(new ExecutionEnvelope(
                LifecycleEntry.NEW_TURN,
                InitialExecutionMode.SYNC_AGENT_TURN,
                ToolCapabilityCeiling.WRITE_CAPABLE,
                ToolExposure.WRITE_CATALOG_WITH_GATE,
                durabilityPreference == DurabilityPreference.NONE
                        ? DurabilityPreference.ALLOWED
                        : durabilityPreference), contextPolicy, tenantPolicy);
    }

    private ExecutionEnvelope applyPolicyBounds(ExecutionEnvelope envelope,
                                                AgentContextPolicy contextPolicy,
                                                AgentTenantPolicy tenantPolicy) {
        ToolCapabilityCeiling capabilityCeiling = strictestCapability(
                envelope.capabilityCeiling(),
                contextPolicy.capabilityCeiling(),
                tenantPolicy.capabilityCeiling());
        ToolExposure toolExposure = normalizeExposureForCapability(
                capabilityCeiling,
                strictestExposure(
                        envelope.toolExposure(),
                        contextPolicy.toolExposure(),
                        tenantPolicy.toolExposure()));
        DurabilityPreference durabilityPreference = durabilityPreference(
                false, contextPolicy, tenantPolicy, envelope.durabilityPreference());
        InitialExecutionMode initialMode = durabilityPreference == DurabilityPreference.REQUIRED
                ? InitialExecutionMode.DURABLE_WORKFLOW_ENTRY
                : envelope.initialMode();
        return new ExecutionEnvelope(
                envelope.lifecycleEntry(),
                initialMode,
                capabilityCeiling,
                toolExposure,
                durabilityPreference);
    }

    private boolean hasPolicyBounds(AgentContextPolicy contextPolicy, AgentTenantPolicy tenantPolicy) {
        return contextPolicy.capabilityCeiling() != null
                || contextPolicy.toolExposure() != null
                || contextPolicy.durabilityPreference() != null
                || tenantPolicy.capabilityCeiling() != null
                || tenantPolicy.toolExposure() != null
                || tenantPolicy.durabilityPreference() != null;
    }

    private DurabilityPreference durabilityPreference(boolean durableRequired,
                                                     AgentContextPolicy contextPolicy,
                                                     AgentTenantPolicy tenantPolicy,
                                                     DurabilityPreference base) {
        if (durableRequired
                || base == DurabilityPreference.REQUIRED
                || contextPolicy.durabilityPreference() == DurabilityPreference.REQUIRED
                || tenantPolicy.durabilityPreference() == DurabilityPreference.REQUIRED) {
            return DurabilityPreference.REQUIRED;
        }
        if (base == DurabilityPreference.ALLOWED
                || contextPolicy.durabilityPreference() == DurabilityPreference.ALLOWED
                || tenantPolicy.durabilityPreference() == DurabilityPreference.ALLOWED) {
            return DurabilityPreference.ALLOWED;
        }
        return DurabilityPreference.NONE;
    }

    private ToolCapabilityCeiling strictestCapability(ToolCapabilityCeiling... values) {
        ToolCapabilityCeiling selected = null;
        if (values != null) {
            for (ToolCapabilityCeiling value : values) {
                if (value == null) {
                    continue;
                }
                if (selected == null || capabilityRank(value) < capabilityRank(selected)) {
                    selected = value;
                }
            }
        }
        return selected != null ? selected : ToolCapabilityCeiling.WRITE_CAPABLE;
    }

    private int capabilityRank(ToolCapabilityCeiling value) {
        return switch (value) {
            case NO_TOOLS -> 0;
            case READ_ONLY -> 1;
            case PROPOSE_ONLY -> 2;
            case WRITE_CAPABLE -> 3;
        };
    }

    private ToolExposure strictestExposure(ToolExposure... values) {
        ToolExposure selected = null;
        if (values != null) {
            for (ToolExposure value : values) {
                if (value == null) {
                    continue;
                }
                if (selected == null || exposureRank(value) < exposureRank(selected)) {
                    selected = value;
                }
            }
        }
        return selected != null ? selected : ToolExposure.WRITE_CATALOG_WITH_GATE;
    }

    private int exposureRank(ToolExposure value) {
        return switch (value) {
            case ANSWER_ONLY -> 0;
            case READ_ONLY_CATALOG -> 1;
            case ACTION_PROPOSAL -> 2;
            case WRITE_CATALOG_WITH_GATE -> 3;
        };
    }

    private ToolExposure normalizeExposureForCapability(ToolCapabilityCeiling capabilityCeiling,
                                                        ToolExposure toolExposure) {
        if (capabilityCeiling == ToolCapabilityCeiling.NO_TOOLS) {
            return ToolExposure.ANSWER_ONLY;
        }
        if (capabilityCeiling == ToolCapabilityCeiling.READ_ONLY
                && exposureRank(toolExposure) > exposureRank(ToolExposure.READ_ONLY_CATALOG)) {
            return ToolExposure.READ_ONLY_CATALOG;
        }
        if (capabilityCeiling == ToolCapabilityCeiling.PROPOSE_ONLY
                && toolExposure == ToolExposure.WRITE_CATALOG_WITH_GATE) {
            return ToolExposure.ACTION_PROPOSAL;
        }
        return toolExposure;
    }
}
