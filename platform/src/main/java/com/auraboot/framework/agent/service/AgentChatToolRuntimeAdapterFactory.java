package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.runtime.AgentRuntimeStateFactory;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.runtime.PendingToolSnapshotFactory;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.agent.runtime.context.AgentContextBlock;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
class AgentChatToolRuntimeAdapterFactory {

    private final AgentRuntimeStateFactory runtimeStateFactory;
    private final PendingToolStore pendingToolStore;
    private final PendingToolSnapshotFactory pendingToolSnapshotFactory;
    private final AgentChatTurnOutcomeAdapter outcomeAdapter;
    private final AgentChatToolExecutionAdapter toolExecutionAdapter;

    ChatTurnRuntime.ChatToolLoopCallbacks callbacks(AgentChatPortImpl owner,
                                                    List<AgentContextBlock> contextBlocks) {
        return new AgentChatToolRuntimeAdapter(
                owner,
                runtimeStateFactory,
                pendingToolStore,
                pendingToolSnapshotFactory,
                contextBlocks,
                outcomeAdapter,
                toolExecutionAdapter)
                .callbacks();
    }
}
