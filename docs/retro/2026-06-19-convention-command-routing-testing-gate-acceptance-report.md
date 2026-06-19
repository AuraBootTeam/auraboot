---
type: retro
status: active
created: 2026-06-19
owner: diqi
related: ../backlog/2026-06-19-convention-over-config-command-routing.md
---

# 验收报告 — 标准 DSL 页面"约定大于配置"命令路由(Phase 0 showcase 切片)

> SOT:[`docs/backlog/2026-06-19-convention-over-config-command-routing.md`](../backlog/2026-06-19-convention-over-config-command-routing.md)。
> 本报告是本次测试声明的唯一来源,先于结论。

## allowed_claim

**`targeted pass` — Phase 0(showcase 模型切片)端到端验证通过**:约定命令路由在 showcase 创建/编辑全链路成立(干净 URL、无 `?commandCode=`、显式 override 保留),由后端单测 + 后端 live API + 前端单测 + 4 场景真浏览器 golden 共同支撑。

**不是**平台级 `completion-claim`:生成器 `autoCreateDefaultPages` 改造 + 全量 re-seed + 全量页面 golden + enterprise overlay 验证属 **Phase 1(did_not_run,见下)**。

## claim_level

`targeted-tested`(单一模型切片;非"完整/所有/黄金"总量词目标)。

## current_sot / business_scope

- `current_sot`:本次新建的设计 backlog(上方 related)。
- `business_scope`:`showcase_all_fields` 模型的**创建/编辑表单提交命令路由**;入口 = 列表「新建」按钮 + 行「编辑」+ 表单「保存」。
- `historical_or_superseded_rules`:无。
- **非目标(本切片)**:删除命令的约定路由(走 `useActionHandler` 另一路径,本切片未接,showcase 删除按钮显式 `command` 保留);其它模型;生成器与全量 re-seed。

## 改动面(被测对象)

后端:`PageSchemaDTO.commands`(新字段)/ `CommandService.resolveCrudCommands`(按 `execution_config.type` 解析)/ `CommandServiceImpl` 实现 / `PageSchemaController.getByPageKey` 填充。
前端:`PageSchemaDTO`+`UnifiedSchema` 类型 `commands` / `canonicalizePageSchemaDto` 透传 / `FormPageContent.resolveSubmitCommandCode`(显式 override > 约定 by mode > CRUD 回退)。
数据:`showcase_all_fields_list.json` 新建按钮、`showcase_all_fields_form.json` 提交按钮去掉 `command`。

## 测试层矩阵

| 层 | 是否需要 | 状态 | 证据 |
|---|---|---|---|
| 后端单测 | 是(新 service 方法) | ✅ tested | `CommandServiceResolveCrudCommandsTest` 5/5(XML `failures=0 errors=0`) |
| 后端 live / runtime seam | 是(page-schema 契约改变) | ✅ tested | slot-55 `GET /api/pages/key/showcase_all_fields_{form,list,detail}` 返回 `commands={create:sc:create_showcase,update:sc:update_showcase,delete:sc:delete_showcase}` |
| 后端编译/打包 | 是 | ✅ tested | worktree `:bootJar` BUILD SUCCESSFUL |
| 后端回归(DTO 相邻) | 是(DTO 加字段) | ✅ tested | `PageSchemaConverterMobileUxTest` 5/5、`PageSchemaDiffServiceTest` 9/9 |
| 前端单测 | 是(新解析逻辑) | ✅ tested | `resolveSubmitCommandCode` 6/6、`canonicalize commands carry-through` 2/2 |
| 前端回归 | 是(改 FormPageContent/canonicalize/types) | ✅ tested | meta rendering+utils 套件 **394 passed / 36 files / 0 fail** |
| Web E2E(真浏览器 golden) | 是(用户可见提交链路) | ✅ golden(4/4) | `/tmp/golden-convention.mjs` 对 slot-55 真浏览器 |
| 权限负向 | 否(本切片无新权限面) | did_not_run | 见下 |
| Artifact | 否(无导出/下载) | N/A | — |
| 集成覆盖率 jacoco | 否(targeted 切片) | coverage_not_measured | — |

## feature/action 覆盖矩阵

| 行为 | 入口 | 期望 | 状态 | 证据 |
|---|---|---|---|---|
| 创建(约定) | `/p/showcase_all_fields/new`(无 commandCode) | 路由 `sc:create_showcase`、URL 干净、落库 | ✅ golden S1 | exec=`sc:create_showcase` status=200,URL 无 commandCode,提交后跳列表 |
| 编辑(约定) | 列表行「编辑」→ `/p/showcase_all_fields/edit/<id>`(干净) | 路由 `sc:update_showcase`、回显后更新 | ✅ golden S2 | loadedName 回显、exec=`sc:update_showcase` status=200、editUrl 无 commandCode |
| 列表「新建」按钮 | 列表 toolbar | 跳 `/p/showcase_all_fields/new`、**无** `?commandCode=` | ✅ golden S3 | url=`/p/showcase_all_fields/new` |
| 显式 override(回归) | `/p/showcase_all_fields/new?commandCode=sc:create_showcase` | 仍路由 `sc:create_showcase` | ✅ golden S4 | exec=`sc:create_showcase` status=200 |
| 必填校验仍生效 | 表单提交 | `sc_name` 必填、`sc_code` 只读不误标必填(PR #840) | ✅ | S1/S2 提交经命令引擎;必填语义见 FormPageContent 既有 + #840 |

矩阵无 `draft/unknown/missing/partial` 行(在本切片 business_scope 内)。

## browser_evidence / backend_evidence

- browser:slot-55(host-first,零 docker)真 chromium + storageState;4 场景捕获 `/api/meta/commands/execute/*` 网络请求与状态码。
- backend:page-schema 端点 live 返回 commands(curl 实证)+ 单测 + 回归。

## did_not_run（明确未执行,Phase 1/范围外）

- **Phase 1 平台化**:`autoCreateDefaultPages` 生成器停配 command + 全量默认页 re-seed + 全量页面 golden + enterprise overlay 全量 import 验证。
- **删除命令约定**:`useActionHandler` 行动作路径未接约定(showcase 删除仍显式配置)。
- **权限负向 spec**:未新增。理由:页面 schema 端点权限(`PAGE_SCHEMA_READ`)与暴露面未变——`commands` 仅对本就能查看该页的用户返回,且页面 schema 一向含按钮命令码;无新增权限面。仍建议 Phase 1 补一条负向用例。
- **jacoco 覆盖率**:未测(targeted 切片)。

## skip_fixme_threshold_retry_audit

无 skip / fixme / threshold / retry 兜底;golden 无 `waitForTimeout` 兜底通过(用显式 `waitForSelector` + 网络断言),核心路径经真浏览器交互(非 `page.request`)。命令唯一性 422 通过随机名规避(非掩盖)。

## remaining_blockers

切片内:无。平台推广(Phase 1)为后续工作,非阻塞。

## Final Evidence Pack

```text
acceptance_report: docs/retro/2026-06-19-convention-command-routing-testing-gate-acceptance-report.md
claim_level: targeted-tested
current_sot: docs/backlog/2026-06-19-convention-over-config-command-routing.md
business_scope: showcase_all_fields create/edit form command routing (convention over configuration)
integration_tests: CommandServiceResolveCrudCommandsTest 5/5; backend regression PageSchemaConverterMobileUxTest 5/5, PageSchemaDiffServiceTest 9/9
integration_coverage: coverage_not_measured (targeted slice)
e2e_specs: /tmp/golden-convention.mjs (4 scenarios, real browser, slot-55)
feature_action_matrix: create / edit / list-new-button / explicit-override — all closed in scope
browser_evidence: 4/4 golden pass (clean-URL create, convention edit→update, list新建 clean URL, override preserved)
backend_evidence: live GET /api/pages/key/* returns commands map; :bootJar BUILD SUCCESSFUL
artifact_evidence: N/A (no export/download)
permission_negative: did_not_run (no new permission surface; PAGE_SCHEMA_READ unchanged)
visual_feedback: required validation preserved; readOnly required-marker fix (#840) intact
skip_fixme_threshold_retry_audit: clean (no skip/fixme/threshold/retry; no waitForTimeout pass; UI-driven)
did_not_run: Phase 1 generator + full re-seed + all-pages golden + enterprise overlay; delete-command convention; jacoco; permission-negative spec
remaining_blockers: none in slice
allowed_claim: targeted pass (Phase 0 showcase slice fully verified end-to-end; NOT a platform-wide completion claim)
```
