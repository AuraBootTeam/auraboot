# 规则中心消费者页面复核报告 2026-06-13

## 范围

本轮复核覆盖这些“消费规则中心能力”的页面和平台管理页:

- Automation: `/automations`, `/automation/new`
- BPM: `/p/bpm_process_management`, `/bpmn-designer`, `/bpm/task-center`
- SLA: `/bpm/sla-monitor`, `/p/sla_config`
- Permission: `/enterprise/permissions`, `/p/data_permission`
- Connector/Webhook: `/p/api_connector`, `/p/webhook`

运行环境:

- backend: `http://127.0.0.1:6453`
- BFF: `http://127.0.0.1:3543`
- frontend: `http://127.0.0.1:5243`

## 页面布局结论

真实浏览器截图和 DOM 量化结果已保存到:

- `test-results/rule-center-consumer-audit/consumer-layout-audit-2026-06-13.json`
- `test-results/rule-center-consumer-audit/*-consumer-audit.png`

本轮检查项:

- 11 个页面均无水平溢出。
- 11 个页面均未再出现按钮中文字纵向换行的问题。
- `/bpmn-designer` 工具栏已从纵向挤压修复为横向按钮组。
- `/p/api_connector` 和 `/p/webhook` 确认为平台 DSL 管理页，布局正常，不是 DecisionOps 手写页面。

## 已完成闭环

### API Connector

真实浏览器已完成:

- `/p/api_connector` 新建
- `auth_type` 自定义下拉选择
- JSONB textarea 填写 `default_headers` / `retry_policy`
- 列表回显
- 编辑页回显
- 保存后后端反查
- 行操作更多菜单删除
- 删除后后端反查清零

修复缺陷:

- 编辑页加载 JSONB 时，后端返回 `{type:"jsonb", value:"..."}` envelope。
- 旧逻辑会把 envelope 当业务 JSON 再保存，形成嵌套包装。
- 已在通用 Meta Form 层修复 JSON/JSONB 加载和提交归一化。

新增回归:

- `web-admin/tests/e2e/admin/api-connector-lifecycle.spec.ts`
- 覆盖创建、编辑回显、JSONB 不嵌套包装、删除。

### Webhook

真实浏览器已完成:

- `/p/webhook` 新建
- `event_type=record_created` 自定义下拉选择
- 复杂过滤表达式保存: `all + any + not`
- headers 保存
- 列表回显
- 编辑 `max_retries`
- 后端反查
- 行操作更多菜单删除
- 删除后后端反查清零

说明: Webhook 已有 `web-admin/tests/e2e/admin/webhook-lifecycle.spec.ts`，本轮未新增重复 spec。

## 条件/规则语义结论

已验证通过:

- 前端 AST / preview / policy / table 单测: 38 个通过。
- 后端规则消费者语义测试通过:
  - `ConditionAstEvaluatorTest`
  - `AutomationDecisionE2EIntegrationTest`
  - `SlaDecisionE2EIntegrationTest`
  - `PermissionConditionGuardIT`

后端已覆盖:

- nested AND/OR
- NOT
- missing field -> UNKNOWN
- UNKNOWN -> Permission deny
- Automation 引用决策匹配触发
- SLA 由决策输出 deadline

## 真实 Gap

### G-RULE-UI-1: 条件配置 UI 仍不是完整 AND/OR/NOT

`web-admin/app/shared/decision/ui/ConditionBuilder.tsx` 当前明确是 flat group:

- 支持平铺条件行。
- 支持根级 AND / OR 切换。
- 不支持在 UI 中新增 nested group。
- 不支持在 UI 中包一层 NOT。

因此不能声称“复杂 AND/OR/NOT 在 Automation/BPM/SLA/Permission 跨模块 UI 中完整配置、保存、触发”已完成。当前真实状态是:

- 后端 AST 语义已通。
- 前端 AST preview 已通。
- Webhook textarea 可保存复杂表达式文本。
- 可视化 ConditionBuilder 还没有 nested/NOT authoring 能力。

建议下一步:

1. 将 `ConditionBuilder` 从 flat group 升级为树形条件构造器。
2. 增加 UI 行动点: add condition / add group / wrap NOT / unwrap NOT / delete / reorder。
3. 所有消费者统一使用这个 block，而不是各模块自写条件 UI。
4. 浏览器 E2E 必须覆盖 `ALL(ANY(...), NOT(...))` 的配置、保存回显、后端 runtime true/false、trace。

### G-RULE-XMOD-1: 跨模块 UI 触发矩阵还未完整

本轮验证了后端消费者语义和关键页面布局，但还没有完成以下全路径浏览器矩阵:

- Automation UI 中配置复杂条件 -> 保存 -> 触发事件 -> action 被执行/不执行。
- BPM gateway/property panel 中配置复杂条件 -> 保存 BPMN -> 发布 -> 启动流程 -> 路由命中。
- SLA 配置中引用决策/条件 -> 触发流程节点 -> deadline/breach/escalation audit。
- Permission ABAC 条件从权限页配置 -> 保存 -> 非授权用户访问 -> 403/按钮禁用。

这些必须作为后续独立 E2E 工作项，不应被后端单测替代。

## 验证命令

```bash
pnpm --dir web-admin exec vitest run \
  app/framework/meta/rendering/pages/__tests__/FormPageContent.test.ts \
  app/plugins/core-designer/components/bpmn-designer/components/__tests__/BPMNToolbar.test.tsx \
  app/framework/smart/automation/components/__tests__/AutomationEditor.test.tsx \
  app/shared/decision/ast/__tests__/conditionAst.test.ts \
  app/shared/decision/ui/__tests__/ConditionBuilder.test.tsx \
  app/shared/decision/ui/__tests__/DecisionConditionDesigner.test.tsx \
  --reporter=dot
```

结果: 6 files / 36 tests passed。

```bash
pnpm --dir web-admin exec vitest run \
  app/shared/decision/ast/__tests__/conditionAst.test.ts \
  app/shared/decision/ui/__tests__/ConditionBuilder.test.tsx \
  app/shared/decision/ui/__tests__/DecisionConditionDesigner.test.tsx \
  app/shared/decision/policy/__tests__/policyPreview.test.ts \
  app/shared/decision/table/__tests__/decisionTable.test.ts \
  --reporter=dot
```

结果: 5 files / 38 tests passed。

```bash
NO_PROXY=localhost,127.0.0.1 PW_BASE_URL=http://127.0.0.1:5243 \
  npx playwright test tests/e2e/admin/api-connector-lifecycle.spec.ts --project=chromium
```

结果: 23 passed / 1 skipped。

```bash
./gradlew test \
  --tests 'com.auraboot.framework.decision.ast.ConditionAstEvaluatorTest' \
  --tests 'com.auraboot.framework.decision.AutomationDecisionE2EIntegrationTest' \
  --tests 'com.auraboot.framework.decision.SlaDecisionE2EIntegrationTest' \
  --tests 'com.auraboot.framework.permission.PermissionConditionGuardIT' \
  -x :platform-plugin-api:test \
  -x :platform-storage-s3:test \
  -x :platform-storage-oss:test \
  -x :platform-storage-minio:test \
  -x :platform-mq-kafka:test \
  -x :platform-mq-rabbitmq:test
```

结果: BUILD SUCCESSFUL。

```bash
pnpm --dir web-admin exec tsc --noEmit --pretty false
```

结果: passed。
