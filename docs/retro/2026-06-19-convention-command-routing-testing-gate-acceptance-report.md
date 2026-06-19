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

---

# 验收报告补遗 — Phase 1 OSS 插件页面清扫(2026-06-19)

> 前提:Phase 0 已 merge(#874,`e8e3c9129`),约定机制对**任意模型**已生效(page-schema 端点对所有 model 挂 `commands`,`FormPageContent` 对所有 model 按模式解析)。Phase 1 = 把 OSS authored 插件页面里**冗余的** create/update 命令清掉,让它们走约定 → 干净 URL。

## allowed_claim(Phase 1)

**`targeted pass` — OSS authored 页面 create/update 命令清扫完成并验证**:对所有 OSS 插件页面做了**安全清扫**(仅当按钮 command 正好等于该模型约定 create/update 时才去掉,变体/跨模型/内联命令自动保留),crm-starter + org-management 真浏览器 golden 通过。**不含** enterprise overlay 与 delete 命令约定(见 did_not_run)。

## 关键澄清(范围收敛的依据,取证)

- **`?commandCode=` 只来自 navigate 动作**(create/edit 跳转表单);**delete 是原地 `type:command`、不 navigate → 不产生 URL 参数**,故 delete 不在 URL 清理范围(其约定化是独立的零配置增强,无 URL 收益)。
- **生成器 `autoCreateDefaultPages` 产出空 stub**(`[{"blockType":"toolbar"}...]`,**无任何带 command 的按钮**)→ 无需改生成器;`?commandCode=` 仅源于 authored 插件页面。证据:`MetaModelServiceImpl.java:2144-2156`。

## 清扫范围与安全规则

- 规则:`code∈{create,edit}`+`action.type=navigate`+`command==约定[model].create/update`,或 `code=submit`+`action.type=command`+`command==约定 create/update`(且模型同时有 create+update)→ 去掉 `command`;否则保留。约定映射来自 `ab_command_definition.execution_config.type`。
- 结果:**15 文件 / 18 命令**(crm-starter 12:6 list create + 6 form submit;org-management 6:3×(create+edit))。showcase 的 create/update 已在 Phase 0;wd(workflow-demo)**正确未动**(`create_and_submit` 变体 + `save_draft` 非 submit + `wd:update_leave_balance` 内联 `type:command`)。
- 正确跳过取证:`crm_account_detail` 的 add/edit/delete 是 **crm_contact 子资源**命令(≠本页模型约定,跳);`wd_leave_balance_list` edit 是内联 `type:command`(非 navigate,跳)。

## 测试层矩阵(Phase 1)

| 层 | 状态 | 证据 |
|---|---|---|
| 平台 validator(全 OSS swept 插件 re-import) | ✅ tested | slot-55 `import-plugins.sh demo`:org-management / crm-starter / showcase 全 **OK** + reference-integrity OK |
| Web E2E 真浏览器 golden | ✅ golden(4/4) | `/tmp/golden-sweep.mjs`:crm_account + org_department 各「干净 URL 创建→正确约定命令 200」+「列表 新建 → 干净 URL」 |
| 前端/后端回归 | 复用 Phase 0 | Phase 1 无代码改动(纯 JSON 数据),机制单测/回归见上半报告 |
| diff 安全性 | ✅ | 结构化 round-trip 0 噪声,`git diff --stat` 15 文件 18+/36-(仅删 command 行) |

## did_not_run(Phase 1,明确)

- **enterprise overlay 插件页面清扫**:`auraboot-enterprise` 是独立仓(plugins/web-admin-ext),需另起 worktree+栈;本轮未做。**下一增量**,recipe 同 `/tmp/sweep-convention.mjs`(按 `execution_config.type` 精确匹配)。
- **delete 命令约定**:`useActionHandler` 行删除路径未接约定(delete 无 URL 收益);若要"删除按钮也零配置"需补 wiring。
- **全量逐页 golden**:仅抽样 crm_account/org_department(+ Phase 0 showcase)真浏览器;其余 swept 页面由平台 validator re-import 覆盖(非逐页 golden)。

## allowed_claim(总)

Phase 0 机制全平台生效 + Phase 1 OSS authored 页面清扫完成并抽样 golden 验证;**enterprise 与 delete 约定为下一增量**。非"全平台逐页 golden 完成"声明。

---

# 验收报告补遗 — Phase 2 删除命令约定 + enterprise 范围核实(2026-06-19)

> 完成 OSS「标准页零配置」全 CRUD 闭环:删除按钮也由约定路由(`commands.delete`),无需配置。

## allowed_claim(Phase 2)

**`targeted pass` — 删除约定接入 + OSS 删除命令清扫,真浏览器 golden 验证**:`useActionHandler` 命令路径在按钮无显式 command 时按 `operationType` 从 `runtime.getSchema().commands` 解析(create/update/delete 三类统一),OSS 列表删除按钮去掉冗余 delete 命令;showcase + crm_account 真删除 golden 通过。

## enterprise 范围核实(取证,结论:实质 no-op)

实测 `auraboot-enterprise/plugins/*/config/pages/` 的 create/update 命令引用 **仅 4 处,全在内部 `test-fixtures` 插件**(默认不导入,`AURA_ENV=test` 才启用,非生产页);`web-admin-ext` **0 处**。即真实 authored CRUD 页全在 OSS(已 #883 + 本轮清扫)。**enterprise overlay sweep 实为 no-op**,不单独起企业版栈做装饰性清扫。

## 改动

- 前端:`useActionHandler` 命令路径——`effectiveCommand = 显式 command || runtime.getSchema().commands[operationType]`(operationType 由按钮 code/label 推导 create/update/delete),并加无命令防御 throw。显式 command 仍优先。
- 数据:OSS 列表删除按钮去 delete 命令 **11 文件 / 11 命令**(crm-starter 6 + org-management 3 + showcase 1 + workflow-demo 1)。

## 安全规则与歧义处理(取证)

- 仅当删除按钮 `command` **正好等于后端 API 权威解析的** `commands.delete` 才去掉。删除每模型唯一 → 无歧义。
- **歧义模型显式保留**:`wd_leave_request` 有两个 `type=create` 命令,后端 `resolveCrudCommands`(putIfAbsent first-match)解析 create=`wd:create_and_submit_leave_request`(API 实测)。其 submit 命令**不动**(保留显式,避免依赖 putIfAbsent 顺序的脆弱性)。这修正了自建 last-write conv-map 与后端 first-match 不一致的隐患——改用 page-schema API 的 `commands` 作权威白名单。

## 测试层矩阵(Phase 2)

| 层 | 状态 | 证据 |
|---|---|---|
| 平台 validator(swept 插件 re-import) | ✅ tested | slot-55:org-management / crm-starter / showcase OK + reference-integrity OK |
| Web E2E 真浏览器 golden | ✅ golden(2/2) | `/tmp/golden-delete.mjs`:showcase + crm_account 行删除(按钮无 command)→ 约定解析 `sc:delete_showcase` / `crm:delete_account` 200 + 行数 6→5 / 2→1 |
| 前端回归 | ✅ tested | hooks + rendering pages **433 passed / 38 files**(含 useActionHandler async/bpm/flow-args 全套件,0 失败) |

## did_not_run(Phase 2)

- 逐插件删除 golden(抽样 showcase/crm;org/wd 由 validator re-import + 同一 useActionHandler 路径覆盖)。
- useActionHandler 命令解析的独立单测(内联于大 useCallback,未抽纯函数;由真浏览器 golden + 既有 hooks 回归覆盖)。

## allowed_claim(全任务总)

约定机制全平台生效(Phase 0)+ OSS authored 页面 create/edit/delete **零配置闭环**(Phase 1 + Phase 2)+ 抽样真浏览器 golden 全绿;enterprise 经取证为 no-op。非"全平台逐页 golden 完成"声明。

