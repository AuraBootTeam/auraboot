# ACP P0/P1 会话后续 backlog(2026-05-06)

本文件记录 2026-04-30 ~ 2026-05-06 一批 ACP capability 落地(P0 5 项 + P1 6 项,12 个 commit `164fb5ef → 94b97ad6`)中**没有随 main 合并落地**的遗留项。每项 what / why / 建议 owner 或触发条件,避免散落在 commit message。

相关 commit:
- `164fb5ef` P0-1 Anthropic Prompt Caching
- `432144eb` P0-6 Multi-Agent Spawn (SubAgentRunner + 4 Blocker fix)
- `1b827e4f` P0-5 Parallel Tool Calls
- `a4f7323a` P0-2 Extended Thinking + SSE
- `3cf0565d` Parallel Tool Micrometer 指标
- `36d0b8f5` IntentParser thinking via LlmClient.ChatOptions
- `229640ac` Anthropic Batch API
- `04d50e0d` Vision 多模态
- `49503a3f` Workflow LLM action node
- `3057ec40` SubAgentRunner async exec + ParentJoinService + delegate_task
- `8224e69a` Replay UI backend REST
- `94b97ad6` Replay UI frontend page

设计/分析底稿:`/Users/ghj/.claude/plans/ai-agent-gentle-balloon.md`

---

## A. 测试缺口(高优先,P1)

### A.1 ParentJoinService / delegate_task 端到端集成测试

**What**:`3057ec40` 落地了 SubAgentRunner async wiring + ParentJoinService event listener + `platform.delegate_task` LLM 工具,但**新功能仅靠现有 30 个 SubAgentRunner / Interrupt / PlatformToolProvider 回归测试覆盖**(都 PASS 但只证明没退化)。spawn → execute → terminal → ChildRunCompletedEvent 的端到端链路无自动化测试。

**Why 未做**:agent 在写测试前耗尽 rate limit,commit message 已诚实标注 "Out of scope (follow-up PR)"。

**建议**:
- `ParentJoinServiceIntegrationTest`(BaseIntegrationTest):seed 父+子 run,publish SessionEndedEvent for child,断言 ChildRunCompletedEvent 被发(用 `@RecordApplicationEvents`)
- `DelegateTaskToolIntegrationTest`:mock LLM 调 `platform.delegate_task`,断言 ab_agent_run 出现 `subtask_origin='delegate_task'` 子 run + ApprovalGate 被触发
- `SubAgentRunnerExecutionIntegrationTest`:spawn 后等异步,断言子 run 进入 running → succeeded(用 CountDownLatch + ChildRunCompletedEvent 监听)

**预估**:0.5-1 天

---

### A.2 Replay UI Playwright E2E spec

**What**:`8224e69a` + `94b97ad6` 落地 backend REST + 前端页面,vitest 4 case 通过,但**无 Playwright E2E 验证侧边栏 → /admin/agent-runs → row click → drawer 全链路**。

**Why**:MVP 范围内显式 deferred。

**建议**:`web-admin/tests/e2e/aurabot/admin-agent-runs.spec.ts`,跑 14 维度的"D6 数据渲染" + "D8 操作反馈"。

**预估**:0.5 天

---

### A.3 Vision E2E smoke

**What**:`04d50e0d` 落地多模态 image content blocks,unit/integration test 用 stub WebClient 验证序列化,但**无真 Anthropic key 跑 vision 端到端**。

**Why**:E2E 需要真 API key,留 manual smoke。

**建议**:OPS 在 staging 用真 key 跑一次:截图 → AuraBot 上传 → "describe this image" → 确认返回是图片描述而非"我看不到附件"。

**预估**:5 分钟人工

---

### A.4 Workflow LLM action node E2E

**What**:`49503a3f` 落地 Automation `action-llm-call`,11 个 LlmCallExecutorTest PASS。但**无 Playwright E2E 验证 trigger → action-llm-call → 下游节点用 ${llmOutput}**。

**建议**:`web-admin/tests/e2e/automation/llm-call-node.spec.ts`,在 Automation 编辑页拖 LLM 节点 + 配置 prompt + 触发测试。

**预估**:0.5 天

---

## B. 协议/适配层增量(中优先,P1.x)

### B.1 OpenAI Batch / parallel function call / vision 适配

**What**:本会话 P0-4 / P0-5 / P1 vision 都是 **Anthropic-only** 落地。OpenAI 兼容 provider 走显式拒绝路径(throw IllegalArgumentException)。

**Why**:Anthropic 路径已是平台主选,OpenAI 兼容仅作 fallback;且 OpenAI 的 batch / vision / parallel 协议形状不同,适配需要单独工程。

**建议 owner**:任何要求 OpenAI / 国产模型支持 batch / vision / parallel 的 ticket(目前 0)。
- OpenAI Batch:`/v1/batches` 协议,JSONL 格式不同
- OpenAI parallel function call:协议本身已支持,需 adapter
- OpenAI vision:`image_url` 与 Anthropic `image.source` 形状不同

**预估**:每项 1 周

---

### B.2 国产 provider thinking 适配

**What**:`a4f7323a` 透传 Anthropic `thinking` 字段,OpenAI-compatible 显式 drop with debug log。**国产模型(deepseek/qwen 等)thinking 协议各异**(deepseek-reasoner 用 `reasoning_content`,glm-4 用 `thinking_content`),未适配。

**建议**:每个国产 provider 自带 reasoner endpoint 时单独 PR 适配。

**预估**:1-2 天/家

---

### B.3 Anthropic prompt caching 进阶

**What**:`164fb5ef` 落地"system + 最后一个 tool"的 ephemeral cache。

**Deferred**:
- **system prompt 多段缓存**:稳定段(租户级 prefix)+ 动态段(用户级 suffix)分离,提升命中率(cache 最低粒度 1024 tokens)
- **anthropic-version 1h cache 升级**:目前 5min TTL,Anthropic 已支持 1h beta(`anthropic-beta: extended-cache-ttl-2025-04-11` header),长会话可省更多
- **DEBUG hit-rate metric**:目前 DEBUG 日志打印 tools.size + last tool name,但**没有 cache hit ratio counter**。Micrometer 加 `aura_agent_anthropic_cache_hit_total` / `_miss_total`

**预估**:2-3 天

---

### B.4 IntentParser via LlmClient — 待 LlmClient.ChatOptions 全面渗透

**What**:`36d0b8f5` 扩展 LlmClient 加 ChatOptions 重载,IntentParser query>200 字符自动开启 thinking。但 **LlmClient 只有 IntentParser 一个高层 caller**,其他 LLM 入口仍走 `LlmProvider.chat(LlmChatRequest)` 底层。

**建议触发**:发现某 service 用 `LlmClient.chat(String)` 但需要 thinking/maxTokens 控制时,扩展该处。

---

## C. 多 agent 进阶(P2)

### C.1 父 run 同步等待子 run(blocking join)

**What**:`3057ec40` 实现 fire-and-forget 异步 + ChildRunCompletedEvent 通知。但**父 run 不能等"子 run 完成后再继续"** — 这是 DSL workflow / 多步推理常见模式。

**建议**:`ParentJoinService.joinChildRun(parentRunId, childRunId, timeoutMs)` 方法,基于 `CountDownLatch` + event listener;超时抛 `JoinTimeoutException`。

**预估**:1 周

---

### C.2 跨租户 ACL 细化

**What**:`SubAgentRunner` 当前 strict refuse cross-tenant spawn(`IllegalStateException`)。但企业内可能有"跨租户 supervisor agent"场景(平台租户的 system agent 派子 run 给业务租户的 agent)。

**建议**:加 `cross-tenant ACL 表`(默认 deny);只有显式 grant 的 (parent_tenant, child_tenant) 对才允许。

**预估**:1-2 周(含权限模型设计)

---

### C.3 Inbox/UI 父子 run 树形渲染

**What**:Replay UI(`94b97ad6`)在 Drawer 里显示 Child Runs 但是**平铺 list**,无树形递归。

**建议**:用 react-tree 或 anteater hierarchy 组件;深度限制 5 层避免无限递归。

**预估**:0.5-1 天

---

### C.4 LLM-callable delegate_task 工具的实战集成

**What**:`platform.delegate_task` 已注册(L2,requiresApproval=true),LLM 可主动派子 agent。但**主流 agent skill 模板(approval_workflow / data_entry_assistant 等)的 system prompt 没教 LLM 用这个工具**。

**建议**:在 5 个内置 skill 的 system prompt 加一段说明:"你可以用 platform.delegate_task 把可独立的子任务派给子 agent,父 agent 不阻塞。"

**预估**:0.5 天

---

## D. 持久化与可观测增量(P1.x)

### D.1 thinking 内容持久化到 ab_im_message

**What**:`a4f7323a` 通过 SSE 实时推送 thinking blocks 给前端,但**没存进 ab_im_message**。刷新页面后看不到上次的推理过程。

**建议**:加 `ab_im_message.thinking_content TEXT` + `thinking_signature TEXT` 列;ChatToolExecutor / AuraBotChatService 在 turn 终止时落库。

**预估**:1-2 天

---

### D.2 LlmChatResponse.warnings 流到 SSE / UI

**What**:`a4f7323a` 加 `LlmChatResponse.warnings: List<String>`,目前只在 `max_tokens 自动扩展` 时填一条 + log warn。**没透到 SSE event / 前端 UI**。

**建议**:SSE 加 `event: warning` 类型,前端 toast 显示。

**预估**:1 天

---

### D.3 child_aggregate_cost rollup

**What**:父 run terminal 时,子 run 还在跑,其 cost 永远不会回灌父 run。财务/配额账目从一开始就漏。

**建议**:`ab_agent_run` 加 `child_aggregate_cost DECIMAL(10,6)` + `child_aggregate_tokens INTEGER`;ChildRunCompletedEvent listener 反向更新父 run。

**预估**:2-3 天

---

### D.4 Anthropic prompt cache hit ratio metric

**What**:见 B.3 第 3 项。

---

### D.5 ShadowRun 比对页(Replay UI 扩展)

**What**:`94b97ad6` 是 run/action/interrupt 视图,**没有展示 ShadowRun 比对**(skill draft vs production run 的输出 diff)。Learning Loop 闭环已在后端跑,前端零 visibility。

**建议**:`/admin/agent-runs/shadow-runs` 独立页;按 draft 分组,显示 fidelity_match_rate / cost_delta / output_match_rate。

**预估**:1 周

---

## E. 工作流 LLM 节点增量(P1.x)

### E.1 Streaming response in workflow context

**What**:`49503a3f` LlmCallExecutor 同步 chat 一次返完整响应。**长响应或代码生成场景体感差**。

**建议**:Automation 引擎加 streaming 支持(若现有节点都同步则推迟到引擎升级)。

**预估**:1 周(含引擎改动)

---

### E.2 Workflow 节点内 vision 输入

**What**:`action-llm-call` 当前只接 text prompt + ${var} 插值。**不能接图片**(form 上传 image → workflow LLM 节点描述 → next node 路由)。

**建议**:configSchema 加 `imageVariableNames: List<String>`(从 trigger 上下文取 base64 image);执行时构造 multi-modal LlmChatRequest。

**预估**:2-3 天

---

### E.3 Workflow 多轮对话

**What**:每次 action-llm-call 都是单轮 chat,无法做"先生成草稿 → 用户改 → 再 refine"流程。

**建议**:暂不做(复杂度高,可用 manual approval node + 多个 action-llm-call 模拟)。

---

## F. 文档与 dev 体验(P3)

### F.1 gradle-wrapper.jar 加入 git tracking

**What**:每次新建 worktree 都要从 main 复制 `platform/gradle/wrapper/gradle-wrapper.jar`(每个本会话 agent 都做了一次)。该 jar 是**git 不跟踪的**,应纳入版本控制。

**建议**:`git add -f platform/gradle/wrapper/gradle-wrapper.jar` 一次性归档。

**预估**:5 分钟

---

### F.2 AgentTemplateSeeder INSERT SQL 升级 execution_config

**What**:`a4f7323a` 加 `ab_agent_definition.execution_config JSONB` 列(`P0-2 fix B1`)。但 **AgentTemplateSeeder 的 5 个内置 skill INSERT SQL 没写这列**,seed 出来的 skill execution_config 都是默认 `{}`。

**Why 未做**:plan 引用的 5 个 skill 名(`grounding_intent_parse` 等)是 plan agent 幻觉,seeder 实际有的是 `approval_workflow` / `data_entry_assistant` / `report_analysis` / `crm_operations` / `ops_inspector`。

**建议**:把 `report_analysis`(多跳推理 skill)opt-in 默认开 thinking_enabled:`UPDATE ab_agent_skill SET execution_config = '{"thinking_enabled":true,"thinking_budget_tokens":8000}'::jsonb WHERE skill_code='report_analysis'`。配套加测试。

**预估**:0.5 天

---

### F.3 AiField 加图片输入(Vision 扩展)

**What**:`04d50e0d` 在 AuraBot chat 加了 paperclip 上传,但**通用 form AiField 组件没加**。意味着"在 form 字段旁点 AI 自动填 → 上传发票截图 OCR 自动填"无法工作。

**建议**:`web-admin/app/ui/smart/form/AiField.tsx` 加可选 image input mode + 沿用 base64 + AnthropicLlmProvider vision。

**预估**:1-2 天

---

### F.4 拖拽上传(Vision UX 增强)

**What**:Vision 当前只支持 paperclip 点击。

**建议**:HTML5 drag/drop API 接 chat input area。低优先 UX 增强。

---

## G. 不做(显式拒绝项)

承接 plan 文件 §⛔:

- **正面对标 Claude Code / Cursor / Devin**:不做(12-18 人月,与 vertical AI workspace 路线不符)
- **Code Agent / IDE 级编辑**:不做(同上;`platform.create_model` 这类结构化 DSL 生成保留)

---

## 总计

- A 测试缺口:4 项,~3 天
- B 协议适配:4 项,~3-4 周
- C 多 agent 进阶(P2):4 项,~3-4 周
- D 持久化与可观测:5 项,~2-3 周
- E Workflow LLM 增量:3 项,~2 周
- F 文档/dev:4 项,~3-4 天
- G 不做:2 项

**优先建议**:A.1 + A.2(测试缺口)+ F.2(seeder)+ B.3 第 3 项(cache metric)= 共 2-3 天工作量,把当前已 merged 的能力"补圆"再考虑后续。
