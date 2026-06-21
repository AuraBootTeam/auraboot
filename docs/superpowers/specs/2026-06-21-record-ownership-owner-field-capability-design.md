---
type: design-spec
status: draft
owner: diqi
created: 2026-06-21
topic: Generic record-ownership (owner_type / owner_id) — assign a record to a user or a team
---

# 通用记录归属能力 (Record Ownership: owner_type / owner_id)

## 1. 目标与背景

让**任意动态模型**的一条记录可以「归属给一个用户 **或** 一个团队(`ab_team`)」,作为**平台级通用能力**沉到 core,而不是 CRM 专属。`crm_lead` 的「分配给」是第一个消费者(当前是自由文本 `crm_lead_assigned_to`)。

平台已有 `ab_team` 功能团队实体(`/api/org/teams`,`TeamController`,`teamService.ts`)+ 选人组件(`UserSelect`/`MemberPicker`),但**没有**:① 通用归属字段约定;② 选人/选团队二合一组件;③ 多态归属的列表/详情显示;④ 「记录归属于我或我所在团队」的数据权限 scope。本能力补齐 ①②③(M1),④ 作为 M2。

### 决策(已与 owner 确认,锁定)
| 项 | 决定 | 理由 |
|---|---|---|
| 命名 | `owner_type` / `owner_id`(两列) | 与 agent 任务执行者 `assignee_*` **分域、零碰撞**(后者已被 `agent-control-plane` 占用,字段码租户内唯一);语义=归属/负责人,更贴「分配给」 |
| 归属层 | **core 通用**,新建 config-only 插件 `core-ownership` | 字段定义/字典沉到平台,任何模型可绑;组件/渲染器进 `web-admin` 平台层 |
| 可归属对象 | `user` \| `team`(`ab_team`),预留 `role` 扩展 | 用户要的两类;dict 留扩展位 |
| 数据形态 | B:两列 `owner_type`(enum)+ `owner_id`(string) | 便于检索/索引/ABAC(对齐平台 `ab_agent_task` 多态先例) |
| 团队语义 | 整团队共有(成员都能操作);**不**做自动轮询分派 | owner 决定 |
| 旧数据 | 直接清 `crm_lead` 测试数据,**不兼容** | owner 决定 |
| 可见性过滤 | M2(独立 ABAC 增强),M1 不做 | 当前 demo 无按人过滤;过滤是通用数据权限策略 |

## 2. 已核实的平台事实(支撑设计,避免推断)

- **字段码租户内唯一** `ux_meta_field_current ON ab_meta_field(tenant_id, code)`,无「必须带模型前缀」校验 → 裸名 `owner_type`/`owner_id` 合法;且字段定义与模型绑定分离,**一处定义、绑多模型**。系统字段 `created_by/tenant_id/pid` 即裸名先例。
- **跨插件复用先例**:`agent-control-plane` 用裸 `assignee_type`+`assignee_id`(dict `acp_assignee_type`)绑到 `agent_task`,binding 机制不与定义插件耦合。**(风险见 §7:跨插件 binding〔字段在 A 插件、binding 在 B 插件〕需在 Slice 1 实证。)**
- **表单一个控件可写多个字段**:`FormPageContent` 的 field onChange 直接持有 `setFormData`,组件 onChange 内可 `setFormData(prev => ({...prev, owner_id, owner_type}))`(已读代码确认;参考 `{field}_display` sibling-set 模式)。
- **组件注册**:`ComponentRuntimeManifest.ts` 的 `runtime('picker','UserSelect',{aliases})` + `ComponentLoader`/`runtime-component-loaders.ts` 的 `import.meta.glob('../../../../ui/smart/**/*.tsx')` 解析。新增 `ownerselect: runtime('picker','OwnerSelect',{aliases:[...]})` 即可。
- **cell renderer 拿得到整行** `CellRendererContext.record` → 可同时读 `owner_type`+`owner_id` 渲染 👤/👥。
- **当前用户团队可服务端查** `TeamMemberService.getTeamMembershipsByUserId(userId, tenantId)` + `CurrentUserTeamResolverImpl`(供 M2)。
- **数据权限**:`DataScopeType{NONE,SELF,DEPT,DEPT_AND_SUB,ALL}` + `CUSTOM` AST(`ConditionToSqlBuilder`,`actor` scope)→ M2 可新增 scope 或用 CUSTOM AST。
- **不新增 dataType**(`DslRegistry.DataType` 13 类固定):靠字段级 `component:"ownerselect"` 覆盖 + cell renderer,不动 dataType 白名单。

## 3. 架构(4 部分;M1 = 1–4,M2 = 5)

### Part 1 — core 数据模型(新插件 `auraboot/plugins/core-ownership`,config-only)
- `config/dicts.json`:dict `owner_type`,items `user`(👤 用户)/`team`(👥 团队),`sortNo` 10/20,预留 role。
- `config/fields.json`:
  - `owner_type`:`dataType: enum`,`dictCode: owner_type`,displayName 归属类型 / Owner Type。
  - `owner_id`:`dataType: string`,`constraints.maxLength: 64`,displayName 归属对象 / Owner。
- `plugin.json`:`pluginType: config`,resourceDirs 含 `dicts`+`fields`;**导入顺序须早于业务插件**(消费者 binding 才能解析到字段码)。
- 不含任何模型/页面——纯字段+字典定义,供任意模型 binding。

### Part 2 — 通用组件 `OwnerSelect`(平台层)
- 文件 `web-admin/app/ui/smart/picker/OwnerSelect.tsx`,注册 `ownerselect`(aliases `OwnerSelect`/`SmartOwnerSelect`)。
- UX:分段切换 `[人 | 团队]` + 对应搜索:
  - 人:复用 `UserSelect` 取数逻辑(`/api/tenant/members/search`),值=用户 pid。
  - 团队:`teamService.fetchTeams()`(`/api/org/teams`,`ab_team`),值=团队 pid。
- 绑定 `owner_id` 字段;onChange 同时写两列:`onChange({ owner_id: <pid>, owner_type: 'user'|'team' })`,表单层 `setFormData` 展开两键(§2 已验证机制)。
- 受控:初始有值时按 `owner_type` 解析 `owner_id` → 显示名(查 member/team)。
- 设计系统:控件高度/焦点环/disabled 依 `ux-design-system §2`;分段用现有 segmented 样式;**禁硬编码颜色/尺寸**。

### Part 3 — 通用显示渲染器(列表/详情)
- 新 cell renderer `owner`(`valueType:"owner"`)在 `CellRendererRegistry.tsx`:读 `record.owner_type` + `record.owner_id` → `👤 <用户名>` / `👥 <团队名>`,空值 `-`。
- 名称解析:前端解析(团队走 `teamService`,用户走成员查询)+ 轻缓存(参考 picker 的 on-mount resolve);**不**引入后端投影(M1 保持前端通用;后端 `owner_display` 投影列作为后续优化,记 §7)。
- 图标走 `icon-resolver`(`User`/`Users`),中性色(色彩留给状态语义,呼应列表来源图标决策)。

### Part 4 — `crm_lead` 作首个消费者(crm-starter)
- 删 `crm_lead_assigned_to`(field + binding + 命令 inputFields 引用 + 页面引用);**清空 `crm_lead` 测试数据**(reset/清表,无兼容)。
- crm-starter 依赖 `core-ownership`(`requires` 或导入顺序),`config/bindings/crm_lead.json` 追加 binding:`owner_type`、`owner_id`。
- 表单 `crm_lead_form.json`「分配与需求」段:`owner_id` 字段 `component:"ownerselect"`(替换上一轮的自由文本占位符)。
- 列表 `crm_lead_list.json`:`owner_id` 列 `valueType:"owner"`(替换裸 `crm_lead_assigned_to` 列)。
- 命令 `crm:create_lead`/`crm:update_lead` 的 `inputFields`:移除 `crm_lead_assigned_to`,加 `owner_type`、`owner_id`。

### Part 5 — M2(独立阶段,不在 M1):ABAC「归属于我或我团队」
- 目标:列表只看 `owner_id = 当前用户` 或 `(owner_type='team' AND owner_id ∈ 我的团队 pid)`。
- 两选一(M2 spec 再定):① 扩 `DataScopeType` 增 `OWNER_SELF_OR_TEAM` scope(编译进 `DataPermissionEngineImpl`);② `CUSTOM` ConditionNode AST(`owner_id = actor.userId OR (owner_type='team' AND owner_id IN actor.teamPids)`),`actor.teamPids` 由 `TeamMemberService` 注入。
- M1 不实现;此处仅锁定可行路径,避免 M1 把字段设计成无法支撑过滤。

## 4. 数据流
- 建/改:`OwnerSelect` → 表单写 `owner_type`+`owner_id` → `crm:create_lead/update_lead` → `mt_crm_lead.owner_type/owner_id`。
- 读/显示:list/detail API 返回两列 → `owner` cell renderer 解析名 → 👤/👥。
- (M2)查询:数据权限 scope 注入 WHERE。

## 5. 影响面清单
- 新增:插件 `core-ownership`(dict+fields);`OwnerSelect.tsx` + 注册;`owner` cell renderer;单测。
- 修改:`crm-starter` 的 fields/bindings/commands/`crm_lead_form.json`/`crm_lead_list.json`;清 `crm_lead` 数据。
- core 改动:`ComponentRuntimeManifest.ts`(+1 entry)、`CellRendererRegistry.tsx`(+1 renderer)。
- 无 Flyway/schema 改动(动态模型列由 model-driven DDL 生成;`core-ownership` 字段 → 各消费者 `mt_*` 列)。

## 6. 测试 / 验收(golden)
- 单测:`OwnerSelect`(分段切换、emit `{type,id}`、回显名);`owner` renderer(user→👤名、team→👥名、读两列、空→`-`)。
- 平台 validator:`core-ownership` + `crm-starter` import `success=true`。
- 真浏览器 golden(host-first :5161):表单选「人」→ 保存 → 列表显示 👤名;改选「团队」→ 保存 → 列表 👥团队名;**实命令落库**断言 `mt_crm_lead.owner_type/owner_id`(用 throwaway lead,跑完删,不动 demo 数据)。
- (M2 golden 推迟:以团队成员身份列表过滤到我/我团队的线索。)

## 7. 开放问题 / 风险(实现前/中确认)
1. **跨插件 binding(Slice 1 首验)**:`crm-starter` 的 binding 引用 `core-ownership` 定义的 `owner_id`——需 core-ownership 先导入且 binding 按 fieldCode 解析。若验证不通过,回退:把 owner 字段集放一个「被 crm-starter 依赖」的共享插件,或 crm-starter 直接定义(失去跨模型复用,需回报 owner)。
2. **OwnerSelect 一控件写两字段**:机制已验证(`setFormData` sibling-set),但 onChange 契约(组件 emit 对象 vs 标量)要在 Slice 2 跑通真表单。
3. **显示名解析**:M1 前端解析 + 缓存;若列表行多导致 N 次查询,作 batch 解析或后端 `owner_display` 投影(记为优化,不阻塞 M1)。
4. **role 扩展**:dict 预留,M1 不实现。
5. **导入顺序**:`core-ownership` 须在 crm-starter 前导入(reset/init 顺序 + plugin `requires`)。

## 8. 实现切片(供后续 writing-plans)
- **S1 core-ownership 插件**:dict+fields,导入验证 + 跨插件 binding 实证(crm_lead 绑 owner_*,import success,DDL 出列)。
- **S2 OwnerSelect 组件**:TDD 单测 + 注册;表单 `component:"ownerselect"` 写两列,真表单保存 golden。
- **S3 owner cell renderer**:TDD 单测;列表 `valueType:"owner"` 显示 👤/👥 名,golden。
- **S4 crm_lead 收口**:删旧字段/清数据/命令 inputFields/列表列;端到端 golden(选人/选团队 → 保存 → 列表显示 + 实命令落库)。
- **(M2，独立 spec)** ABAC owner scope。
