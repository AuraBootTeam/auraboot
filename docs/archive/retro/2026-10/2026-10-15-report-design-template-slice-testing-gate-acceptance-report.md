---
type: retro
created: 2026-10-15
status: closed
distilled_to:
  - web-admin/app/plugins/core-designer/components/report-designer/README.md
  - web-admin/app/ui/meta/README.md
---

# 报表设计模板切片验证

allowed_claim: targeted-tested；仅 Web 单元与组件测试执行；Java 单元与真栈 IT 待执行。browser E2E did_not_run（executed=0）。未声明完整七场景验收。

本切片为既有报表设计器增加定义交换，不替代结果导出。使用已有统一文档历史；导入是一次可撤销修改，保存目标不变。设计模板导入、字段校验与 read-only 禁用已有窄测试。业务新建模型与页面/报表浏览器路径已写入候选测试，需在主会话统一 Enterprise 栈运行。

```text
acceptance_report: this file
claim_level: targeted-tested
current_sot: web-admin/app/plugins/core-designer/components/report-designer/README.md
business_scope: report-template parse/export/import/undo/readonly; model/page/report browser candidate journeys
integration_tests: did_not_run; MetaFieldBusinessLabelTest and MetaFieldServiceIntegrationTest.testBusinessLabelPersistence registered; did_not_run
integration_coverage: coverage_not_measured
e2e_specs: web-admin/tests/e2e/showcase/customer-designers-journey.spec.ts
e2e_collection: 3 tests collected with PW_PROFILE=full --project=chromium --no-deps --list
e2e_execution: executed=0; did_not_run pending shared Enterprise runtime
feature_action_matrix: template roundtrip, invalid format/version/shape/identity, missing sources, duplicate ids, size limit, import feedback, undo/redo and readonly covered by narrow tests; saved reopen/download remain browser candidates
browser_evidence: none
backend_evidence: none
artifact_evidence: definition roundtrip unit assertions; customer fixture included; actual browser download did_not_run
permission_negative: component-level disabled import/export; backend role-denial browser proof did_not_run
visual_feedback: inline error/import status component assertions; original screenshot review did_not_run
skip_fixme_threshold_retry_audit: narrow tests have no skip/fixme/retry; browser tests have no mocks/retry/afterAll cleanup
narrow_execution: vitest six report-template/provider/field-label/create-action/resolver spec files; 34 passed, 0 failed
mutation_evidence: field label changed to mutated-label in the owned source; field-label unit runner exit 1; source restored in finally; narrow suite rerun green
static_typecheck: tsc --noEmit --pretty false exit 0
remaining_blockers: Java test execution, menu-driven browser execution, runtime source identity, original screenshot review, full permission negative, actual downloaded file proof; no backend compile/runtime pass claim
allowed_claim: report-template narrow tests passed; browser journeys collected only
```

验证命令从 `web-admin` 运行：

```bash
./node_modules/.bin/vitest run app/plugins/core-designer/components/report-designer/components/__tests__/ReportTemplateActions.test.tsx app/plugins/core-designer/components/report-designer/services/__tests__/reportTemplateIO.test.ts app/plugins/core-designer/components/report-designer/state/__tests__/ReportDocumentProvider.test.tsx
./node_modules/.bin/tsc --noEmit --pretty false
PW_PROFILE=full ./node_modules/.bin/playwright test tests/e2e/showcase/customer-designers-journey.spec.ts --project=chromium --no-deps --list
```

源码基线 `68311bd4683bd6df78538963a34e74c9f5d6e414`，隔离分支 `codex/enterprise-scenarios-designers`。未启动、重置或停止任何运行环境；未发布或合并。

## 字段业务名称链路补充

新增业务显示名称，实际 payload 为 `uiSchema.label`；创建前检查既有编码，避免误创建版本。
源码审计定位 `MetaFieldServiceImpl.createDirectly` 丢弃 `uiSchema`、DTO 转换遗漏，以及通用表单父/子字段仅从 extension 取名称。现已补齐这些路径，更新版本未提供 UI 配置时保留旧值。使用既有列，无 schema 迁移。Java DTO 单测与字段创建/版本真栈 IT 已新增，尚未在本隔离树执行。

Web 窄测试包括必填业务名、非法编码、有效字段创建与绑定 payload、取消不写入、重复编码不创建、不绑定；模板解析、undo/redo 与 read-only 测试保持执行。故意将提交 label 改成 `mutated-label` 后，字段单测退出 1；恢复原文件后重新运行六个窄测试文件，34 条通过。变异只证明 Web label 断言有效，不能替代数据库残留/权限验收。

真实 Enterprise 栈应再核对新字段 `uiSchema.label` 与有效 `displayName`、发布/生成后的表单和列表名称、刷新后的持久化、取消及重复编码没有多余版本/绑定，以及只读用户不能写配置。

## S2 业务记录与只读身份候选验证补充

同一个 SC02 候选测试已扩展：从生成后的真实业务列表点击新建，填写客户名称/商机金额，断言 POST payload 和返回 pid；从该行编辑金额 42000 → 68000，断言 PUT payload、刷新与 GET readback，再打开编辑表单确认 68000。

测试使用 task-owned 自定义只读角色，仅授予刚发布模型的 read permission；重用从作者页面预览进入的真实运行 URL，验证同一条记录可读、新建与编辑入口不可见、直接 PUT 返回 403，随后由管理员反查金额仍为 68000。角色、用户与模型记录保留为验收痕迹，不发送邀请邮件。不依赖业务成功路径的 API 写入；角色配置是 fixture setup，直接 PUT 仅用于权限否定探针。

该扩展仍为 collected-only：3 条浏览器用例已重新收集，executed=0。运行与视觉复核归主会话统一 Enterprise 栈。本次仅改候选测试与本报告，不以 collection 代替业务验证。
