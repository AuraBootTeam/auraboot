package com.auraboot.framework.agent.runtime;

import java.util.List;
import java.util.Map;

/**
 * Storage boundary for persisted LLM chat message tapes.
 */
public interface ChatMessageTapeStore {

    void storeConversationMessages(String sessionId, List<Map<String, Object>> messages);

    List<Map<String, Object>> loadConversationMessages(String sessionId);
}
