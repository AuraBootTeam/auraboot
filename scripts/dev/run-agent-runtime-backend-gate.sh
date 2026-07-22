#!/bin/bash
#
# Focused backend gate for the generic agent runtime architecture.
#
# This intentionally excludes Page Designer tests. It covers runtime policy,
# pending/approval resume, durable ACP handoff, AuraBot and named-agent adapters,
# compensation handlers, and admin runtime diagnostics.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

cd "$PROJECT_ROOT/platform"

./gradlew :compileJava :compileTestJava --no-daemon

./gradlew :test \
  --tests 'com.auraboot.framework.agent.runtime.*' \
  --tests 'com.auraboot.framework.agent.runtime.policy.*' \
  --tests 'com.auraboot.framework.agent.runtime.context.AgentContextAssemblerTest' \
  --tests 'com.auraboot.framework.agent.service.ToolLoopServiceSafetyTest' \
  --tests 'com.auraboot.framework.agent.service.StepLoopServiceCheckpointTest' \
  --tests 'com.auraboot.framework.agent.service.StepLoopServiceLlmResponseGuardTest' \
  --tests 'com.auraboot.framework.agent.service.StepLoopParallelToolTest' \
  --tests 'com.auraboot.framework.agent.service.AgentRunServiceSyncTest' \
  --tests 'com.auraboot.framework.agent.service.PlanServiceTest' \
  --tests 'com.auraboot.framework.agent.service.SkillEngineTest' \
  --tests 'com.auraboot.framework.agent.service.StepLoopServiceThinkingIntegrationTest' \
  --tests 'com.auraboot.framework.agent.service.AgentApprovalGateServiceConcurrencyTest' \
  --tests 'com.auraboot.framework.agent.service.AgentChatPortImpl*' \
  --tests 'com.auraboot.framework.agent.AgentApprovalGateIntegrationTest' \
  --tests 'com.auraboot.framework.agentchat.handoff.HandoffPermissionPolicyTest' \
  --tests 'com.auraboot.framework.agentchat.reply.AgentReplyTaskChokepointTest' \
  --tests 'com.auraboot.framework.conversation.ConversationTurnServiceImplAcpDispatchTest' \
  --tests 'com.auraboot.framework.conversation.ConversationTurnServiceImplAcpResumeTest' \
  --tests 'com.auraboot.framework.conversation.ConversationTurnServiceImplDispatchTest' \
  --tests 'com.auraboot.framework.conversation.ConversationTurnServiceImplNamedAgentTaskTest' \
  --tests 'com.auraboot.framework.conversation.ConversationTurnServiceImplResumeTest' \
  --tests 'com.auraboot.framework.aurabot.service.AuraBotChatServiceGroundingTest' \
  --tests 'com.auraboot.framework.aurabot.service.ChatSessionStoreReliabilityTest' \
  --tests 'com.auraboot.framework.integration.agent.AgentRunControllerIntegrationTest.runtimeOps_surfacesExecutionStateDiagnostics' \
  --tests 'com.auraboot.framework.architecture.AgentRuntimeArchitectureTest' \
  `# --- guardrails added 2026-07-21/22; see docs/backlog/2026-07-22-agent-remaining-gaps-execution-spec.md §A1.` \
  `# Named class by class rather than widening to agent.service.* / agent.provider.*: a broad` \
  `# pattern would sweep heavy shared-DB integration tests into this gate, and a gate that is` \
  `# slow and flaky is one somebody eventually switches off. A gate nobody runs protects nothing. #` \
  --tests 'com.auraboot.framework.agent.AgentApprovalGrantConsumptionIT' \
  --tests 'com.auraboot.framework.agent.AgentLifecycleSuspendIT' \
  --tests 'com.auraboot.framework.agent.MemorySecretWritebackIT' \
  --tests 'com.auraboot.framework.agent.StaleRunRecoveryIT' \
  --tests 'com.auraboot.framework.agent.memory.MemorySecretGuardTest' \
  --tests 'com.auraboot.framework.agent.PromptInjectionBoundaryTest' \
  --tests 'com.auraboot.framework.agent.service.ActionIdentityAuditIT' \
  --tests 'com.auraboot.framework.agent.dto.AiActionRiskLevelBridgeTest' \
  --tests 'com.auraboot.framework.agent.service.RunDeadlineEnforcementIT' \
  --tests 'com.auraboot.framework.agent.service.AgentLoopCostLimitIT' \
  --tests 'com.auraboot.framework.agent.provider.McpExternalDescriptionTest' \
  --tests 'com.auraboot.framework.agent.provider.UsageRecordingProviderAttributionTest' \
  --no-daemon
