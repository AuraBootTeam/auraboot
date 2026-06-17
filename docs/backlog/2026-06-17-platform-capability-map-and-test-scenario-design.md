---
type: backlog
status: active
created: 2026-06-17
---

# 平台能力大图 + 企业级测试场景统一设计

> 缘起:owner 担心"agent 一直很弱"。先用真 DeepSeek 把 agent 决策智能量化(见
> [2026-06-17-agent-intelligence-live-quality-measurement.md](./2026-06-17-agent-intelligence-live-quality-measurement.md),
> 工具选择 5/5 + 参数抽取 5/5·值 100% + 对抗 8/8 + F6 缺信息拒绝瞎编),证明智能层不弱;
> 但 agent 不是孤岛——它作用在**展现层 / 治理门(agent gate)/ 业务引擎(SLA·rule·BPMN·
> automation·decision)/ 命令管道 / 业务域(CRM·quality)**之上。本文结合**现有文档+代码取证**,
> 给一张**完整能力大图**,再据此设计**企业真实典型场景的完整端到端测试场景套件**,把 agent gate
> 统一纳入验证。口径:取证不推断,verified/inferred 分明,失败照报。

来源取证:4 路只读盘点(gate 治理层 / 规则·SLA·BPMN·automation·decision 引擎 / 展现层 /
CRM·命令管道),每条 file:line。下表「现状」列区分 ✅verified(读码/已测)、🟡inferred(推断未独验)、
❌gap(无测/无路径)。

---

## A. 能力大图(6 层 + 横切)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ L1 展现层  DSL 页面(list/form/detail/workbench/dashboard)·30 blockType ·     │
│            24 chartType · ChatBI(NL→图,即席)· 自定义 block                    │
├─────────────────────────────────────────────────────────────────────────────┤
│ L2 Agent 智能层  工具选择 · 参数抽取(native tool-use)· NL 建模 ·               │
│                  多 agent 协作(DELEGATE/BROADCAST/PIPELINE)· ChatBI intent     │
├─────────────────────────────────────────────────────────────────────────────┤
│ L3 Agent 治理门(agent gate)  5 层 ToolPolicyEngine(Capability→Argument→       │
│    Context→Durability→Approval, fail-secure)· ToolAclChecker(5 维)·            │
│    AgentApprovalGateService · DurableToolExecutionLedger · 幻觉断路器 ·         │
│    风险阶梯 L0-L4 · @RequirePermission/ABAC PolicyEvaluator                     │
├─────────────────────────────────────────────────────────────────────────────┤
│ L4 业务引擎层  Automation(8 触发器+10 执行器)· EventPolicy · BPMN/SmartEngine  │
│    4.0.0 · SLA(多级预警+升级)· Decision(5 适配器+灰度+版本)· IoT Rule(EMQX)     │
├─────────────────────────────────────────────────────────────────────────────┤
│ L5 执行管道  CommandExecutor / CommandPipeline 14 阶段                          │
│    (Load→SchemaValidate→Idempotency→StateCheck→Assert→PreInvariant→            │
│     FieldMap→Handler→Effect→PostInvariant→Completion)→ 真 DB 写 + 审计          │
├─────────────────────────────────────────────────────────────────────────────┤
│ L6 业务域  CRM(account/contact/lead/opportunity/activity/campaign/complaint)·  │
│            Quality(capa/iqc/fqc/spc…hybrid handler)· 各垂直插件                 │
└─────────────────────────────────────────────────────────────────────────────┘
   横切:可观测(ab_agent_observation)· Eval 五层(L0 单测/L1 stub/L2 录放/L3 真模型/
        L4 在线)· 多租户隔离 · 权限治理 · 审计血缘
```

**一句话**:执行契约层(L3 gate + L5 管道)9/10 强;业务引擎层(L4)五大引擎生产可用;
智能层(L2)首测正向;展现层(L1)基础 CRUD 强、workbench/ChatBI/dashboard 真浏览器 golden 缺。

---

## B. 测试金字塔(每条场景按这套分层取证)

| 层 | 手段 | 抓什么 | 进 CI? |
|---|---|---|---|
| T0 单测 | JUnit/vitest(mock) | 纯逻辑/枚举/normalizer | ✅ |
| T1 stub eval | StubLlmProvider | agent 管道接线(不测决策) | ✅ |
| T2 录放 | JDK HttpServer 回放 | provider 线格序列化/解析 | ✅ |
| T3 live eval | 真 DeepSeek(key-gated) | **agent 决策质量**(选工具/抽参/不幻觉) | ❌ nightly |
| T4 命令管道 IT | 真 DB,无 rollback | 命令→14 阶段→真 DB 写+审计 | ✅ |
| T5 gate IT | 真 DB | 治理门正向放行/负向拦截 | ✅ |
| T6 引擎 IT | 真 DB(+SmartEngine) | automation/SLA/BPMN/decision 配置→触发→运行时→审计 | ✅ |
| T7 真浏览器 golden | Playwright(host-first) | UI 配置→渲染→数据→交互→状态变化(0 console error) | ❌ 专项 |

> 完整场景 = **跨多层成对取证**:UI 操作证据 + 后端运行时/DB 证据,任一边缺 = did-not-run。

---

## C. 企业真实典型场景 × 端到端验证链(核心)

> 每个场景是一条**完整业务流**,穿过多层。验证链逐层列断言 + 测试类型 + 现状。

### S1 · 智能客服闭环(CS agent → 投诉登记 → 自动指派 → SLA)
**业务流**:客户邮件投诉 → agent 读邮件抽字段 → `crm:create_complaint` → 自动指派 → 响应 SLA 计时。
**涉及层**:L2 智能 + L3 gate + L5 管道 + L6 CRM + L4 automation/SLA。
| 断言 | 层 | 类型 | 现状 |
|---|---|---|---|
| agent 选对 `crm:create_complaint`、不选 delete | L2 | T3 | ✅(archetype 5/5) |
| 从邮件抽对 account/contact/description/severity | L2 | T3 | 🟡 待扩(form-fill 已证机制) |
| capability gate 放行(有权限)、无权拒 | L3 | T5 | ✅verified |
| 命令管道 14 阶段 → `mt_crm_complaint` 真落库(code/status=open) | L5/L6 | T4 | ✅verified(CustomerServiceAgentIntegrationTest) |
| 自动指派 automation 触发 + `ab_automation_log` | L4 | T6 | 🟡 需接 automation |
| SLA 响应 deadline 激活 + 超时升级 | L4 | T6 | 🟡 需接 SLA |

### S2 · 高危操作审批闭环(delete/release → agent gate → 人审 → 执行)
**业务流**:agent 拟执行 L3/L4 命令(删投诉/放行质量挂起)→ ApprovalPolicy 拦 → pending 卡 → 人审批 → DurableLedger → 真执行。
**涉及层**:L2 + **L3 gate(本场景主角)** + L5。
| 断言 | 层 | 类型 | 现状 |
|---|---|---|---|
| agent 对 L3/L4 标 `expectsConfirmation`、不自动执行 | L2 | T3 | ✅(archetype forbid 5/5) |
| ApprovalPolicy 创建 pending + 幂等 key + plan_hash | L3 | T5 | ✅verified |
| 无 policy 时 **fail-secure**(不 fail-open) | L3 | T5 | ✅verified |
| 非授权 approver 拒(approver_rules 空=deny all) | L3 | T5 | ✅verified |
| **超时 auto-expire**(scheduled 5min)→ run fail | L3 | T5 | ❌ E2E gap |
| **plan_hash 篡改检测**(改 request_data → reject) | L3 | T5 | ❌ E2E gap |
| approve → DurableLedger claim → 真执行 → DB 状态变 | L3/L5 | T5+T4 | 🟡 部分 |
| 浏览器 pending 卡 → approve/reject → 状态翻转 | UI | T7 | ✅(acp-approval-closeloop 3/3) |

### S3 · 质量异常自动 CAPA(缺陷 → automation/EventPolicy → BPMN 审批 → CAPA → SLA 升级)
**业务流**:缺陷记录 → EventPolicy/Automation 触发 → BPMN 流程(人审)→ `qc:create_capa`(hybrid handler)→ SLA deadline + Decision 规则升级。
**涉及层**:L4(automation+BPMN+SLA+decision)+ L5 + L6 quality。
| 断言 | 层 | 类型 | 现状 |
|---|---|---|---|
| 缺陷 create → automation/EventPolicy 触发(SpEL 条件) | L4 | T6 | ✅verified(automation/eventpolicy IT) |
| BPMN startProcess + userTask 创建(SmartEngine 4.0) | L4 | T6 | ✅verified |
| `qc:create_capa` handler 执行 → `mt_qc_capa` 落库 | L5/L6 | T4 | 🟡(create 70%,handler 金标缺) |
| SLA 激活 + 15s 扫描 + Decision 规则升级 | L4 | T6 | ✅verified(SLA scheduler)/ 🟡链路金标 |
| 跨插件链(CRM→Quality)端到端 | L4-L6 | T6 | ❌ gap(无 golden) |

### S4 · 自然语言建模 → 一键部署(开发者 agent)
**业务流**:"建设备点检对象:编号/点检人(引用)/时间(日期)/结果(枚举)/备注" → NlModeling 生成 model+command+page DSL → validator → import → 页面可用。
**涉及层**:L2(NL 建模)+ L1(页面)+ L5(import)。
| 断言 | 层 | 类型 | 现状 |
|---|---|---|---|
| 字段名/类型/枚举/引用生成正确,DSL 合法(白名单) | L2 | T3 | 🟡 待测(NlModelingService) |
| `apply` → import-directory-sync `success:true` | L5 | T4 | ✅(import validator) |
| 生成的 list/form/detail 页真浏览器可用(非空壳) | L1 | T7 | 🟡 |
| "一键编排"(generate→validate→apply→verify 自动串) | — | — | ❌ gap(编排层缺) |

### S5 · ChatBI 即席图表(生成图表)
**业务流**:"按地区统计本季度销售额,柱状图" → ChatBI LLM→intent(agg/groupBy/filter/chartType)→ SQL → 数据 → 图。
**涉及层**:L1(ChatBI)+ L2(intent 解析)。
| 断言 | 层 | 类型 | 现状 |
|---|---|---|---|
| agg/groupBy/filter/chartType 解析正确,字段不幻觉 | L2 | T3 | 🟡 待测(ChatBiLlmParser) |
| SQL 在 DynamicController 可重现、数据无 null 串 | L1 | T7 | ❌ 无 golden |
| 图表渲染不崩(chartType∈24,0 console error) | L1 | T7 | ❌ 无 golden |
| 落库成持久 dashboard | L1 | — | ❌ gap(即席,无保存) |

### S6 · 工作台联动(dashboard/workbench)
**业务流**:打开对账台/驾驶舱 → 顶部 KPI 卡出数 → 点 metric-strip 筛选 → 下面表行数变 → review-drawer 选行看证据 → 候选确认改状态。
**涉及层**:L1(workbench block 家族)。
| 断言 | 层 | 类型 | 现状 |
|---|---|---|---|
| KPI 卡数据真出(DOM 查 value,非 `-`) | L1 | T7 | ❌ 无 workbench golden |
| 点 metric-strip → table 行数减少(真 queryParam 变) | L1 | T7 | ❌ |
| review-drawer side-by-side 加载 + 确认/撤销改状态列 | L1 | T7 | ❌ |
| 0 console exprError(DSL 求值器短路 &&/\|\|/??) | L1 | T7 | ❌ |

### S7 · 多 agent 协作(mission 拆解)
**业务流**:高层目标 → DELEGATE/BROADCAST/PIPELINE 拆子任务 → 子 agent 执行 → 完成事件上卷 → 父任务聚合。
**涉及层**:L2 协作。
| 断言 | 层 | 类型 | 现状 |
|---|---|---|---|
| 三模式分发 + 子任务链(parent_id) | L2 | T4 | ✅verified(AgentCollaborationService) |
| 子任务完成事件(CHILD_TASK_COMPLETED)唤醒父 | L2 | T4 | ✅verified(事件+轮询权威) |
| 真模型驱动的多步收敛(不空转/不死循环) | L2 | T3 | ❌ 无独立收敛测 |

### S8 · Agent 幻觉/越权防护(gate 负向专项)
**业务流**:诱导 agent 调不存在工具/伪造参数/跨租户/外部副作用重放。
**涉及层**:L3 gate 全负向。
| 断言 | 层 | 类型 | 现状 |
|---|---|---|---|
| 调不存在工具 ≥3 次 → 幻觉断路器熔断 run 终止 | L3 | T5 | ✅verified(ToolLoopService) |
| 跨租户 recordId → Context gate 拒 | L3 | T5 | 🟡 单测有、E2E gap |
| ACL 规则 deny → 工具不执行 | L3 | T5 | 🟡 IT 综合、独立 E2E gap |
| 外部副作用重试 → DurableLedger replay 不重复执行 | L3 | T5 | ✅verified |

---

## D. Agent gate 统一验证矩阵(owner 要求纳入)

> 12 个治理门,每个**正向放行 + 负向拦截**成对验证。现状来自 gate 盘点。

| Gate | 拦截对象 | 正向 | 负向 | 现状 |
|---|---|---|---|---|
| 1 Capability(L1) | 权限+capability ceiling | 权限足→放行 | 缺权→deny missing_permission | ✅ |
| 2 Argument(L2) | 参数类型/格式 | 合法→hash 稳定 | type 错→argument_invalid | ✅ 单测/❌ IT |
| 3 Context(L3) | recordId 归属/跨租户 | recordId∈context | 跨租户→context_conflict | 🟡 单测/❌ E2E |
| 4 Durability(L4) | 外部副作用边界 | claim acquired=true→执行 | 重试→replay 不重复 | ✅ |
| 5 Approval(L5) | L3/L4 风险工具 | 人审 pending→approved | 无批准→hold;无权审→deny | ✅ /超时·篡改 ❌ E2E |
| 6 ApprovalGate svc | 创建/批准/超时/幂等/plan_hash | CRUD+event→resume | fail-secure no-policy;hash mismatch→reject | ✅ /超时·篡改 ❌ |
| 7 ToolAclChecker | 5 维 ACL | 5 元组 match→allow | no-match+有规则→deny | 🟡 IT/❌ E2E |
| 8 RuntimeAuth | 效果类(WRITE/NET/SECRET) | plan 期预授权 | forbidden effect→deny | 🟡 contract-only |
| 9 DurableLedger | 外部 API 幂等 | claim→执行→complete | crash→recovery job 扫描重试/补偿 | ✅ |
| 10 幻觉断路器 | 不存在工具/伪造参数 | 正常→count=0 | 3 次→熔断 run 终止 | ✅ |
| 11 风险 L0-L4 | 风险分级 | L0/L1 直执 | L3/L4→审批+durable | ✅ /L2 durability E2E gap |
| 12 权限门 ABAC | @RequirePermission/属性级 | 权限⊇required→allow | 缺→403 | ✅ /agent 工具 E2E gap |

**gate 整体判定**:Capability/Argument/Approval/Durability/幻觉/权限 = **真拦(verified)**;
Context/ACL = 单测/IT 有、**独立 E2E gap**;RuntimeAuth = **contract-only 未实装**;
ApprovalGate **超时 auto-expire + plan_hash 篡改**两条高危路径 **E2E 缺**(建议优先补)。

---

## E. 现状覆盖 vs gap(诚实清单)

**✅ 已 verified(真测过)**:
- agent 智能:工具选择 5/5、参数抽取 5/5·值 100%、对抗 8/8、缺信息拒绝瞎编(本会话 3 个 live IT)。
- gate:Capability/Argument/Approval/Durability/幻觉/权限/风险 L0-L4 + DurableLedger replay。
- 引擎:Automation/EventPolicy/BPMN(SmartEngine 4.0)/SLA/Decision 五大引擎真栈 IT。
- 管道:CommandPipeline 14 阶段 → 真 DB + 审计(CommandExecutor IT)。
- 业务域:CRM 7 模型 config-only CRUD + crm:create_complaint 真栈 golden;quality close_capa handler 单测。
- 展现:DSL list/form/detail + CRUD + 权限 ABAC(已出货产品线验证)。

**🟡 inferred / 部分**:
- NlModeling 真模型质量、ChatBI intent 质量(待 T3)。
- SLA 升级链路金标、跨引擎版本、Decision 灰度全链 golden。
- quality 其余 handler(iqc/fqc/spc)、qc:release_quality/dispose/close(推断存在未独验)。
- Dashboard/widget、24 chartType 真浏览器存活率。

**❌ gap(无测/无路径)**:
- **agent gate**:Context E2E、ACL 独立 E2E、ApprovalGate 超时 auto-expire、plan_hash 篡改、RuntimeAuth 实装。
- **workbench/dashboard 联动 golden**(metric-strip 点击→table 变、review-drawer、0 exprError)。
- **ChatBI 端到端 golden**(数据准确+图渲染)+ 一句话生成持久 dashboard(无路径=build gap)。
- **跨插件命令链 golden**(CRM→automation→quality)。
- **多 agent 真模型收敛测**。
- **NL→一键部署编排层**(各零件在,自动串联缺)。

---

## F. 落地优先级(ROI 排序,先 gate + 智能广度)

1. **P0 · agent gate 高危路径补 E2E**(owner 点名):ApprovalGate 超时 auto-expire + plan_hash 篡改 + Context 跨租户拒 + ACL allow/deny。这几条是**安全相关、负向、当前 gap**,用真栈 IT(T5)即可,不需 UI。
2. **P0 · 智能广度**:S4 NlModeling(NL→model 字段/类型/枚举正确)+ S5 ChatBI intent —— 用本会话同款 live-LLM 探针(T3),覆盖 owner 范围里的"生成图表/自动建模"。
3. **P1 · 业务闭环金标**:S1 客服闭环接 automation+SLA、S3 质量自动 CAPA 全链(T4+T6 成对)。
4. **P1 · workbench/ChatBI 真浏览器 golden**(T7,host-first 零 docker):S6 工作台联动、S5 ChatBI 渲染。
5. **P2 · 跨插件命令链 golden**(S3 末)、多 agent 收敛(S7)、一键部署编排(S4 末,需先补编排层=build)。

**已建样板**(可直接复制扩量):`AgentArchetypeLiveQualityIT`(工具选择)/ `AgentFormFillLiveIT`(参数抽取)/
`AgentFormFillHardLiveIT`(对抗)—— 三者都是 native tool-use 真链路 + 自包含 catalog + 真 DeepSeek + 诚实报告,
是 T3 的可复用模板;P0.2 的 NlModeling/ChatBI 探针照此扩。

---

## 安全
所有 live(T3)IT:`@Tag("agent-eval-live")` + `DEEPSEEK_API_KEY` gated(plain `testAgent` 跳过),
tenant seed 用后即删。⚠️ 复发风险:integration-test SQL DEBUG 把 `ab_cloud_config` apiKey 明文记日志
(已每轮 redact);建议在 CloudConfig 日志层做脱敏(与"加密存储"gap 相邻),从源头消除。
