package com.auraboot.framework.automation.event;

/** Live automation progress; the model response aggregate stays with the executor. */
public record AutomationStreamChunk(long seq, String delta, String thinkingDelta, boolean done) {}
