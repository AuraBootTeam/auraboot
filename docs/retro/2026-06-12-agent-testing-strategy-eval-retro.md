---
type: retro
status: closed
created: 2026-06-12
distilled_to: [docs/backlog/2026-06-12-agent-testing-strategy-and-eval-loop.md]
---

# Retro — Agent 测试策略 eval 回路 endgame(2026-06-12)

> `/aura-endgame` 全自动推进「agent 测试策略」文档剩余全部任务。设计/gap/计划文档 =
> `docs/backlog/2026-06-12-agent-testing-strategy-and-eval-loop.md`(P0–P4 由该文档承载)。
> 本次为 P5 复核 + P6 复盘收口。

## 交付总览(7 PR,全 merged)

| 项 | 交付 | 测试 | PR |
|----|------|------|----|
| ① L3 eval 回路 | `CapabilityEvalRegressionGate`(5 维绝对界+滚动中位基线)+ `ScheduledCapabilityEvalJob`(config-gated 默认关) | 12 | #610 |
| ② L2 录制回放(OpenAI) | `OpenAiCompatibleLlmProviderRecordReplayTest`(JDK HttpServer 回环,真请求序列化+tool-call 解析) | 4 | #611 |
| ② L2(Anthropic sync)+ unify | `AnthropicLlmProviderRecordReplayTest` + `checkRegression` 委托 gate | 3 | #612 |
| ③ archetype 用例 | `AgentArchetypeEvalCases`(cs/pcba/competitive 手标 NL→期望/forbidden)+ 接进 scheduler | 6 | #613 |
| ④ L4 在线 eval | `AgentOnlineEvalService` + `AgentTurnQualityJudge` + `HeuristicTurnQualityJudge` | 9 | #614 |
| ② L2(Anthropic SSE 流式) | `AnthropicStreamSseReplayTest`(replay SSE 帧过 `handleAnthropicSseEvent`) | 4 | #616 |

新增 38 个确定性单测,全绿。

## P5 完成前全量复核(五项)

1. **方向** ✅:交付物对齐策略文档「五层 portfolio + 把信心路由到最便宜可靠层 + 真 LLM 从每次提交解耦」。
   新建的是确定性/录制层(L2/L3 gate/L4 heuristic),贵的真 LLM 跑被门控解耦,符合终局。
2. **进度** ✅:gap tracker 四项 + fast-follow 全 DONE(逐项 `grep`/`ls` 核对 7 测试类 + 6 主类均在 main,见本次收口命令输出,不凭印象)。
3. **gap** ✅:无新漏列的确定性 gap。剩余项均为**已显式记录的 LLM-key-gated block 点**(真模型跑 ③ archetype/`CapabilityEvalLiveIT`、`LlmTurnQualityJudge`、L4 定时触发+看板、cassette 改真捕获),非「未完成开发」。
4. **UX 截图** — N/A:纯后端测试基建,无 UI 行动点。
5. **测试完备性** ✅:每个 slice 配确定性单测(无 DB/Spring/key 的纯逻辑 + Mockito + JDK HttpServer 回放);无 skip 包装产品缺口、无 threshold 兜底;LLM-key 路径按 `/aura-endgame` 纪律 stub/record + 记 block 点,**未用真栈 mock 掉内部 seam**。

**结论**:确定性范围清零;LLM-key-gated 部分合法 stub/record + 文档记录,**不需另起迭代**。

## 弯路 / 返工(诚实)

1. **stale 本地 `origin/main` → worktree off stale base → 跨 PR 文档互相 clobber 风险**。
   连续多 PR 时,`gh pr merge`(服务端)不更新本地 `origin/main` ref;之后 `git worktree add … origin/main`
   未先 `fetch` → worktree 基于过期 base。多个 PR 编辑**同一 doc** 时,后一个 squash 会把前一个的 doc 改动盖掉。
   - 代价:SSE slice 的 worktree 基于 #611 的 stale main(漏了 #612–614 的代码+doc 改),compile 漏 `StreamingAggregator(List)` 构造器签名变化、且差点把 ④ 的 doc 笔记 clobber。
   - 已避免/恢复:`fetch origin main` 后发现真 main 的 doc 笔记完好(无 clobber 落地);`git reset --hard origin/main` 把 worktree 迁到真 base(保留 untracked SSE 测试)+ 修构造器 + 在真 doc 上加 SSE 笔记。
   - 本可更早避免:**每次 `worktree add … origin/main` 前先 `git fetch origin main`**(或 worktree 后立即 `git rebase origin/main`)。

2. 其余:`List.of(...).contains(null)` 抛 NPE(JDK 不可变集合拒 null 查询)→ archetype 结构测试首跑红;改 stream 空检查。小坑,一次修复。

## 根因四分类(同 `/handover` Step 3 词汇)

- **A 门禁质量**:无新增 gap;确定性单测覆盖到位。
- **B 输入信息不足**:无;策略文档自洽。
- **C 提示词·编排质量**:无重大;`/aura-endgame` 全自动编排顺畅。
- **D 验证纪律**:唯一弯路——**多 PR 连发时未在每个 worktree-add 前 fetch**,导致 stale base。改进:多 PR 会话里 `worktree add origin/main` 前强制 `git fetch origin main`,或 add 后 `git rebase origin/main`;编辑共享 doc 的 PR 尤其要确认 base 是真 main。

## 固化(precipitation)

- **durable 教训**:`worktree add … origin/main` 前先 `fetch`(stale 本地 ref → 跨 PR doc clobber)→ 已加 AGENTS.md 红线关键字速查表一行(指向 `git-workflow.md` 既有 worktree 纪律)。
- **可复用模式**:L2 provider 录制回放(JDK `HttpServer` 回环 / SSE 帧过真解析器,零依赖)+ agent 测试五层策略 → 已加 AGENTS.md 关键字行,指向本 feature 的策略文档做权威参考。
- **后续 backlog**:LLM-key-gated 真模型跑(③/`CapabilityEvalLiveIT`/`LlmTurnQualityJudge`)+ L4 定时触发+质量看板 + cassette 改真捕获 —— 全在策略文档 §5「落地方案」+ 各 ✅ 行的「后续刀/block 点」记录。
