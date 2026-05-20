package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.runtime.PendingToolSnapshotFactory;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.agent.runtime.context.AgentContextBundle;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
class AuraBotChatToolRuntimeAdapterFactory {

    private final ChatTurnRuntime chatTurnRuntime;
    private final LlmProviderFactory llmProviderFactory;
    private final ChatToolResolver chatToolResolver;
    private final ChatToolExecutor chatToolExecutor;
    private final ObjectMapper objectMapper;

    TurnOutcome run(TurnContext ctx,
                    String providerCode,
                    LlmProviderFactory.ProviderConfig config,
                    String model,
                    String systemPrompt,
                    List<com.auraboot.framework.aurabot.dto.ChatMessage> history,
                    String userMessage,
                    int maxTokens,
                    List<LlmChatRequest.Tool> tools,
                    ChatToolResolver.ResolvedTools resolved,
                    String modelCode,
                    String sessionId,
                    AgentContextBundle contextBundle,
                    ResponseSink sink,
                    UserPermissionService userPermissionService,
                    PendingToolStore pendingToolStore,
                    PendingToolSnapshotFactory pendingToolSnapshotFactory,
                    int maxToolRounds) {
        return new AuraBotChatToolRuntimeAdapter(
                chatTurnRuntime,
                llmProviderFactory,
                chatToolResolver,
                chatToolExecutor,
                userPermissionService,
                pendingToolStore,
                pendingToolSnapshotFactory,
                objectMapper,
                maxToolRounds)
                .run(ctx, providerCode, config, model, systemPrompt,
                        history, userMessage, maxTokens, tools, resolved,
                        modelCode, sessionId, contextBundle, sink);
    }
}
