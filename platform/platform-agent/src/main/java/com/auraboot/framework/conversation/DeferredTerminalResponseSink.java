package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.dto.ResultContract;
import com.auraboot.framework.aurabot.service.RagContextProvider;
import java.util.List;
import java.util.Map;

/** Forward streaming content immediately, but close Web SSE only after the turn finalizer returns. */
final class DeferredTerminalResponseSink implements ResponseSink {
    private final ResponseSink delegate;
    private Runnable terminal;
    private boolean failed;
    DeferredTerminalResponseSink(ResponseSink delegate) { this.delegate = delegate; }
    @Override public void onTextChunk(String text) { delegate.onTextChunk(text); }
    @Override public void onToolStart(String id, String name, Map<String,Object> input) { delegate.onToolStart(id,name,input); }
    @Override public void onToolResult(String id, Map<String,Object> result, boolean success) { delegate.onToolResult(id,result,success); }
    @Override public void onConfirmRequired(String id,String name,String description,Map<String,Object> input,String pending) { delegate.onConfirmRequired(id,name,description,input,pending); }
    @Override public void onConfirmRequired(String id,String name,String description,Map<String,Object> input,String pending,Map<String,Object> extension) { delegate.onConfirmRequired(id,name,description,input,pending,extension); }
    @Override public void onError(String message,String traceId) { failed=true; terminal=()->delegate.onError(message,traceId); }
    @Override public void onDone(String content,String traceId) { if (!failed) terminal=()->delegate.onDone(content,traceId); }
    @Override public void onThinking(String content,int tokens,String signature) { delegate.onThinking(content,tokens,signature); }
    @Override public void onWarnings(List<String> warnings) { delegate.onWarnings(warnings); }
    @Override public void onRetrievalEvidence(List<RagContextProvider.RetrievalEvidence> evidence) { delegate.onRetrievalEvidence(evidence); }
    @Override public void onResultContract(ResultContract contract) { delegate.onResultContract(contract); }
    @Override public boolean isClientConnected() { return delegate.isClientConnected(); }
    @Override public void onTurnBegin(String turn,Long agent,Long conversation,Long reply,Long initiator) { delegate.onTurnBegin(turn,agent,conversation,reply,initiator); }
    @Override public void onStreamEnd(String turn,Long message,Integer tokens) { delegate.onStreamEnd(turn,message,tokens); }
    @Override public void onTurnCancelled(String turn) { delegate.onTurnCancelled(turn); }
    void flushTerminal() { if (terminal != null) { Runnable frame=terminal;terminal=null;frame.run(); } }
}
