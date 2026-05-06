# 2026-05-06 ACP Capability Follow-up — Parallel Batch Design

## Background

2026-04-30 → 2026-05-06 一批 ACP P0/P1 capability(12 commits `164fb5ef → 94b97ad6`)落地后,
`auraboot/docs/backlog/2026-05-06-acp-p0-p1-followups.md` 列出 26 项 followup。
本批次挑 5 项最小耦合、全 OSS、各 ~0.5d 的并行任务,把已 merge 的 ACP 能力**补圆**(测试 + 指标 + seed)。

参考:`auraboot/docs/backlog/2026-05-06-acp-p0-p1-followups.md`(尤其 §A、§B、§F)。

## Slate(5 个 task,并行执行)

| # | GAP | 类型 | 主要文件域 | 估时 | 优先级 |
|---|-----|------|-----------|------|--------|
| T1 | A.1 ParentJoinService / delegate_task / SubAgentRunner IT | 后端 IT | `platform/src/test/java/.../agent/` | 0.5–1d | P1 |
| T2 | A.2 Replay UI Playwright E2E | 前端 E2E | `web-admin/tests/e2e/aurabot/admin-agent-runs.spec.ts` | 0.5d | P1 |
| T3 | A.4 Workflow LLM action node E2E | 前端 E2E | `web-admin/tests/e2e/automation/llm-call-node.spec.ts` | 0.5d | P1 |
| T4 | F.2 AgentTemplateSeeder execution_config | 后端 seed + IT | `AgentTemplateSeeder.java` + `AgentTemplateSeederIntegrationTest` | 0.5d | P3 |
| T5 | B.3-3 Anthropic prompt cache hit ratio metric | 后端 Micrometer + IT | `AnthropicLlmProvider.java` + 配套 IT | 0.5d | P1.x |

> 5 task 落点完全分离:2 后端 IT 文件域(`agent/` vs `bootstrap/seeder/` vs `agent/provider/`)+ 2 前端 spec 文件 + 1 后端 metric 注入点 → 零 git 冲突。

## 每个 Task 的目标 + 验收

### T1 — A.1 ParentJoinService / delegate_task / SubAgentRunner IT

**目标**:为 `3057ec40`(SubAgentRunner async wiring + ParentJoinService event listener + `platform.delegate_task` 工具)补端到端 IT。当前仅靠回归测试覆盖,spawn → execute → terminal → ChildRunCompletedEvent 链路无显式断言。

**主源码(只读参考,不改)**
- `platform/src/main/java/com/auraboot/framework/agent/service/ParentJoinService.java`
- `platform/src/main/java/com/auraboot/framework/agent/service/SubAgentRunner.java`
- `platform/src/main/java/com/auraboot/framework/agent/service/ChildRunCompletedEvent.java`
- `platform/src/main/java/com/auraboot/framework/agent/provider/PlatformToolProvider.java`(含 `platform.delegate_task` 注册)
- 已有的 `platform/src/test/java/com/auraboot/framework/integration/agent/SubAgentRunnerIntegrationTest.java`(读完确认覆盖了什么、没覆盖什么,避免重复)

**新增 IT 文件**
- `platform/src/test/java/com/auraboot/framework/integration/agent/ParentJoinServiceIntegrationTest.java`
- `platform/src/test/java/com/auraboot/framework/integration/agent/DelegateTaskToolIntegrationTest.java`
- `platform/src/test/java/com/auraboot/framework/integration/agent/SubAgentRunnerExecutionIntegrationTest.java`

**用例最小集**(每文件 ≥3 用例,真 PG + 真 Redis,继承 `BaseIntegrationTest`)
- `ParentJoinServiceIntegrationTest`
  - seed 父+子 run → publish `SessionEndedEvent` for child → 用 `@RecordApplicationEvents` 断言 `ChildRunCompletedEvent` 被发出 + payload 字段值正确(parentRunId / childRunId / terminalStatus)
  - 子 run cancel terminal → 父监听仍触发(verify 终态枚举至少含 succeeded/failed/cancelled)
  - 跨租户子 run terminal → 父 listener 不接收(strict tenant boundary)
- `DelegateTaskToolIntegrationTest`
  - mock LLM 返回 `platform.delegate_task` tool call → 断言 `ab_agent_run` 出现 `subtask_origin='delegate_task'` 子 run + ApprovalGate 触发(因 L2 + requiresApproval=true)
  - 入参缺失关键字段 → 工具返回结构化错误,父 run 不创建子 run
- `SubAgentRunnerExecutionIntegrationTest`
  - spawn → 用 `CountDownLatch` + `ChildRunCompletedEvent` listener 等异步 → 断言子 run 状态从 `running` → `succeeded`,`token_usage` / `cost` 字段非空
  - 跨租户 spawn 抛 `IllegalStateException`(memory `feedback_subagent_worktree_verify` 红线)

**验收命令**
```bash
LOG=/tmp/pw-acp-t1-$(date +%Y%m%d-%H%M%S).log
./gradlew :platform:test \
  --tests "com.auraboot.framework.integration.agent.ParentJoinServiceIntegrationTest" \
  --tests "com.auraboot.framework.integration.agent.DelegateTaskToolIntegrationTest" \
  --tests "com.auraboot.framework.integration.agent.SubAgentRunnerExecutionIntegrationTest" \
  2>&1 | tee "$LOG"
```
全部 PASS,日志末尾 `BUILD SUCCESSFUL`。

---

### T2 — A.2 Replay UI Playwright E2E

**目标**:`94b97ad6` 落地的 admin agent runs 页面(/admin/agent-runs + drawer)目前只有 vitest(`web-admin/app/plugins/core-aurabot/__tests__/AgentRunsPage.test.tsx`),无 Playwright E2E。补 14 维度中 D6(数据渲染) + D8(操作反馈)关键路径。

**主源码(只读参考)**
- `web-admin/app/plugins/core-aurabot/services/agentRunsApi.ts`(前端 API 客户端)
- `web-admin/app/plugins/core-aurabot/resources.ts`(页面注册)
- `web-admin/tests/e2e/templates/thr-leave-request-lifecycle.spec.ts`(金标准模板)

**新增 spec**:`web-admin/tests/e2e/aurabot/admin-agent-runs.spec.ts`

**最小覆盖**
- 登录 → 从侧边栏菜单导航到 `/admin/agent-runs`(**禁 `page.goto` 直达**)
- 列表渲染:断言 ≥1 行;某行 status / cost / duration 字段对应 API 返回的具体值(用 `await page.request.get('/api/admin/agent-runs')` 拿真值后断言 cell text)
- 行点击 → drawer 打开 → 子区域(actions / interrupts / child runs)各显示具体 id/text 而非仅 `toBeVisible`
- 关键操作:若有 "重放/导出/复制 id" 按钮,点击后断言 toast / clipboard 状态(取存在的至少 1 个)
- 列表为空 fallback path(若 fixture 难造可在另一 describe 用 `page.route` mock 但要在 spec 头部注释 "exception: D7 empty-state via mock")

**禁项**(违反即返工)
- `page.goto('/admin/agent-runs')` 直达
- `waitForTimeout` / `afterAll` 清理
- spec body 内 `page.request.put/post` 替代 UI 操作(memory `feedback_no_fake_100_percent_claim`)
- 仅 `toBeVisible` 不带具体值断言

**验收**
```bash
LOG=/tmp/pw-acp-t2-$(date +%Y%m%d-%H%M%S).log
bash scripts/oss-test.sh tests/e2e/aurabot/admin-agent-runs.spec.ts -- --workers=4 2>&1 | tee "$LOG"
```
0 failed,无 retry-mask(retries 默认即可)。

---

### T3 — A.4 Workflow LLM action node E2E

**目标**:`49503a3f` 落地的 Automation `action-llm-call` 仅 11 个 LlmCallExecutorTest unit/IT 覆盖,无 trigger → action-llm-call → 下游节点用 `${llmOutput}` 链路 E2E。

**主源码(只读参考)**
- `platform/src/main/java/com/auraboot/framework/automation/executor/impl/LlmCallExecutor.java`
- `web-admin/tests/e2e/automation/automation-designer.spec.ts`(模板风格)

**新增 spec**:`web-admin/tests/e2e/automation/llm-call-node.spec.ts`

**最小覆盖**
- 登录 → 侧边栏 → Automation 编辑器
- 新建/打开一个 automation,拖入 trigger + LLM 节点 + 一个文本输出节点
- LLM 节点配 prompt(包含 `${trigger.someField}` 插值)+ outputVariableName(如 `llmOutput`)
- 下游节点 body 引用 `${llmOutput}`
- 触发测试运行(designer 内置 dry-run / 测试入口),断言下游节点收到的文本包含 LLM 返回片段(用 stub LLM provider 或预设可控输入)
- 保存 automation 后刷新页面,断言节点配置持久化(LLM 节点的 prompt / outputVariableName 仍在)

**禁项**(同 T2)+ 不允许"配置存在即通过":必须看到 dry-run/preview 实际输出。

**验收**
```bash
LOG=/tmp/pw-acp-t3-$(date +%Y%m%d-%H%M%S).log
bash scripts/oss-test.sh tests/e2e/automation/llm-call-node.spec.ts -- --workers=4 2>&1 | tee "$LOG"
```

---

### T4 — F.2 AgentTemplateSeeder execution_config(thinking 默认开)

**目标**:`a4f7323a` 加 `ab_agent_definition.execution_config JSONB` 列,但 `AgentTemplateSeeder` 5 个内置 skill INSERT SQL 仍写默认 `{}`。需要把 `report_analysis`(多跳推理 skill)opt-in 默认开 thinking。

**主源码(改)**
- `platform/src/main/java/com/auraboot/framework/application/bootstrap/seeder/AgentTemplateSeeder.java`(读完先确认 5 个 skill 实际名称:`approval_workflow` / `data_entry_assistant` / `report_analysis` / `crm_operations` / `ops_inspector`,不要照搬 plan 里幻觉的 `grounding_intent_parse`)

**修改要点**
- `report_analysis` skill INSERT 时 `execution_config = '{"thinking_enabled":true,"thinking_budget_tokens":8000}'::jsonb`
- 其余 4 个保持 `{}`
- 若 seeder 是幂等 upsert,确保已 seed 环境再跑会更新已存在的 row(否则需要 reset 验证)

**新增/扩 IT**
- `platform/src/test/java/com/auraboot/framework/integration/agent/AgentTemplateSeederIntegrationTest.java`(已存在,扩用例)
- 新增 `seed_report_analysis_default_thinking_config` 用例:run seeder → 查 `ab_agent_skill where skill_code='report_analysis'`,断言 `execution_config->>'thinking_enabled' = 'true'` 且 budget = 8000
- 至少 1 个反例用例:`approval_workflow` execution_config 仍是空 `{}`(不被误改)

**验收**
```bash
LOG=/tmp/pw-acp-t4-$(date +%Y%m%d-%H%M%S).log
./gradlew :platform:test \
  --tests "com.auraboot.framework.integration.agent.AgentTemplateSeederIntegrationTest" \
  2>&1 | tee "$LOG"
```
**禁项**:仅看编译通过就声称"seed 正确",必须 IT 真实查 DB 字段值。

---

### T5 — B.3-3 Anthropic prompt cache hit ratio metric

**目标**:`164fb5ef` 落地"system + 最后一个 tool"的 ephemeral cache 已 5 月初上线,但 DEBUG 日志只 print tools.size + last tool name,**无 hit/miss counter**。补 Micrometer counter 让 cache 命中率可观测。

**主源码(改)**
- `platform/src/main/java/com/auraboot/framework/agent/provider/AnthropicLlmProvider.java`(在响应解析、识别 cache_creation_input_tokens / cache_read_input_tokens 字段处 inc counter)

**注入点**
- 解析 Anthropic 响应 `usage.cache_read_input_tokens > 0` → `aura_agent_anthropic_cache_hit_total.increment()`
- `usage.cache_creation_input_tokens > 0 && cache_read_input_tokens == 0` → `aura_agent_anthropic_cache_miss_total.increment()`
- counter 名沿用现有 `aura_agent_*` 前缀 + Micrometer `Counter` API + 必要 tag(`provider="anthropic"`、`model=`)
- 不破坏现有 metric 命名空间;新 counter bean 通过 `MeterRegistry` 注入 — 首次调用 lazy register 即可

**新增 IT**:`AnthropicLlmProviderCacheMetricIntegrationTest`
- stub WebClient 返 fake Anthropic response(`cache_read_input_tokens=512`)→ provider.chat → 从 `MeterRegistry.find("aura_agent_anthropic_cache_hit_total")` 取 `count()` 断言 == 1
- 第二次响应 `cache_creation_input_tokens=512, cache_read_input_tokens=0` → miss counter == 1
- 第三次响应无 cache 字段 → 两个 counter 都不变

**验收**
```bash
LOG=/tmp/pw-acp-t5-$(date +%Y%m%d-%H%M%S).log
./gradlew :platform:test \
  --tests "com.auraboot.framework.agent.provider.AnthropicLlmProviderCacheMetricIntegrationTest" \
  2>&1 | tee "$LOG"
# 启动 platform 后:
# curl http://localhost:8080/actuator/metrics/aura_agent_anthropic_cache_hit_total
```

---

## Worktree 策略

每个 task 独立 worktree,避免 5 agent 在同一工作树串改:

```
/Users/ghj/work/auraboot-worktrees/
  acp-followup-a1/   (branch feat/acp-followup-a1-parent-join-it)
  acp-followup-a2/   (branch feat/acp-followup-a2-replay-ui-e2e)
  acp-followup-a4/   (branch feat/acp-followup-a4-workflow-llm-e2e)
  acp-followup-f2/   (branch feat/acp-followup-f2-seeder-thinking)
  acp-followup-b3/   (branch feat/acp-followup-b3-cache-hit-metric)
```

5 个 worktree 都基于 OSS 仓 `/Users/ghj/work/auraboot/auraboot/` `main`(当前 HEAD `91d64d74`)。
- 主进程(controller)用 `git worktree add` 创建,然后用 `Agent` 的 `isolation: "worktree"` 或在 prompt 中指定 cwd
- agent 必须先 `cd` 到 worktree 路径并 `pwd` + `git branch --show-current` 自检后才动手

## Subagent prompt 公约(每个 agent 必读 + 必须复述自检)

每个 agent 在工作开始前必须确认/做到:

1. **位置自检** — `pwd` 必须输出本 task 对应 worktree 路径;`git branch --show-current` 必须是本 task 分支。不一致 → 立即停止并报告
2. **Standards 红线** — 后端 IT 必须真 PG + 真 Redis(`BaseIntegrationTest`),禁 H2 / `@Mock` DB / mock Redis(`docs/standards/core/testing-backend.md`)
3. **E2E 红线** — 必须从侧边栏菜单导航,禁 `page.goto` 直达 / `waitForTimeout` / `afterAll` 清理(`docs/standards/core/testing-e2e-web.md`、memory `feedback_oss_e2e_loop_discipline`)
4. **Workers** — Playwright 跑 `--workers=4` 上限(memory `feedback_playwright_workers_dev_hmr`)
5. **测试日志** — 全程 `tee /tmp/pw-acp-{task}-$(date +%Y%m%d-%H%M%S).log`(memory `feedback_save_test_output`)
6. **诚实** — 测试 PASS 才声称完成;如果遇到 product gap,在 PR 描述列 backlog,不用 `test.skip` / threshold-loosen / PUT-API-bypass / retries:N 兜底(memory `feedback_no_fake_100_percent_claim`)
7. **reset-and-init** — T4 改 seeder 后必须 `bash scripts/oss-reset-and-init.sh`(或在 IT 内部独立验证),禁直接 SQL `ALTER` 修 schema(memory `feedback_subagent_worktree_verify`)
8. **commit 规范** — 英文 imperative,无 Co-Authored-By(memory `feedback_no_coauthored`),不 amend 已 push commit
9. **完成定义** — 测试 PASS + commit 推到分支 + 给出 PR 标题草稿 + 日志路径 + 反向证据(对照 §6 红线 audit)
10. **不要做的事** — 不创建 HANDOVER 文件(memory `feedback_no_handover_same_session`),不上手 unrelated 重构(超出本 task 范围),不修改不在本 task 文件域内的代码

## Risks

| Risk | 缓解 |
|------|------|
| T1 BaseIntegrationTest 需 PG + Redis 已起 | agent 跑测试前 `psql` / `redis-cli ping` 自检;若未起,调 `bash scripts/oss-reset-and-init.sh` 或显式报告阻塞 |
| T2/T3 Playwright HMR 抖动 | `--workers=4`(已在公约 §4),失败先看 dev server log 再判断 code 问题 |
| T4 seeder 是 idempotent vs insert-once | agent 读 `AgentTemplateSeeder.java` 确认逻辑,再决定测试是否需要 reset DB |
| T5 Anthropic response 字段名变化 | agent 读 `AnthropicLlmProvider` 现有解析路径,`cache_read_input_tokens` 名以仓内代码为准 |
| 5 worktree 都从同一 main 创 | 各自分支,不会冲突;但若 task 顺手碰共享文件(如 `build.gradle.kts`)需在 PR 描述标注 |
| Replay UI E2E 数据空 | 若 fixture 难造,T2 spec 头部注释 "exception: empty-state via page.route mock",其余 happy-path 仍走 UI |

## Acceptance(整批)

- 5 个 PR(分支已 push)各带:
  - 测试 PASS 日志路径 `/tmp/pw-acp-{task}-*.log`
  - PR 标题草稿(英文 + Conventional Commits 风格)
  - 一段反向证据(对照公约 §6 audit)
- 5 个 task 没有相互改动重叠(controller 在汇总阶段 `git diff main..feat/acp-followup-*` 确认)
- 后续合并主仓由 owner 决定(本 batch 不直接合 main)

## 不在本批次范围

明确不在(避免 scope creep):
- A.3 Vision E2E smoke(需真 Anthropic key,人工跑)
- B.1/B.2 OpenAI 兼容 + 国产 provider thinking 适配
- C.1 父 run 同步等待子 run(blocking join)— 1 周量级
- D.1 thinking 内容持久化到 ab_im_message — 1-2 天 + DB schema 变更,需独立设计
- D.3 child_aggregate_cost rollup — 2-3 天 + DB schema 变更
- F.1 gradle-wrapper.jar git tracking — 一行 `git add -f`,不值一个 worktree

## References

- Backlog 文件:`auraboot/docs/backlog/2026-05-06-acp-p0-p1-followups.md`
- 后端 IT 红线:`auraboot-enterprise/docs/standards/core/testing-backend.md`
- E2E 红线:`auraboot-enterprise/docs/standards/core/testing-e2e-web.md`
- 金标准 spec:`auraboot/web-admin/tests/e2e/templates/thr-leave-request-lifecycle.spec.ts`
- E2E 真实性自审:`/e2e-truth` skill
