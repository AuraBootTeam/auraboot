package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.runtime.context.AgentContextAssembler;
import com.auraboot.framework.agent.runtime.context.AgentContextBlock;
import com.auraboot.framework.agent.runtime.context.AgentContextBundle;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.conversation.TurnContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
class AgentChatContextAdapter {

    private final AgentContextAssembler contextAssembler;

    List<AgentContextBlock> assemble(TurnContext ctx, ChatRequest request) {
        if (request == null || request.getPageContext() == null) {
            return List.of();
        }
        AgentContextBundle bundle = contextAssembler.assemble(
                new AgentContextAssembler.Request(
                        ctx != null ? ctx.tenantId() : null,
                        ctx != null ? ctx.channel() : null,
                        request.getPageContext(),
                        null,
                        null,
                        List.of()));
        return bundle.blocks();
    }
}
