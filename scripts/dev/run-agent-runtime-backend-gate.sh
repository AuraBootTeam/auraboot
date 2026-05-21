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

./gradlew :compileJava :compileTestJava

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
  --tests 'com.auraboot.framework.architecture.AgentRuntimeArchitectureTest'
