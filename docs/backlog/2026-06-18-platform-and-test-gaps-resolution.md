---
type: backlog
status: active
created: 2026-06-18
owner: agent-quality-campaign
related:
  - docs/backlog/2026-06-17-platform-capability-map-and-test-scenario-design.md
  - docs/backlog/2026-06-17-s1s3-business-loop-golden-gap-and-plan.md
---

# 平台 & 测试 gap 全面解决 — 跟踪 SOT(2026-06-18)

> 续 S1/S3 业务金标。owner 指示:**全面解决所有 gap;平台 gap 先设计方案确认再建,测试/基建 gap 直接执行**。F2(schema.sql demo 表)owner 明确**暂忽略**。

## 0. 取证后的分类(§16 盘自家底,修正了若干初判)

### ❌ 不是平台 gap(已存在,取证证伪)
- **S4 部署编排**:`platform.create_model` agent tool 已存在(`PlatformToolProvider.createModel` 串 `NlModelingService.generate()`+`apply()`,L3 审批)。→ 仅需端到端验证(test gap)。并发会话 #782 也在收 nl-modeling apply。
- **RuntimeAuth 效果级授权**:已实装且强制(`EffectClass` 8 类 + `RuntimeAuthorizationService`/`Default…` + `ToolLoopService` 每调用 `authorizeIncremental` 拦截 + 审计 `ab_agent_authorization_decision`)。设计文档「contract-only 待核实」证伪 = 实装。→ 仅需补 enforcement 测试。

### ✅ 真平台 gap(已确认方案)
| gap | 方案 | 状态 |
|---|---|---|
| **②F3 record 级 SLA** | `targetType="RECORD"`(targetKey=modelCode)+ `SlaActivationListener.onRecordCreate` 复用 deadline 引擎,`DynamicDataServiceImpl` record-create 钩子 lazy 调用 | ✅ **DONE**(`RecordLevelSlaActivationIT` 2/2,RED→GREEN) |
| **③S5 dashboard 生成 skill** | 复用已存在 `dashboards.schema.json` 作 native tool-use inputSchema + `DashboardService.create` 落库;`DashboardGeneratorSkill` + live IT | ⬜ next |
| ①F2 demo 表遮蔽 | (删 schema.sql demo 表 + 修 CS agent IT)| ⏸ **owner 暂忽略** |

### 🔧 纯测试/基建 gap(直接执行)
| gap | 内容 | 依赖 | 状态 |
|---|---|---|---|
| S1-2 投诉自动指派 + 响应 SLA | automation update_record on create + RECORD SLA | 用 ②F3 | ⬜ |
| S6 工作台浏览器 golden | host-first Vite+Playwright,KPI/筛选/抽屉/0 exprError | — | ⬜ |
| S5 图表渲染浏览器 golden | ChatBI 渲染 + 新 dashboard 生成 golden | 用 ③S5 | ⬜ |
| S7 多 agent 真模型收敛测 | 不空转/不死循环 | — | ⬜ |
| ApprovalGate 超时真栈 IT | 替换现 mock-only | — | ⬜ |
| S4 create_model 端到端验证 | 验证现有 agent 部署路径 | — | ⬜ |
| RuntimeAuth enforcement 测 | 验 forbidden effect→deny | — | ⬜ |
| 真插件可达 host-first golden | import 真 crm/quality 插件 golden | **CRM 部分被 ①F2 阻塞** → 仅 quality 部分可做 | ⏸ 部分 blocked |

## 1. 验证纪律
真栈 IT(`BaseIntegrationTest`,integration-test profile→共享 aura_boot,非破坏自清);平台改动 TDD RED→GREEN + 回归;每片真凭据 XML + 验收报告;浏览器 golden host-first 零 docker;live IT 跑后 redact key。逐 PR 收口。
