---
type: plan-impl
status: active
created: 2026-10-02
---

# 权限透明化 review 与实施账本

当前裁决：完整方案已确认并落库，五仓候选开发进行中。最新候选尚未完成真实浏览器与全量回归，r6 启动受宿主机验证名额上限阻挡，CI 私网隧道亦未启用。[验收报告](../../retro/2026-10-02-permission-transparency-testing-gate-acceptance-report.md)记录当前证据边界。下方首批账本和合并后记录保留历史时点，稳定实现契约以关联 SoT 为准。

目标：新增能力可以找到，同一记录的主记录/页签/字段/动作可独立解释，上传后的读取链路可复现，RBAC 编辑有清晰范围与保存反馈。首批后端修复 PR #2154 已于 2026-10-02 合并，squash `05f34f1e4325`。续作分支 `codex/permission-transparency-ui` 基于该提交。

## 已冻结的行动点与状态

| 行动点 | 已确认事实 | 本轮状态 | 验收证据要求 |
| --- | --- | --- | --- |
| 非法范围写入 | 未校验值且解析未知值为 all | 已修，定向验证中 | HTTP 400、DB 不变、坏存储值不授权 |
| 默认范围批量应用 | 两段更新原先没有共同事务 | 已加事务，回滚故障注入尚未执行 | 后半段失败时角色默认及逐项范围都回滚 |
| 记录解释 | recordPid 被回显，实际为空记录 | 已接入实际记录与目标成员身份，定向验证中 | 记录值、跨租户负例、异常恢复、HTTP 边界 |
| 新能力进入上方区域 | 分组到菜单区域使用前端硬编码，报价业务面缺映射 | 已授权实施，待真实浏览器验收 | 新能力声明→区域展示→授予→重新登录后实际访问 |
| team 范围显示 | 后端支持 team，编辑器选项缺失且未知值回到 all | 已授权实施，待真实浏览器验收 | 显示/保存/read-back/多角色实际数据范围 |
| 默认和实际范围 | 默认值可能遮住逐项覆盖，默认读取失败被隐藏 | 已授权实施，待真实浏览器验收 | 默认、继承、逐项覆盖、混合、加载失败各状态 |
| 报价上传后读取 | 上传能力含 file.read，文件关系与输出页签另有授权 | 根因未复现 | 新上传文件→关系→报价读取→附件预览→输出，各步请求及拒绝原因 |
| self/own/team/share | own 非枚举，share 属 ReBAC | 契约已整理，浏览器未验证 | 本人/他人/同团队/跨团队/共享撤销/过期/跨租户 |
| RBAC 外观与按钮 | 旧角色建议、超长原始码与不准确“例外”含义 | 已授权实施，待真实浏览器验收 | 草稿、变更预览、保存失败/成功、读回与分角色实效 |
| 企业有效权限解释 | 旧引擎 explain 仍忽略 Long recordId | 尚未修改 | 企业接口真实记录评估，不复用矩阵接口通过冒充 |

## React/DSL 能力矩阵

| 行动点 | DSL 能力/平台缺口 | 实现位置与理由 |
| --- | --- | --- |
| 能力名称、分组、包含权限 | capability 声明与 DSL 为真源 | 优先声明；现有分组解释 helper 需要修复 |
| team 与有效范围解释 | 后端范围枚举和解释服务 | 现有管理编辑器补齐，不另建业务页 |
| 授权草稿、变更预览、保存 | DSL 无等价角色授权矩阵编辑能力 | 沿用现有 React 权限编辑器；owner 在“合并 继续”中确认前述矩阵 |
| 访问解释 | 后端统一 canOperate 流水线 | 现有审计/解释入口接入，避免新增平行裁决器 |

## UI 方向

沿用现有组织管理页面的导航与组件。顶部显示角色、成员数和有效能力摘要；业务能力按用户可理解的菜单区域组织。数据范围展示“角色默认”和“逐项覆盖”，未识别值显示错误而不是全部数据。原始权限码收在高级区域，并准确区分直接授权、能力展开和派生权限。修改先形成草稿，再给变更预览与保存；保存后读取服务端结果，失败保留草稿。

RBAC 一次“允许”不足以证明页签或字段可见。记录解释、页签能力、字段权限分开展示证据，但共享同一角色与记录上下文。

## 回归复用入口

后端复用 `integration/security/rbac` 的独立权限矩阵及 existing controller IT。浏览器复用 `permission/role-default-scope-golden.spec.ts`、`permission/capability-save-ui-golden.spec.ts`、`rbac/rbac-platform-baseline.golden.spec.ts` 和报价 `quote-surface-permission-release`、`quote-data-scope-isolation`、`x03-team-scope-golden`、`quote-bom-badfile-permissions`。先核对固定 catalog 的实际收集与执行分母，不新增另一份“全量”清单代替既有 gate。

后端验收使用 slot151；UI 续作用独立产品 runtime `permission-transparency-ui-r2` / slot154 / DB `enterprise_154`。所有浏览器证据只对应其冻结源码，不借用共享 canonical 的运行栈。

## 当前执行账本

2026-10-02 定向统一重跑结果，非全量回归：

| 层 | 命令/入口 | collected | executed | passed | failed | skipped | did_not_run | evidence |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| service/controller UT | aura gradle permission-transparency :test，6 个指定类 | 90 | 90 | 90 | 0 | 0 | 0 | `.workspace/evidence/permission-transparency/backend-final/` 的 JUnit XML |
| HTTP + DB IT | 同次运行 PermissionMatrixPolicyControllerIT | 6 | 6 | 6 | 0 | 0 | 0 | 同目录对应 JUnit XML；DB auraboot_151 |
| UI targeted browser（首轮） | 5 个既有权限文件 | 12 | 12 | 8 | 4 | 0 | 0 | Workspace `ui/browser-results.json`；快速交互状态时序待修正 |
| roles-full（首轮 quoteops 分组） | 产品公开 gate | 20 | 20 | 19 | 1 | 0 | 0 | Workspace `ui/roles-full.log`；formal expiry 在最后请求耗尽 15 秒全用例预算，另有 chromium 隔离用例 1 项通过，但总体门仍为失败 |

受控 mutation 将实际记录改传 null，`evaluatesActualRecordWithTargetIdentityAndRestoresCaller` 执行 1 / 失败 1；恢复后上述 96 项定向检查通过。初次 IT 的 1 项失败来自断言使用不存在的 `success` 响应字段，按实际 `code` 契约修正，保留初次 XML。报告与执行/源码身份索引保留在 Workspace `.workspace/evidence/permission-transparency/`，未将其升级为全量验收。

## SOT Updates

范围、记录诊断和上传后读取契约见 [permission-scope-and-record-diagnostics](../../system-reference/permission-scope-and-record-diagnostics.md)。UI 方案已在隔离分支实施，尚未形成发布及浏览器验收证据。全量回归及上传根因仍未关闭。

## 合并后续作（2026-10-02）

PR #2154 已核实进入 origin/main，执行 closeout 后删除旧分支，目录保留用于 UI 续作。首套 UI runtime slot152 已关闭，当前独立产品 runtime 为 `permission-transparency-ui-r2` / slot154 / DB `enterprise_154`；五个源仓来自已冻结 worktree，未使用 canonical 未提交代码。金蝶 L4 默认连接为空，使用公开 launcher 配置 `QUOTE_BOM_USE_JIEJIA_L4_DEFAULTS=0` 继续权限回归；不作为金蝶实链通过证据。

前端已实施：新声明能力自动进入主选择区、team 选项、默认与实际范围分离、非法/缺失/读取失败显式状态、保存变更预览、失败保留草稿、切换角色/页签/路由的草稿保护、高级统计改称“未被业务能力覆盖”。执行中的 UI 状态矩阵保存在 `.workspace/evidence/permission-transparency/ui/acceptance-matrix.md`，浏览器尚未验收，不宣称全量通过。

## 当前重验边界

前端 48 项 UT 和 typecheck 已通过。定向浏览器第一轮 8/12；取消草稿的同一用例手动及带 trace 通过，快速路径待通过目标 role PID 和草稿回显同步确认。原子动作编辑按持久化/read-back 展示，测试不能要求乐观更新。角色门禁第一轮 19/20，销售上传及多价格通道、采购导入、四类角色菜单、审批通过/拒绝已执行；expiry 重验沿用同文件正式审批的 120 秒旅程预算。失败证据保留，不能以重验结果抹除。

新增权限编辑文案同时进入构件 YAML 和 bootstrap seed，既有 DB 无新 seed 时也能加载；覆盖说明使用新的语义 key，避免旧 DB 的“破例/来源”文案覆写。DB 租户覆盖仍优先。完整角色门禁（含覆盖登记裁决）、share 撤销/过期、上传后附件预览、企业旧 explain 与 PCBA 错误 role name 声明均保持在未关闭分母。最终结果以 Workspace 对应源码的执行账本为准。

重验补充：UI 11/12；同一旧勾选用例在无 trace 时失败、带 trace 5 次通过，尚不证明稳定。本轮进一步使用 hydration marker、固定 capability test ID 及显式 setChecked 驱动，保留选择改变/保存 dirty 的原断言，待重验。角色 20 quoteops + 1 chromium 均通过，产品总门因为 14 条覆盖注册差异仍失败。已核实公开 launcher 错用共享 plugins 根目录；正确冻结根目录后只剩 7 个文件摘要更新，所有断言锚点都存在。续作修复从 runtime manifest 传递 plugins/CRM 根目录，缺失即拒绝，并补 3 项路由单测；旧实现 mutation 会红。登记按已核验断言更新，未将登记结果替代执行。

根因复核：React Router 默认 entry.client 使用 StrictMode，编辑器初始 effect 产生重复读取；旧实现允许迟到响应覆盖用户草稿。延迟旧响应的受控测试执行 1/失败 1，修复请求代次后定向 UT 50/50、skip=0。浏览器测试仍保留鼠标两次勾选；新增原子授权已持久化但回读故障的 UI retry 用例，验证旧快照不能继续编辑。最终 UI 13 项与固定产品 24 个 golden 待本次冻结构件重验。


## 管理员权限能力完整实施方案（2026-10-02 owner 已确认）

状态：owner 已确认并授权完整开发。以下为冻结实施目标；盘点证据不等于上线及验收。

### 1. 范围和证据

本轮针对当前报价/BOM/CRM/组织/平台管理组合，读取当前 runtime 的管理员权限矩阵、能力声明、菜单及成员基线，并对比冻结源码中的能力声明。不是全平台所有可选插件的权限完备性验收，也不是逐项业务入口功能验收。

- Runtime：permission-transparency-ui-r2，Web http://127.0.0.1:5304，Backend http://127.0.0.1:6604；本轮 runtime verify 为 ok。
- OSS e7e7d6b1a77dd812a677c0f8a740e202551b6699；Quote 2b5d3087dc3f726febafa7db861aebd174ea4921；Enterprise eef734f194fa；CRM 10b7b395d6bd；Plugins 521d0cc19871。
- 采集时间：2026-10-02T11:04:14.552672+00:00。仅执行只读 GET；没有新增、撤销或重写角色权限。
- 1617 个 supported 动作，1615 个管理员已授权动作，61 个运行时声明能力，121 个动作被声明能力覆盖；1494 个已授权动作未覆盖。覆盖不是授权来源，也不是访问裁决。
- 源码中有 62 条能力声明、61 个唯一 code，来自 5 份配置；重复 code 为 sys.cap.member_base。

### 2. 建议确认的产品边界

1. 普通租户管理员的所有合理授权需求，应能通过上层业务或平台管理能力表达，不要求理解原子权限码。
2. 一套现有权限引擎和授权表；业务能力继续通过 includes 展开，不引入第二套授权模型。
3. 能力表示“可以做什么”；记录范围、字段敏感权限、共享关系保持各自既有语义，不合并为菜单授权。self 是范围，own 不新增为别名枚举，share 不作为普通数据范围。
4. 菜单是组织与解释维度；无菜单入口但可独立授予的业务/API操作也必须有上层能力。
5. 底层诊断默认只读。普通管理员的日常授权主界面不出现原子动作勾选器和覆盖数量告警。原子写入若保留，只进入受限维护工具，不承担补齐业务功能的职责。
6. 不以覆盖率归零为目标，不批量补授权，不将未知用途动作直接归为内部动作。角色模板沿用已有销售、采购等角色，不在本轮新增模板框架。

### 3. 全量动作清单及分流

[逐项动作清单](/Users/ghj/work/auraboot/.workspace/evidence/permission-transparency/ui/permission-layering-action-inventory.csv)包含每个动作的模块、资源、当前管理员授权、成员基线、全部声明关联能力、直接关联菜单及可见状态。分类是核查优先级，不是已确认业务语义；无菜单不能证明无人工授权需求。

| 核查分类 | 动作数 | 本轮结论 |
| --- | ---: | --- |
| 已有声明覆盖 | 121 | 保留；仍需核验能力依赖闭包和敏感边界 |
| 未覆盖且有直接菜单关联 | 19 | 优先补上层能力/入口依赖；部分菜单在当前组合中隐藏，不擅自开放 |
| 未覆盖且已有成员基线、无上述菜单关联 | 2 | 核验基线安全性，避免重复人工配置 |
| 其余未覆盖模型动作 | 1227 | 按实际模型入口与调用方确定能力依赖、独立业务操作或内部动作 |
| 其余未覆盖非模型动作 | 248 | 按具体命令/API/后台入口裁决，不能仅按前缀打包 |

五类互斥且合计1617。1227是本表分流后的模型数量；先前1494个未覆盖已授权动作中1228为model前缀，两者筛选口径不同。当前模型条目包含E2E夹具，发布配置须与测试注册分开核对，不能将测试模型包装成产品能力。

### 4. 当前上层能力目录

以下为运行时实际61项能力，不是新增设计。动作数可重叠，不可相加推导全目录。

| 功能组 | 能力 code | 名称 | 包含动作数 |
| --- | --- | --- | ---: |
| 报价管理 | qo.cap.quote_view | 查看报价 | 7 |
| 报价管理 | qo.cap.quote_draft | 新建报价草稿 | 1 |
| 报价管理 | qo.cap.quote_edit | 编辑报价 | 2 |
| 报价管理 | qo.cap.document | 生成报价单 | 3 |
| 项目管理 | bom.cap.project | 维护 BOM 项目 | 4 |
| 来料处理 | qo.cap.intake | 上传客户资料与导入 BOM | 9 |
| 转换作业 | bom.cap.convert | 执行 BOM 转换与导出 | 40 |
| 寻源与定价 | qo.cap.sourcing | 寻源、采纳价与工艺费评审 | 8 |
| 物料库 | bom.cap.library_view | 查看物料库 | 2 |
| 物料库 | bom.cap.library_manage | 管理物料库 | 1 |
| 敏感信息 | qo.cap.cost | 查看成本 | 2 |
| 敏感信息 | qo.cap.evidence | 查看寻源证据 | 2 |
| 规则配置 | bom.cap.rules_view | 查看 BOM 规则 | 2 |
| 规则配置 | bom.cap.rules_manage | 编辑 BOM 规则 | 1 |
| 组织与权限管理 | org.cap.hr | 维护组织架构 | 2 |
| 组织与权限管理 | org.cap.hr_view | 查看组织架构 | 1 |
| 组织与权限管理 | org.cap.team | 维护团队 | 2 |
| 组织与权限管理 | org.cap.member | 管理账号成员 | 7 |
| 组织与权限管理 | org.cap.member_view | 查看账号成员 | 1 |
| 组织与权限管理 | org.cap.role | 管理角色与授权 | 4 |
| 组织与权限管理 | org.cap.role_view | 查看角色 | 2 |
| 组织与权限管理 | org.cap.permission | 管理权限与菜单 | 5 |
| 组织与权限管理 | org.cap.tenant | 维护企业信息 | 3 |
| 系统管理 | sys.cap.console | 系统配置与安全策略 | 1 |
| 系统管理 | sys.cap.model_service | 模型服务配置 | 1 |
| 系统管理 | sys.cap.login_channel | 登录渠道管理 | 1 |
| 系统管理 | sys.cap.cloud_config | 云服务配置 | 1 |
| 系统管理 | sys.cap.notification_rule | 通知规则管理 | 1 |
| 系统管理 | sys.cap.automation | 自动化查看 | 1 |
| 系统管理 | sys.cap.data_permission | 数据权限策略 | 1 |
| 系统管理 | sys.cap.integration | 集成与连接器 | 2 |
| 系统管理 | sys.cap.plugin | 插件与应用模板 | 2 |
| 系统管理 | sys.cap.scheduled_task | 定时任务管理 | 1 |
| 系统管理 | sys.cap.member_base | 成员基础访问 | 5 |
| 平台基础 | sys.cap.saved_view_personalize | 维护个人列表视图 | 2 |
| 客户管理 | crm.cap.account_view | 查看客户 | 2 |
| 客户管理 | crm.cap.account | 维护客户资料 | 3 |
| 客户管理 | crm.cap.account_contact_full | 查看完整客户联系方式(电话/邮箱) | 1 |
| 客户管理 | crm.cap.contact | 维护联系人 | 3 |
| 线索与商机 | crm.cap.lead | 维护线索 | 3 |
| 线索与商机 | crm.cap.opportunity | 维护商机 | 3 |
| 报价业务面 | qo.cap.surface_bom_price | 查看BOM价格业务面 | 1 |
| 报价业务面 | qo.cap.surface_process_fee | 查看加工点数业务面 | 1 |
| 报价业务面 | qo.cap.surface_output | 查看报价输出业务面 | 1 |
| APP-03成本与报价 | qo.cap.app03_scenario | 创建并查看成本方案 | 5 |
| APP-03成本与报价 | qo.cap.app03_costing | 维护、计算并冻结成本方案 | 7 |
| APP-03成本与报价 | qo.cap.app03_approval | 批准假设、缺口免责与正式报价 | 10 |
| 客户请求与服务 | crm.cap.customer_request | 处理客户请求 | 3 |
| 客户请求与服务 | crm.cap.complaint | 处理投诉 | 2 |
| 客户请求与服务 | crm.cap.activity | 维护活动记录 | 3 |
| 客户请求与服务 | crm.cap.sla | SLA 策略 | 2 |
| 客户请求与服务 | crm.cap.clarification | 澄清事项 | 3 |
| 报价与需求 | crm.cap.quote_summary | 报价汇总 | 3 |
| 报价与需求 | crm.cap.approval_case | 审批单 | 3 |
| 质量与风险 | crm.cap.review | 评审 | 3 |
| 质量与风险 | crm.cap.risk | 风险 | 3 |
| 营销活动 | crm.cap.campaign | 营销活动 | 2 |
| 邮件触达 | crm.cap.email_template | 邮件模板 | 2 |
| 邮件触达 | crm.cap.email_log | 邮件日志 | 2 |
| 自动化规则 | crm.cap.lead_score_rule | 线索评分规则 | 2 |
| 自动化规则 | crm.cap.assignment_rule | 分配规则 | 2 |

### 5. 优先补齐的19个菜单关联动作

关联菜单仅证明入口配置关系，不能证明完整操作所需权限。visible=false表示当前返回的菜单配置不可见，不代表动作永远不需要配置。

| 动作 | 关联菜单 | 当前菜单可见 | 建议 |
| --- | --- | --- | --- |
| dashboard_mgmt | 仪表盘管理 | False | 纳入对应平台管理能力候选；当前组合是否开放此功能需确认 |
| dashboards | 仪表盘 | False | 纳入对应平台管理能力候选；当前组合是否开放此功能需确认 |
| decision.definition.manage | 条件片段库、决策表 | False、False | 规则/决策管理能力候选；按查看、维护、发布审批等授权边界划分 |
| decision.definition.read | 规则中心、策略工作台、决策定义、发布治理、执行日志、数据模型 | False、False、False、False、False、False | 规则/决策管理能力候选；按查看、维护、发布审批等授权边界划分 |
| decision.policy.read | 事件策略 | False | 规则/决策管理能力候选；按查看、维护、发布审批等授权边界划分 |
| dict_management | 字典管理 | False | 纳入对应平台管理能力候选；当前组合是否开放此功能需确认 |
| field_management | 字段库 | False | 纳入对应平台管理能力候选；当前组合是否开放此功能需确认 |
| member_management | 账号 | True | 优先核验并纳入已有组织、团队、账号、角色授权能力的入口依赖；不新增重复权限按钮 |
| meta.changelog.read | 审计日志 | False | 纳入对应平台管理能力候选；当前组合是否开放此功能需确认 |
| model.bom_material_revision.read | 正式物料版本 | False | 核验正式物料版本是否属于现有物料库查看能力，或确需独立业务能力 |
| model_management | 模型管理 | False | 纳入对应平台管理能力候选；当前组合是否开放此功能需确认 |
| named_query_management | 命名查询 | False | 纳入对应平台管理能力候选；当前组合是否开放此功能需确认 |
| org_management | 组织管理 | True | 优先核验并纳入已有组织、团队、账号、角色授权能力的入口依赖；不新增重复权限按钮 |
| org_teams | 团队 | True | 优先核验并纳入已有组织、团队、账号、角色授权能力的入口依赖；不新增重复权限按钮 |
| permission_management | 角色、权限/授权关系 | True、False | 优先核验并纳入已有组织、团队、账号、角色授权能力的入口依赖；不新增重复权限按钮 |
| pgm.page_schema.read | 页面配置 | False | 纳入对应平台管理能力候选；当前组合是否开放此功能需确认 |
| query_builder | 查询构建器 | False | 纳入对应平台管理能力候选；当前组合是否开放此功能需确认 |
| report.definition.view | 报表管理 | False | 纳入对应平台管理能力候选；当前组合是否开放此功能需确认 |
| sys.connector.update | 集成连接器 | False | 纳入对应平台管理能力候选；当前组合是否开放此功能需确认 |

### 6. 底层动作的候选归属

此表用于安排实现核查，不是直接拼接includes的指令。尤其system/meta/model可能是兼容入口、元数据管理或业务依赖，必须查调用方；不得盲目把read全部加入全员基线。

| 动作族 | 归属建议 | 必须核实的边界 |
| --- | --- | --- |
| 报价、来料、寻源、审批、输出、附件 | 优先完善已有报价能力；按实际需要拆上传/查看/生成/下载等独立边界 | 记录读取、业务面、文件关联、输出读取、成本和证据不能互相旁路 |
| BOM、物料库、规则、项目 | 保留已有6项能力，核验依赖及查看/维护/导出拆分必要性 | execute与export是否允许独立授予；物料版本读归属 |
| CRM各业务模型 | 归入现有20项能力或必要独立操作 | 查看与维护是否需独立、客户联系信息敏感权限 |
| system.*、meta.*及元数据模型 | 模型、字段、菜单、字典、命令、查询、页面等平台管理能力；只读渲染依赖另核 | 重复/兼容权限码实际消费者；管理读不能自动当全员渲染读 |
| dashboard、report、notification | 查看、个人配置、团队配置、发布/管理、导出等能力 | 个人与团队边界、导出和调度副作用 |
| decision、automation | 查看、维护、执行、审批发布、回滚等能力候选 | 敏感发布不纳入普通编辑档；同名历史动作不盲目合并 |
| sys.file.*、文件模型 | 业务能力依赖；确有独立资料管理需求时声明独立能力 | file.read和关联记录权限仍共同判定，上传者不自动绕过关系 |
| data.record_share.manage、audit.* | 共享管理、访问审计等能力候选 | 共享不是scope枚举；审计查看与管理区分 |
| billing、acp、ai、qr、iot等 | 按已启用产品的管理员职责声明能力；未启用功能仅留诊断 | 不能因为无当前菜单就归为内部动作或给全租户授权 |
| 测试模型/无用户入口内部模型 | 经注册来源和调用方证明后标内部/测试用途，仅诊断 | 不凭model前缀认定，内部动作仍保留后端鉴权 |

### 7. 已确认实现/配置问题与待核实风险

| ID | 证据与判断 | 最小落地方式 |
| --- | --- | --- |
| P01 | sys.cap.member_base在platform-admin为5动作、quote-core为23动作；当前runtime采用platform版本。Registry按tenant+code更新，重复声明可覆盖，配置结果依赖导入路径/顺序 | 明确唯一声明所有者；核验各基础读消费者再整理配置；不取两者并集或扩大tenant_member基线 |
| P02 | quote两个surface能力带permissions字段，DTO仅识别includes等字段；该字段不参与当前能力展开 | 核实这些模型读是否真实必要；必要时显式声明到合适能力，修正字段并校验未知字段；禁止机械搬入includes |
| P03 | 能力granted为containsAll，部分授权与零授权显示相同 | 计算none/partial/full，展示缺少动作；部分授权不被普通能力保存静默清理 |
| P04 | applySelection保护部分授权，但按当前完整能力集合计算可撤销动作，不存能力授权历史来源 | 预览与保存共用差异计算；明确表示“本角色将移除的动作”，不能宣称按历史来源精准撤销 |
| P05 | 主能力草稿保存、原子立即保存两种交互混用 | 主界面移除原子写入；诊断只读，维护工具独立；不新增跨接口统一事务 |
| P06 | 四个当前可见组织菜单动作未被声明覆盖；已有组织能力使用另一组动作码 | 核验菜单过滤及API实际检查，修正已有能力入口依赖；本轮仅确认声明缺口，不冒称实际403已复现 |
| P07 | tier有approver，但前端预设只识别viewer/editor/admin；不能由层级自动推导敏感审批授权 | 保持审批显式选择、校验配置值；是否新增审批预设另行确认，不改变现有引擎 |
| P08 | 角色成员基线隐式参与有效权限合并；当前实际基线5动作，与quote-core“平台基础读”不同 | 页面区分角色直接授权和已有基线，优先复用现有机制；核验安全性后再决定配置调整 |
| P09 | 菜单标注通过menu.permissionCode与includes相交生成，不是完整访问证明 | 使用“相关菜单”措辞，避免承诺移除后用户必然不能访问；用户还有其他角色和共享 |
| P10 | 原子写入成功但回读失败仍提示更新失败 | 若保留维护入口，区分写入失败和回读失败，不在失败界面允许编辑旧快照 |

P01/P02/P03等是本轮源码/运行时确认的具体问题；补齐业务依赖的最终清单仍需逐入口验证。没有发现足以要求重做权限引擎、角色关系表或scope模型的证据。

### 8. 建议页面和新权限发布流程

页面沿用现有角色编辑器：角色与成员信息 → 业务/平台管理能力分组 → 就近显示数据范围及敏感能力 → 授权变更预览 → 保存和服务端回读。诊断入口展示角色动作、能力关联、基线及访问解释。1494项未覆盖不在主界面作为管理员操作提示。

新增权限流程建议：
1. 插件作者定义原子动作及实际后端检查。
2. 判断是需要独立选择的能力、已有能力的必需依赖，还是经证明的内部/测试动作。
3. 在现有capabilities配置声明名称、组、includes、敏感性和排序；记录范围/字段策略复用已有机制。
4. import验证重复code归属、未知字段、不存在的includes；不能依靠运行时fallback把未声明动作当正常业务配置。
5. 发布时核查可配置入口、命令、tab、字段与能力的关联；内部豁免必须有理由。先利用现有catalog/validator，证明确实缺口再加小范围校验，不本轮发明新schema。
6. 现有角色的动作授权保持不变；新增/补齐声明可能导致“部分授权”状态，但不自动补齐动作。新增业务能力仍需管理员主动授权。

### 9. 最小实施切片与确认项

| 顺序 | 修改范围 | 交付结果 |
| --- | --- | --- |
| A | 配置及Registry/import校验 | 唯一基础能力所有者、报价无效字段修正、已启用可配置功能的能力归属清单 |
| B | 现有能力解析/角色编辑器 | none/partial/full、主界面能力完整可配置、覆盖统计进入只读诊断 |
| C | 现有能力应用服务 | 共用预览/应用差异计算、共享动作保留和残留部分授权说明 |
| D | 具体业务权限缺陷 | 只在精确报价/附件/审批/tab/字段旅程复现后局部修正；不以admin绕过或扩权兜底 |

owner 已确认这四点：
- 所有正常管理员可配置权限只出现在上层；底层明细默认只读，原子写入移出日常入口。
- 业务能力也包含平台管理；按功能分组，记录范围和字段控制复用现有体系。
- 当前组合优先整理，隐藏/未启用平台模块不自动开放；先处理19个菜单关联缺口及既有能力依赖。
- 不迁移角色授权、不自动补授权、不重做权限引擎；只有上述已确认问题与精确复现缺陷进入实现。

### 10. 后续验收边界

本轮新功能浏览器E2E did_not_run（executed=0）；本轮是只读盘点和方案，不是功能验收。后续按e2e规范复用已有固定门禁：能力选择/保存及角色范围UI、报价surface/隔离/团队共享/审批/附件等真实旅程。补none/partial/full、重叠能力撤销、未覆盖残留保留、预览与保存一致的hermetic/unit验证；入口授予/撤销、刷新和目标用户业务结果采用journey/real-stack/browser。重复声明及未知字段用现有import配置验证入口。不得把1617条清单或194条既有静态要求登记当执行通过数。

现有product golden仍有BOM规则转换失败，原报价附件问题仍未精确复现，enterprise旧explain与多角色范围合并等风险保持在原review-ledger，不因本方案关闭。

### 11. 数据文件

- [逐动作清单](/Users/ghj/work/auraboot/.workspace/evidence/permission-transparency/ui/permission-layering-action-inventory.csv)
- [汇总和源码/runtime差异](/Users/ghj/work/auraboot/.workspace/evidence/permission-transparency/ui/permission-layering-inventory-summary.json)
- [实时API快照](/Users/ghj/work/auraboot/.workspace/evidence/permission-transparency/ui/permission-layering-runtime-snapshot.json)
- [源码声明快照](/Users/ghj/work/auraboot/.workspace/evidence/permission-transparency/ui/permission-layering-source-declarations.json)
- [既有执行及问题账本](/Users/ghj/work/auraboot/.workspace/evidence/permission-transparency/ui/review-ledger.md)

SOT Updates：本轮仅形成待确认提案，未更改已上线契约；确认实施后同步既有permission-scope-and-record-diagnostics及对应平台/产品SoT，不将本文作为稳定契约的唯一来源。

### 任务执行关联

| ID | 目标/行动点 | 主裁决/复用入口 | 当前状态 |
| --- | --- | --- | --- |
| AUTH-01 | 上层能力配置完整，当前可见入口依赖补齐 | existing capability/import tests + role/业务真实旅程 | pending |
| AUTH-02 | 部分授权、补齐授权、残留说明 | resolver/helper UT + capability-save-ui-golden | pending |
| AUTH-03 | 原子诊断只读、覆盖提示移出主界面 | permission golden + screenshot review | pending |
| AUTH-04 | 共用预览/保存差异计算、重叠保留、失败原子性 | capability service/controller UT/IT + browser preview/save | pending |
| AUTH-05 | 唯一基础能力所有者、无效声明及校验 | plugin/import tests + runtime import/readback | pending |
| AUTH-06 | 精确报价/附件/tab/字段/审批权限缺陷 | existing fixed quote/BOM/role/share gates | pending |
| AUTH-07 | 完整回归、SoT、PR与证据收口 | fixed catalog, current commits, original screenshot reviews | pending |

开发推进使用本表，不另建平行 active gap tracker。工作台证据是执行记录，仓库本计划是任务轨道；稳定行为同步既有SoT。

### 实施进度（2026-10-02，仍未验收）

- 完整方案已提交 `6cedef50a2`。首批实现 OSS `c0f1def88e`、Quote `aa80b402f3`；企业记录诊断复用修复 `dfe51ca866`、PCBA 角色名称修复 `970d6bf1cd`。这些是本地提交，不等于推送、PR 通过或发布。
- 主界面仅写声明能力；原子目录只读；部分状态支持补齐和明确撤销，未选择的历史零散授权在普通保存中保留。预览和保存共用差异计算，共享动作由仍选中的能力保留。范围仍复用逐 resource/action API，逐项保存及回读，未承诺整批范围更新原子性。
- Quote 的基础读能力拆为 `qo.cap.platform_read`，保持原 23 项读取集合；不把它误称为隐式成员基线。正式部署恢复选择时排除派生诊断能力。无效 `permissions` 字段已按既有业务角色契约并入对应页签的 `includes`；未迁移现存角色授权。
- 六个平台维护能力已根据控制器守卫核对依赖；数据权限、连接器/Webhook、定时任务还需现有 DSL 模型读写及命令执行依赖。源码合同测试防止仅授予菜单入口。该配置补齐正在验证，不宣称受限管理员实际操作已通过。
- 当前矩阵 1617 行是 supported 矩阵动作分母，不是全部活跃权限目录；声明能力的部分代码不在该矩阵中。因此不能用“未出现在矩阵”裁定声明依赖不存在。写入检查采用活跃权限目录，后续 runtime 导入及实际授予仍须验证。
- 前端定向 UT 当前 56 passed / 0 failed / 0 skipped / retry=0；服务与声明定向测试及 Controller 6 项隔离 IT 已执行。企业记录转换/不可用/跨租户 3 项 UT 通过，真实企业 IT 尚未执行。类型检查新增 mock 的响应契约缺少 desc 已修正，待当前重跑结果。
- 增补浏览器用例：真实 PID 授权夹具 → 部分状态 → 上层明确撤销 → 服务端预览精确动作 → 保留查看能力共享动作 → 补齐 → 刷新持久化。新增用例尚未执行，不计入通过数。
- `permission-transparency-ui-r2` 已暂停、DB `enterprise_154` 保留。新 verification `permission-capability-verify-r3` / slot155 构建通过，首次自动分配使用侧车默认端口冲突；随后恢复调用错误地跳过空库初始化，只有 1 张表，启动失败。两次失败不作验收证据，等待进程及本 runtime 两个侧车会话已精确停止，DB/日志保留。下一套验证使用明确 slot 和完整初始化。
- AUTH-01 的语义盘点、AUTH-04 的故障回滚、AUTH-06 的精确附件旅程及既有 BOM 失败、AUTH-07 的固定回归/截图/SoT/PR 均未关闭。注册覆盖与实际执行仍分别汇报。


## 依赖收口与第六轮候选（2026-10-02）

- r5 编辑器定向浏览器 14 项通过；OSS 15 UT + 8 real-stack IT、Enterprise 4 UT + 4 real-stack IT 通过。OSS IT 包含真实角色权限交换与注入失败后的数据库事务回滚。
- r5 固定产品门在第49项附近因 Playwright SIGKILL、Web/BFF/backend 同时消失而中断；46项已通过、2项匹配场景失败，但没有完整执行报告。运行时验证为 invalid，原因未确定；保留证据并 suspend，不能据此宣称全量通过。
- 原图审查发现范围对话框显示技术模型名称，未配置值被误标为无效；修复为读取已有模型业务名称，保留 null 语义。业务名称加载失败时显式报错并禁止保存。新增2 UT，目前前端58 UT通过；第六轮浏览器尚未执行。
- 能力依赖按真实命令入口补齐 `meta.command.execute`，不再要求通过报价上传能力间接获得依赖。报价查看/输出/文档包含文件读取；维护能力补齐自身记录读取。BOM规则查看覆盖当前启用规则模型读取，规则菜单要求读取，编辑命令继续要求维护。
- 显式模型 permission 声明补齐既有 resourceCode/action 身份，避免覆盖自动生成元数据后丢失默认范围继承；不改权限裁决算法。
- 客户导入策略已启用，因此增加独立“批量导入客户”能力；客户页面实际暴露“移入公海”动作，因此增加独立“移入客户公海”能力及目标读取依赖。两者均不隐式授予现有角色，不开放隐藏公海管理菜单，不解除联系方式脱敏。
- 组织维护命令中的 `ORG.hr.manage` 与实际声明 `org.hr.manage` 不一致，统一为已存在小写权限，补齐组织记录与命令依赖。
- 静态 capability gate 原先把命令后缀直接当 permission action，导致正确的成员 approve/reject/suspend/restore 被误判 ghost；按已有 CommandActionDeriver 规则修正，并保留错后缀负例。
- BOM固定回归使用正式建料命令生成独立匹配物料及冻结投影，不降低匹配断言。报价真实新建上传旅程增加资料页签下载、运行目标与逐字节内容验证；共享拒绝要求明确403，不接受500冒充授权拒绝。

仍未关闭：第六轮冻结构件真实UI、BOM两项匹配回归、新增资料下载、独立能力角色实效、固定产品/角色门、逐原图复核、最终SoT与五仓PR提交。新的CRM修改使原只读依赖仓变为 in-scope；工作区提交审计必须据此更新。完整动作授权不是一次性交付所有业务入口验收，具体未执行入口仍须在证据分母保留。


### 当前新增验证与环境阻挡

当前启用菜单及其关联/常规详情页面共静态审计40页、37个声明/命令权限引用，均有上层能力关联，目录现为64项。证据 `ui/capability-current-active-surface-audit.json` 明确仅 static_audited，未推出运行访问结论；未引用页面和后端专用依赖仍保留其验证边界。

- 新增成员列表默认入口取决于 org-tree-picker 注册，修复无组件时误导性空组织入口；有效期文案进入构件与 seed。既有 permission-v2-golden 成员用例改为真实添加、确认移除、reload回显和关键payload验证。
- BOM规则详情10个编辑/删除按钮补与列表相同的 bom.rule.manage 声明。既有 role-capability-closed-loop 新增独立维护能力→实际创建→撤销为查看→重新登录读取、按钮隐藏、精确403与无写入旅程。该文件当前 collection 为4，executed=0。
- r6 capacity gate 拒绝2次：本任务 r3 已无进程，公开 runtime close 释放登记并保留所有数据；释放名额被另一个任务占用。没有修改预算或停止其他任务。已请 owner 提供一个验证名额。
- CI 私网 ping和SSH不可达，wg interfaces为空；现有受限配置存在，但 sudo -n wg-quick up 需要密码，未改变网络设置。已请 owner 在本机启用既有隧道。

本轮 Goal 仍 active，完整产品门、角色门及当前UI验收仍为未执行，不关闭AUTH条目，不标 shipped。

### 五仓候选提交及客户删除入口修复

五仓均已创建草稿审查入口：OSS #2157、Quote #534、Enterprise #1431、Plugins #612、CRM #25。未合并；新增三个 PR 的聊天挂载被附件工具 transport closed 阻挡，GitHub PR 创建成功。

CRM 客户列表原通用批量删除入口以 crm.account.manage 显示，但调用要求 model.crm_account_common.delete 的通用 API。关闭通用入口，保留已有 crm:delete_account 业务批量命令及关联业务拒绝逻辑，不扩大角色底层删除授权。新增配置负例及既有三个角色真实UI删除旅程断言；CRM配置3项通过，浏览器尚未执行。

r6 第四次完整启动仍被验证容量拒绝；没有启动服务、导入配置或执行当前浏览器测试。当前PR保持draft，完整验收与CI阻挡不因提交而关闭。
