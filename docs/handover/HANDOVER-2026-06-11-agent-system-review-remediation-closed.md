---
type: handover
status: active
created: 2026-06-11
---

# Session Handover - 2026-06-11 — Agent 系统 review+修复 全线收口

## Session Summary

对平台 agent 体系(`framework/agent` 362 文件 + conversation/agentchat/ai 层 + ENT ACP 契约)做了全面架构 review,随后三轮修复全部 MERGED:**OSS #537**(ChatBI/eval LLM 真实装 + task 完成事件)、**OSS #548**(SSE wire 对齐 + 5 个 acp.* 权限码 gate + 2 个 godclass 拆分)、**OSS #551**(session retro)、**ENT #381**(契约文档对账 + acp-implementation-map)、**ENT #394**(经验固化进 canonical)。本任务线状态 = **CLOSED**;本文为接续 deferred 项的新会话而写。

## Tasks Completed(全部 merged main,worktree/分支已清)

- [x] 全面 review:4 路并行 explore + 主对话逐条 §15 实证;**7 处继承结论被证伪**(详 retro)
- [x] A1 ChatBI LLM 解析(`ChatBiLlmParser`,LLM 优先/keyword 兜底/`parseMode` 上报/参数化 filter/防幻觉字段校验)+ 15 单测
- [x] A2 能力评估真 LLM 模式(`LlmToolSelectionService`,幻觉分区计分,无 provider 显式降级 keyword)+ 10 单测
- [x] A3 task 级完成事件(`AgentTaskCompletedEvent` 4 个终态过渡点 + `TaskJoinService` latch,两个委托等待循环事件优先/轮询权威)+ 8 测试
- [x] B3 权限 gating:5 个新码(`acp.runtime.manage`/`acp.agent_run.admin`/`acp.memory.admin`/`acp.profile.admin`/`acp.learning.review`)注册 bootstrap + 注解 8 个 controller;用户自助流有意不 gate;`validate-permission-codes` 0 drift
- [x] B2(2/6):`CapabilityViewService` 1387→670(+`CapabilitySyncService`/`CapabilityGraphService`/`CapabilityMappingSupport`)、`AgentRunController` 1280→541(+Audit/Ops controller+`AgentRunQuerySupport`),URL/权限/公共 API 逐一保持
- [x] C1 文档对账:ENT meta 双向漂移修正 + 新增 `docs/standards/meta/acp-implementation-map.md`(契约→实现类→表)
- [x] C2 SSE 对账:删死路径 `auraBotApi.chat()`(调 `/chat` stub、0 调用方)+ 旧枚举,导出 `AuraBotSseEventName` = 真 wire 协议
- [x] Retro + 固化:`docs/backlog/2026-06-11-agent-system-remediation-session-retro.md`(根因:stale truth ~50% / dispatch prompt 缺 falsification ~25% / 批处理自纪律 ~20%);ENT 固化到 spike-verification-discipline + 2 个 gotcha 文件 + AGENTS.md 速查表 2 行

## Tasks In Progress

无(本线收口)。Deferred 项见 Next Steps。

## Key Decisions

| 决策 | 选择 | 理由 |
|------|------|------|
| legacy ChatBI vs chatbi/v2 | 不合并,legacy 按 AiSearch 模式补 LLM | v2=语义层会话式(依赖 semantic model),legacy=模型直查无状态;不同层非重复栈 |
| eval LLM 模式降级语义 | 无 provider 时显式降级且持久化 `eval_mode=keyword` | eval 行不许标 llm 却没真调模型(诚实评分) |
| 完成信号架构 | 事件=进程内延迟优化,DB 轮询永远是权威 | Spring 事件不跨实例;多节点部署任务可能在别处完成 |
| `/api/admin/**` GET gating | 不加码 gate,保持 AdminRoleInterceptor 既有契约;码只 gate 破坏性操作 | security IT 编码了该契约;尊重测试表达的设计意图 |
| B2 范围 | 只拆 CapabilityViewService+AgentRunController,loop 核心四类缓拆 | 四类深度互耦(service↔runtime),安全拆需先建 tool-loop 行为 harness |
| B1 | 撤销 | 立项依据(sync 内联 PluginImportServiceImpl)被实测证伪——文档过期 |

## Pitfalls & Workarounds(详细版见 retro,此处只列接手会再撞的)

1. **历史 review/契约文档 finding 是快照**:引用前必须 grep 现行代码(本会话 7 处证伪零白修全靠这条)。
2. **`@RequirePermission` IT 三件套**:SecurityContext 放 `CustomUserDetails`(MetaContext 不够)+ `grantPermissionToTestRole` + `evictUserPermissions`;MockMvc 下 deny 显示为 **500** 非 403。已固化 `engineering-gotchas/backend-spring-db.md`。
3. **批量脚本编辑**:断言匹配数==1 / 失败先 revert 再重试(脏状态重跑出鬼数据)/ 提交前 `git diff --stat` 核对。已固化 main-conversation-discipline。
4. **drive-by 修别项目缺口先并发检测**:本会话顺手注册 billing 码与并发 #547 撞 CONFLICTING,rebase 解决。
5. `AuraEvent` payload `Map.copyOf` 拒 null → 可空字段省略 key;`publishEvent` 双重载 → captor 用 `ApplicationEvent` 类型。

## Current State

### Git
- 全部 merge 到两仓 main;本线 worktree/分支全清(`MERGED_AND_DELETED`)。
- 本地:enterprise canonical 已 pull 到 `b895546e4`(#394);OSS canonical 在 `codex/crm-endgame-gaps`(CRM 会话占用,已 fetch 未切换);website 已 pull。
- ⚠️ 并发会话状态:enterprise 工作区有别会话的 perm-gov 文档删除 + untracked `platform/plugins/`(autostash 保留,勿动);OSS main 上有 2 个既有 docs-governance error(xxl-job 文档 status 枚举,非本线引入)。

### 权威文档
- Tracker(含全部 finding/状态/证据):`auraboot/docs/backlog/2026-06-10-agent-system-review-and-remediation.md`
- Retro(根因分析):`auraboot/docs/backlog/2026-06-11-agent-system-remediation-session-retro.md`
- 实现对照表:`auraboot-enterprise/docs/standards/meta/acp-implementation-map.md`

## Next Steps(deferred,各有前置,按 ROI 排序)

1. **A5 L3 审批闭环浏览器黄金 E2E**(ai-governance 白皮书核心承诺;需前端栈专门会话,pending 卡→批准→命令执行→状态断言)
2. **A6 live-LLM eval 回归**(A2 已铺好 `LlmToolSelectionService`;需 LLM key/预算决策,可用低价 provider 做 opt-in suite)
3. **A4 上下文 LLM 摘要压缩**(`ContextWindowManager` 现只截断;需产品决策:模型/成本上限/摘要落库位置)
4. **B2 loop 核心四类拆分**(ToolLoopService 1206/StepLoopService 1081/AgentRunService 1092/ChatTurnRuntime 1056;前置=tool loop 行为测试 harness,顺带解 B4 service↔runtime 边界)
5. **I-1 implementation-map 脚本门禁**(map 中类名 grep 现行代码必命中;retro 立项)
6. **I-2 存量 finding 文档生命周期**(新规则已固化,存量不回溯;修 finding 的 PR 回标原报告)

## Context for Next Session

- 开工先读 tracker §1-§3(剩余项的证据与前置都在)+ retro §三(改进清单)。
- 关键代码入口:`agent/runtime/ChatTurnRuntime.runToolLoop`(loop 核心)/ `agent/service/LlmToolSelectionService`(A6 基础)/ `agent/service/ContextWindowManager`(A4)/ `conversation/ConversationTurnServiceImpl`(三路分发)。
- 并发检测命令(开工必跑):`git ls-remote --heads origin '*agent*'` + `git log origin/main --oneline -10`(本会话两次撞并发:billing 码 / CRM 占 canonical)。
- 共享 IT DB = host `aura_boot:5432`,并发会话可能重置(env-invalid 先重跑再判,见 memory feedback)。
