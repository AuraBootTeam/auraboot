package com.auraboot.framework.im.service;

import com.auraboot.framework.im.model.ImMessage;

/** Optional AI turn handler for an already persisted IM message. */
public interface ImAiResponder {
    void generateResponse(ImMessage userMessage, Long tenantId);
}
