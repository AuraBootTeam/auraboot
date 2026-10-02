---
type: plan-impl
status: active
created: 2026-10-02
---

# 权限透明化 review 与实施账本

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
