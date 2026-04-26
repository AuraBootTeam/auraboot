# ConversationTurnService Phase A 执行方案 v4（定稿）

**状态**：execution plan v4 — owner 已定 Q-A.4-Q-A.7 决策，可开干
**日期**：2026-04-26（v1 → v2 → v3 → v4 同日四轮）
**分支**：`feat/conversation-turn-service-phase-a`（A.1 SPI 已 push）
**关联**：
- `auraboot/docs/plans/2026-04/2026-04-26-conversation-turn-service-design.md` v3.3
- `feedback_verify_source_before_arch_decision.md`
- AGENTS.md `### 长期演进视角`（owner 决策应用此规则）

---

## 0. v1 → v4 演进总结

| 版本 | 触发 | 核心变化 |
|------|------|---------|
| v1 | 初版 | A.3 重构 doStreamChat，cut over /chat/stream |
| v2 | review 6 P0/P1 + 4 P2 | 改不重构 + shadow endpoint，回避所有 refactor |
| v3 | 长期演进反思 | 改回重构 aurabot 主路径，shadow `/chat/stream-v2` |
| **v4** | **owner 拍板 Q-A.4=A'**：async 只在 controller/adapter，business lifecycle 内部 sync | **chatService split sync core；runTurn 真同步实现；cut over /chat/stream（无 shadow）；TurnRequest 携带原 ChatRequest** |

### v3 → v4 根因（之前 4 轮没收敛的根本）

reviewer 4 轮戳出"finalize 抢跑 / SPI bypass / chokepoint 装饰化 / SSE 假通过"等问题，根因都是 **async 边界从未明确**。v3 让 streamChat 留 async 但 runTurn 立刻返回 = finalize 与 LLM 真完成时刻分离 → 整个 outcome propagation 是假的。

**v4 铁律**：async 是 transport 关心的事（HTTP / SSE 必须立刻返 emitter）；business lifecycle (begin → execute → end/suspend) 内部 sync 闭合。Controller 在 async worker 里同步调 runTurn，runTurn 同步调 chatService.executeAuraBotTurn，executeAuraBotTurn 同步返回 TurnOutcome。整条路径 outcome propagation 真实。

这与 ACP 已落地的 StepLoopService / GroundingService 的 sync 设计一致，是同一架构哲学。

---

## 1. owner 决策（v4 基础）

| Q | 决策 |
|---|------|
| Q-A.4 async 边界 | **A'：sync core + async only at controller/adapter boundary** |
| Q-A.5 stream-v2 vs cut over | **直接 cut over `/chat/stream`，无 shadow endpoint** |
| Q-A.6 TurnRequest 是否携带原 ChatRequest | **是**：Phase A 携带原始 ChatRequest，不拆字段、不重建缩水 DTO |
| Q-A.7 runTurn SPI 真实现 | **是**：Phase A 必须真实现 `runTurn(TurnRequest, ResponseSink): TurnOutcome`，不允许 cast，不允许 runTurnPhaseA |

---

## 2. Phase A v4 总览

| 步骤 | 内容 | 风险 |
|------|------|------|
| **A.1** | ✅ DONE — SPI + DTOs | 低 |
| A.2 | SseResponseSink + ResponseSink SPI 微调（onDone/onError 加 traceId 参数）+ TurnRequest 加 legacyRequest 字段 | 低 |
| **A.2b** | **Pre-refactor SSE baseline 录制**（A.3 改代码前必跑）| — |
| **A.3** | **chatService split**：新增同步 core `executeAuraBotTurn(TurnContext, ChatRequest, ResponseSink): TurnOutcome`；现有 streamChat 保留作 legacy async wrapper（含 named agent 路径） | **高** |
| A.4 | ConversationTurnServiceImpl 真实实现 runTurn（同步调 executeAuraBotTurn + finalizeTurn dispatch） | 中 |
| A.5 | AuraBotController `/chat/stream` cut over：纯 transport（创建 emitter / snapshot identity / 提交 async / 立刻返回）| 中 |
| A.6 | Spring config — observeOnly + Micrometer | 低 |
| A.7 | E2E + SSE pre-refactor baseline diff + sender_type baseline + 后端身份校验 | 中 |

总 commit ≈ 7 个（A.2 / A.2b / A.3 / A.4 / A.5 / A.6 / A.7），加 A.1 共 8 commit。

---

## 3. Pre-condition checks（A.2 开始前必跑）

```bash
cd /Users/ghj/work/auraboot/auraboot

test -f platform/src/main/java/com/auraboot/framework/agent/service/StepLoopService.java && echo "✓ Phase 3"
test -f platform/src/main/java/com/auraboot/framework/agent/service/GroundingService.java && echo "✓ Phase 4"

cd /Users/ghj/work/auraboot-worktrees/conv-turn-svc-phase-a
test -f platform/src/main/java/com/auraboot/framework/conversation/ConversationTurnService.java && echo "✓ A.1"

# baseline sender_type
psql -h localhost -U ghj -d aura_boot -P pager=off \
  -c "SELECT sender_type, count(*) FROM ab_im_message GROUP BY sender_type ORDER BY sender_type;" \
  > /tmp/sender-type-baseline.txt

# baseline metrics namespace
curl -s http://localhost:6443/actuator/prometheus | grep -i 'aurabot.*turn' | head -5
# 预期 0 行
```

---

## 4. A.2 — SseResponseSink + SPI 微调 + TurnRequest 加 legacyRequest

### A.2.1 ResponseSink SPI 修订

```java
public interface ResponseSink {
    void onTextChunk(String text);
    void onToolStart(String toolId, String toolName, Map<String, Object> input);
    void onToolResult(String toolId, Map<String, Object> result, boolean success);
    void onConfirmRequired(String toolId, String toolName, String description, Map<String, Object> input);
    /** v4: traceId 在 done/error 时才知道（doStreamChatInner 内创建），sink 构造时不需 */
    void onError(String message, String traceId);
    void onDone(String finalResponse, String traceId);
    default boolean isClientConnected() { return true; }
}
```

设计 v3.3 §3.4 接口签名同步更新（A.2 commit 必含 design doc 更新）。

### A.2.2 SseResponseSink 实现

```java
public class SseResponseSink implements ResponseSink {
    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;

    public SseResponseSink(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    @Override public void onTextChunk(String text) {
        sendRaw("chunk", Map.of("content", text));
    }

    @Override public void onDone(String fullContent, String traceId) {
        Map<String, Object> data = new HashMap<>();
        data.put("content", fullContent);
        if (traceId != null) data.put("traceId", traceId);
        sendRaw("done", data);
        completeQuietly();
    }

    @Override public void onError(String message, String traceId) {
        Map<String, Object> data = new HashMap<>();
        data.put("error", message != null ? message : "Unknown error");
        if (traceId != null) data.put("traceId", traceId);
        sendRaw("error", data);
        completeQuietly();
    }

    @Override public void onToolStart(String toolId, String toolName, Map<String, Object> input) {
        sendJsonString("tool_start", Map.of(
                "toolId", toolId, "toolName", toolName,
                "input", input != null ? input : Map.of()));
    }

    @Override public void onToolResult(String toolId, Map<String, Object> result, boolean success) {
        sendJsonString("tool_result", Map.of(
                "toolId", toolId,
                "result", result != null ? result : Map.of(),
                "success", success));
    }

    @Override public void onConfirmRequired(String toolId, String toolName, String description,
                                             Map<String, Object> input) {
        sendJsonString("confirm_required", Map.of(
                "toolId", toolId, "toolName", toolName,
                "description", description != null ? description : "",
                "input", input != null ? input : Map.of()));
    }

    private void sendRaw(String name, Map<String, Object> data) {
        try { emitter.send(SseEmitter.event().name(name).data(data)); }
        catch (Exception ignore) { /* 与现有 send* 行为一致 */ }
    }

    private void sendJsonString(String name, Map<String, Object> data) {
        try { emitter.send(SseEmitter.event().name(name).data(objectMapper.writeValueAsString(data))); }
        catch (Exception ignore) {}
    }

    private void completeQuietly() {
        try { emitter.complete(); } catch (Exception ignore) {}
    }
}
```

### A.2.3 TurnRequest 加 legacyRequest 字段（Q-A.6）

```java
public record TurnRequest(
        long tenantId,
        long userId,
        Long humanMemberId,
        String channel,
        String agentCode,
        Long conversationId,                 // Phase A nullable
        String clientMsgId,                  // Phase A nullable
        String userMessage,
        Map<String, Object> pageContext,
        Map<String, Object> options,
        InboundMode inboundMode,
        TriageBucket precomputedBucket,
        ChatRequest legacyRequest            // ★ v4 新增：Phase A 携带原始 ChatRequest 不破行为
) {}
```

### Commit

```
feat(conv-turn): A.2 SseResponseSink + SPI traceId migration + TurnRequest legacyRequest field

- ResponseSink: onDone/onError add traceId parameter (set inside doStreamChatInner)
- onConfirmRequired: 4-param signature aligned with sendConfirmRequired source
- SseResponseSink: byte-aligned to OSS send* helpers (raw Map for chunk/done/error,
  JSON-string for tool_*/confirm; emitter.complete() in done/error)
- TurnRequest carries original ChatRequest preserving sessionId/history/pageContext/
  knowledgeBaseIds (Q-A.6)
- design doc v3.3 §3.4 SPI signatures updated in same commit
- SseResponseSink unit tests (10 cases: each event byte alignment)
```

---

## 5. A.2b — Pre-refactor SSE baseline 录制（A.3 前必做）

### 关键 — 录基准必须在改代码前

reviewer P1.6 戳出：v3 plan 里 A.7 比较 `/chat/stream` 与 `/chat/stream-v2`，但 A.3 已把两端点都桥到 sink，对比的是两个新路径，不是与原实现对比。**修：A.3 改任何代码前，对原 `/chat/stream` 做 SSE EventStream 录制存档。**

```bash
# A.2 commit 之后立刻跑（chatService 还没 split）
TOKEN=$(curl -s -X POST http://localhost:6443/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Test2026x"}' | jq -r '.data.jwt')

mkdir -p /tmp/sse-pre-refactor
for SCENARIO in "trivial-greeting:你好" "explain-with-context:这个表单有哪些字段" "platform-query:查询本月销售" "tool-confirm:删除最后一条客户记录"; do
    NAME="${SCENARIO%%:*}"
    MSG="${SCENARIO#*:}"
    BODY=$(jq -n --arg msg "$MSG" '{message: $msg, agentCode: "aurabot"}')

    curl -s -N \
        -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' \
        -d "$BODY" \
        --max-time 60 \
        http://localhost:6443/api/ai/aurabot/chat/stream \
        > /tmp/sse-pre-refactor/${NAME}.raw

    grep "^event: " /tmp/sse-pre-refactor/${NAME}.raw | sort -u > /tmp/sse-pre-refactor/${NAME}.events
    grep "^data: " /tmp/sse-pre-refactor/${NAME}.raw | sed 's/^data: //' \
        | jq -c 'walk(if type=="object" then
                       (if has("traceId") then .traceId="<X>" else . end)
                       | (if has("toolId") then .toolId="<X>" else . end)
                       | (if has("content") then .content="<C>" else . end)
                       | (if has("result") then .result="<R>" else . end)
                   else . end)
                | {keys: (keys|sort), types: (to_entries | map({(.key): (.value|type)}))}' \
        > /tmp/sse-pre-refactor/${NAME}.shape
done

ls -la /tmp/sse-pre-refactor/
echo "★ baseline locked. A.3 may start. A.7 will diff against these."
```

提交 baseline metadata（不提交 raw payload，因含 LLM 内容）：

```bash
sha256sum /tmp/sse-pre-refactor/*.shape > docs/plans/2026-04/sse-baseline-2026-04-26.sha256
git add docs/plans/2026-04/sse-baseline-2026-04-26.sha256
git commit -m "test(conv-turn): A.2b lock pre-refactor SSE baseline (sha256 only)"
```

---

## 6. A.3 — chatService split 同步 core（核心高风险 PR）

### A.3.1 设计

按 owner 5 anchor #1：新增 sync core method。

```java
// AuraBotChatService.java

/**
 * Phase A.3 sync core. Handles aurabot main path only; named agent path stays
 * in legacy streamChat (will migrate in Phase B+ with group-chat-adapter sub-design).
 *
 * <p>Returns TurnOutcome reflecting actual completion. Sync internally — caller
 * (turnService.runTurn or legacy streamChat) owns async boundary.
 *
 * <p>Does NOT:
 * - manage MetaContext (caller's responsibility)
 * - handle named agent (agentChatPort) routing — caller handles
 * - call asyncTaskExecutor.execute (caller is already on worker thread)
 */
public TurnOutcome executeAuraBotTurn(TurnContext ctx, ChatRequest request, ResponseSink sink) {
    com.auraboot.framework.agent.service.ChatSseContext.setEmitter(/* ??? */);
    // ChatSseContext requires SseEmitter; if needed, sink must back to emitter
    // (Phase A: only SseResponseSink is in use, can expose underlying emitter via getter)
    try {
        return doStreamChatInnerSinkAware(ctx, request, sink);
    } catch (Exception e) {
        log.error("executeAuraBotTurn failed: {}", e.getMessage(), e);
        sink.onError(e.getMessage(), null);
        return new TurnOutcome.Failed(e.getMessage(), e);
    } finally {
        com.auraboot.framework.agent.service.BifContext.clear();
        com.auraboot.framework.agent.service.ChatSseContext.clear();
    }
}

/**
 * doStreamChatInnerSinkAware = current doStreamChatInner but:
 * - sendChunk/sendDone/sendError/sendToolStart/sendToolResult/sendConfirmRequired
 *   replaced with sink.on* equivalents (13 call sites)
 * - returns TurnOutcome at every termination point:
 *     - normal sendDone path → return new Success(...)
 *     - sendError path → return new Failed(...)
 *     - confirm_required pause → return new PendingConfirmation(...)
 *     - tool loop max rounds → return new Failed("Tool loop exceeded...")
 * - traceId passed to sink.onDone/onError (sink doesn't hold traceId)
 */
private TurnOutcome doStreamChatInnerSinkAware(TurnContext ctx, ChatRequest request, ResponseSink sink) {
    // ... 670+ lines refactored from existing doStreamChatInner
    // Each return statement returns appropriate TurnOutcome
}
```

### A.3.2 ChatSseContext 兼容

`ChatSseContext.setEmitter(emitter)` 现有用法是 ThreadLocal，AiTraceService 等内部 service 可能从 TL 拿 emitter。Phase A 不能破。

**v4 决定**：`SseResponseSink` 暴露内部 `getEmitter()`（package-private），`executeAuraBotTurn` 仅当 sink 是 `SseResponseSink` 时调 `ChatSseContext.setEmitter(sink.getEmitter())`。其他 sink（未来 WS / sync JSON）自行设计 ChatSseContext 替代物（Phase B+）。

```java
// SseResponseSink (v4 加 package-private getter)
SseEmitter getEmitter() { return emitter; }

// executeAuraBotTurn (v4 兼容)
if (sink instanceof SseResponseSink ssr) {
    ChatSseContext.setEmitter(ssr.getEmitter());
}
```

### A.3.3 streamChat 保留作 legacy async wrapper

```java
public void streamChat(Long tenantId, Long userId, String userPid, String username,
                       Long memberId, ChatRequest request, SseEmitter emitter) {
    asyncTaskExecutor.execute(() -> {
        try {
            MetaContext.setContext(tenantId, userId, userPid, username);
            if (memberId != null) MetaContext.setMemberId(memberId);

            // Routing: named agent goes legacy AgentChatPort (untouched in Phase A)
            String agentCode = request.getAgentCode();
            if (agentCode != null && !agentCode.isBlank() && !"aurabot".equals(agentCode)
                    && agentChatPort != null) {
                if (!agentChatPort.agentExists(tenantId, agentCode)) {
                    sendError(emitter, "Agent not found or inactive: " + agentCode);
                    return;
                }
                agentChatPort.streamAgentChat(tenantId, agentCode, request, emitter);
                return;
            }

            // Aurabot main path: through new sync core
            SseResponseSink sink = new SseResponseSink(emitter, objectMapper);
            TurnContext ctx = TurnContext.legacyDefault(tenantId, userId, memberId);  // helper for legacy
            executeAuraBotTurn(ctx, request, sink);
            // outcome ignored: legacy path has no finalize hook
        } catch (Exception e) {
            log.error("Chat stream failed: {}", e.getMessage(), e);
            sendError(emitter, e.getMessage());
        } finally {
            MetaContext.clear();
        }
    });
}
```

### A.3.4 13 个 send* call site 替换映射

| 现 send | 新 sink | 备注 |
|---------|---------|------|
| `sendChunk(emitter, content)` | `sink.onTextChunk(content)` | line 1389 |
| `sendDone(emitter, content)` | `sink.onDone(content, null);` + return Success | |
| `sendDone(emitter, content, traceId)` | `sink.onDone(content, traceId);` + return Success | |
| `sendError(emitter, msg)` | `sink.onError(msg, null);` + return Failed | |
| `sendError(emitter, msg, traceId)` | `sink.onError(msg, traceId);` + return Failed | |
| `sendToolStart(emitter, ...)` | `sink.onToolStart(...)` | |
| `sendToolResult(emitter, ...)` | `sink.onToolResult(...)` | |
| `sendConfirmRequired(emitter, ...)` | `sink.onConfirmRequired(...);` + return PendingConfirmation | |
| `streamTextContent(text, emitter, traceId)` | inline: `sink.onTextChunk(text); sink.onDone(text, traceId); return Success` | helper 删 |
| `streamFinalResponse(response, emitter, traceId)` | 改签名收 sink；返 TurnOutcome | |

强化 grep gate（v3 review P1.7 fix）：

```bash
# 严格 grep — A.3 commit 后 doStreamChatInnerSinkAware 内不能有 emitter 用法
EXCLUDE_REGEX='AgentChatPort|streamAgentChat|new SseEmitter|ChatSseContext\.setEmitter|sink\.getEmitter|^// |^ \* '

grep -nE 'send(Chunk|Done|Error|ToolStart|ToolResult|ConfirmRequired)\(emitter|stream(FinalResponse|TextContent)\(.*emitter|emitter\.send|emitter\.complete' \
    platform/src/main/java/com/auraboot/framework/aurabot/service/AuraBotChatService.java \
    | grep -vE "$EXCLUDE_REGEX" \
    | head -20
# 预期 0 行（除白名单内）
```

### Commit

```
feat(conv-turn): A.3 chatService split — sync core executeAuraBotTurn

THE BIG STEP. Architecture per Q-A.4=A':
- New sync core executeAuraBotTurn(TurnContext, ChatRequest, ResponseSink): TurnOutcome
- Returns real TurnOutcome at every termination (Success / Failed / PendingConfirmation)
- 13 send* call sites replaced with sink.on* (return appropriate outcome)
- traceId passed to sink.onDone/onError (no longer held in sink)
- ChatSseContext.setEmitter compat via SseResponseSink.getEmitter() package-private
- Legacy streamChat retained as async wrapper that:
  - routes named agent to AgentChatPort (untouched, Phase B+ migration)
  - aurabot main path: builds SseResponseSink + TurnContext default + calls executeAuraBotTurn
  - outcome ignored in legacy path (turnService.runTurn is the outcome consumer)

Refactor preserves SSE byte stream (verified by A.7 against /tmp/sse-pre-refactor/).
```

---

## 7. A.4 — ConversationTurnServiceImpl 真实 runTurn（Q-A.7）

### 设计

```java
@Service
public class ConversationTurnServiceImpl implements ConversationTurnService {

    private final AuraBotChatService chatService;
    private final TurnSideEffects sideEffects;

    public ConversationTurnServiceImpl(AuraBotChatService chatService,
                                        @Qualifier("turnSideEffects") TurnSideEffects sideEffects) {
        this.chatService = chatService;
        this.sideEffects = sideEffects;
    }

    @Override
    public TurnOutcome runTurn(TurnRequest request, ResponseSink sink) {
        TurnContext ctx = beginTurn(request);
        sideEffects.metricsRecorder().recordTurnBegin(ctx);

        TurnOutcome outcome;
        try {
            outcome = chatService.executeAuraBotTurn(ctx, request.legacyRequest(), sink);
        } catch (Exception e) {
            log.error("runTurn caught executeAuraBotTurn exception: {}", e.getMessage(), e);
            outcome = new TurnOutcome.Failed(e.getMessage(), e);
        }

        try {
            finalizeTurn(ctx, outcome);
        } catch (Exception e) {
            log.warn("finalizeTurn threw: {}", e.getMessage());
        }
        return outcome;
    }

    @Override
    public TurnOutcome resumeTurn(String pendingTurnId, ConfirmDecision decision, ResponseSink sink) {
        throw new UnsupportedOperationException("Phase B B.6 wires resumeTurn");
    }

    private TurnContext beginTurn(TurnRequest request) {
        String turnId = UniqueIdGenerator.generate();
        Long inboundMessageId = sideEffects.persistence().persistInbound(
                /* placeholder */ null, request.userMessage(), request.clientMsgId());
        return new TurnContext(turnId, request.tenantId(), request.userId(),
                request.humanMemberId(), null, null, request.conversationId(),
                inboundMessageId, request.precomputedBucket(), null, Instant.now());
    }

    private void finalizeTurn(TurnContext ctx, TurnOutcome outcome) {
        switch (outcome) {
            case TurnOutcome.Success s -> {
                sideEffects.persistence().persistOutbound(ctx, s);
                sideEffects.eventEmitter().emit(new TurnCompletedEvent(ctx, s));
            }
            case TurnOutcome.Interrupted i -> {
                sideEffects.persistence().persistOutbound(ctx, i);
                sideEffects.eventEmitter().emit(new TurnCompletedEvent(ctx, i));
            }
            case TurnOutcome.Failed f -> {
                sideEffects.auditWriter().writeFailure(ctx, f);
                sideEffects.eventEmitter().emit(new TurnCompletedEvent(ctx, f));
            }
            case TurnOutcome.PendingConfirmation pc -> {
                // suspendTurn semantics:
                // - partial 非空才 persistOutbound（与 endTurn 区分）
                // - savePending(payload to ChatSessionStore) — Phase B impl
                // - emit TurnSuspendedEvent (NOT TurnCompletedEvent)
                if (pc.partialResponse() != null && !pc.partialResponse().isBlank()) {
                    sideEffects.persistence().persistOutbound(ctx, pc);
                }
                // TODO Phase B: chatSessionStore.savePending(ctx.turnId(), buildPendingTool(ctx, pc));
                sideEffects.eventEmitter().emit(new TurnSuspendedEvent(ctx, pc));
            }
        }
        sideEffects.metricsRecorder().recordTurnEnd(ctx, outcome);
    }
}

record TurnCompletedEvent(TurnContext ctx, TurnOutcome outcome) {}
record TurnSuspendedEvent(TurnContext ctx, TurnOutcome.PendingConfirmation pc) {}
```

### Commit

```
feat(conv-turn): A.4 ConversationTurnServiceImpl real sync runTurn (Q-A.7)

- runTurn truly implements SPI (no UnsupportedOperationException, no cast bypass)
- Sync internally: chatService.executeAuraBotTurn returns real TurnOutcome
- finalizeTurn dispatch:
  - Success/Interrupted/Failed → endTurn path → TurnCompletedEvent
  - PendingConfirmation → suspendTurn path → TurnSuspendedEvent (P1.4 fix:
    only persist if partial non-empty; Phase B will savePending payload)
- Phase A side effects all NOOP except metrics (observeOnly profile)
- resumeTurn still throws (Phase B B.6 wires)
```

---

## 8. A.5 — AuraBotController `/chat/stream` cut over（Q-A.5）

### Cut over，无 shadow

按 Q-A.5：直接切 `/chat/stream`，无 `/chat/stream-v2`。但 controller 里仍保留"named agent → legacy emitter path"分支。

### 设计

```java
@RestController
@RequestMapping("/api/ai/aurabot")
@RequiredArgsConstructor
public class AuraBotController {

    private final AuraBotChatService chatService;       // legacy (named agent + bridge)
    private final ChatToolResolver chatToolResolver;
    private final ConversationTurnService turnService;  // ★ A.5 注入
    private final ObjectMapper objectMapper;            // ★ A.5 注入
    @Qualifier("asyncTaskExecutor")
    private final java.util.concurrent.Executor asyncExecutor;  // ★ A.5 注入

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);

        // Identity snapshot BEFORE async (MetaContext is thread-local)
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String userPid = MetaContext.getCurrentUserPid();
        String username = MetaContext.getCurrentUsername();
        Long memberId = MetaContext.getCurrentMemberId();

        asyncExecutor.execute(() -> {
            try {
                MetaContext.setContext(tenantId, userId, userPid, username);
                if (memberId != null) MetaContext.setMemberId(memberId);

                // Routing: named agent → legacy AgentChatPort path (Phase A unchanged)
                String agentCode = request.getAgentCode();
                if (agentCode != null && !agentCode.isBlank() && !"aurabot".equals(agentCode)) {
                    // Delegate to legacy streamChat which handles named agent routing
                    chatService.streamChat(tenantId, userId, userPid, username, memberId,
                            request, emitter);
                    // Note: legacy streamChat already does asyncTaskExecutor.execute internally;
                    // double async is intentional for Phase A (named agent path migration is Phase B+).
                    return;
                }

                // Aurabot main path: through turnService.runTurn (sync inside async worker)
                SseResponseSink sink = new SseResponseSink(emitter, objectMapper);
                TurnRequest turnReq = new TurnRequest(
                        tenantId, userId, memberId, "web",
                        request.getAgentCode(),
                        null, null,                          // conversationId, clientMsgId — Phase B
                        request.getMessage(),
                        null, null,
                        InboundMode.NEW_FROM_REQUEST,
                        null,
                        request);                            // legacyRequest = original ChatRequest

                turnService.runTurn(turnReq, sink);
                // No explicit emitter.complete() here — sink.onDone/onError handle it.
            } catch (Exception e) {
                log.error("chat stream failed: {}", e.getMessage(), e);
                try { emitter.completeWithError(e); } catch (Exception ignore) {}
            } finally {
                MetaContext.clear();
            }
        });
        return emitter;
    }

    /** /execute unchanged: legacy resumeAfterConfirmation through Phase A; B.6 wires resumeTurn */
    @PostMapping(value = "/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter executeAction(@RequestBody ChatRequest.ExecuteRequest request) {
        /* unchanged */
    }
}
```

### 命名 agent 路径处理（关键说明）

按 Q-A.5 cut over `/chat/stream`，但**named agent 路径**（agentCode != "aurabot"）当前在原 `chatService.streamChat` 内部 routing 到 `agentChatPort.streamAgentChat`。Phase A 范围**不**改 named agent path（Q13=α 推迟到 Phase B+）。

v4 设计：controller cut over 后，named agent 通过 `chatService.streamChat(...)` 委派（原入口完全不动）。Aurabot 主路径走 `turnService.runTurn(...)`。判断在 controller 完成。

### Commit

```
feat(conv-turn): A.5 AuraBotController /chat/stream cutover (Q-A.5 no shadow)

Direct cutover per owner decision. Routing in controller:
- Named agent (agentCode != aurabot, !blank): delegate to legacy
  chatService.streamChat (untouched; Phase B+ migration via group adapter)
- Aurabot main path: build TurnRequest with legacyRequest=original
  ChatRequest, build SseResponseSink, asyncExecutor.execute → MetaContext
  setup → turnService.runTurn → MetaContext.clear

async only at controller boundary; turnService.runTurn is sync inside
worker thread. SseEmitter completion via sink.onDone/onError (terminal
events call emitter.complete()).

/execute and /chat unchanged.
```

---

## 9. A.6 — Spring config

```java
@Configuration
public class ConversationTurnConfig {

    @Bean
    public TurnSideEffects.MetricsRecorder turnMetricsRecorder(MeterRegistry registry) {
        Counter begun = Counter.builder("aurabot.turn.begin").tag("phase", "A").register(registry);
        Counter ended = Counter.builder("aurabot.turn.end").tag("phase", "A").register(registry);
        return new TurnSideEffects.MetricsRecorder() {
            public void recordTurnBegin(TurnContext ctx) { begun.increment(); }
            public void recordTurnEnd(TurnContext ctx, TurnOutcome o) { ended.increment(); }
        };
    }

    @Bean(name = "turnSideEffects")
    public TurnSideEffects turnSideEffects(TurnSideEffects.MetricsRecorder metrics) {
        return TurnSideEffects.observeOnly(metrics);
    }
}
```

---

## 10. A.7 — 验证（pre-refactor baseline diff + worktree 前端）

### A.7.1 后端身份校验

```bash
EXPECTED_WORKTREE="conv-turn-svc-phase-a"
ps -ef | grep -E "MetaApplication|java.*platform" | grep -v grep | head -1 | grep -q "$EXPECTED_WORKTREE" \
  || { echo "ERROR: backend not from feature worktree"; exit 1; }
```

### A.7.2 SSE pre-refactor baseline diff（4 scenarios）

```bash
TOKEN=$(curl -s -X POST http://localhost:6443/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Test2026x"}' | jq -r '.data.jwt')

mkdir -p /tmp/sse-after-refactor

for SCENARIO in "trivial-greeting:你好" "explain-with-context:这个表单有哪些字段" "platform-query:查询本月销售" "tool-confirm:删除最后一条客户记录"; do
    NAME="${SCENARIO%%:*}"
    MSG="${SCENARIO#*:}"
    BODY=$(jq -n --arg msg "$MSG" '{message: $msg, agentCode: "aurabot"}')

    curl -s -N \
        -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' \
        -d "$BODY" --max-time 60 \
        http://localhost:6443/api/ai/aurabot/chat/stream \
        > /tmp/sse-after-refactor/${NAME}.raw

    grep "^event: " /tmp/sse-after-refactor/${NAME}.raw | sort -u > /tmp/sse-after-refactor/${NAME}.events
    grep "^data: " /tmp/sse-after-refactor/${NAME}.raw | sed 's/^data: //' \
        | jq -c 'walk(if type=="object" then
                       (if has("traceId") then .traceId="<X>" else . end)
                       | (if has("toolId") then .toolId="<X>" else . end)
                       | (if has("content") then .content="<C>" else . end)
                       | (if has("result") then .result="<R>" else . end)
                   else . end)
                | {keys: (keys|sort), types: (to_entries | map({(.key): (.value|type)}))}' \
        > /tmp/sse-after-refactor/${NAME}.shape

    # diff against pre-refactor
    diff /tmp/sse-pre-refactor/${NAME}.events /tmp/sse-after-refactor/${NAME}.events \
        || { echo "FAIL: ${NAME} event types diverged"; exit 1; }
    diff /tmp/sse-pre-refactor/${NAME}.shape /tmp/sse-after-refactor/${NAME}.shape \
        || { echo "FAIL: ${NAME} payload shape diverged"; exit 1; }
done

# verify recorded sha256 matches pre-refactor baseline
sha256sum /tmp/sse-pre-refactor/*.shape | diff - docs/plans/2026-04/sse-baseline-2026-04-26.sha256
```

### A.7.3 sender_type 分布

```bash
psql -h localhost -U ghj -d aura_boot -P pager=off \
  -c "SELECT sender_type, count(*) FROM ab_im_message GROUP BY sender_type ORDER BY sender_type;" \
  > /tmp/sender-type-after.txt
diff /tmp/sender-type-baseline.txt /tmp/sender-type-after.txt
```

### A.7.4 metrics

```bash
curl -s http://localhost:6443/actuator/prometheus | grep -E 'aurabot_turn_(begin|end)' | head -3
# 预期非 0
```

### A.7.5 worktree 前端 E2E（v3 review P1.9 fix）

```bash
cd /Users/ghj/work/auraboot-worktrees/conv-turn-svc-phase-a/web-admin
[ -d node_modules ] || pnpm install
NO_PROXY=localhost npx playwright test tests/e2e/aurabot/ 2>&1 | tee /tmp/pw-aurabot-after.log
```

### A.7.6 后端集成测试

```bash
cd /Users/ghj/work/auraboot-worktrees/conv-turn-svc-phase-a/platform
./gradlew :test --tests "com.auraboot.framework.integration.agent.*" \
                --tests "com.auraboot.framework.aurabot.*" \
                --tests "com.auraboot.framework.conversation.*" -q
```

### Acceptance criteria

| 检查 | 必过 |
|------|------|
| 后端身份属 worktree | ✅ |
| 4 scenario SSE event 序列 + payload shape diff vs pre-refactor = 0 | ✅ |
| sender_type 分布 identical | ✅ |
| `aurabot_turn_*` metrics > 0 | ✅ |
| worktree 前端 E2E pass = baseline | ✅ |
| 后端 3 组 service 测试 0 fail | ✅ |
| 故意 doStreamChatInnerSinkAware 抛 → finalizeTurn 仍调到（单测） | ✅ |
| Named agent (agentCode!=aurabot) E2E 仍正常 | ✅ |

---

## 11. PR 划分

| PR | commits | 风险 | review 重点 |
|----|---------|------|------------|
| PR-1 | A.1（已 push） | 低 | SPI 形态 |
| PR-2 | A.2 + A.2b | 低 | SPI 微调 + SseResponseSink + baseline lock |
| **PR-3** | **A.3** | **高** | chatService split / 13 send 替换 / SSE pre-baseline diff / grep gate |
| PR-4 | A.4 + A.5 + A.6 | 中 | runTurn 真实现 + controller cutover + observeOnly 注入 |
| PR-5 | A.7 验收报告 | — | 不改代码 |

总 5 个 PR（含 A.1）。

---

## 12. 风险

| 风险 | 缓解 |
|------|------|
| chatService split 漏 send* call site | A.3 grep gate 强化（含 helper 函数模式）|
| ChatSseContext.setEmitter ThreadLocal 兼容 | SseResponseSink 暴露 getEmitter() package-private |
| Named agent 路径 controller 双 async | 显式注释；Phase B+ 单独 sub-design 时收敛 |
| pre-refactor baseline 含 LLM 非确定输出 | shape diff 用 jq walk 规范化 traceId/toolId/content/result |
| TurnRequest.legacyRequest 在 Phase A 后仍存在 | Phase B 把 ChatRequest 字段拆进 TurnRequest 真 record，删 legacyRequest |

---

## 13. 评审通过判据

1. owner 已确认 Q-A.4-Q-A.7（v4 已基于此）
2. §3 pre-condition 全过
3. A.2b SSE baseline 已锁（`/tmp/sse-pre-refactor/*` + sha256 commit 入仓）
4. 同意 A.3 用"chatService split sync core + legacy wrapper"作为 0 行为变更路径

满足 → 进 A.2 实施。

---

## CHANGELOG

- 2026-04-26 v1 初始化（被否决：6 P0/P1 + 4 P2）
- 2026-04-26 v2 重写：scope 收敛"不重构"+ shadow endpoint
- 2026-04-26 v3 反思：长期演进推翻 v2，重构 aurabot 主路径但保留 shadow
- 2026-04-26 v4 owner 拍板 Q-A.4=A'：sync core + async only at controller boundary
  - chatService split: 新 sync core executeAuraBotTurn(...): TurnOutcome
  - runTurn 真实现 SPI（不 cast）
  - cut over /chat/stream（无 shadow）
  - TurnRequest 携带原 ChatRequest（legacyRequest 字段）
  - PendingConfirmation 真 suspendTurn 路径（partial 非空才 persist）
  - SSE pre-refactor baseline 锁定后才开 A.3
  - 强化 grep gate（含 helper 函数模式 + 白名单区）
  - worktree 前端 E2E
  - SPI traceId 在 onDone/onError 参数（非 sink 构造时）
  - 设计 v3.3 §3.4 同步更新（A.2 commit 含）
