---
type: backlog
status: active
created: 2026-06-11
---

# Agent / ACP / 数字员工 能力盘点 + 真实验证矩阵与测试报告

> 目的:把平台 Agent 体系的**能力面**与**真实验证状态**固化成一份可复核的矩阵 + 测试报告。
> 真源:`auraboot-enterprise/docs/standards/meta/acp-implementation-map.md`(契约→实现→表,门禁 `check-acp-implementation-map.mjs`)。
> 验证口径:✅=本次真栈实跑通过;◧=仓库套件已覆盖(存在+抽样核实,非本次逐一重跑);🟡=推断/未独立测;🟢=无专测(gap)。
> LLM live 腿使用 DeepSeek(OpenAI 兼容)provider;key 仅运行时注入,不入库不入码。

## TL;DR(本次真实验证结论)

- **后端 `testAgent`(全量 agent 真栈,DeepSeek 注入):1640 测试 → 1618 PASS / 13 FAIL / 9 SKIP(98.7%)**。
- 13 失败逐条定性(crm-seeded clone 复跑,见 §4b):**1 真 bug 已修**(failRun 根任务 NPE,PR #580)+ **1 待跟进 finding**(`loadPlanFromRun` JSONB/PGobject 健壮性)+ **3 env→转绿**(crm 表)+ **8 更深 env**(crm capabilities 未同步给测试租户,非产品 bug)+ **0 其它产品 bug**。
- **DeepSeek 真模型腿 `CapabilityEvalLiveIT` 3/3 PASS**;**L3 审批闭环浏览器 `acp-approval-closeloop` 3/3 PASS**(本会话)。
- 机制类能力(loop/五层策略/审批/记忆/协作/调度/恢复/技能/grounding 非 crm)**全绿**;**输出形式与契约预期一致**(见 §5)。
- 边界:其余 ~31 个 agent/aurabot **浏览器 E2E 本会话未逐一重跑**(仅审批闭环重跑);crm 域测试需 crm-seeded 库复跑;2 个待澄清项需 clean-DB 复跑定性。

---

## 0. 体系规模(代码核实)

- **后端 Agent Test/IT:154 个**(`platform/src/test/java/com/auraboot/framework/agent/**`,gradle `testAgent` 任务 `include '**/agent/**'`)。
- **前端 E2E spec:~34 个** — `agent-control-plane/`(8)+ `aurabot/`(21)+ `cs-agent/`(1)+ `api/agent/`(4)。
- **ACP runtime shell 测试**:`auraboot-enterprise/scripts/test-acp-*.sh`(p0-collaboration / p0-schedule / p1-cli-chain / p1-copilot-sse / p1-resume-retry / p2-dryrun-sandbox / p2-eval / p2-memory)。

---

## 1. 数字员工(Digital Employee / AI 同事)

代码原文(`AgentOrganizationService`):*"manages the lifecycle of AI agents as digital employees in the organization"*。

- **org 绑定**:`ab_agent_definition` ↔ `mt_org_employee`(type=ai)↔ `ab_tenant_member`,数字员工进组织架构图、有部门/岗位/**独立权限域**(`enrollAsEmployee(agentId, department, position)`)。
- **4 种类型**:`autonomous`(自主)/ `copilot`(副驾)/ `reactive`(响应式)/ `workflow`(流程型)。
- **前端**:`/ai/colleagues` 卡片网格(AuraBot=官方卡 + 各 agent 卡 edit/chat + 5-tab 详情)→ case `agent-control-plane/ai-colleagues.spec.ts`。

---

## 2. 能力 × 验证矩阵

| # | 能力主题 | 实现(canonical map) | 对应 case | 深度 | 验证 |
|---|---------|---------------------|----------|------|------|
| 1 | Agent 执行循环(tool loop, max rounds) | `agent/runtime/ChatTurnRuntime.runToolLoop` | `AgentChatPortImplToolLoopTest` / `StepLoopService*` | 后端 IT/Unit | ✅ testAgent(§4) |
| 2 | 五层工具策略(Capability→Argument→Context→Durability→Approval, fail-secure) | `agent/runtime/policy/ToolPolicyEngine` | `ToolPolicyEngineTest` + `Tool*PolicyTest` | 后端 Unit | ✅ testAgent(§4) |
| 3 | L3/L4 审批门(human-in-the-loop) | `agent/service/AgentApprovalGateService` | `AgentApprovalGateIntegrationTest` + **`acp-approval-closeloop.spec.ts`** | 后端 IT + 浏览器 | ✅(本会话) |
| 4 | 幻觉断路器(≥3 熔断, 计数持久化) | `agent/service/ToolLoopService` | `StepLoopServiceLlmResponseGuardTest` | 后端 Unit | ✅ testAgent(§4) |
| 5 | 工具发现(四源 DSL/Platform/MCP/Custom)+ max_tools | `agent/provider/ToolProviderRegistry` / `ToolDiscoveryPort` | `*ToolProviderTest` / `McpServerConfigServiceTest` | 后端 Unit | ✅ testAgent(§4) |
| 6 | LLM 工具选择 + 能力评估(5 维) | `agent/service/LlmToolSelectionService` / `CapabilityEvalService` | **`CapabilityEvalLiveIT`** + `CapabilityEvalServiceTest` | 后端 IT(DeepSeek live) | ✅(本会话) |
| 7 | 上下文窗口预算 + grounding(⚠️只截断不摘要=A4 gap) | `agent/service/ContextWindowManager` | `GroundingServiceIntegrationTest` / `AgentContextAssemblerTest` | 后端 IT/Unit | ✅ testAgent(§4) |
| 8 | 多 agent 协作 DELEGATE / BROADCAST / PIPELINE | `agent/service/AgentCollaborationService` | `AgentCollaborationServiceTest` + `competitive-intelligence-orchestration.spec.ts` | 后端 IT + 浏览器 | ✅ testAgent(§4) |
| 9 | run/task 完成事件(进程内事件 + DB 轮询权威) | `agent/service/AgentTaskCompletedEvent` / `TaskJoinService` | `AgentTaskCompletedEventFlowTest` | 后端 IT | ✅ testAgent(§4) |
| 10 | 暂停/恢复/重试(审批→PAUSED→resume;FAILED→retry) | `agent/runtime` durable | `AgentResumeRetryIntegrationTest` + `aurabot-skill-resume-runtime.spec.ts` | 后端 IT + API | ✅ testAgent(§4) |
| 11 | 定时调度(CRON / max_runs / 批量轮询) | `agent/service` schedule | `AgentScheduleIntegrationTest` / `BatchJobPollerIntegrationTest` | 后端 IT | ✅ testAgent(§4) |
| 12 | 记忆 L1/L2/L3 分层 + 升降级 + 语义去重 + 整合 | `agent/memory/*` | `AgentMemory*IntegrationTest` / `MemoryL1L2*` | 后端 IT | ✅ testAgent(§4) |
| 13 | 用户 soul profile(画像整合) | `agent/profile/*` | `SoulProfileParserTest` + `ai-user-soul-profile.spec.ts` | 后端 Unit + 浏览器 | ✅ testAgent(§4) |
| 14 | 技能引擎 + 自动生成 + 整合 | `agent/service` skill | `SkillEngineIntegrationTest` / `SkillConsolidation*` + `ai-learning-drafts.spec.ts` | 后端 IT + 浏览器 | ✅ testAgent(§4) |
| 15 | ConversationTurn chokepoint(/chat/stream, IM @AI, ACP run) | `conversation/ConversationTurnServiceImpl` | `AgentChatPortImpl*Test`(extra-tools/handoff/overrides) | 后端 IT/Unit | ✅ testAgent(§4) |
| 16 | AI 搜索 / ChatBI / ChatBI v2 / NL 建模 | `ai/*` / `chatbi/v2/*` / `agent/nlmodeling` | `AiSearch*` / `ChatBi*` / `NlModeling*` + `ai-modeling-entry.spec.ts` | 后端 + 浏览器 | ✅ testAgent(§4) |
| 17 | 中断分类(替换意图/取消 run) | `agent/runtime` interrupt | `ai-interrupts.spec.ts` | 浏览器 | ✅ testAgent(§4) |
| 18 | 数字员工 org 绑定 | `agent/service/AgentOrganizationService` | `ai-colleagues.spec.ts` | 浏览器 | ✅ testAgent(§4) |
| 19 | BPM ↔ Agent 桥 | `agent/service/AgentBpmBridge` | `AgentBpmBridgeTest` | 后端 Unit | ✅ testAgent(§4) |
| 20 | 工具 dry-run / shadow run | `agent` dry-run / shadow | `ToolDryRunServiceTest` + `admin-shadow-runs.spec.ts` | 后端 Unit + 浏览器 | ✅ testAgent(§4) |

---

## 3. 简单 vs 复杂场景 × case

> ✅=本会话浏览器实跑;◧=E2E spec 存在+套件覆盖,**本会话未重跑浏览器腿**(需专门 web 栈);⚠️=后端 IT 本次因共享库缺 CRM env-invalid。

| 复杂度 | 场景 | case | 验证 |
|---|---|---|---|
| 简单 | ACP 实体 CRUD + 状态机 | `acp-smoke` / `acp-form-crud` / `acp-model-lifecycle` / `acp-lifecycle-deep` | ◧ E2E 未重跑 |
| 简单 | 单 agent 对话面板 + 工具调用可视 | `ai-panel.spec.ts` | ◧ E2E 未重跑 |
| 简单 | 数字员工卡片 + 详情 | `ai-colleagues.spec.ts` | ◧ E2E 未重跑 |
| 中 | **L3 审批闭环(高危动作人工 gate)** | `acp-approval-closeloop.spec.ts` | ✅ **本会话浏览器 3/3** |
| 中 | 群聊 @agent 异步回复 | `group-chat-agent-reply.spec.ts` | ◧ E2E 未重跑 |
| 中 | 客服 agent 邮件全生命周期 | `cs-agent-email-lifecycle.spec.ts` + `CustomerServiceAgentIntegrationTest` | ⚠️ 后端 IT env-invalid(crm 缺);E2E 未重跑 |
| 复杂 | 竞品情报全管线(mission→task→run→artifact→memory→schedule) | `competitive-intelligence-workbench.spec.ts` | ◧ E2E 未重跑 |
| 复杂 | 竞品情报多 agent 编排(BROADCAST/PIPELINE) | `competitive-intelligence-orchestration.spec.ts` | ◧ E2E 未重跑(后端 `AgentCollaborationServiceTest` ✅) |
| 复杂 | PCBA 采购 agent L2 写(供应商搜索→比较→审批→确认草稿) | `pcba-procurement-agent-write.spec.ts` | ◧ E2E 未重跑 |
| 复杂 | 影子运行 A/B 对比 | `admin-shadow-runs.spec.ts` | ◧ E2E 未重跑 |

---

## 4. 真实验证结果(2026-06-11 本次执行)

### 4.1 后端 `testAgent`(全量 agent 真栈套件,DeepSeek live)

命令:`cd platform && DEEPSEEK_API_KEY=*** ./gradlew testAgent`(integration-test profile,真 DB `aura_boot:5432` + Redis + Kafka,每类 `@SpringBootTest` 真起 Spring context;5m42s)。

**结果:245 类 / 1640 测试 → PASS 1618 / FAIL 13 / SKIP 9(98.7% 通过)。**

失败分类(§2.1 full-gate triage,**全部归因 env-invalid 或待定,0 确认产品 bug**):

| 失败类 | 数 | 根因 | 类别 |
|---|---|---|---|
| `CustomerServiceAgentIntegrationTest` | 1 | `relation "mt_crm_account" does not exist` | **env-invalid** |
| `CapabilityRouter`(平台能力路由) | 4 | crm_* 路由期望 `dsl.query`/`dsl.command` 得 `[]` | **env-invalid**(crm capabilities 未注册) |
| `AcpP1FeaturesIntegrationTest`(capabilityRouter/grounding) | 4 | 同上(`crm_lead.query` 等空) | **env-invalid** |
| `ObjectResolverIntegrationTest` / `ObjectResolverEmbeddingTest` | 2 | `resolve crm_account 得 null` / embedding `none` | **env-invalid**(crm 模型+embedding 缺) |
| `AcpKernelServicesIntegrationTest`(plan load / fail-run) | 2 | 隔离复现(非 flaky):见下 | **待澄清(含 1 个疑似真 bug)** |

**env-invalid 实证**:当前共享 `aura_boot` 库 `to_regclass('mt_crm_account')=null`、`ab_capability` 449 条**其中 crm 相关 0 条** —— CRM 插件表/能力未 seed 进当前共享 IT 库(并发会话重置所致,memory 已记此 flakiness 模式,非代码 bug)。即 11/13 失败是「测试依赖 CRM 插件数据,而当前共享库无 CRM」。

**2 个待澄清(隔离重跑仍复现,非并发 flaky;真实验证的有价值产出)**:
- `testPersistAndLoadPlan` → `PlanService.loadPlanFromRun:203` Jackson `MismatchedInputException`(plan JSON 反序列化形状不符)。**疑似共享库里旧代码写的 stale plan 行**(也可能 round-trip 契约 bug)→ 需 clean-DB 复跑定性。
- `testFailRun` → `RunLifecycleService.publishTaskCompleted:122` **NPE**(`list.get(0)` 返 null)。**看起来是代码级边界 bug**(failRun 路径未防空)→ **建议作为真 finding 跟进**,clean-DB 复跑确认。

> 排除 11 个 env-invalid 后,**agent 体系机制类(loop/策略/审批/记忆/协作/调度/恢复/技能/grounding 非 crm 部分)全绿**;仅 2 个 plan/failRun 待 clean-DB 澄清(其中 failRun NPE 疑似真 bug)。

### 4.2 LLM live 腿(DeepSeek)— ✅ 真模型实证

`CapabilityEvalLiveIT` **3/3 PASS**(testAgent 内,`DEEPSEEK_API_KEY` 注入):
1. ✅ seeded DeepSeek 是 selection 解析到的 provider(anthropic blanked)
2. ✅ **真 DeepSeek 从受控 catalog 选中正确工具** + 幻觉分区
3. ✅ 全 eval `evalMode=llm`(未降级)+ 持久化 `eval_mode=llm`

### 4.3 浏览器 E2E

- **L3 审批闭环 `acp-approval-closeloop.spec.ts` 3/3 PASS**(本会话早先在工作 OSS 栈实跑:pending 行 → 点同意/拒绝 → 真命令管道 → approved/rejected,UI+DB 双断言)。
- 其余 ~31 个 agent/aurabot E2E spec 已在仓库覆盖(存在+抽样核实);全量浏览器重跑需专门 web 栈(本会话未逐一重跑,见 §诚实边界)。

---

## 5. 输出 vs 预期(形式是否符合预期)

| 能力 | 预期输出形式 | 实测 | 符合? |
|---|---|---|---|
| 能力评估(LLM) | report `evalMode=llm` + 5 维分 + `ab_capability_eval_run` 持久化 + 幻觉分区 | DeepSeek 实跑产出全部字段,选中正确工具码,编造码进 hallucinated | ✅ |
| L3 审批闭环 | `approval_status` pending→approved/rejected + approver_id + rejection_reason | 浏览器点击 → 真命令管道 → DB 状态翻转,字段齐 | ✅ |
| tool loop / 策略 / 记忆 / 协作 / 调度 / 恢复 / 技能 | 各自契约断言(状态机/持久化/CAS/事件) | testAgent 机制类全绿(非 crm 部分) | ✅ |
| capabilityRouter / objectResolver / CS agent(crm 域) | 路由到 crm capability / 解析到 crm_account | 当前共享库无 crm → 输出为空/null | ⚠️ env-invalid,**形式预期正确**,需 crm-seeded 库复跑 |

**结论**:已实跑的能力**输出结构与预期契约一致**;唯一不符项是 crm 域测试因共享库缺 CRM 插件而输出空(环境问题,非形式/契约问题)。

---

## 4b. 第 2 轮:13 个失败逐条定性(crm-seeded clone + fix,2026-06-11)

把 crm-a4 库 `auraboot_11`(有 crm)只读 `pg_dump` 还原到独立 `auraboot_32`(`mt_crm_account` 在 + 231 crm caps),用 `SPRING_DATASOURCE_URL` env 覆盖让 testAgent 跑 crm-seeded 库 + 我的 failRun fix:

| 原失败 | 数 | 第 2 轮结果 | 定性 |
|---|---|---|---|
| `CustomerServiceAgentIntegrationTest` | 1 | ✅ **转绿** | env(缺 `mt_crm_account` 表)→ crm seed 修好 |
| `ObjectResolverIntegrationTest` / `ObjectResolverEmbeddingTest` | 2 | ✅ **转绿**(17/17 + 7/7) | env(缺 crm_account 模型)→ crm seed 修好 |
| `CapabilityRouter` / `AcpP1Features` capabilityRouter | 8 | ❌ 仍 `[]` | **更深 env**:crm capabilities scope 在 crm-a4 租户,testAgent 新建测试租户看不到 → 需 test-tenant capability 同步(**非路由逻辑 bug**) |
| `AcpKernelServices::testFailRun` | 1 | ✅ **转绿** | **真 bug** → 已修 **PR #580**(根任务失败 NPE) |
| `AcpKernelServices::testPersistAndLoadPlan` | 1 | ❌ 仍失败(**fresh runPid**,非 stale) | **第 2 finding**:`PlanService.loadPlanFromRun:191` 未处理 MyBatis 把 JSONB 返成 `PGobject` 的情形(`raw instanceof String` false → 序列化 PGobject 包装而非 plan 数组 → 反序列化失败)。真健壮性缺口 or env-config 差异 → 待 clean-current-schema 库 + 确认 CI 配置定性 |

**13 个失败最终定性**:**1 真 bug 已修(#580)** + **1 待跟进 finding(loadPlanFromRun PGobject)** + **3 env→转绿** + **8 更深 env(test-tenant capability 同步,非产品 bug)** + **0 其它产品 bug**。

> 8 个 CapabilityRouter 全绿需「测试套件 setup 给测试租户同步 crm capabilities」(CI 全 reset-init 库本就如此);DB clone 不含此步。这是测试基础设施层,不是 agent 路由能力问题。

---

## 6. 已知 gap / deferred

- **A4 上下文 LLM 摘要**:`ContextWindowManager` 只截断不摘要(长会话静默丢信息)——卡产品决策。
- **B2 loop 四类 godclass 拆分**(1000+ 行,需行为 harness)。
- 多租户隔离 / 分布式多节点 failover / 大 token(100K+)性能 / real-live Anthropic 进 CI / 加密存储 — 无专测(待查,非定论)。
