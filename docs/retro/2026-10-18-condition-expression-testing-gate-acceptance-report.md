---
type: retro
status: active
created: 2026-10-18
---

# 模型驱动组合条件编辑与真实引擎试算

allowed_claim: 组合条件主旅程已真实跑通；只读角色浏览器验收及全量回归仍有限制，不声明整个平台通过。

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

## 本轮执行结果

受管证据目录：`/Users/ghj/work/auraboot/.workspace/evidence/acceptance-detail-tabs/runs/2026-10-18T05-00-31-217Z-86dfb3d0-8dec-403a-8197-f388887fd286`。

| 层 | 固定入口/范围 | 执行 | 通过 | 失败 | 跳过 | 证据 |
|---|---|---:|---:|---:|---:|---|
| 后端单元 | `:test` / ConditionAstEvaluatorTest + DecisionRuntimeNucleusTest | 32 | 32 | 0 | 0 | backend-unit-final.log；Gradle XML |
| 前端相关目录 | `vitest run app/shared/decision app/ui/smart/decision` | 414 | 403 | 11 | 0 | frontend-unit-final.json/log |
| 基线对照 | 修改前82f583aa的两个相关测试文件，filesystem-only archive | 54 | 43 | 11 | 0 | baseline-unit.json/log |
| 浏览器真实栈 | playwright.condition.config.ts / condition-expression-real.spec.ts | 1 | 1 | 0 | 0 | browser-08/condition-browser-results.json、receipt、trace |
| 证伪 | 放开不兼容字段比较：失败；恢复：通过 | 2次 | 恢复通过 | 注入失败 | 0 | mutation-receipt.json、red/restored.log |

11个前端失败的完整名称与基线一致，集中在 DecisionOpsConsole 旧工作台模拟与期望；不能称前端全量绿色。
全量 tsc 还有既有 WidgetRenderer 对 WidgetConfig.ring/showLabel 的2项错误（typecheck.log），不声称编译全绿。
后端单元行覆盖不替代旅程覆盖；real-stack integration coverage 未测量，不声明覆盖率达标。

浏览器通过真实菜单进入条件片段库，创建本轮随机编码的新规则，真实目录包含交付验收/项目字段。
五个条件形成嵌套 AND、OR、NOT；金额128000、项目预算150000、高风险时成立；普通风险时不成立；
预算改110000后通过字段比较分支成立；已验收状态被NOT排除；预算缺失为UNKNOWN。
每次输入变化旧结果失效。草稿保存/刷新回读 AST 一致；发布测试自有片段作为夹具后，UI创建新版本，
GET版本列表证明原版本不变。发布UX自身不在本轮浏览器通过声明内。

试算请求 explain=true，断言实际左右值、逐节点三态、actionPlans为空；试算前后 __test__ 执行日志总数均0。
匿名同源请求401。只读编辑禁用、隐藏字段、非连续项拒绝包裹、移除保留/删除与迟到响应隔离有单测，
尚未完成真实受限角色的浏览器旅程，因此 E10 保留 partial，不用匿名HTTP替代这一缺口。

## 截图与视觉复核

browser-08/screenshots 的11张原图已逐张以 agent-vision 复核；E06同时提供下半区域，证明内层滚动内容入镜。
模型/字段和值有明确列层次；括号、AND/OR及NOT形成可读结构；右侧展示显式样例输入与后端判断依据。
枚举浮层标签、选择态清楚；移除确认区分保留条件/整组删除；旧结果失效有文字反馈。
发现并修复试算按钮被旧hover样式覆盖导致浅底白字的问题，后续图已使用修复样式。
当前1440×1000桌面范围可用，复杂规则需纵向滚动；未执行移动端、读屏和真实用户可用性评估。
逐场景九维记录与保留缺口见同目录 manifest.md、condition-acceptance-matrix.json。

## Final Evidence Pack

```text
claim_level: targeted journey verified with explicit residuals
current_sot: docs/system-reference/web/model-driven-condition-editor.md
runtime: acceptance-detail-tabs / slot30 / http://localhost:5180
source_roots: frozen acceptance-detail-tabs Core/Enterprise/BPM + frozen workspace checkout
integration_tests: actual browser -> BFF -> backend evaluator and persisted fragment API
integration_coverage: coverage_not_measured
e2e_specs: tests/e2e/decisionops/condition-expression-real.spec.ts
e2e_execution: browser-08, 1 executed, 1 passed, 0 skipped, retries=0
feature_action_matrix: condition-acceptance-matrix.json
permission_negative: anonymous HTTP401; restricted-role browser not run
artifact_evidence: receipt.ast, immutableVersionPid, nextDraftPid
visual_feedback: agent-vision individual original review, desktop only
skip_fixme_threshold_retry_audit: no skip/fixme/retries/API fallback replacing UI editor journey
did_not_run: whole product regression, restricted-role UI, mobile, screen reader, integration coverage
remaining_blockers: E10 role UI partial; 11 baseline unit failures; 2 baseline type errors
allowed_claim: targeted editor and authoritative trial work; no full regression/UI golden pass claim
```

## SOT Updates

- 稳定契约沉淀至 `docs/system-reference/web/model-driven-condition-editor.md`，README增加入口。
- 企业DecisionOps总览的职责边界未改变；没有新增规则引擎、业务页面或DSL格式。
- 本报告保留 active，以保留只读角色浏览器和全量基线债务，不伪装为所有缺口关闭。
