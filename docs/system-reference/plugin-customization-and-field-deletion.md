---
type: system-reference
status: active
updated: 2026-11-19
---

# 插件定制保护与字段删除准入

本文定义 Page、Dashboard、Menu、Agent 的插件重导入保护，以及普通字段删除的保留数据边界。架构审查、需求和过程账本位于 Enterprise 的 `platform-business-system-foundation.md` / `2026-10-30-business-foundation-journeys.md`。本文不定义通用三方合并、破坏性迁移或自动回退。

## 安全覆盖

| 策略 | 已存在资源 | 保护状态读取 |
| --- | --- | --- |
| ERROR | 拒绝冲突 | 无需读取标记 |
| SKIP | 保留内容、归属、基线和标记 | 无需读取标记 |
| OVERWRITE_SAFE | 只更新已跟踪且未修改的资源；修改过或未跟踪的既有内容跳过 | 必须成功读到已跟踪记录的非空标记；否则失败 |
| OVERWRITE | 操作者明确选择替换 | 不以修改标记作为阻断条件；其他权限/校验继续有效 |

Page 使用 `ResourceType.PAGE/page`，Dashboard 使用 `ResourceType.DASHBOARD/dashboard`，两者的同名资源互不混用。资源类型及导入次序由 `ResourceType` 维护，Dashboard 的物理表为 `ab_dashboard`。

用户保存 Page、更新/发布/取消发布 Dashboard 时，Controller 事务包含修改标记与内容操作。`PluginResourceTracker` 使用小写数据库 code、行锁和精确一行写入判定；标记查询/写入失败会中止该事务。插件导入调用 service，不经过这些用户 Controller。

导入沿用现有程序化事务和资源行锁。`PluginImportSupport` 统一读取保护状态；不得把存储错误或未知标记解释成未修改。锁定查询使用独立的强类型状态投影，显式读取 `user_modified`，避免缺失列或 NULL 被实体的 Java 默认 false 掩盖。SKIP 不写入资源跟踪基线，尤其不能清空旧 `user_modified`，否则第二次安全导入会失去保护。

预览按真实资源存在性判定 CREATE/UPDATE，并使用小写资源身份和强类型保护投影。既有未跟踪 Page/Dashboard 也按需保留的内容展示；查询故障/NULL 不可被静默显示为“未修改”。预览提供的是资源变化与保护提示，执行仍在导入事务内重新检查，不是锁定未来状态的承诺。

自动 stub 是有限例外：只有 `auto_created=true` 且 `rowVersion=1` 的未编辑 stub 允许标准定义接替。通过页面 service 保存会移除 stub 标记；历史编辑后的 stub 不再仅凭标记绕过保护。

## 菜单接管、重导入与退出

菜单的 `OVERWRITE_SAFE` 只同步同租户、同 plugin ID 的已跟踪且未修改资源；不同安装 PID 可以属于同一 plugin ID。既有未跟踪、已认领、已修改或属于另一插件的菜单均保留，SKIP 不转移归属或重写基线。已跟踪记录的修改标记为 NULL 或读取失败时中止导入，不能视为未修改。

跨插件菜单接管需要操作者显式选择 `OVERWRITE`；它沿用既有覆盖权限与校验，不是自动迁移授权，也不提供通用资源移交或回退协议。接管保留菜单 PID，更新菜单行和 tracking 归属。旧包之后以默认安全策略重导入原声明时，不会自动抢回菜单；旧包显式选择 OVERWRITE 仍表示再次接管，因此不能把它作为安全重导入使用。

声明瘦身的 prune 与插件卸载的菜单清理共用保护选择：仅选择 tracking 明确属于 shared/plugin_owned、修改标记明确为 false、菜单行与 tracking 的 plugin PID 一致、且非 authoringManaged 的本租户菜单。用户认领、修改、未知保护状态和归属不一致的菜单不自动清理。选择时锁定菜单及 tracking 行，锁持续到导入/卸载事务结束。

未修改的旧菜单仍按原规则退出；被接收方接管的菜单不再属于旧包清理范围。有保留子菜单时，不能清理其父菜单；整个清理失败并保留原层次，不静默重挂父级。停用插件与卸载插件仍是不同操作。

真实 PostgreSQL 回归入口：`:platform-app:test --tests '*PluginMenuHandoverIntegrationTest'`。用例覆盖用户认领/修改、显式接管、旧包原声明安全重导、瘦身、停用/卸载、同包重复更新、未知标记、authoringManaged、租户隔离和保留子菜单。它验证菜单服务与数据归属，不等同权限/UI验收，也不证明 Agent、角色等其它类型具备相同接管合同。

## 已退役薄包装的保留式接管

以下旧安装身份退出源码分发，已有资源交给现有安装单元；不会在启动时自动迁移或卸载。

| 旧 plugin ID | 接收安装单元 | 功能提供方 |
| --- | --- | --- |
| `com.auraboot.platform-admin-ee` | `com.auraboot.platform-admin` | Enterprise 授权管理附加菜单 |
| `com.auraboot.d7-knowledge-wiki` | `com.auraboot.core-aurabot` | Enterprise D7 工作台、权限和导航；私有实现/知识不进入 OSS |
| `com.auraboot.ai-employees` | `com.auraboot.core-aurabot` | OSS Agent；四个角色改为可选模板，已有员工保留 |

模块通过 `META-INF/auraboot/package-replacements/*.json` 声明确切旧身份、接收方、资源白名单和附加声明；Enterprise 合同仅随 Enterprise 制品提供。接收方沿用既有 plugin ID，不新增替代插件。接管只在显式接收方导入事务中运行；既有资源待移交时必须选择 `OVERWRITE_SAFE`，其它策略拒绝。菜单和权限的安全导入保留其它 owner 或已认领资源，角色保留现有授权；接管随后只转移资源行与 tracking 的 owner，不重建内容、不重置 claim/修改标记/基线、不更改原 importId、身份、员工关联或授权绑定。

附加声明只合并其明确支持的菜单/权限/角色/i18n列表，不对整个manifest做JSON往返转换，避免DTO计算属性混入页面DSL；原页面对象及未知字段校验保持原样。Agent能力预检先于接管检查执行，缺模块时在普通资源读写前拒绝。

接管检查租户、事务、完整 tracking、资源 PID、实际 owner、类型/代码白名单和非 NULL 保护状态，异常整笔回滚。旧包退出前重新校验 inventory，仍有未接收资源则拒绝。旧 plugin ID 的重导、版本升级和历史导入回滚均明确拒绝并指向接收方，不静默成功或覆盖接收方。接管成功后可从原生命周期或资源卸载入口退出旧安装记录。必须先升级 Core/Enterprise 制品，再导入接收方；旧版本运行时不具备此合同，不自动迁移生产。

成功导入 receipt 记录 `packageResourcesAdopted`。含接管的导入拒绝通用 import rollback，防止退回旧插件元数据而遗留转交资源；需要恢复时保留接收方并显式审查归属，不提供自动反向接管。

`core-aurabot` 的 `agentTemplates` 仅是提供方无关的配方，只有请求指定 `agentTemplateCodes` 才成为当前导入的 Agent 定义。默认不创建四个员工；模板首次创建为 disabled、不绑定模型或组织身份，由租户显式配置 provider、工具范围、启用并发布不可变版本后，再走既有入职流程。入职前检查有效部署，未发布模板明确拒绝且不创建用户/成员/员工；组织入职/退出与动态字段授权共享 REPEATABLE_READ 写事务，失败整体回滚。已有认领/修改资源仍按安全策略保留。CLI 接收：`aura plugin publish <receiver-directory> --conflict-strategy overwrite_safe --yes`；选择模板可追加 `--agent-templates aria`。Enterprise 注册表拒绝旧包名，防止跨根目录回退。

原 `defaultBootstrap` 通配授权配置不转为新默授权；既有授权保留，新角色按明确权限初始化。真实 PG 合同用例为 `PluginPackageReplacementIntegrationTest`；Enterprise 声明以相同字节的测试 fixture 验证，不代表已执行 D7 页面、内部知识或授权业务的浏览器验收。

首次启用使用`POST /api/agent/definitions/{agentPid}/activate`，请求显式提供`providerCode`，要求当前租户配置可用且调用者有`acp.runtime.manage`。停用定义、供应商绑定、插件定制保护标记和首版不可变发布/部署在同一事务完成，失败全部回滚；不自动入职，不增授工具、角色或模型权限。相同供应商的重复启用复用同一发布；已启用后不能用此入口更换供应商。`resume`只恢复已暂停部署；未启用模板的resume/publish返回明确业务拒绝，不再500。后续发布沿用现有publish入口；首次启用不通过通用dynamic update绕过字段writer。

现有 AI 同事详情页（`/p/c/ai_colleague_detail`，`AgentDetailTabs` custom block）向具有运行管理权限的用户提供“首次启用并发布”入口。停用模板必须显式选择当前租户已配置的 AI 服务；配置为空、读取失败和未选择服务均不得提交。等待期间阻止重复提交，失败保留选择并提供错误反馈。成功后刷新定义并保留独立入职操作；停用模板不显示暂停或入职入口，版本页禁止直接发布并提示先启用。服务发现接口返回裸数组，不能经 ApiResponse 归一化客户端读取。

同事列表必须区分无权限、加载失败和成功空数据，失败时不显示空列表或创建入口。版本列表的`matches_draft`由数据库使用与发布相同的`ab_agent_release_spec`内容散列比较，不使用`updated_at`推断；暂停部署仍关联当前版本，并以暂停状态展示。页面显示名称、同事类型译名、版本号与业务状态，不显示Agent技术码、发布PID/hash或内部策略版本。部署范围使用名称选择用户/组织成员/角色；既有policy响应的`channelPolicy.subjectChoices`只读补充当前租户候选，身份值为字符串避免精度丢失，保存时仍经既有标准化为内部权限列表，候选名称不写入策略快照。缺失候选以不可用原选择展示并保留原身份，不回退裸编号。

## Agent 保护、接管与完整恢复

Agent 的 `OVERWRITE_SAFE` 使用锁定的强类型修改状态；读取异常、已跟踪记录的 NULL 标记均拒绝写入，不能按未修改继续。已有未跟踪、用户修改/认领或其它 plugin ID 拥有的定义返回 SKIP，保持内容、评测用例、owner 和基线。同包未修改定义可同步；显式 `OVERWRITE` 仍是操作者选择的替换/接管策略，不会额外授予工具或模型权限。

导入在现有 `ab_plugin_resource.previous_state/current_state` 保存 schemaVersion=1 的完整前/后像：Agent 数据库配置行（含 NULL、身份/组织关联、运行配置与授权范围）、该租户/code 的评测用例行，以及前一份 tracking。评测用例恢复保留原 ID/PID、plugin_source、删除状态和元数据；不另建历史库或向普通用户新增快照接口。Agent 对应插件的导入元数据沿用已有版本化 metadata snapshot。

显式回滚只接受该插件最新成功导入、仍由该 import 拥有且资源数量相符的记录。恢复前锁定 tracking、定义和评测行，核当前数据库内容与导入后像一致；新用户修改/认领、评测变化、转给第三方、跨租户、缺失或不支持的快照均拒绝。旧快照没有完整前态时不猜默认值。原 owner 已退役/缺失也拒绝恢复，不自动重装旧包。

满足条件后，在同一事务恢复配置、评测用例、原 owner/import ID、原修改/认领标记、基线及旧插件元数据。tracking 使用能恢复 NULL 并刷新 MyBatis 本地缓存的 Mapper 写入，避免后续清理读取旧列表误删已恢复记录。恢复后对完整内容复核，不完整则回滚整个事务。显式替换前已经存在的用户认领可随原快照恢复；接管后新认领仍拒绝回滚，二者不混同。

资源感知的卸载调用 Agent provider，按当前 owner/后像核验后停用定义与评测行；用户认领资源继续保留。接管后旧包按安全策略重导、停用/卸载不能抢回或删除接收方定义。显式覆盖仍可能再次接管，不能当作安全重导。此合同不提供卸载后任意重新激活或通用业务数据回退；后续有新事实时需要单独处置。

真实 PostgreSQL 入口：`:platform-app:test --tests '*PluginAgentHandoverIntegrationTest'`；组件/IM 投影回归为 `:platform-app:integrationTest --tests '*AgentConsumerBoundaryPostgresIT'`。前者验证公共导入、回滚、资源退出与拒绝路径，后者验证真实 Mapper/provider、IM 显示和租户边界。Hermetic 用例只保留 provider 委托合同，不能用 mock Mapper 的调用断言冒充完整恢复。未由这些用例证明 HTTP 权限、模型调用、工具执行或其它资源类型迁移安全。

## 既有安装升级

两个新增 Flyway migration 负责旧记录转换：

- `V20261030113000` 按 Dashboard 的真实 PID 和 tenant 转换旧 `page` 跟踪记录；不能把真正的 Page 跟踪记录错转成 Dashboard。
- `V20261030123000` 对旧 Page/Dashboard 记录保守设置修改标记。旧用户保存曾使用大写资源名，false 不足以证明“用户没有修改过”。

**升级影响**：旧页面和看板在安全导入下可能保留，即使实际上未修改；需检查差异后显式选择 OVERWRITE。新安装/新导入在此迁移后正常形成未修改基线，后续安全升级可更新。迁移不修改页面/看板内容，不自动发布，也不需要用户重新创建页面。

## 模块自有 seed 的重导入

Automation、决策、条件片段和事件策略复用同一导入请求的 conflictStrategy，但保留各模块版本/发布机制，不并入另一套版本库。新定义正常创建；既有定义在 ERROR 下拒绝，SKIP 保留；OVERWRITE_SAFE 仅在当前模块状态与本插件上次成功导入基线一致时更新，否则保留并在 warnings 说明。显式 OVERWRITE 进入模块原有更新/新版本/校验/发布流程。OVERWRITE 不绕过不可变版本、冲突 draft 或其他模块校验。

成功导入历史的 result.moduleSeedBaselines 保存实际模块状态（含定义和版本/草稿身份），不新增版本库或跟踪表。顶层审计时间、发布人和触发计数不参与比较，JSON 业务载荷不作递归删字段。基线缺失的历史定义仍保守保留；读取失败中止导入。SKIP/SAFE 冲突沿用旧基线，只有成功写入才更新基线；失败随原导入事务回滚。整包 OVERWRITE 不应作为单资源冲突的默认处理方式。元数据定义/版本表在导入事务中使用有界写锁串行化状态比较和更新（等待上限三秒，读取不阻塞），防止新 draft 插入绕过比较。这是保守配置更新边界，不承诺无写入停顿或自动三方合并。条件片段查找仅明确 NOT_FOUND 可按不存在处理，其余异常中止导入。

Automation 的 pluginId/automationKey 身份保存在模块自有 ab_automation.seed_plugin_id/seed_key；用户可改显示名称，导入仍定位同 PID。来源字段不从公共 CRUD 请求接收，数据库约束其成对存在、租户内有效定义唯一，已建立的来源不可修改。显式覆盖可以接管唯一同名 legacy 定义；旧记录不自动猜测来源。若同模型有未知来源且无法唯一匹配，ERROR 拒绝，SKIP/SAFE 保留并警告，避免把改名后的旧定义当作新定义重复启用。跨模型移动和来源冲突需要单独迁移。

所有现有调用修改 tracker 的控制面写入都在同一 Spring 事务中保存内容和标记。Command/Field 的真实 Spring 回归需证明标记故障时内容、字段版本与原标记一起回滚，正常保存仍可提交。

## 双客户升级的操作口径

插件管理的上传确认默认使用 OVERWRITE_SAFE。页面默认导入为草稿，不自动发布流程；导入完成不等于全部资源均已替换，也不等于草稿已进入运行态。客户 A 的页面和规则已修改时保留当前配置，客户 B 的同源未改定义可吸收共享更新；插件包版本可以前进，而被保留资源继续沿用原内容和基线。

执行结果显示保留配置数与业务说明，原始资源标识和诊断可展开。历史接口仅投影 warnings 与 preservedResourceCount，不能把内部 moduleSeedBaselines 原样暴露给客户端；刷新后保留说明和失败原因仍可查询。四类模块的保留也计入资源 SKIP 数量，不能只统计 Page/Dashboard。未知基线与可证实的客户修改都会保守保留，当前提示不承诺自动辨别或三方合并。

晚阶段定义校验失败时，复用原导入事务回滚本次数据库修改；已有页面、有效规则和业务记录仍可使用，修正包后可再次导入。这不覆盖任意破坏性 DDL、外部消息撤回、运行中自动化补偿、卸载及删除后重装。

双客户实栈入口为 PluginTwoCustomerUpgradeIntegrationTest，覆盖重复升级、失败后业务读写、隔离、历史回显以及独立连接下的配置读写锁边界。浏览器入口为 web-admin/playwright.plugin-upgrade.config.ts（two-customer-upgrade.spec.ts）：使用新建的 A/B 测试空间，从插件管理上传/预览/确认，再验证失败后的标准 CRUD 修改、重开、修正包与无权导入。空间、定制和测试记录通过现有 API 布置，不将其冒充设计器的全操作验收。PW_UPGRADE_ACCOUNT_FILE 指向本地私有账号 JSON（email/password）；凭据须置于独立的私有证据目录，不能只存于换代时会清除的 runtime 目录。

## 普通字段删除

物理删除入口 `SchemaManagementServiceImpl.removeFieldFromModel` 复用 `FieldDeletionGuard`：

1. 主键/系统字段拒绝删除；检查物理对象存在与标识合法性。
2. 在事务中锁定物理表，随后检查共享列绑定、所有租户行的非空值及已保存定义的字段引用。
3. 页面引用索引和实际页面、查询、命令、规则/条件及其版本、事件策略版本、自动化、Dashboard、Report/模板及字段定义参与检查。不能只依赖可能过期的引用缓存。
4. 检查失败、结果未知或有依赖时拒绝；全部满足后才 DROP。物理表锁持有到事务结束，阻止检查与删除之间插入数据。锁等待上限为三秒，超时走失败结果。
5. PID 与数字 ID 的解绑入口复用同一链路；`ModelFieldBindingSupport` 必须核对 SchemaOperationResult 成功后才删绑定。失败结果不会被当作解绑成功。

`refuseIfDataExists=false` 不是破坏性销毁授权，不能绕过公共入口的非空数据保护。跨租户共享物理列存在其他有效绑定时，即使表为空也拒绝删除。若同一物理表由已进入 active/shadow 应用绑定租户的插件模型持有，普通删除同样拒绝，避免绕过现有不可变应用与受控迁移机制；这一保护按插件模型保守裁决，不尝试推断文件 bundle 内的任意引用。字段库删除还需检查有效绑定和定义引用，防止删除元数据后留下失效配置。按模型移除字段时，若其他模型仍在复用该字段定义，则保留定义；解绑失败不得继续删除字段定义。平台/引擎拥有的 `ab_*`、`se_*` 表及 metadata-only 模型不允许通过普通模型字段操作执行物理删除。

### 引用检查的边界

这是保守的删除准入，不是新的业务依赖图引擎：复用现有引用表和平台定义存储，对完整字段标识、物理列名与已登记绑定 alias 的 token 做检查，保留版本也计入保护。描述或保留定义出现同名字段时可能要求先人工核查、清理过期声明；不自动删除引用、不扩大到任意 Java/外部服务代码的静态证明。跨租户检查只返回通用拒绝原因，不泄露其他租户的名称或内容。

物理 Schema 与用户字段名不兼容的迁移、有数据的列收缩、外部代码依赖等，应走单独审阅的数据迁移流程；普通删除接口不承担这些生命周期。缺表/缺列的原有幂等行为不等于能恢复已经缺失的数据。

## 代码与测试入口

- 插件：`PluginImportSupport`、`PluginPageNavigationImporter`、`PluginImportServiceImpl.saveOrUpdatePluginResource`、`PluginResourceTracker`、`PluginResourceMapper`。
- 字段：`FieldDeletionGuard`、`SchemaManagementServiceImpl`、`ModelFieldBindingSupport`、`MetaFieldServiceImpl`。
- Hermetic：`PluginCustomizationProtectionTest`、`FieldDeletionGuardTest`、`ModelFieldUnbindProtectionTest` 及既有 Schema/生成器/字段版本回归。
- 真实 PostgreSQL：`:platform-app:integrationTest --tests '*CustomizationProtectionPostgresIT'`。必须由受管 runtime 注入 DATABASE/POSTGRES 变量，缺前提即失败，不静默跳过；fixture 使用该隔离库的真实 Flyway schema。

PostgreSQL 用例是组件集成验证，实际执行 mappers、导入器、Dashboard service/Controller、资源持久化和 Schema service；无关版本历史服务及模型解析使用测试夹具。不冒充完整 HTTP 权限或浏览器旅程，也不以此声称全部平台已通过验证。原始执行、反向对照和覆盖范围随本轮提交保存在 Workspace 任务证据中。


## V2 handover preview receipts (first implementation slice)

V2 adds preview and durable preview receipts only. It does not implement ownership
transfer, receiver preparation imports, exit, reverse handover, or readiness
publication. Existing v1 replacement behavior is unchanged. Registering a v2 source
does not add it to the v1 globally retired list; lightweight Base remains usable.

Trusted contracts are single JSON objects under
`META-INF/auraboot/resource-handovers/*.json`. Core provides the catalog/validator;
Enterprise supplies `pcba-base-manufacturing-preview`, revision 1, with the sole
capability `preview`. `resource-handover.v2` requires `tenant-scoped`, exact package
versions, unique typed resource identities and one declared receiver per MOVE.
Unknown properties, duplicate JSON keys/contracts/resources, wildcard versions,
unsupported capabilities and unresolved binding/dictionary declarations fail
closed. Requests cannot supply a contract, SQL, table names, receivers or tenant.

Administrative endpoints:

| Method | Path | Behavior |
|---|---|---|
| GET | `/api/plugins/handovers/contracts` | Trusted contract summaries and canonical SHA-256 digests |
| POST | `/api/plugins/handovers/previews` | Validate inventory and persist a preview receipt |
| GET | `/api/plugins/handovers/previews/{batchPid}` | Read one tenant-scoped immutable receipt |
| GET | `/api/plugins/handovers/previews/by-key?idempotencyKey=...` | Resolve a receipt when its acknowledgement/PID was lost |

The POST body has `contractId`, `contractDigest`, `sourcePluginPid` and
`idempotencyKey`. Unknown request properties are rejected even with a lenient
application mapper. Every service entry rechecks authenticated tenant/actor and
both `plugin.plugin.manage` and `sys.plugin.update`; no grants are added.
Responses contain public PIDs, contract/resource codes and explicit planned MOVE
or VERIFY_REFERENCE entries, never entities, snapshots, raw tenant/actor IDs or
internal row IDs. `executionAvailable` is always false.

Preview validates the source's actual tracking and live owner, exact live PIDs,
field types, model/field binding identity, field-dictionary relationships, dictionary
items, external provider tracking and nullable protection state. Bindings have no
entity owner column: their source ownership is checked through tracking. Missing,
ambiguous, extra/untracked source-owned versions, foreign ownership and unknown
protection fail without persisting a successful receipt. Known claims and modified
content are snapshotted unchanged; preview never adopts them. Source entity
content and dictionary items are not reconciled with receiver templates.

A separate repeatable-read transaction captures metadata and writes only
`ab_plugin_handover_batch` and `ab_plugin_handover_item`. JSONB uses the existing
precision-preserving `JsonNodeTypeHandler` and explicit MyBatis result maps.
Batch/item persistence is atomic; an item failure rolls back all receipt writes.
The database only accepts PREVIEWED status in this slice. A receipt includes all
planned receivers and references, private metadata snapshots/fingerprints and a
15-minute expiry. Expiry is reported without updating or refreshing the old receipt.

The tenant/key uniqueness constraint is authoritative. Admission uses the existing
DistributedLock infrastructure outside the snapshot transaction. A concurrent
uniqueness/serialization collision may resolve only an already committed, matching
request/complete receipt in a new read transaction; it never retries creating or
adopting resources. Same key/different request is a conflict. Retrieval verifies
snapshot digest, header/request binding and every persisted item's integrity.
Replays return the original snapshot, even if live metadata subsequently changes;
they do not certify current eligibility or extend expiry.

The preview observes installed version/status and manifest-content hashes. It does
not verify deployed artifact provenance, full executable dependency closure or
serving readiness. All are explicit limitations in the response. There are no
execute/exit/reverse routes and no changes to import/uninstall gates for v2. The
later transaction/lifecycle/recovery proposal remains in Workspace migration plan
sections 6.4.17–6.4.18; its COMMITTED and later states are not implemented here.
