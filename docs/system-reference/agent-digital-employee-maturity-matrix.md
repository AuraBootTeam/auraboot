---
title: AI 数字员工框架成熟度矩阵
type: system-reference
status: active
created: 2026-07-24
---

# AI 数字员工框架 — 成熟度 gap 矩阵(取证版)

> **方法**:阶段 0 盘自家底(§16)。每一维的成熟度判定都落到**实测证据**(grep 到的类/表/调用点),区分 ✅**已实测** 与 🟡**推断**;缺口只写取证坐实的,不臆测。
> **口径不是**「对标某外部产品」,而是「auraboot 自身的数字员工能力,按数字员工必备维度盘点到什么程度」。

## 成熟度级别

| 级别 | 含义 |
|---|---|
| **M0** | 缺失 / 仅占位 |
| **M1** | 基础:有实现,但单一路径 / 未接线 / 未真栈验证 |
| **M2** | 生产可用:接线 + 真栈验证 + 权限/治理 + 多路径 |
| **M3** | 成熟:M2 + 可观测 + 回归门 + 规模化(多租户 / leader election / scheduled) |

## 一句话结论

auraboot 的数字员工框架**整体已达 M2–M3**(不是 gappy 框架):记忆 L1/L2、durable mission、工具生态+MCP、多 agent handoff/群聊、eval 回路(judge+online promote)、可观测(trace+usage+action)、人机门禁、ABAC 全都有深实现,42 张 `ab_agent*/ab_ai*/ab_capability*/ab_approval*` 表。**真实缺口集中在少数「已建未接线 / 手写债 / 单一路径」处**,不在「能力缺失」。

## 维度矩阵

| # | 维度 | 关键实现证据(✅ grep 实测) | 成熟度 | 取证坐实的缺口 |
|---|---|---|---|---|
| 1 | 对话/回合运行时 | `ConversationTurnService.runTurn/resumeTurn`(chokepoint)、`ChatTurnRuntime`、`StepLoopService`、`AgentRunService`、pending/resume 全套(`ConversationTurnServiceImplResumeTest` 覆盖 dispatch/hash/decision) | **M3** | resume 的「approved→真执行→DB」整合无 rerunnable pin(gap 1) |
| 2 | 记忆 L0/L1/L2 | 表 `ab_agent_memory`/`_observation`/`_memory_promotion`/`_access_log`/`_memory_tier_event`;`MemoryTierEvaluator`、`ChatMemoryPromotionScanner`、`MemoryL1L2Promoter/Demoter/OrphanScanner`、`MemoryL1L2LeaderElection`、`MemorySecretGuard`、promotion metrics | **M3** | 幻觉写回 L1 被预召回自我强化(已知,靠 MemorySecretGuard+工具失败不入记忆缓解;需持续回归) |
| 3 | 规划/任务分解 | `PlanService.generatePlan`、`AgentPlanStep`、`AgentRunService` | **M2** | replan 策略深度未系统评估(🟡 推断) |
| 4 | Durable mission / 多步 | `JdbcDurableWorkflowCheckpointStore`、`DurableToolExecutionClaim/Record`、`DurableToolCompensationService`(SPI `DurableToolCompensationHandler` + 通用 `ProviderToolCompensationHandler` + scheduled sweep + **outcome metric `DurableToolCompensationMetrics` #1468**)、`StaleRunRecovery`、`RunDeadlineEnforcement`、`AgentLoopCostLimit` | **M3** | ✅ compensation 框架**完整**(SPI+通用 explicit-ref handler+调度+测试);「pending without handler」是**设计**(显式 opt-in,永不猜 rollback),非缺实现——OSS 无任何工具声明 `compensationToolRef` 故未被行使;已加 `aura_agent_tool_compensation_total{outcome}` metric 让"某域需补 handler"从 INFO 日志升为可告警信号 |
| 5 | 工具生态 | providers:`DslToolProvider`/`CustomToolProvider`/`McpToolProvider`/`PlatformToolProvider`;`ToolProviderRegistry`、always-on(`discoverAlwaysOn`)、`UsageRecordingLlmProvider`;`McpClient` 真 MCP | **M3** | list:/get: 命名 agent 执行曾误路由(已修 #1449);ACP fixture 工具污染栈致 live 写工具选择不稳(gap 3 根因) |
| 6 | 技能资产层 | `ab_agent_skill`(skill_tools/execution_mode/actionability)、`AgentSkillService.resolveSkillTools`、`AgentTemplateSeeder`、bound skill→tool(通用 aurabot #1440 + 命名 agent #1449)、**`SkillEngine` 已接线**(#1461) | **M2/M3** | ✅ **`SkillEngine` 已接线到 `POST /skills/{code}/execute`**——曾发现两个并行执行器:端点原调 `AgentSkillService.executeSkill`(只**规划**返回 steps,从不执行),而全 4 模式的 `SkillEngine`(template/sequential/orchestration/dsl_dispatch)生产零调用者。已改端点真经 `SkillEngine.execute` 执行(解析 tenant provider 使 orchestration 真跑非静默降级),planner 更名 `planSkill`(仅内部 solution 展开用)。真栈 IT `SkillEngineWiredExecutionIT`(路由证 + 真数据证)进 backend gate |
| 7 | 人机协作门禁(HITL) | confirm 门(`ConfirmCard`+`requiresConfirmation`+`REQUIRE_USER_CONFIRMATION`)、approval 门(`AgentApprovalGateService`+`ab_agent_approval`+`ab_approval_policy`+grant 一次性消费 IT)、`escalate_to_human`(always-on) | **M3** | 写场景 confirm→approve→DB 无浏览器 UI-E2E golden(gap 3;testid 已加 #1451) |
| 8 | 权限/安全信封 | `UserPermissionService`(role→perm+缓存)、ABAC `SubjectPermission`+`SubjectPermissionEvaluator`、`DataPermission`、工具发现门 `hasAnyDeclaredPermission`、平台写后 read-back 权限 | **M3** | bound skill 不越权信封已测(withholds tool);写命令权限需授权到角色(seed 栈默认不授 crm.account.manage) |
| 9 | 护栏/风险 | `AiActionRiskLevel`(L0–L4)、`AiActionRiskAssessor`、`AgentLoopCostLimit`、`PromptInjectionBoundary`、`MemorySecretGuard`、`readOnlyProfile` cap、`guardrails.provider/preferredProvider` | **M2/M3** | risk→confirm/approval 映射覆盖度未逐命令核(🟡) |
| 10 | 多 agent 协作 | `HandoffToolProvider`+`HandoffPermissionPolicy`+`HandoffResult`、群聊 `GroupChatAgentRouter`+`GroupChatTurnContextAssembler`+`GroupChatMessagePort`(SPI）+**真 IM-backed `GroupChatMessageAdapter`(8 方法全实现、`messageMapper.insert` 真持久化、`@Primary` 确定性胜出 #1467）**、ACP delegate | **M2/M3** | ✅ 群聊 port **非"待接"**——`GroupChatMessageAdapter` 早已是真实现,`NoOpGroupChatMessagePort` 只是 `@ConditionalOnMissingBean` 的 core-only 兜底;原风险=consumer 用 `getIfAvailable(NoOp::new)`(双 bean 会 `NoUniqueBeanDefinitionException`)+ NoOp 靠 `@ConditionalOnMissingBean`(scan-order 脆),已加 `@Primary` 消除 + 可证伪 wiring IT(去 `@Primary` 即红);多 agent 编排广度未真栈系统验证(🟡) |
| 11 | 评估回路 | `CapabilityEvalCase`/`AbCapabilityEvalRun`、`ScheduledCapabilityEvalJob`、`AgentTurnQualityJudge`、`OnlineEvalCasePromoter`、`AgentOnlineEvalService`、`CapabilityEvalRegressionGate`、`CapabilityEvalLiveIT`(需 key) | **M3** | live eval 与每 PR CI 解耦(设计如此;stub/record 兜底);阈值+k 次抗噪已有 |
| 12 | 可观测性 | `AiTraceService`+`ab_ai_trace`/`_span`、`GenAiUsageRecord`+`ab_gen_ai_usage`、`ActionRecorder`+`ab_agent_action`、`AgentRuntimeObservabilityService`、`AgentRunAudit/Ops` controllers | **M3** | — |
| 13 | 身份/多租户/渠道 | `ChannelSessionResolver`+`ab_agent_channel_session_state`、web/IM/群聊/`cs_widget`(RAG-only)、多租户隔离(tenant interceptor + `selectByQueryWithoutTenant` 显式绕过)、per-agent Cloud Config provider | **M3** | 命名 agent 需 Cloud Config provider+model(env key 只喂通用 aurabot;seed 栈需配 provider) |
| 14 | 产品化(数字员工界面) | `core-ai-colleagues` 5 页**全 DSL 化**(`/p/c/ai_settings_hub`·`ai_colleagues`·`ai_colleague_detail`·`ai_colleague_new`·`ai_colleague_chat`)、agent-control-plane(mission/agent/tool CRUD DSL 页) | **M2/M3** | ✅ **§7 债已清:5 页手写 tsx→DSL 全转完成**(owner 2026-07-24 激进全转决策)——settings 用通用 card-grid(#1478,含平台 per-row navigate 增强);colleagues/detail/wizard/chat 各用注册的 custom block(`AgentColleaguesGrid`/`AgentDetailTabs`/`AgentCreateWizard`/`AgentChatEmbed` 在 `ui/smart/agent`+`ComponentRuntimeManifest`,`{blockType:"custom",component:…}`,无平台 enum 改动),逐页真栈 golden。`pages/ai/*.tsx` 已清空。数字员工 golden 未进默认 CI(gap 2:自包含 runner 已固化) |

## 🔴 真实缺口清单(取证坐实,按优先级)

1. ~~**技能编排执行器 unwired**(维度 6)~~ → ✅ **已解决(#1461)**:选择「接线到运行时」而非降级——`POST /skills/{code}/execute` 端点从「调 planner 返回 steps、从不执行」改为真经 `SkillEngine.execute` 执行全 4 模式;解析 tenant 默认 provider 使 orchestration 真跑;planner 更名 `planSkill`(仅内部 solution 展开)。旧端点无任何前端/移动消费方,契约变更(plan→`SkillResult`)零破坏。真栈 IT `SkillEngineWiredExecutionIT` 双证(executor-exclusive 错误路由 + 真数据执行)已进 backend gate。
2. **产品化手写债**(维度 14):`core-ai-colleagues` 5 页手写 React 应按 §7 DSL 化(同域 agent-control-plane 已证 DSL 够用)。
3. **写场景可靠测试基座**(维度 7,gap 1+3):create→confirm→DB 的确定性 pin —— 后端撞租户命令 fixture、浏览器撞 live LLM 工具选择不稳(ACP fixture 污染栈)。需干净确定性基座。
4. ~~**群聊 message port 默认实现**(维度 10)~~ → ✅ **已澄清+加固(#1467)**:此前判「待接」是 §15 phantom gap——真 IM-backed `GroupChatMessageAdapter`(8 方法全实现、真持久化、有 `GroupChatMessageAdapterTest`)一直存在,`NoOpGroupChatMessagePort` 只是 `@ConditionalOnMissingBean` 的 core-only 兜底。真正的隐患是选择脆性:三处 consumer 用 `ObjectProvider.getIfAvailable(NoOp::new)`(双 bean 抛 `NoUniqueBeanDefinitionException`),而 NoOp 靠 `@ConditionalOnMissingBean` on `@Component`(scan-order 不确定)排除。已给 adapter 加 `@Primary` 让真实现确定性胜出;可证伪 IT `GroupChatMessagePortWiringIT`(强制双 bean,去 `@Primary` 即 `NoUniqueBeanDefinitionException` 红)进 backend gate。
5. ~~**compensation handler 按域补**(维度 4)~~ → ✅ **已澄清+加固(#1468)**:§15 phantom gap——compensation 框架**完整**(SPI `DurableToolCompensationHandler` + 通用 `ProviderToolCompensationHandler`〔任何声明 `compensationToolRef` 的工具都能回滚,永不猜〕+ scheduled sweep + 3 分支测试)。「pending without handler」是**显式 opt-in 的设计**,不是缺实现;只是 OSS 无任何生产工具声明 `compensationToolRef`(仅测试里有),故补偿从未被行使。"按域补" 是真实但**当前无 OSS 域需要**的扩展点——臆造一个域 handler 是 §19 make-work。真正的洞:该"需补 handler"信号此前只有 INFO 日志,已加 `aura_agent_tool_compensation_total{tenant,outcome}`(compensated/pending_no_handler/handler_incomplete/failed)让它**可告警**(变异验证:去掉 increment 对应测试即红)。

## 诚实边界(本矩阵没做到的)

- 每维成熟度是**基于 grep 到的实现证据 + 本工作线真栈实测**的判定;标 🟡 的(replan 深度 / 群聊编排广度 / risk 映射覆盖)**未逐一真栈跑**,是推断,需专项验证才能从 🟡 升 ✅。
- 本矩阵盘的是**能力有没有 + 接没接线**,不是**每条能力的质量回归覆盖率**(那要每维再拉黄金集,是各维独立的后续)。
- 未覆盖:成本/延迟 SLO、红队/对抗鲁棒性、跨语言 worker 生态的数字员工侧 —— 若要纳入需再起一轮取证。
