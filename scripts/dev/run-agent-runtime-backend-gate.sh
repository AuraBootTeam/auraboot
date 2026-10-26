#!/bin/bash
#
# Focused backend gate for the generic agent runtime architecture.
# Preserves the established selector catalog across the owning library/application test tasks.
# Page Designer and external live-provider probes remain outside this gate.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT/platform"

./gradlew :compileJava :compileTestJava :platform-agent:compileTestJava :platform-app:compileTestJava --no-daemon

./gradlew :platform-agent:test \
  --tests 'com.auraboot.framework.agent.runtime.*' \
  --tests 'com.auraboot.framework.agent.runtime.policy.*' \
  --tests 'com.auraboot.framework.agent.authorization.*' \
  --tests 'com.auraboot.framework.agent.runtime.context.AgentContextAssemblerTest' \
  --tests 'com.auraboot.framework.agent.service.ToolLoopServiceSafetyTest' \
  --tests 'com.auraboot.framework.agent.service.StepLoopServiceCheckpointTest' \
  --tests 'com.auraboot.framework.agent.service.StepLoopServiceLlmResponseGuardTest' \
  --tests 'com.auraboot.framework.agent.service.AgentRunServiceSyncTest' \
  --tests 'com.auraboot.framework.agent.service.PlanServiceTest' \
  --tests 'com.auraboot.framework.agent.service.SkillEngineTest' \
  --tests 'com.auraboot.framework.agent.service.ActiveMemoryServiceSemanticRecallTest' \
  --tests 'com.auraboot.framework.conversation.TurnCompletionMemoryListenerTest' \
  --tests 'com.auraboot.framework.agent.service.AgentApprovalGateServiceConcurrencyTest' \
  --tests 'com.auraboot.framework.agentchat.handoff.HandoffPermissionPolicyTest' \
  --tests 'com.auraboot.framework.agentchat.reply.AgentReplyTaskChokepointTest' \
  --tests 'com.auraboot.framework.conversation.turn.*' \
  --tests 'com.auraboot.framework.agent.identity.*' \
  --tests 'com.auraboot.framework.agent.service.InterruptDispatcherTenantScopeTest' \
  --tests 'com.auraboot.framework.agent.service.RunLifecycleServiceTest' \
  --tests 'com.auraboot.framework.agent.service.AgentRunTerminalStoreTest' \
  --tests 'com.auraboot.framework.agent.service.AgentResumeGrantTest' \
  --tests 'com.auraboot.framework.conversation.AssistantModelFallbackTest' \
  --tests 'com.auraboot.framework.conversation.ConversationTurnServiceImplNamedAgentTaskTest' \
  --tests 'com.auraboot.framework.aurabot.service.AuraBotChatServiceGroundingTest' \
  --tests 'com.auraboot.framework.aurabot.service.ChatSessionStoreReliabilityTest' \
  --tests 'com.auraboot.framework.agent.memory.MemorySecretGuardTest' \
  --tests 'com.auraboot.framework.agent.PromptInjectionBoundaryTest' \
  --tests 'com.auraboot.framework.agent.dto.AiActionRiskLevelBridgeTest' \
  --tests 'com.auraboot.framework.agent.AiActionRiskAssessorTest' \
  --tests 'com.auraboot.framework.agent.service.GoalDriftObservationTest' \
  --tests 'com.auraboot.framework.agent.provider.McpExternalDescriptionTest' \
  --tests 'com.auraboot.framework.agent.provider.UsageRecordingProviderAttributionTest' \
  --no-daemon

./gradlew :platform-app:test \
  --tests 'com.auraboot.framework.agent.service.StepLoopParallelToolTest' \
  --tests 'com.auraboot.framework.agent.service.SkillAssetLayerTenantIntegrationTest' \
  --tests 'com.auraboot.framework.agent.service.StepLoopServiceThinkingIntegrationTest' \
  --tests 'com.auraboot.framework.agent.service.AgentChatPortImpl*' \
  --tests 'com.auraboot.framework.agent.AgentApprovalGateIntegrationTest' \
  --tests 'com.auraboot.framework.agentchat.GroupChatMessagePortWiringIT' \
  --tests 'com.auraboot.framework.conversation.ConversationTurnServiceImplAcpDispatchTest' \
  --tests 'com.auraboot.framework.conversation.ConversationTurnServiceImplAcpResumeTest' \
  --tests 'com.auraboot.framework.conversation.ConversationTurnServiceImplDispatchTest' \
  --tests 'com.auraboot.framework.conversation.ConversationTurnServiceImplResumeTest' \
  --tests 'com.auraboot.framework.integration.agent.AgentRunControllerIntegrationTest.runtimeOps_surfacesExecutionStateDiagnostics' \
  --tests 'com.auraboot.framework.architecture.AgentRuntimeArchitectureTest' \
  --tests 'com.auraboot.framework.agent.AgentApprovalGrantConsumptionIT' \
  --tests 'com.auraboot.framework.agent.AgentWriteCommandPipelineIT' \
  --tests 'com.auraboot.framework.agent.SkillEngineWiredExecutionIT' \
  --tests 'com.auraboot.framework.agent.AgentLifecycleSuspendIT' \
  --tests 'com.auraboot.framework.agent.MemorySecretWritebackIT' \
  --tests 'com.auraboot.framework.agent.AgentMemoryIntegrationTest' \
  --tests 'com.auraboot.framework.integration.agent.AgentMemoryScopeIntegrationTest' \
  --tests 'com.auraboot.framework.integration.agent.MemoryCompactionIntegrationTest' \
  --tests 'com.auraboot.framework.agent.AgentResumeRetryIntegrationTest' \
  --tests 'com.auraboot.framework.agent.AgentApprovalTimeoutEnforcementIT' \
  --tests 'com.auraboot.framework.agent.memory.MemoryL1L2Phase4IntegrationTest' \
  --tests 'com.auraboot.framework.agent.StaleRunRecoveryIT' \
  --tests 'com.auraboot.framework.agent.service.ActionIdentityAuditIT' \
  --tests 'com.auraboot.framework.agent.service.RunDeadlineEnforcementIT' \
  --tests 'com.auraboot.framework.agent.service.AgentLoopCostLimitIT' \
  --no-daemon

./gradlew :platform-app:integrationTest \
  --tests 'com.auraboot.framework.agent.integration.runtime.DurableToolExecutionRecoveryIT' \
  --tests 'com.auraboot.framework.agent.service.AgentRunTerminalStoreIT' \
  --no-daemon

# Uncached, real-PostgreSQL namespace/expiry/compaction/provenance qualification.
./gradlew :platform-agent:memoryGovernanceIntegrationTest --no-daemon
