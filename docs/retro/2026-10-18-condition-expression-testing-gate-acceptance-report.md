---
type: retro
status: active
created: 2026-10-18
---

# 模型驱动组合条件编辑与真实引擎试算

allowed_claim: implementation in progress; no product completion claim yet.

## Feature Contract 与唯一跟踪入口

目标：从现有规则中心进入，使用真实元数据选择模型、属性、运算符和比较值，编辑嵌套 AND/OR/NOT 与字段间比较，保存 DSL 后重开回显，通过真实规则引擎试算并解释结果。

参照：2026-10-18 用户确认的 expression-v2 高保真交互稿（独立设计预览49851，非产品）。沿用现有 `ConditionBuilder`、`DecisionRuleBindingBlock`、条件片段/版本 API、`/api/decision/facts/catalog`、`SIMPLE_CONDITION/AST_EVALUATOR`。不新增业务 TSX 页面、不建立第二套 DSL/求值器，不合并或发布。本轮不扩展整个规则中心或 SLA 导航。

角色：有规则管理权限的编辑者；只读消费者；无求值权限者。状态：载入元数据、空组、非法配置、失效的旧结果、请求中、旧响应被丢弃、后端错误、TRUE/FALSE/UNKNOWN、只读。

副作用：保存仅写草稿/新版本，不更改已发布版本；试算不发布、不触发通知审批、不写执行日志；解释仅显式 explain 试算返回。

## 行动点 → DSL / 平台能力

| 行动点 | 复用能力 | 本轮平台增强 |
|---|---|---|
| 模型/属性/运算符/值 | 真实 fact catalog → FieldOption | 选择式控件、类型值、权限可见性 |
| 括号/AND/OR/NOT | 已有 Condition AST 与 ConditionBuilder | 连续同层项包裹、撤销重做、删除语义 |
| 字段间比较 | 已有 PathOperand | 兼容字段筛选、RHS选择 |
| 保存重开 | 规则绑定/片段版本 API | 保留同一序列化口径，无页面专用数据 |
| 试算解释 | 已有 DecisionRuntime + ConditionAstEvaluator | 显式 explain、逐节点三态与实际左右值、过期请求隔离 |

这是现有平台表达式控件增强和 DSL custom block 复用，不申请新增业务 React 页面。

## 四轴与验证分工

- pure-logic / hermetic / blocking-commit / unit：括号变换、字段兼容、三态边界。
- ui / hermetic / blocking-commit / unit：控件联动、撤销重做、只读、旧响应隔离。
- journey / real-stack / blocking-commit / browser：真实菜单进入、元数据、编辑、草稿保存/刷新、后端试算解释及失败态。
- api / real-stack / blocking-commit / http：拒绝无权限求值，明确不写执行日志；与 browser 证据分开。

机器账本与截图预留位：受管 evidence round 的 condition-acceptance-matrix.json。保留未执行项；不通过截图数计算覆盖率。

## 当前执行记录

- 第一轮前端既有测试因旧多选/AND按钮契约及数字字符串期望失败；新设计改为 checkbox 集合与组关系 select，数值 DSL 使用 number，同步对应断言。
- 初次 Gradle `test --tests ...` 命中过多子项目，plugin-api 无对应测试而退出1；改用精确根项目 `:test`，不更改门槛。
- 全量 tsc 首次发现本轮类型问题和旧 WidgetConfig.ring/showLabel 两项错误；本轮类型修复后重新核对，未用过滤输出来声明全量通过。

## Final Evidence Pack（执行后回填）

```text
acceptance_report: this file
claim_level: implementation-in-progress
current_sot: Enterprise DecisionOps system-reference 101; this Feature Contract
business_scope: approved expression editor journey
integration_tests: pending
integration_coverage: coverage_not_measured
e2e_specs: pending
e2e_collection: pending
e2e_execution: pending
feature_action_matrix: condition-acceptance-matrix.json
browser_evidence: pending
backend_evidence: pending
artifact_evidence: persisted Condition AST and version
permission_negative: pending
visual_feedback: pending
skip_fixme_threshold_retry_audit: pending
did_not_run: not yet finalized
remaining_blockers: implementation/verification ongoing
allowed_claim: implementation in progress
```
