---
type: system-reference
status: active
created: 2026-10-24
updated: 2026-10-27
implementation_status: implemented-local-validated-pr-open
---

# 公告管理需求与 BPM 集成设计

## 阅读导航

- 需求、范围、职责与安装形态：§1–§3。
- 权限、状态、页面和运营规则：§4–§10。
- 实施依赖、验收计划与默认决策：§11–§13。
- [P0源码盘点](#14-p0-实际能力盘点与实施契约)、[关键缺口及S1–S3详细契约](#15-关键缺口决策与-s1s3-详细设计)。
- [剩余闭环与设计自查](#16-剩余设计闭环实施就绪与自查)：页面、知悉、催办、统计导出、运维和全需求对账。

## 1. 定位与文档状态

本文是 OSS 公告管理的需求、UX、权限及集成契约的唯一入口。需求讨论采用的默认规则已落入 S1–S8 实现；**本期实现与本地分层验证已完成，三个关联 PR 保持开放，未合并、未发布生产**。当前裁决以 §18.28、[验收报告](./2026-10-25-announcement-s2-s8-testing-gate-acceptance-report.md)及其同一证据 manifest 为准。S1 历史证据见 [S1 验收记录](./2026-10-24-announcement-s1-testing-gate-acceptance-report.md)。阶段是依赖顺序，不是等待用户逐阶段确认的闸门。§14 是实施前盘点，§17 和 §18.1–18.27 是历史切片；其“待完成”描述由当前裁决取代。测试数量不等于功能覆盖率，未执行边界不会因实现完成而消失。

目标是为当前租户提供“编写 → 免审或审批 → 立即或定时发布 → 多渠道提醒 → 阅读与确认知悉 → 催办与统计导出”闭环。

Aura BPM 定位为“独立应用 + 可嵌入能力”：独立产品拥有流程设计、执行、任务、审批与管理体验；业务应用通过平台公开契约消费同一套能力。OSS 不包含 BPM 产品实现，也不因公告审批而引入产品内部依赖。

### 1.1 现有源码参考与差距

以下是设计前源码盘点入口，只证明源码存在，不证明运行时链路通过：

| 入口 | 现有内容 | 本期需补齐或核对 |
| --- | --- | --- |
| [公告插件](../../plugins/core-announcement/plugin.json) | 公告配置资源 | 受众、版本、权限、知悉、发布安排和审计 |
| [公告命令](../../plugins/core-announcement/config/commands.json) | 草稿、发布、撤回、重新发布 | 与新状态、版本和审批契约统一 |
| [工作台公告服务](../../platform/src/main/java/com/auraboot/framework/workbench/service/impl/AnnouncementServiceImpl.java) | 公告读写 | 与 DSL 命令统一业务规则，消除旁路 |
| [工作台查询](../../platform/src/main/java/com/auraboot/framework/workbench/mapper/AnnouncementMapper.java) | 有效公告筛选 | 管理命令使用 published，服务使用 active，需统一发布状态及实际可见性 |
| [Header](../../web-admin/app/routes/Header.tsx) | 铃铛及 Inbox | 工作区模式目前隐藏 Inbox，需保证待办可达 |
| [通知下拉](../../web-admin/app/ui/notification/NotificationDropdown.tsx) | 最近未读、全部已读、通知中心 | 公告筛选、准确业务导航、错误态 |
| [待办下拉](../../web-admin/app/ui/inbox/InboxDropdown.tsx) | 待处理事项及审批类型 | 知悉事项、生命周期和计数语义 |
| [工作流契约](../../platform/platform-plugin-api/src/main/java/com/auraboot/framework/plugin/extension/WorkflowCapability.java) | 公开能力接口 | 逐项核对 §9 契约，不假定接口已全部支持 |

Aura BPM 产品侧参考为 `AuraBootTeam/aura-bpm` 仓库的 `app.yaml`、`plugin/plugin.json`、`packages/aura-bpm-web` 与 `BpmWorkflowCapability`。这是所有权说明，不是 OSS 对兄弟源码或私有包的构建依赖。

## 2. 本期范围与非目标

### 2.1 本期范围

| 领域 | 范围 |
| --- | --- |
| 租户 | 当前租户有效成员，严格租户隔离 |
| 受众 | 全员、人员、部门、角色；部门包含下级，条件并集去重 |
| 内容 | 标题、富文本正文、附件、普通/重要、置顶、有效期 |
| 发布 | 草稿、预览、免审、审批、立即发布、定时发布、取消预约、撤回、重新发布 |
| 审批 | 租户管理员配置默认公告流程；串行节点，指定人员或角色，任一人/全部人同意 |
| 查看 | 我的公告、详情、搜索、未读与待确认筛选、工作台入口 |
| 反馈 | 首次阅读、确认知悉、确认期限、逾期完成 |
| 提醒 | Web 站内通知，复用 Header 铃铛 |
| 待办 | 审批及确认知悉，复用统一待办 |
| 催办 | 手动和自动，未读与未确认分开处理 |
| 统计 | 接收、阅读、确认、逾期、失效成员和分渠道结果 |
| 导出 | 授权导出接收、阅读、知悉明细及汇总 |
| 审计 | 内容版本、审批、发布、撤回、催办、导出和权限相关操作 |
| 扩展 | 小程序渠道接入契约，跨端共用公告与知悉事实 |

### 2.2 非目标

- 小程序页面、真实订阅授权获取和真实微信消息发送；不宣称首期已具备外部送达能力。
- 跨租户公告、访客或外部客户接收。
- 自动向上级升级催办、代确认、批量确认知悉。
- 旧版本继续展示、新版本同时审批的并行版本机制。
- 跨服务远程 BPM 集成、共享业务数据库或产品间源码依赖。
- 全量 BPM 产品改造；仅补齐公告嵌入所需的公共能力与产品实现。
- 生产部署；文档、实现、PR 和安装设计均不等于生产发布授权。

## 3. 职责与安装组合

### 3.1 所有权矩阵

| Owner | 拥有 | 边界 |
| --- | --- | --- |
| OSS 公告 | 内容、版本、受众、发布计划、查看权限、阅读、知悉、催办、导出、业务审计 | 不读 BPM 表，不依赖 BPM 内部类 |
| OSS 通知 | 站内提醒、渠道路由、发送日志、去重、重试 | 不把发送成功当成阅读或知悉 |
| OSS 待办 | 通用事项展示、计数、导航与生命周期承载 | 审批结果由 BPM 决定，知悉结果由公告决定 |
| OSS 公共工作流契约 | 能力发现、版本化 API/SPI、通用工作流结果与导航契约 | 不引入 SmartEngine、Drools、BPM 产品 migration、菜单或页面 |
| Aura BPM | 引擎、流程定义、审批人、任务、审批历史、流程异常处理、前端贡献和产品迁移 | 只消费公开平台 API/SPI，不反向拥有公告业务状态 |
| 应用组合 | 平台与插件版本锁定、前端贡献、配置导入顺序、迁移集合、安装校验 | 通过显式组合交付，不运行时猜测安装或自动修复 |

CRM、EDU 等应用复用同一公开工作流边界；领域状态与页面继续归各应用，不复制公告内部实现。

### 3.2 安装形态

| 形态 | 构件 | 行为 |
| --- | --- | --- |
| 纯 OSS 公告 | Core + 公告配置/实现 + 通知/待办等必要能力 | 完整免审公告闭环；审批显示未启用 |
| 公告 + BPM 组合 | 上述构件 + 锁定的 BPM 后端插件、前端贡献、产品迁移与配置 | 开启公告审批与审批待办 |
| 独立 Aura BPM 应用 | 平台构件 + BPM 产品构件与自身应用清单 | 独立流程产品；不默认拥有公告功能 |

组合版在同一 runtime 内以模块化单体装配，使用该组合拥有的数据库，显式应用 Core/BPM/公告所需迁移集合；不连接另一套独立 BPM 实例的数据库。独立 BPM 产品仍可单独部署和发布。

组合入口已落在 Aura BPM 的 `app.announcements.yaml` 与 `scripts/build-application.mjs --composition announcements`，不新建公告产品仓。默认 `app.yaml` 仍是独立 BPM。构建仅消费显式冻结 Core catalog，不读取兄弟 OSS worktree；公开安装命令和验证边界见 §18.26。

### 3.3 组合安装流程与能力缺失

1. 解析应用清单与锁文件：验证 Runtime/API、插件、前端贡献、迁移、配置版本及摘要。
2. 在部署阶段显式执行 Core 与产品迁移，加载准确插件构件；迁移具体顺序由依赖图裁决。
3. 显式初始化与导入必要配置、权限、菜单及流程定义；定义经授权发布并绑定租户后才可审批，启动和打开页面不得自动 bootstrap/repair。
4. 验证工作流能力可发现，流程已发布且属于当前租户，前端任务/历史入口可导航。
5. 验证免审和审批两条真实旅程，以及版本身份、权限与缺失能力负向行为。

缺少 BPM 时可以保存草稿；允许授权免审的租户中，有免审权限者可以主动选择免审。强制审批策略下阻止发布。不得从审批失败、超时或未安装自动降级到免审。卸载/禁用 BPM 前必须处理运行中审批、待办和历史读取依赖，存在未结流程时阻止直接卸载。

## 4. 权限与受众

### 4.1 权限矩阵

| 权限/身份 | 操作 | 数据范围 |
| --- | --- | --- |
| 接收者 | 查看、阅读、确认 | 本人接收且当前可见的公告 |
| 编辑者 | 创建、编辑、提交审批 | 自己负责或被授权管理的草稿 |
| 普通发布者 | 发布/预约、撤回、取消预约 | 管理范围内、拥有管理权的公告 |
| 租户公告管理员 | 全员或定向发布、管理 | 当前租户授权公告 |
| 免审权限 | 主动选择免审 | 仍受租户策略及发布范围约束 |
| 审批人 | 查看送审快照、同意、驳回 | 当前节点有效任务 |
| 统计/催办权限 | 接收明细、汇总、催办 | 被授权管理的公告 |
| 导出权限 | 导出统计与明细 | 同时具备对应数据查看权 |
| 流程管理员 | 维护流程、恢复原任务、重新分配异常审批人 | 当前租户；恢复需要 bpm.monitor.update |
| 公告政策管理员 | 默认流程绑定、免审策略、催办时间及额度配置 | 当前租户；policy_manage，绑定还需流程使用资格 |
| 删除权限 | 删除 | 从未发布且无审批历史的草稿 |

公告管理员不自动获得免审权限；审批不授予正文编辑或发布权限；接收者不可查看他人阅读记录。管理员管理预览不计为接收者阅读或确认。

通过平台统一命令入口执行管理动作时，角色还须具备基础`meta.command.execute`；它与公告各动作权限叠加，不替代edit/publish/exempt等领域授权。自定义发布者角色须明确配置`ab_announcement/publish`的SELF/部门等范围，角色的通用默认范围不代替公告受众授权；全员仍需publish_all。纯接收者无须为阅读和知悉获得管理命令权限。

租户策略为“强制审批”或“允许授权免审”。后者允许有免审权限者选择免审，其余人仍走审批。免审必须填写原因并留痕。已在审批中的公告必须先撤销申请才能切换免审。

### 4.2 范围解析

- 全员仅指当前租户有效成员，排除待邀请、停用及已退出成员。
- 人员、部门及角色取并集，同一人只形成一份该版本接收记录。
- 部门含下级；范围来源复用现有组织与数据权限体系，不新增公告专属组织树。
- 普通发布者限管理范围；角色不扩大授权。角色选择明确展示授权范围内的覆盖人数，不静默裁剪越权选择。
- 提交时冻结受众规则，展示预计人数及名单；审批人批准的是规则，实际名单在发布时解析并固定。
- 发布时重新校验申请人的有效发布权限及受众规则，范围失效、越权或零接收人时阻止发布。
- 发布后新成员默认不补发、不获得历史定向公告权限；退出租户/停用立即失去访问权。
- 名单冻结后角色或部门变化不回写历史接收事实；租户资格失效始终优先阻断访问。
- 列表、搜索、详情、附件、摘要、待办和导出执行服务端一致校验，不能依赖前端隐藏。

## 5. 内容、版本及状态

标题和正文发布时必填；草稿可不完整保存。富文本经过平台安全处理，附件复用平台上传策略及最终化状态，下载继承公告权限。

重要公告默认开启知悉；普通切换为重要时启用默认值，但再次编辑不覆盖已保存选择。允许有编辑权者关闭知悉并在送审摘要明确展示。置顶由授权管理者独立调整并留痕，不触发重新通知。

送审快照冻结标题、正文、附件、重要程度、受众规则、渠道、发布时间、有效期、知悉要求及催办配置。审批通过只对该版本有效，修改这些内容需重新送审。

| 维度 | 状态 |
| --- | --- |
| 审批 | 未提交、审批中、已通过、已驳回、已撤销、技术失败（不等于驳回） |
| 发布 | 未发布、待发布、发布时间已错过、已发布、已撤回、已过期 |

页面组合展示两维状态：审批模式不允许未通过却已发布；有有效免审依据时允许 not_submitted 与 published 组合。免审单独记录模式和原因，不伪造 BPM 审批通过记录。

- 提交审批意味着授权系统批准后按该快照安排发布，无需二次手工点击。
- 审批中不能编辑；撤销后修改，重新提交建立新申请身份。
- 驳回必须说明原因；修改后重新提交。
- 取消预约后释放原发布任务；调整已批准的发布时间需重新审批，免审版本按权限重新预约。
- 已发布修订先撤回，再建立新版本并重新走发布路径；一个公告同时只有一个生效版本。
- 新版本重新记录阅读和知悉，旧版本记录保留。
- 有审批或发布历史禁止直接删除；撤回与归档保留历史。
- 撤回立即停止正文展示、待发送任务和催办；历史消息显示撤回说明，外部已发送消息不保证收回。

## 6. 页面与入口交互

### 6.1 Header 与工作台

不增加公告专用 Header 图标。铃铛承载“新消息”，Inbox 承载“待处理”，工作台近期公告进入同一接收者详情。

| 入口 | 内容 |
| --- | --- |
| 铃铛 | 公告发布、阅读/知悉催办、审批结果、发布异常 |
| Inbox | 审批任务、确认知悉事项 |
| 我的公告 | 完整列表、检索与历史查阅 |
| 公告管理 | 编辑、发布、统计、催办和审计 |

铃铛下拉支持全部/公告筛选、最近消息预览、查看全部通知/公告。公告项展示重要程度、标题、来源、时间、知悉期限和待确认标签。点击打开准确版本的业务详情，不根据任意来源字符串拼接通用路由。

铃铛计未读通知，Inbox 计待处理事项，不相加或重复汇总。全部已读只清除通知未读，不记录公告阅读、不完成知悉或审批。通知已读但知悉未完成时，待办继续可达；工作区模式也必须提供待办入口。

空、加载失败、无权限明确区分；失败提供重试，不显示为暂无通知。切换租户后清除旧租户缓存、计数和订阅，防止跨租户摘要泄漏。

### 6.2 我的公告与详情

列表支持全部/未读/待确认、关键词及重要程度筛选，置顶优先，再按发布时间排序。默认展示当前有效公告，历史提醒仍可打开失效状态说明。

完整正文成功加载后记录首次阅读；下载附件、看到摘要、收到渠道消息都不算阅读。详情展示发布人、时间、正文、附件、有效期及知悉要求。确认按钮放在正文后，下拉摘要不提供确认。

确认成功显示时间，刷新及跨端保持一致；重复确认返回既有结果。超期仍可确认，标记逾期完成。确认同时标为已读；无知悉要求不显示按钮。不支持代确认、批量确认或撤销确认。

### 6.3 编辑、预览与审批

编辑页分为内容、接收范围、发布设置、知悉要求四组。受众选择展示名称、部门路径、规则与去重预计人数，支持查看预计清单。

发布方式仅展示有权使用的选择；审批流程使用租户配置的默认公告流程，展示节点预览，发布者不能任意选择流程或修改审批人。无能力时说明未启用及处理入口。

确认期限采用实际发布后若干天，必须早于或等于过期时间；无过期时间仍可设置知悉期限。预览展示接收者视角与受众、渠道、发布时间、免审/审批、知悉和自动催办摘要。

按钮分别为保存草稿、提交审批、立即发布、预约发布。提交前确认影响与预计人数；失败保留输入，离开未保存页提示，重复点击不能重复创建。

审批任务展示送审版本、附件、受众规则及预计人数、发布安排、意见与历史。同意/驳回后刷新任务状态；角色异常提供管理员处理信息，不能自动跳过。

### 6.4 管理、统计与导出

管理列表提供搜索、审批/发布状态和发布方式筛选，操作按权限和状态隐藏或禁用并说明原因。详情展示内容版本、审批、发布安排、统计、渠道结果、催办与审计。

统计展示固定接收人数、已读、未读、已确认、未确认、逾期未确认、当前无需催办成员、分渠道处理结果。历史接收人数不随离职变化；失效成员单列，不继续催办。实际人数与送审预计人数差异可追溯。

导出本期纳入：基于授权公告、准确版本与当前筛选导出汇总及接收明细。默认 CSV（UTF-8，兼容常用表格工具），包括公告标题/版本、接收者展示名、受众归属快照、阅读/确认状态及时间、知悉期限、成员资格、渠道结果；不导出 openid、token、内部原始凭据。无知悉要求时输出“不适用”，不能算成未确认。

导出记录申请人、时间、版本、筛选、数据快照时间与行数；下载再次校验权限，文件受控过期，防止公式注入。大结果使用可追踪导出任务，失败可见；浏览器下载必须验证文件名、编码、列名、内容与筛选一致性。

## 7. 定时发布、知悉和催办

### 7.1 发布调度

调度由公告模块拥有，复用平台能力，不把所有发布等待做成 BPM 节点。按租户时区展示，保存准确时间与时区解释。

到预约时间仍未通过审批，进入发布时间已错过；以后通过不自动补发。调度恢复时默认允许延迟 30 分钟（租户管理员可配置）；超过窗口等待重新安排。批准晚于预约时间与调度技术延迟必须区分，前者不适用延迟窗口。

发布前重新检查版本、批准/免审依据、权限、受众、附件与有效期。批准保留，发布执行失败单独记录失败原因和恢复状态；修复同一任务不得重复发布或重复分发。定时到达时已过有效期则阻止发布。

### 7.2 自动与手动催办

- 自动默认租户时区每日 09:00、最多三次，首次发布提醒不计入。
- 第一次自动催办取发布后至少 24 小时的首个 09:00（恰好相等也可）；按租户日历每天最多一次。
- 要求知悉时默认催未确认者（包含已读未确认）；知悉截止后停止自动催办，保留手动知悉催办。
- 不要求知悉时只自动催未读者，最多三次，过期/撤回即停止。
- 手动可选未读或未确认；无知悉要求不提供未确认选项。
- 手动默认同一公告、版本及接收人一小时内不重复提醒；手动与自动共用去重窗口。每日一次/最多三次限制用于自动催办，手动另受权限、频率限制及审计约束。
- 执行前预览对象和人数，发送前重新检查资格与完成情况；反馈已创建、跳过及失败数量和原因。
- 已确认、成员失效、公告撤回或过期者退出；截止后手动仍受公告有效期约束。
- 自动次数按成功持久化的催办批次计，同一批次技术重试不增加次数；已入队的渠道失败在原批次重试，不生成新催办。
- 租户免打扰配置优先；受影响批次顺延到允许窗口，到期则停止，不集中补发错过的多次催办。

## 8. 通知、待办与小程序扩展

| 事件 | 通知 | 待办/业务状态 |
| --- | --- | --- |
| 提交审批 | 当前节点审批人提醒 | BPM 创建审批待办 |
| 同意到下一节点 | 下一节点提醒 | 关闭旧任务、创建新任务 |
| 通过/驳回/撤销 | 申请人结果提醒 | BPM 关闭任务，公告更新准确版本 |
| 发布 | 每接收人一条发布提醒 | 有知悉要求则创建一项确认待办 |
| 阅读 | 更新对应阅读事实 | 知悉待办继续保留 |
| 确认 | 相关展示刷新 | 公告完成该版本知悉待办 |
| 撤回/过期 | 历史提醒失效展示 | 关闭确认待办并保留原因 |

知悉不是每人一个 BPM 实例。消息已读、公告阅读、确认知悉、任务完成、渠道结果分别保存和统计。审批任务重新分配或他人完成时，旧操作者操作必须返回最新状态，不残留可执行旧按钮。

每次发布/催办按公告版本、业务事件、接收人、渠道建立幂等身份，内部持久化与分发使用平台已有可靠机制，避免提交成功但消息丢失。公告有效与渠道成功分开：单渠道失败不回滚公告；重试不重复生成公告或待办。

首期 Web 站内提醒为基础渠道。小程序预留账号绑定、订阅授权、模板映射、可用性、深链接、结果记录和重试契约；未来共用受众、版本、阅读及知悉事实。未授权/未配置不能虚报发送成功，登录或手机号授权不等于订阅授权。

## 9. BPM 嵌入契约与一致性

### 9.1 能力清单（目标语义，非既有方法签名）

| 能力 | 必要语义 |
| --- | --- |
| 发现与就绪 | 可用状态、兼容契约、当前租户默认已发布流程 |
| 发起 | 租户、业务类型/标识、公告版本、申请人、不可变快照引用、幂等键 |
| 任务详情 | 当前节点、处理资格、送审详情引用、允许动作 |
| 同意/驳回 | 任务身份、版本校验、意见、服务端操作者身份 |
| 撤销 | 校验申请人/管理员资格，关闭运行中申请并留痕 |
| 结果同步 | 流程实例、公告版本、明确结果、事件身份/顺序和发生时间 |
| 历史 | 节点、人员、结果、意见及时间，支持合法历史读取 |
| 导航 | 业务详情/任务/历史准确定位，页面重新校验权限 |

租户与操作者取可信服务端上下文，不信任客户端任意传入。使用公开 API/SPI 与 typed frontend contribution；页面以 DSL-first 和既有公共审批区块表达，平台缺口先补公共能力，不能把 BPM 产品页面复制到 OSS。

### 9.2 审批规则

租户流程管理员维护默认公告流程；指定人员或角色，串行多节点，任一/全部同意；任一驳回结束申请。流程实例固定定义版本，流程后续修改不追溯改变运行中实例。

进入节点时解析并固定该节点有效审批人，禁止申请人自审；排除后无人或审批人失效进入待处理异常，管理员重新分配并审计。管理员不能以重新分配伪造已同意。发布者提交后失权按 §4/§7 阻止后续发布。

### 9.3 可靠结果处理

重复发起返回同一申请身份；旧流程结果不改变新版公告，重复结果不重复发布。终态事件按实例/版本/事件身份幂等消费，乱序和冲突结果明确拒绝并留痕。撤销与通过、发布与撤回并发以受控状态转换裁决，不能只靠前端判断。

若结果同步暂时失败，展示处理中/同步异常并告警，通过可追溯重试与显式状态核对恢复；不得把未知视为通过，也不能自动创建新申请。批准与发布分开持久化，发布失败恢复原任务。

## 10. 异常与安全体验

| 场景 | 预期 |
| --- | --- |
| BPM 未安装、流程未发布 | 草稿可存、审批阻断；显示配置原因，不自动免审 |
| 角色无人、自审排除后无人 | 异常待处理，管理员重新分配 |
| 审批人失效 | 取消其操作资格，保留事实并转异常处理 |
| 重复提交/重复结果 | 返回已有结果，无重复流程或发布 |
| 并发编辑 | 版本冲突提示，保留输入，不覆盖他人修改 |
| 发布时权限或范围失效 | 阻断并说明，调整快照后重新审批 |
| 发布时零受众 | 阻断，保留原因及批准记录 |
| 调度超窗/审批超时 | 显示已错过，需重新安排 |
| 单渠道失败 | 公告有效，分渠道可追踪重试 |
| 详情加载失败 | 不记阅读，提供重试 |
| 附件未完成/失败 | 阻止提交，定位文件，保留正文 |
| 确认时撤回/过期 | 拒绝确认并展示最新状态 |
| 催办前已完成 | 跳过，结果可见 |
| 导出中权限收回 | 阻止下载，任务与审计记录保留 |
| 网络超时但服务端已成功 | 用原操作身份核对结果，避免新建重复任务 |
| Header 更新中断 | 显示可重试状态，恢复后核对计数，不显示伪零值 |

用户可见文字支持 i18n，展示业务名称而非 UUID、内部状态码或原始 HTML/JSON。富文本、附件和导出分别防止脚本、越权下载与公式注入。审计包含租户、操作者、公告/版本、动作、前后状态、结果、时间及关联身份，不记录密钥或渠道凭据。

发布/分发采用批量和异步处理，接收者分页、查询索引、队列积压和失败可观测；不在页面请求内逐人同步发送。容量数值由实施前真实租户规模测量确定，不能以未测值宣称可承载。最少监控发布延迟、失败发布、BPM 结果同步失败、分发失败、催办失败和待处理任务积压。

## 11. 分阶段实施与出口

下表保留 P0→P4 的依赖顺序；对应 S1–S8 已实施并完成本地分层验证，实际出口和未执行边界统一见 §18.26 及验收报告。P0 当时仅盘点源码，不能把其静态事实追认为运行证据。

| 阶段 | OSS 工作 | Aura BPM/组合工作 | 出口 |
| --- | --- | --- | --- |
| P0 现状与契约冻结 | 对账状态、命令/API旁路、权限来源、通知/待办/调度、导出能力；冻结模型与公开契约差距 | 对账发起/任务/结果/历史/导航和安装兼容 | 单一状态和身份契约、版本化协作边界、真实缺口与验收范围明确 |
| P1 公告基础闭环 | 版本、受众、权限、免审、列表详情、附件、定时/撤回、阅读/知悉及审计 | 无需嵌入 BPM 实现；能力缺失路径明确 | 纯 OSS 真栈旅程可验证，不伪报审批支持 |
| P2 BPM 嵌入 | 公共契约缺口、默认流程配置入口、业务快照、状态同步与幂等 | 实现插件端契约、审批贡献、人员/角色节点、自审/异常、组合清单与锁 | 组合安装可复现，提交→审批→发布及失败恢复链路成立 |
| P3 统一入口与运营 | 铃铛/待办、工作区入口、自动/手动催办、统计、导出、渠道结果 | 审批待办生命周期与业务导航协同 | 准确计数、知悉闭环、催办限制、真实文件下载 |
| P4 综合验收与文档同步 | 权限/并发/时序/导出/无 BPM 全链路、原图视觉复核、运行说明 | 锁定构件的组合安装及升级/能力缺失验证 | 按 §12 证据逐项裁决，标出未执行项，更新本文实际状态 |

后续代码开发在稳定任务工作区执行，只为实际改动仓创建 checkout；未改依赖优先消费锁定构件。组合验收记录各构件与源码身份，不从兄弟 main 隐式构建。是否提交/合并/生产发布另按对应授权与仓库流程执行。

## 12. 验收要求与证据规则

本节规定验收要求；当前执行结果在 §18.26 和同一验收报告。覆盖从 DSL、命令、页面、API、状态和副作用抽取并关联具体断言，不以本表或用例数替代业务覆盖。

| 需求域 | 最低证据 |
| --- | --- |
| 纯 OSS | 菜单→草稿→免审/定时→铃铛→详情→知悉；无 BPM 阻断审批；刷新持久化 |
| BPM 组合 | 实际安装→菜单送审→真实人员/角色审批→准确版本结果→发布，不能只用模拟回调 |
| 权限 | 普通管理范围、全员管理员、免审策略、自审、跨租户/猜链接/附件/导出负向，失败不写入 |
| 状态与时序 | 重复提交、回调乱序、撤销竞态、权限收回、晚批准、调度中断和发布恢复 |
| 阅读/待办 | 全部已读不完成业务；已读未确认仍待办；跨端/刷新同态；失效终止 |
| 催办 | 24小时起点、每日/三次、截止停止、手动截止后、去重、失效跳过及渠道重试 |
| 导出 | 菜单按钮真实下载、名称/编码/列/解析内容/行数/筛选/权限及公式注入 |
| 渠道 | 站内成功与外部未启用/失败分开，幂等不重复计数 |
| 视觉 | 列表、编辑、详情、Header/待办及加载/空/错/无权/逾期状态逐张打开原始截图复核 |

测试按四轴分类：被测面、依赖、裁决权、驱动入口。hermetic unit 验纯状态与时间/集合规则；real-stack IT 验实际持久化、插件、工作流与任务副作用；journey/real-stack/browser 从精确用户入口触发并断言 payload、目标 runtime、DB/业务结果与刷新状态。菜单行动不能用直接 API 替代；导出文件需解析。

验收分别报告 registered/collected、executed、passed、failed、skipped、did_not_run；构建、静态检查和文档完整性不代表功能通过。声明全量完成前核对覆盖分母、真假绿及视觉证据。

## 13. 已采用的默认决策与实施核对项

| 项目 | 决策 |
| --- | --- |
| BPM 定位 | 独立应用 + 可嵌入能力 |
| 范围 | 当前租户；人员/部门/角色，部门含下级 |
| 发布权限 | 普通发布者限管理范围，公告管理员可面向全员 |
| 审批与免审 | 两者均在本期；租户策略 + 独立免审权限；免审原因必填 |
| 流程 | 流程管理员维护默认公告流程，禁止自审，人员/角色串行节点 |
| 知悉 | 每条可选，重要默认开启，截止后允许人工确认 |
| 自动催办 | 每日一次最多三次，知悉截止后停止，保留手动 |
| Header | 复用铃铛和待办，不新增公告图标 |
| 导出 | 本期包含，默认受控 CSV |
| 组合 | 现有应用组合机制，不新建公告产品仓 |
| 手动催办 | 一小时接收人级去重，可配置 |
| 调度 | 租户时区，09:00 自动催办，发布延迟窗口默认 30 分钟 |
| 附件 | 复用现有文件策略，不另设绕过策略 |
| 期限 | 知悉截止不晚于公告有效期 |

没有待用户批准的产品决策阻断本设计。P0 源码核对结果、接口差距和安装方案统一见 §14；附件运行时限额、免打扰配置接线、兼容制品摘要和真实容量仍须在实施环境核实。缺失时先落实公共契约与显式配置，不猜测符号、API 或静默放宽权限。


## 14. P0 实际能力盘点与实施契约

### 14.1 盘点口径与结论

2026-10-24 的本轮为 **source-audited / plan-only**：读取 OSS 任务分支与 Aura BPM 本地干净 main 的源码、配置、构建脚本；没有启动服务、调用业务 API、运行数据库或浏览器测试、执行组合安装。源码存在和既有测试文件存在不能替代运行时可用结论。

| 输入 | 固定身份 | 使用方式 |
| --- | --- | --- |
| OSS | 代码基线 `c1538e194f77995c614cc3be84dd9fcb325bb6c5`；盘点时文档提交 `d7d95a3f8f55c8ccd4b531831daf413fdcb926c4` | 同一公告任务分支，仅更新本文 |
| Aura BPM | `6730ac5144826c9feca8ce5b999cbcbf8ce94d24`，盘点时 main 干净 | 只读依赖，不创建 BPM 编辑工作区 |

结论：基础设施可复用，但不存在现成的完整公告审批闭环。优先修正公告双写入口与状态、受众权限、不可变版本及结果契约，再开发发布和运营能力。BPM 需要局部扩展，不能仅靠配置即满足本期要求。

下面“可复用”仅指找到相关实现；“需扩展”指现有能力语义不完整；“缺失”限定为本次检查的公告域及列出的入口中未发现相应实现。不存在“已验收”分类。

### 14.2 OSS 模块、复用点与缺口

路径均相对 OSS 仓库根目录。表内符号可在固定基线定位。

| 编号 | 模块/源码证据 | 实际能力 | 缺口与实施决定 |
| --- | --- | --- | --- |
| O01 | `plugins/core-announcement/config/{models,fields,commands,pages,permissions}.json` | config-only 插件；基础 CRUD、发布/撤回；仅 read/manage 两类声明权限；字段含标题、正文、优先级、状态、置顶、发布人/时间、过期时间 | 需扩展：版本、受众、审批/免审依据、定时、知悉、催办和细分权限。模型的 semantic/lifecycle 描述不等于字段或执行能力 |
| O02 | `framework/workbench/{controller/AnnouncementController,service/impl/AnnouncementServiceImpl,mapper/AnnouncementMapper}`（位于 `platform/src/main/java/com/auraboot/`） | `/api/announcements` 直接 CRUD，GET 仅有效列表；写接口检查 `MetaPermission.ANNOUNCEMENT_MANAGE`；服务接受请求状态 | 高优先级：管理命令写 published，工作台查 active；直接 API 和 DSL 都能写。统一业务服务/命令校验，所有写入口执行同一审批、版本和范围规则，不保留旁路 |
| O03 | `framework/workbench/entity/Announcement`、`db/migration/core` 中公告表 | 持有基本公告事实，无本设计的版本/收件/确认聚合 | 缺失：不可变送审版本、固定接收名单、阅读/确认、发布/催办任务及操作身份；Core Flyway 增量迁移，不修改既有 migration |
| O04 | `framework/organization/service/OrganizationService` / `impl/OrganizationServiceImpl` | 动态组织表；`getDeptAndSubPids`、`getEmployeesByDept(...recursive...)`、员工与成员/用户关联、角色员工查询 | 可复用部门展开；需扩展统一受众解析。员工不是租户成员全集，无员工记录的有效成员仍可属于全员/人员/角色受众，不能直接把员工列表当全员 |
| O05 | `framework/permission/service/{DataScopeService,PermissionFacade}`、`DataScopeType`、`platform-plugin-api/.../IdentityDirectoryAccessor` | 数据范围有 NONE/SELF/DEPT/TEAM/DEPT_AND_SUB/ALL；目录支持角色用户 PID 和有效租户用户查询 | 需扩展公告“管理范围→候选受众→发布校验”适配；不能直接用公告记录创建者过滤替代受众授权。OSS 内部复用服务即可，BPM 只消费公开目录；不默认向插件开放整棵组织树 |
| O06 | `framework/notification/routing/DefaultRecipientResolver` | operator/record_owner/watchers，非 watcher 策略也追加关注者 | 不可直接用于定向公告。公告分发必须以批准规则解析后的固定名单为权威，禁止关注者自动进入受众 |
| O07 | `NotificationDeliveryAccessor`、`framework/notification/service/impl/PluginNotificationDeliveryAccessorImpl`、`NotificationDeliveryPlanResolver` | 公开请求按可信 resolutionReference 入队；默认开关 `aura.notification.plugin-delivery.enabled=false`；host 解析唯一 plan，经可靠事件机制分发 | 需扩展公告 host-owned resolver、不可变接收事实、配置及启用校验；不是给插件任意传收件人的通用 send。保持 host authority，不放宽为信任 payload |
| O08 | `framework/inbox/{service/InboxService,controller/InboxController,listener/InboxEventListener,mapper/InboxItemMapper}` | clientItemId 去重、read/acted/dismiss/close；监听 WorkflowEvent 建审批事项；同意/驳回先调真实工作流再记动作 | 可复用审批桥接；需新增知悉事项类型与业务动作，通用 mark_done/dismiss/批量 acted 不得绕过确认。关闭事项操作核对租户与准确版本身份 |
| O09 | `web-admin/app/ui/inbox/{InboxBadge,InboxDropdown}.tsx`、`routes/Header.tsx` | Badge 调 unread-count；SQL 为 pending 且 is_read=false；下拉查 pending；工作区隐藏 Inbox | 与目标计数不同：增加待处理计数契约，读过但未确认仍计待办；保留现有未读 API 语义给其他调用者，不偷换 unread-count；工作区入口、错误态和版本导航需补齐 |
| O10 | `ui/notification/{NotificationHeaderWidget,NotificationDropdown}.tsx`、`plugins/core-dashboard/widgets/workbench/AnnouncementWidget.tsx` | 铃铛未读数/SSE、最近通知、全部已读；工作台是公告摘要卡片 | 需公告筛选/版本路由/错误反馈/租户切换隔离；卡片要进入同一接收者详情，不能只显示截断文本 |
| O11 | `framework/scheduler/service/{SchedulerEngine,impl/DatabaseSchedulerEngine}`、`framework/common/util/TenantClock` | cron、interval、one_time；cron 支持任务时区→租户时区→UTC | 可复用唤醒和时区；尚非公告业务调度。公告需持久化到期任务、抢占/并发栅栏、超窗裁决、取消/重试与准确版本校验，不把 scheduler 的 Runnable 运行当作发布成功 |
| O12 | `framework/meta/service/impl/ExportTaskService` | 命名查询/模型导出、异步任务、租户+用户归属、私有文件、摘要及下载重新授权；过期时间源码默认 24 小时 | 可复用制品生命周期，需接入公告查询/权限/快照/CSV 列与公式防护。不能直接导出公告主表代替接收统计；动态已读变化不能简单导致历史快照下载失效，需区分数据更新与权限收回 |
| O13 | `platform-plugin-api/.../FileAccessor`、`framework/plugin/pf4j/FileAccessorImpl`、`ApplicationFileAccessorImpl` | 文件读取/元信息等窄桥接，平台掌握存储 | 需公告附件关系、定稿和受众下载校验；文件可读取不代表公告接收者有权下载。附件具体运行限额从实际部署配置回读，不凭接口注释填写 |
| O14 | `platform-plugin-api/.../WorkflowCapability`、`framework/plugin/pf4j/{WorkflowCapabilityRegistry,ApplicationModuleRegistry}` | 单工作流 provider 注册；`available(operation)` 与 execute fail-closed；请求/结果目前是 Map payload | 可复用调用边界，缺少统一类型化版本/幂等/终态契约和前端租户就绪信息，见 §14.4 |

补充缺口：当前通知偏好实体的 enabled/category/channel 不能证明租户免打扰日历已接通；知悉期限、每天一次/三次、手动一小时限制和公告批次审计均需公告业务实现。24 小时导出文件保留可作为复用基线，后续若调整须同步本文与部署配置。

### 14.3 Aura BPM 实际能力与缺口

以下均为 Aura BPM 仓库相对路径。固定源码可通过 [盘点提交](https://github.com/AuraBootTeam/aura-bpm/tree/6730ac5144826c9feca8ce5b999cbcbf8ce94d24) 定位；不把产品内部类变成 OSS 依赖。

| 编号 | 源码证据 | 实际能力 | 对公告的判断 |
| --- | --- | --- | --- |
| B01 | `plugin/backend/.../bpm/extension/BpmWorkflowCapability.java` | capabilityId=`aura.bpm.workflow.v1`；有 start、catalog.list、process.status/has-running/withdraw、task.approval-detail/approve/reject 等操作 | 接入骨架存在；不新建第二套工作流 registry |
| B02 | 同上 `start`；`service/BpmIntegrationService.startBusinessProcess`；`ProcessEngineService.startInCurrentContext` | start 收 processDefinitionKey/businessKey/variables/title，返回 processInstanceId；引擎按 key 解析版本并保存表单绑定快照 | 无显式请求幂等键、公告版本、快照摘要和要求的定义版本入参契约；has-running 只是查询，不能代替并发唯一性。引擎有 bizUniqueId 也不能直接推导公告重试语义 |
| B03 | `BpmWorkflowCapability.processStatus`；`listener/ProcessEventListener`；`event/EventBusService` | status 返回 found/status/completed/rejected；completed 同时匹配 completed/approved；process_ended 发布 transient 事件且 payload 为空；事件总线有持久化与内存派发两条路径 | 关键阻断：不能用 completed/process_ended 作为公告批准信号。需明确 approved/rejected/withdrawn/failed 业务结果、版本关联及可恢复结果交付 |
| B04 | `service/AssigneeResolverService`；`config/IdAndGroupTaskAssigneeDispatcher` | 指定人员和角色解析已有；目录支持有效租户成员；普通解析无结果/异常后 dispatcher 存在回退给发起人路径；MI 空集合路径另有处理 | 与禁止自审、无人阻断冲突。公告流程需显式严格策略：过滤申请人、无有效人进入异常，禁止该回退；不把此要求擅自扩大为所有历史流程全量改造 |
| B05 | `converter/JsonToBpmnConverter`、`config/DefaultMultiInstanceCounter`、`UserTaskEditor.tsx` | 有候选人/组、multiInstance、completionCondition 及审批模式 UI；嵌套 assignee 的 userIds/roleIds 主属性取第一项 | 会签/或签基础存在，但 UI 选择并不自动证明“全部名单固定、任一驳回终止”成立；必须统一配置编译、候选名单与终态规则，不能仅配置多个用户即声称满足 |
| B06 | `service/TaskService`、`WithdrawService`、`BpmApprovalDetailService` | 实际审批动作、候选/认领校验、转交/委托、撤回、审批详情与历史 | 可复用；补公告版本只读详情和附件访问。当前 process.withdraw 借第一条 pending task 撤回，需验证发起人撤销整实例语义；管理员异常重新分配不能等同普通转交 |
| B07 | `packages/aura-bpm-web/src/contribution-module.ts`、`auraboot.contribution.json` | 已注册 workflow-panel、task drawer、inline approval、workflow.start、aura.inbox.workflow、aura.resource.workflow、aura.navigation.target；路由为产品所有 | 可复用嵌入区块/服务，不能复制产品 React 到 OSS；前端 workflow.start 不能直接作为公告发布按钮，必须先经公告提交/快照/权限服务 |
| B08 | `app.yaml`、`scripts/build-application.mjs`、`plugin/plugin.json` | 独立 application；plugin `com.auraboot.bpm`；npm `@auraboot/aura-bpm-web`；migration/config 均为 bpm；构建合并平台和产品 catalog | 可复用构件和装配模式；现有脚本为独立 BPM 应用输出，不是已存在的通用公告+BPM 安装器 |

公开契约带 tenantId/actorUserId，但多数 BPM 操作最终从 MetaContext 取身份，而非逐项验证 request 字段。集成需保证可信请求与执行上下文一致，后台调度显式恢复绑定身份并重新授权，不能把传入用户 ID 当授权。

### 14.4 公共契约的具体细化

以下字段是拟定语义，不是已实现 DTO、操作 token 或 API 路径。优先在现有 WorkflowCapability 下形成版本化、可校验请求/结果；若破坏现有语义则新增操作版本，保留现有消费者行为，不用任意 Map 约定悄悄覆盖旧接口。

| 契约 | 已有 | 必须补齐的语义 | Owner/前置 |
| --- | --- | --- | --- |
| 就绪 | registry.available、catalog.list | 区分未安装/操作不支持/租户未配置/定义未发布/就绪；返回可用契约版本及准确流程定义版本；只有展示名称可进入 UI | 平台契约 + BPM 实现，先于审批表单 |
| 提交 | start + businessKey | tenant、trusted actor、businessType、recordPid、announcementVersion、submissionId、idempotencyKey、snapshotReference/hash、definition identity/version；相同键同内容返回同实例，不同内容冲突 | 公告提交聚合 + 平台契约 + BPM，先于实际审批 |
| 任务详情/动作 | task.approval-detail/approve/reject | 任务绑定提交与快照版本；允许动作来自服务端；拒绝空驳回原因、失效资格和旧版本动作；业务详情只读且附件受控 | BPM + OSS 业务详情 |
| 撤销 | process.withdraw/task.withdraw | 明确申请人撤销整个申请与节点撤回的区别；与终态并发有确定结果；返回终态/冲突而非盲取一条任务 | BPM 实现，不以 UI 状态判定 |
| 终态 | process.status + WorkflowEvent | 稳定 result identity、schema version、tenant、submission/version、process instance、definition version、APPROVED/REJECTED/WITHDRAWN/FAILED、occurredAt、顺序或状态栅栏；区分技术完成与业务批准 | 平台中立结果契约，BPM 生成，公告消费 |
| 可靠交付 | WorkflowEvent；BPM 事件日志/瞬时事件；平台可靠集成设施 | 提交后可靠记录、消费确认/重试、重启恢复、查询核对；消费按 result identity+submission 去重，冲突结果拒绝；不得把内存 Spring 发布当可靠 outbox | 先做共同结果语义，再选复用的可靠传输接线 |
| 待办 | clientItemId、read/acted、审批桥接 | 确认事项标识含 tenant/announcement/version/user；确认动作回调公告校验后才 acted；禁止 dismiss/批量通用动作完成知悉；pending count 独立于 unread count | OSS 待办与公告，审批生命周期继续归 BPM |
| 导航 | BPM providers、Inbox deepLink、通知 sourceType/sourceId | 业务类型、记录、版本、任务/提交身份到授权页面的明确解析；已撤回/旧版/权限失效可解释；不接受任意外部 URL | OSS 导航契约 + BPM contribution |
| 分发 | NotificationDeliveryAccessor + host Plan resolver | resolutionReference 可反查准确发布/催办批次和固定收件事实；先校验版本/可见性再入队；不追加 watchers | OSS host-owned 解析，保留现有安全边界 |

持久化概念模型（§15 已细化建议结构，物理 DDL/DTO 仍须在实现前核对命名）：公告主体、不可变版本、受众规则与预计快照、固定接收与阅读/知悉事实、审批提交映射、发布任务、催办批次及收件执行记录、结果消费记录、审计与导出快照引用。唯一约束至少覆盖同版本接收人、审批幂等键、结果身份、发布执行身份和催办批次收件身份。

业务键不应仅使用公告主记录：必须能够区分版本与再次提交；撤销后重提生成新 submissionId。公开 PID、内部 numeric user/member ID、BPM user PID 不能混用。事务持久化先建立提交映射再触发可能同步结束的流程，覆盖“流程在 start 返回前已经结束”的竞态；返回不确定时按原幂等身份核对，不启动第二实例。

### 14.5 组合安装：现有机制与待新增内容

现有可复用机制以 [application composition](../../distribution/application/README.md)、[manifest schema](../../distribution/application/application-manifest.schema.json)、[core-only 示例](../../distribution/application/examples/core-only/app.yaml) 和 [平台构件打包](../../scripts/application/stage-core-only-artifacts.mjs) 为依据。

P0 发现：Core 打包脚本显式收集 core-meta、platform-admin、org-management、core-ownership 等配置，但没有输出 core-announcement 配置 catalog entry；BPM 独立 app 的 importOrder 也没有公告。因此“仓内有插件目录”不等于“锁定制品可安装”。

| 项目 | 纯 OSS 公告组合 | 公告+BPM 组合 |
| --- | --- | --- |
| 建议 manifest 落点（待新增） | `distribution/application/examples/announcements/app.yaml` | `distribution/application/examples/announcements-with-bpm/app.yaml` |
| backend.mode | core-only | application |
| backend.plugins | 不含 BPM | `com.auraboot.bpm` 的准确版本 |
| migrations | core（公告新表/字段归 Core migration） | core → bpm，按实际依赖排序 |
| frontend.contributions | 不含 BPM 产品贡献 | `@auraboot/aura-bpm-web` 准确版本，client/SSR 同图 |
| config | 基础配置 + org-management + core-announcement 及真实依赖 | 前述 + bpm + 公告审批绑定配置 |
| 策略 | 显式启用允许授权免审；无免审权仍不能发布 | 配置默认已发布流程，可强制审批或允许授权免审 |

表中 `core-announcement` 是建议配置 catalog id，现有插件 ID 为 `com.auraboot.core-announcement`；config-only 插件不冒充后端 JAR。不得把上表中的配置依赖排序当作已验证 importOrder：先核对 fields/permission/menus 的资源依赖，再由显式图确定。公告审批默认流程/绑定由组合配置安装，公告基础配置不引用缺失 BPM 表。

待实现的装配步骤：

1. OSS staging 输出公告配置及所需依赖的不可变 catalog 项；发布准确平台 API/Runtime/npm/Core migration 组合，记录版本、源提交和 SHA-256。
2. Aura BPM 对该平台候选构件构建插件/配置/migration/npm；复用现有 artifact build，不从 sibling main 或 mavenLocal 偷读依赖。
3. 组合构建入口聚合两个已验证 catalog，按类型/id/version 去重并拒绝冲突，复制锁定字节；现有 BPM build 不能直接当通用聚合器。
4. 用真实 manifest validate/resolve/verify-lock/verify-artifacts 生成并核对 application.lock；构建前端图并拒绝重复路由、registry owner 或 React 单例冲突。
5. 显式执行 Core migration、BPM migration，加载插件，初始化/导入配置，再由管理员发布并绑定租户默认公告流程；不在启动或租户页面访问中写入初始化数据。
6. 就绪验证分开检查 backend provider operations、前端 contribution、租户流程和权限、公告分发开关/host resolver；任一缺失给出准确原因。
7. 在同一组合 runtime 内验证纯 OSS 与 BPM 组合对应旅程、升级后未结任务和缺失能力行为。构件校验不能替代业务验收。

示例清单不是安装成品，本轮没有生成锁文件、artifact catalog 或镜像，也未执行上述安装命令。最终准确构件版本不能从多个仓当前 `1.0.0` 字符串推导兼容，必须绑定摘要和实际组合结果。

### 14.6 更细的实施依赖顺序

| 顺序 | 对应阶段/Owner | 工作包 | 完成后才允许 |
| --- | --- | --- | --- |
| S1 | P1，OSS 公告 | 统一 published 状态、领域写入口、PID/版本/提交身份与迁移，堵住直接 API/动态 CRUD 状态绕过；权限拆分 | 新发布行为进入同一状态机 |
| S2 | P1，OSS 组织/权限/公告 | 管理范围适配、三类受众解析、租户成员全集、预计及固定名单、附件授权 | 免审发布与接收者查看 |
| S3 | P1，OSS 公告/调度/可靠分发 | 不可变版本、免审审计、定时任务、并发栅栏、超窗/取消/恢复、host 通知解析 | 纯 OSS 发布闭环；不可先用通用 watchers 路由上线 |
| S4 | P2 前置，OSS API + BPM | §14.4 的就绪、幂等 start、准确定义/快照、终态与可靠结果语义，候选契约构件 | BPM 对候选 API 实现与验证 |
| S5 | P2，BPM | 严格审批人策略、自审排除、任一/全部及驳回终止、异常重新分配、整申请撤销、送审详情/历史贡献 | 公告真正消费批准结果 |
| S6 | P2，OSS 公告 + 组合 | 结果幂等消费、同步结束/失败恢复；§14.5 staging、组合清单、聚合构建与准确安装 | 宣称审批组合可安装，之后方可真实链路验收 |
| S7 | P3，OSS UI/Inbox | 我的公告/详情/管理 DSL、铃铛、待办类型/动作及 pending count、版本路由、工作区入口、阅读/确认 | 知悉任务运营；每步同步已有权限契约 |
| S8 | P3，OSS 公告/导出 | 催办批次/限制/时区、统计快照、受控 CSV 与下载重授权、可观测性 | 运营与导出完整旅程 |
| S9 | P4，双方 | 纯 OSS 与锁定 BPM 组合实际安装、菜单驱动全链路、权限负向、并发/重启/超窗、文件解析与原图复核 | 按证据更新实际状态，不能仅按模块编译声明完成 |

S4 的设计在 S1 前冻结，避免数据身份返工；实施可在 S1–S3 稳定基础上接入。S7 的管理/查看基础 UI 随 S1–S3 同步交付，表内顺序强调跨模块依赖，不允许把 P1 验收拖成纯 API 演示。公共契约先出平台候选构件，BPM 再消费，组合验收固定双方准确身份。

### 14.7 P0 出口与剩余核实

本轮已明确模块责任、源码复用边界、缺口、公共契约语义、组合构件及实施顺序。最先阻断正确性的事项是 O02 状态/旁路、O05/O06 范围、B02/B03 提交与批准结果、B04 自审回退，以及 O09 待办计数；这些不能延后到视觉打磨阶段。

尚未由源码审阅证明：锁定构件组合实际可启动、插件跨上下文事件可靠到达、动态权限模型在接收者场景的表现、MI 实际通过/驳回行为、调度多实例及重启恢复、导出快照下载的重新授权语义、附件实际配置限额、免打扰日历接线与容量。它们是实施/验收事项，不是待用户批准的新增需求；后续证据更新同一文档，不将 source-audited 改写为 runtime-verified。


## 15. 关键缺口决策与 S1–S3 详细设计

本节为 **design-only**，细化已确认范围，不新增业务代码。下面列出的新字段、操作 token、权限码和 HTTP 路径均为拟实施契约，不是当前可调用 API。与前文概述相比，本节对状态、版本、重试、取消及接口行为作出更精确裁决。既有 API 在文中单独标注。

### 15.1 P0 关键缺口处理决策

| 缺口 | 固定方案 | 禁止的替代方案 | 实施位置 |
| --- | --- | --- | --- |
| O02 状态与写入口 | 新写统一 published；全部写操作经过公告命令 handler/领域服务，HTTP、DSL、调度、结果消费者共用状态守卫 | REST 直接写 status、仅隐藏按钮、动态 CRUD 或批量导入绕过审批 | S1 |
| O05 受众授权 | 管理权限与接收权限分离；发布范围由当前授权集合判定，接收详情由固定收件事实判定 | 用公告 created_by 的 DataScope 代替收件范围；角色选择扩大授权 | S2 |
| O06 watchers 扩散 | 使用公告 host-owned delivery plan，从固定收件/批次事实解析，禁止追加 watchers | 复用 DefaultRecipientResolver 的并集合并行为 | S3 |
| B02 重复发起 | 不可变版本 + 每次申请 submissionId + 双端幂等键/内容摘要唯一栅栏 | 先 has-running 再 start；网络超时换新键重发 | 公共契约先冻结，S4/S5 实现 |
| B03 完成等于批准 | provider 持久化唯一明确业务终态，事务 outbox 交付，公告按版本与申请消费 | completed、process_ended、某节点通过直接触发发布 | S4–S6 |
| B04 发起人回退 | 公告绑定流程启用 strict 策略，排除申请人，无人进入待处理异常并阻止前进 | 默认分配发起人、空名单跳过、管理员伪造同意 | S5 |
| O09 角标消失/绕过确认 | pending count 独立接口；确认只走公告业务动作；禁止对确认事项通用 dismiss/acted | 复用 unread-count 语义、全部已读/mark_done 完成知悉 | S7；S1 预留确认事实 |
| O07/O11 可靠发布 | 数据库持久任务 + 受控转换 + 成功事务内固定名单和 outbox；渠道异步 | 以内存计时器状态或消息发送成功作为发布状态 | S3 |
| O12 导出重授权 | 导出固定版本/数据快照，下载检查当前有权查看该快照中的全部行；数据自然变化不作撤权 | 将已读/确认变化当内容摘要不一致并拒绝合法历史下载 | S8；S1 定义数据身份 |

### 15.2 S1：聚合、身份与逻辑数据模型

复用 `ab_announcement` 为主体，不建立第二个公告主模型。以下为逻辑实体名，不预定物理新表名。所有事实有 tenant_id，持久关联使用同租户约束；HTTP 只使用公共 PID，内部 Long ID 不作为接收人/文件公共身份。时间保存 UTC Instant，日期规则另存租户时区及解释版本。

| 逻辑实体 | 关键字段（拟新增部分） | 约束与生命周期 |
| --- | --- | --- |
| 公告主体 | pid、ownerUserPid、editorRevision、currentVersionPid、activeVersionPid、lifecycleRevision、pinned、deletedAt；保留既有标题/状态等兼容投影 | `(tenant,pid)` 唯一；一个可编辑草稿、最多一个 activeVersion；投影仅服务端写，不成为第二份内容真源 |
| 可编辑草稿 | announcementPid、revision、title、contentDocument、attachmentRefs、importance、audienceRule、mode、schedule、expiry、ackPolicy、reminderPolicy | 草稿可不完整，保存执行编辑权限/乐观锁；提交时完整校验；不能保存任意发布或审批状态 |
| 不可变公告版本 | versionPid、announcementPid、versionNo、sourceDraftRevision、snapshotSchemaVersion、snapshotHash、title/content/attachments、audienceRule、channelPolicy、scheduleSpec、expirySpec、ackPolicy、reminderPolicy、createdBy/At | `(tenant,announcement,versionNo)` 唯一；冻结内容永不 UPDATE；生命周期/执行状态放独立控制记录，不破坏快照 |
| 版本控制 | versionPid、approvalState、publicationState、publicationRevision、currentSubmissionId、releaseAttemptId、publishedAt、expiresAt、ackDueAt、authorizationBasis | 一版本一条；状态服务端 CAS；业务正文与状态控制分开；EXEMPT/APPROVED/LEGACY 等依据不能由客户端自填 |
| 附件关系 | versionPid/draftPid、filePid、fileMetadataSnapshot、order、finalizedAt | 冻结文件引用及内容身份；禁止在同 filePid 下替换已送审字节；服务端检查完成状态和下载资格 |
| 审批申请映射 | submissionId、versionPid、requestHash、idempotencyKey、applicantUserPid、definitionRef/version/hash、instancePid、state、terminalResultId、lastSequence | `(tenant,idempotencyKey)` 唯一，`(tenant,provider,instancePid)` 唯一；一版本最多一个未结束申请；版本不变撤销重提也生成新申请 |
| 发布任务 | releaseAttemptId、versionPid、submissionId或exemptionOperationId、authorizationActor、scheduledAt、latestAllowedAt、state、expectedPublicationRevision、leaseToken/leaseUntil、attemptCount、nextAttemptAt、lastError | 同版本最多一个非终态任务；每次取消后重新安排使用新 attempt；所有 worker 更新校验 lease+版本栅栏 |
| 接收快照头 | snapshotPid、versionPid、releaseAttemptId、resolvedAt、identity/scopeRevision、ruleHash、recipientCount、snapshotDigest、origin | 成功发布绑定一个封存快照；origin 区分实际发布与存量迁移；预计快照不充当接收事实 |
| 接收与反馈事实 | snapshotPid、versionPid、userPid、memberPidAtPublish、display/orgSnapshot、matchedRuleRefs、firstReadAt、acknowledgedAt、ackVersionHash | `(tenant,version,userPid)` 唯一；阅读/确认首次时间不可覆盖；资格失效是当前判定，不抹去原始接收事实 |
| 分发意图/收件执行 | batchId、versionPid、recipientUserPid、channel、resolutionReference、payloadHash、deliveryKey、state | 发布提醒同版本/用户/渠道唯一；催办按批次隔离；复用可靠 outbox/receipt，避免自建第二套消息队列 |
| 操作幂等与审计 | operationId、actor、action、target/version、requestHash、acceptedResult、前后revision、time、result | `(tenant,actor,action,operationId)` 唯一；同键同内容返回原结果，同键异内容冲突；原操作重放不产生新副作用 |

版本哈希覆盖规范化内容、附件内容身份、受众规则、模式及执行/确认政策；排除展示名实时变化、已读统计等运行态。预计人数不属于批准人数承诺，但预计名单/时间单独保存作审批依据。重要程度为 normal/important；当前 high/urgent 的存量映射均为 important，不生成历史知悉要求。

审批表和 provider 交付实体归 BPM；OSS 审批映射只存公共引用和消费结果，不复制引擎任务表。知悉、催办详细执行记录可在 S7/S8 增量扩展，但 version/user/批次身份现在固定，避免后期改键。

#### 15.2.1 写入边界与防旁路

已有 `CommandHandler.requiresDslPersistence` 可让 handler 拥有完整聚合写入；公告相关命令采用该方式，由统一领域服务完成事务，禁止 FIELD_MAP 先改状态再补副作用。

现有 `ModelMutationGuard`/`FieldWriterGuard` 提供 commandOnlyCreate、commandOnlyDelete 及 allowedWriterCommands 等写守卫。新公告受保护字段声明准确命令白名单，覆盖内容、状态、版本引用和系统时间；既有裸 CRUD、批量更新、import、increment、自动化写入均执行同等守卫。仅有一般 CommandPermit 仍不够，未列入的命令不能改公告字段。

草稿新增通过 commandOnlyCreate + handler；删除按准确命令及“无审批/发布历史”域规则；如现有命令 guard 无法保护某一别名/嵌套写入口，在该边界补通用守卫，不以管理员权限绕过。通用动态查询也不能作为接收者正文/附件的旁路，接收者页面使用专门授权查询；管理动态模型仅向管理权限开放并按行授权。

已存在 `/api/announcements` GET/POST/PUT/DELETE：保留受控适配层。GET 返回符合新接收规则的列表投影；POST 仅创建草稿；PUT 仅允许可编辑草稿字段；DELETE 调删除命令。请求中的 status/publishedBy/publishedAt 等字段不再可写，明确返回验证错误；原状态发布调用迁移到显式命令，禁止悄悄映射成免审。id 参数按明确兼容解析到同租户记录，不能把不明确的数字串猜成另一个 PID。

所有入口锁定主体/版本控制行，校验 expectedRevision，数据库同事务写审计/幂等结果/可靠意图。内部 worker 只能执行预先持久化的任务，不开放客户端可传 system=true 或任意 actor/tenant 的后门。

#### 15.2.2 存量迁移

使用新增 Flyway/显式部署步骤，不改历史 migration，不在启动时修复。规范化 active/published→published、archived→withdrawn、draft→draft；未知状态阻止迁移并列出数据，不猜测。

已发布存量生成不可变 legacy 版本，授权来源标记 LEGACY_EXISTING_CONTENT，保留原发布时间但不伪造 BPM 批准、免审原因、已读或知悉记录。部署时按原租户全员可见语义建立明确的迁移访问人群，标记 MIGRATION_COHORT、记录升级时间；它仅服务存量访问，不声称是当年实际收件名单。历史统计显示“迁移前发送/阅读未知”，不将迁移人数计为历史送达。

迁移不发通知、不创建知悉任务/催办；新成员不自动获得迁移 cohort 权限。legacy 依据只允许原内容继续展示，不能用于再次发布或新版本；任何修订/重发走新的审批或有权免审。旧 manage 权限不自动升级成全员发布/免审/导出，管理员在显式部署配置中完成授权。

### 15.3 状态机与服务端不变量

存储使用小写稳定码，用户界面 i18n。approvalState 为 not_submitted/pending/approved/rejected/withdrawn/failed；publicationState 为 unpublished/scheduled/missed/published/withdrawn/expired。failed 是技术失败，不等于业务驳回。网络不确定和取消请求以申请执行态表达，不伪造审批终态。

| 操作 | 前置 | 状态与事务结果 |
| --- | --- | --- |
| 保存草稿 | 可编辑，revision 匹配 | revision+1，不产生冻结版本、任务或通知 |
| 提交审批 | 当前无未结申请、无生效旧版；完整校验且能力就绪 | 冻结版本（相同内容可复用未发布版本），建立 submission=pending_dispatch，approval=pending；持久化发起意图 |
| 审批明确批准 | 准确 currentSubmission、version/hash、definition 匹配 | approval=approved；未错过时间则建立唯一发布任务；批准本身不直接发送消息 |
| 审批驳回/撤销 | provider 确认准确申请终态 | approval=rejected/withdrawn；publication 保持 unpublished，关闭旧审批任务，允许建立编辑草稿 |
| 免审提交 | 租户允许+发布权限+独立免审权限+原因，其他校验同审批 | 冻结版本、记录免审依据；approval=not_submitted，创建发布任务，不伪造 approved |
| 接受立即发布 | 已批准或免审依据有效 | 返回 accepted 和任务标识；任务完成前仍 unpublished，UI 显示发布处理中 |
| 接受定时发布 | 时间在未来、依据有效 | publication=scheduled；任务保存准确 UTC 时刻及时区 |
| 到预约时刻仍未批准 | approval=pending | publication=missed；之后批准仍 missed，不应用技术延迟窗口 |
| 执行发布 | 依据/任务/版本/权限有效且时间未超窗 | 同一事务封存名单、publication=published、activeVersionPid、publishedAt、有效期/知悉期限及 outbox |
| 取消预约 | scheduled 或尚未提交发布成功的任务 | 取消任务、publication=unpublished、revision+1；若审批尚未结束，仅取消发布安排，原快照不能复用为新安排 |
| 撤回 | 已发布且有撤回权 | publication=withdrawn，清 activeVersionPid、revision+1；停止待执行分发/催办，可靠关闭知悉待办 |
| 到期 | now>=expiresAt | effective 状态立即视为 expired；后台持久化 expired 并关闭任务，不依赖扫描及时性保护访问 |
| 再发布 | 旧版 withdrawn/expired，无有效旧任务 | 新版本和新依据；不恢复旧收件/阅读/知悉状态 |

取消审批中公告的预约不等于撤销审批：原申请仍由 BPM 管理，但其 releaseAttemptId 被废止，随后批准仅留历史，不能重新激活任务；编辑/更换发布时间需先撤销未结申请，等待确认后重新提交。与前文“修改需重新审批”保持一致。

批准、取消、撤回和 worker 发布竞争同一 publicationRevision：只有一个转换成功。取消请求若晚于发布成功返回 STATE_CONFLICT 并展示已发布，用户可另执行撤回。渠道发送已经在外部开始时无法保证收回；撤回可阻止后续发送，不承诺撤销已发生外部副作用。

不变量：每个公告至多一个生效版本；没有 sealed 接收快照就不能 published；没有当前有效批准或免审依据就不能创建有效发布任务；旧 submission 只能更新其历史，不更新 current version；终态冲突需留痕并阻断，不覆盖；草稿复制与重新送审不改变历史。

### 15.4 S2：权限算法、受众与读取接口

权限判定顺序固定为：认证和租户 → 当前成员有效性 → 动作权限 → 记录管理权/任务资格 → 当前发布范围 → 状态和版本 → 字段/附件/输入校验 → 事务写入。读取他人不可见对象统一返回不存在或无访问权限，不泄漏标题及人数；有管理权的用户才获得具体业务阻断原因。

拟定动作权限码（新增，不等于现有声明）：`announcement.announcement.{read,edit,submit,publish,publish_all,exempt,withdraw,stats,remind,export,delete,policy_manage}`。edit/submit/publish 可分别授予；submit 还需 publish，因为批准后会自动发布。publish_all 只扩大受众范围，不授予其他动作；exempt 仍需 publish，export 仍需 stats 和数据范围，流程维护仍走 BPM 产品权限。

既有 read/manage 迁移到明确角色配置，不将 manage 自动解释为所有新动作。记录 owner 不是无限授权，管理员也不能跨租户。改 owner 是单独管理操作，本期不提供普通编辑字段。

#### 15.4.1 发布范围集合

- 发布者候选集合 M 由成员+组织+资源动作授权得出。ALL 只有持有 publish_all 才能形成租户全集；SELF 为本人有效成员，DEPT/DEPT_AND_SUB 为允许部门成员，TEAM 按允许成员组；NONE/未配置拒绝，不回退全员。
- 租户全集来自有效 TenantMember 与用户目录，无员工档案者不被全员/人员/角色解析遗漏；部门依据有效员工-成员关联，未关联者不算部门成员。
- 人员选择必须属于 M；部门选择须整个部门子树处于允许部门范围。部分覆盖的部门显示不可选或要求选择有权子部门，不能偷偷截断。
- 角色选择明确标注“本管理范围内的该角色成员”，解析为 RoleMembers ∩ M；这是冻结规则的一部分，不是执行时隐式裁剪。界面预览和审批快照展示这一限定。
- 全员按钮仅对 publish_all 可用；后台伪造 audience=all 同样拒绝。新增角色或部门关系不得扩大普通发布者权限。

预计解析返回去重名单、matchedRuleRefs、预计时间及授权解释；发布时按相同规则重算。组织自然变化可改变人数，授权范围本身变化则阻止旧批准发布，要求重新提交。批准快照保存授权政策修订身份；执行时若无可用修订计数，必须锁定/比较其依赖的实际授权记录摘要，不能假称已有全局 auth epoch。

#### 15.4.2 读取与反馈

接收者访问条件：当前有效租户成员 + sealed 名单包含本人 + 正确版本仍生效 + 未过期；读权限不靠管理范围。正文、附件、摘要和搜索按相同条件过滤。审批人经真实任务资格获得送审快照只读权，不能用接收者列表权限替代；历史审批参与人按流程审计权限查看历史快照。

管理预览与接收者查看使用不同 viewMode；管理预览不写阅读。read 操作只在完整正文成功展示后由客户端显式提交；服务器校验相同版本和接收资格，重复返回首次时间。不能技术上证明人已认真阅读，因此知悉始终是独立确认动作。

### 15.5 S1–S3 接口与命令目录

优先通过现有 `/api/meta/commands/execute/{code}` 驱动写入与 DSL UI；下表代码是拟新增或拟改语义，不表示当前已导入。读接口建议集中在 `/api/announcements`，避免另造第二套内容服务。返回沿用平台 ApiResponse/分页格式，以下仅定义业务字段。

| 动作 | 拟命令/HTTP | 关键输入 | 返回/权限 |
| --- | --- | --- | --- |
| 新建/保存草稿 | 复用 create_announcement/update_announcement 命令码，改为 handler；既有 POST/PUT 适配到命令 | operationId、expectedEditorRevision（保存）、白名单草稿字段 | announcementPid/editorRevision；edit |
| 预览受众 | 拟 `POST /api/announcements/audience-preview`，只读语义不落业务事实 | 当前草稿/规则、分页/关键词 | estimatedCount、候选明细、scope解释、resolvedAt；publish+记录权；不信任客户端 count |
| 管理列表/详情 | 拟 `GET /api/announcements/manage`、`/{pid}/manage` | 过滤、分页、version | 当前revision、状态、allowedActions、阻断原因；记录管理权 |
| 接收列表 | 既有 GET `/api/announcements` 扩展分页/未读/待确认过滤 | keyword、filter、page；既有 limit 受限兼容 | 接收者投影，不能混入草稿；read |
| 接收详情 | 拟 `GET /api/announcements/{pid}/versions/{versionPid}` | viewMode 默认 recipient | 正文/附件元数据、versionHash、反馈状态、有效期；服务端决定可用模式 |
| 提交审批 | 拟 `announcement:submit_approval` | operationId、expectedEditorRevision、expectedLifecycleRevision | submissionId/versionPid/operationState；submit+publish |
| 免审立即/预约 | 改造 `announcement:publish` 为显式免审路径 | operationId、expectedEditorRevision/lifecycleRevision、exemptionReason、schedule | releaseAttemptId/versionPid/accepted state；publish+exempt |
| 取消预约 | 拟 `announcement:cancel_schedule` | operationId、versionPid、expectedPublicationRevision、reason | 最终状态/revision；publish+记录权 |
| 撤回 | 复用 `announcement:archive`，UI名称仍为撤回 | operationId、versionPid、expectedPublicationRevision、reason | withdrawn/revision；withdraw |
| 再发布 | 复用 republish 仅作为建立修订草稿入口，不能直接变 published | operationId、旧version、expectedLifecycleRevision | 新草稿revision，后续审批/免审另走提交；edit |
| 删除草稿 | 复用 delete_announcement | operationId、expectedLifecycleRevision | 删除结果；delete+无历史 |
| 操作核对 | `GET /api/announcements/operations/{operationId}?commandCode=announcement:...` | 服务端当前actor及原成员上下文，复核对应动作权限 | 本人已提交事务的accepted/completed及最小引用；未查到为unknown，不伪称failed。管理员通过记录执行视图核对，不查询他人操作身份 |
| 阅读/确认 | 拟 `announcement:mark_read` / `announcement:acknowledge` | operationId、versionPid、versionHash | firstReadAt/acknowledgedAt/late；当前本人接收权 |
| 附件下载 | 拟版本附件受控资源路径 | pid/versionPid/filePid | 流式文件；每次重新授权，不返回裸存储 key |
| 预约恢复 | 拟 `announcement:retry_publication`，恢复同任务，不更改快照 | operationId、releaseAttemptId、expectedRevision | accepted/blocked；publish+记录权，超窗不得重试补发 |

mark_read/ack 数据身份和守卫在 S1 固定，页面操作在 S7 完成；submit_approval 在 S1–S3 安装缺少新 BPM 契约时必须显式不可用，不能留下假成功 stub。非本人 userPid、tenantId、approvalState、publishedAt、recipientCount 等由服务端决定，客户端传入拒绝而非默默采信。

写请求要求 operationId；保存和状态转换另要求相应 expectedRevision，缺失为 PRECONDITION_REQUIRED。幂等重放首先校验当前认证/访问权；同键相同摘要返回原 accepted 引用及当前执行状态，不再次转换；同键不同摘要为 IDEMPOTENCY_CONFLICT。original accepted 不是保证发布已完成。分页详情不能回传敏感名单给无 stats 权限者。

错误使用稳定业务码与本地化原因，映射到平台既有错误包装，拟定语义为：INVALID_INPUT、PRECONDITION_REQUIRED、REVISION_CONFLICT、STATE_CONFLICT、IDEMPOTENCY_CONFLICT、SCOPE_DENIED、AUDIENCE_EMPTY、WORKFLOW_UNAVAILABLE、WORKFLOW_NOT_READY、SCHEDULE_MISSED、VERSION_INACTIVE、ATTACHMENT_NOT_FINALIZED。认证/权限/不存在沿用平台标准 HTTP 401/403/404，冲突建议 409，缺少前置 428，输入问题 400/422；不能仅 HTTP 200 且空 data 掩盖失败。最终传输映射实施时与平台统一，不独创另一种 ApiResponse。

### 15.6 S3：发布事务、调度与分发

提交和执行分开：提交事务冻结版本、校验权限与依据、建立发布任务及操作结果，页面获得 accepted；后台可立即处理任务，但没有成功事务之前 UI 不显示已发布。

执行步骤固定：

1. worker 从持久任务原子领取 lease；只挑选到期/可重试任务，增加 fencing token。失联重领仍校验同一 releaseAttempt 与版本修订。
2. 恢复受控租户上下文，核对原授权主体当前有效及动作范围、租户策略、冻结批准/免审依据。无效明确阻断，不临时借管理员身份。
3. 默认步骤 3–4 属于同一个数据库事务。在一致数据快照内重算受众、去重、写入版本接收事实；用集合/批量 SQL，通知不在此事务逐人远程发送。权限/组织依赖修订发生冲突时回滚重算，不能混合多次分页得到不同时间的名单。
4. 最终事务锁主体、版本控制和任务，复核未取消、时间窗、依据及依赖修订；封存唯一名单及count/hash，设置 publishedAt、expiresAt、ackDueAt，记录分发批次与可靠 outbox，再提交。
5. outbox 按批次扩展接收意图，host resolver 校验 sealed 名单、版本及渠道；通知分发与知悉待办投影分开执行并独立幂等，知悉待办不依赖某条通知发送成功。封存收件名单和批次 outbox 必须原子提交；逐收件渠道意图可在提交后从该封存快照可靠扩展，扩展游标和任务幂等持久化，不能要求所有远程发送在发布事务内完成。

默认采用数据库一致快照+集合落地完成发布；若规模要求分批准备，准备代次对用户不可见，最终封存必须验证组织/权限依赖未变化，否则废弃该代次重算。最终成功事务的 sealed 快照是“实际发布时”名单的唯一解释，不能把审批预计名单沿用成实际名单。

任务执行态为 queued/running/succeeded/retryable_failed/blocked/canceled/missed；任务失败不增加新的 publicationState。技术暂态可在原任务带退避重试，固定最大重试与可观测错误，窗口以 latestAllowedAt 为硬上限；权限/零受众/文件/批准异常为 blocked，不自动修数据或换范围。修复且内容、时间、依据均未改变时可显式恢复同任务；改快照必须新版本、新提交。

尚在审批、因而没有发布任务的预约版本，由持久化plannedPublishAt投影及索引扫描到时裁决，不能因尚无任务而永远漏掉missed。该扫描只改变发布就绪状态，不生成批准或发送。

预约 `latestAllowedAt=scheduledAt+30分钟`；立即任务也以 acceptedAt+30分钟作为默认可执行窗口，避免长中断后突然发布，租户配置在提交时固定并展示。后台批准晚于预约时间始终 missed。若预约时本地尚未核验批准、之后才收到延迟结果，也不自动把 missed 改回 scheduled；页面区分“实际晚批准”与“批准结果同步延迟”，由发布者重新安排。30 分钟窗口只用于已具备本地有效批准/免审依据并建立任务后的执行延迟，不能用于延迟批准结果的自动补发。到期判断用服务端时间；过期优先于重试。无 expiresAt 时无期限，知悉期限以实际 publishedAt+指定天数计算；若超过已批准 expiresAt 则阻止发布，不能静默缩短确认期。

分发事件字段为 tenant、batchId、versionPid、recipientSnapshotPid、publicationRevision、configurationVersion、occurredAt；resolutionReference 是服务端生成的不可变引用。事件传输复用 `ReliableIntegrationAccessor.enqueueIdempotent` 与 `IntegrationEventEnvelope`，要求业务事务存在；参考接口 [可靠入队](../../platform/platform-plugin-api/src/main/java/com/auraboot/framework/plugin/extension/integration/ReliableIntegrationAccessor.java) 与 [事务消费者](../../platform/platform-plugin-api/src/main/java/com/auraboot/framework/plugin/extension/integration/ReliableEventConsumerExtension.java)。这些基础设施存在，但公告接线仍待实现。

数据已提交但 SSE 丢失时，刷新从服务端事实重建，不重新发布。版本撤回先写失效状态，分发/待办消费者及详情读取立即复核；未消费意图标 canceled，历史已发送日志保留。任意重放不能重置阅读或重新开启已完成知悉任务。

### 15.7 BPM 公共契约：`approval.*.v1`

沿用单一 `WorkflowCapability` provider；不复用旧 start 的宽松语义。中立操作族为 `approval.readiness.v1`、`approval.start.v1`、`approval.status.v1`、`approval.withdraw.v1`、`approval.viewer.v1`，旧 token 留给既有消费者。五操作族已通过正式组合构件和真实审批旅程验证，证据见 §18.26。DTO/schema 归 platform-plugin-api，BPM 是实现者，任何类型不得含公告实体类或 SmartEngine 类型。

#### 15.7.1 发起及查询

| 契约 | 请求（语义字段） | 结果 |
| --- | --- | --- |
| readiness | 可信 tenant/actor；payload.definitionPid | ready、definition(pid/version/hash)、definitionName、strictPolicy；不就绪返回 reasonCode，不分配实例 |
| start | 可信 tenant/actor；payload.submissionId；完整绑定由业务来源读取 | found、submissionId、state、stateRevision、instancePid（启动前可空）、reasonCode、可选 terminalResult；starting/blocked 均不代表通过 |
| status | 可信 tenant/actor；payload.submissionId | 有权查看业务绑定后返回 found=false 或准确状态与不可变终态；不存在不能当 approved，也不自动创建新申请 |
| withdraw | 可信 tenant/actor；payload.submissionId、reason | 准确 withdrawn 或已经发生的终态；撤销整个申请。OSS 命令层另外承担 operationId/aggregate revision，不把它们误写成 provider 必需字段 |
| viewer | 可信当前 tenant/actor；payload.submissionId | 当前申请人、固定审批参与人或明确监控管理权限的资格校验；允许时返回 allowed 与准确 snapshotHash，否则拒绝；不返回正文或任意人员目录 |

start 仅接受由业务域提交服务持久化并授权的申请；`ApprovalSubmissionSource/Accessor` 返回 `SubmittedApproval`，包含幂等键、businessRef、snapshotRef/hash/schema、准确 definition、strictPolicy、申请人和提交时间。provider 不接受调用者替换这些绑定字段，也不向 BPM 开放公告 mapper。`canStart` 校验首次接收资格，`canExecute` 在延迟启动或恢复执行前复核原成员身份和业务权限，`canWithdraw` 单独校验撤销资格。后台身份必须从宿主目录恢复当前数值成员上下文；不能因用户 PID 相同就继承已删除成员的旧资格。

start 两端按 `(tenant,provider,submissionId)` 建唯一映射和规范化请求摘要；同 submission 相同快照/定义等输入重放返回同实例，任一绑定字段不同报冲突。provider 拒绝与执行上下文不一致的 tenant/actor、未发布定义、内容摘要不匹配和严格策略不支持。流程定义固定版本/hash，不在 start 时重新跟随 key 的 latest。

snapshotRef 是平台服务端可授权解析的不可变引用，不是任意 URL；业务处理器根据 businessType 解析本域快照和附件，BPM 仅持公共引用。任务详情使用真实 task qualification 请求授权视图；不能用可伪造的业务引用直接读取受限内容。长历史访问按授权审计入口，结果事件不携带完整正文或个人名单。

`BusinessPreview` 由业务所有者返回冻结的只读 DSL schema、values、fieldMetadata 和 snapshotHash。BPM 先验证当前参与资格，OSS 再校验固定 provider、哈希和成员身份；两端都不切换为管理员身份读取正文。任务抽屉复用 L1 `useDslForm/DslFormRenderer`，不读取业务表的当前记录或实时字段元数据。附件使用同一送审申请的受控下载入口，每次下载重新校验资格。普通接收正文的阅读/知悉事实不会因审批预览而产生。

#### 15.7.2 严格审批人和终态

strictPolicy 至少固定 `excludeApplicant=true`、`emptyAssignee=BLOCK`、`resolutionFailure=BLOCK`、节点 ANY/ALL、`rejectTerminates=true`。节点进入解析当前有效人员并封存名单；ANY 任一同意结束该节点，ALL 全部同意才结束；任一有效驳回立即结束整申请并关闭其他待办。排除后无人不允许 MI 空集合跳过，也不进入 dispatcher 的 starter fallback。

成员失效进入 ASSIGNEE_BLOCKED 执行异常，不形成业务 REJECTED。流程管理员替换剩余人员须校验租户/自审、理由、审计，已发生同意保留；不能添加虚假同意。角色成员后续变化不自动改封存名单，只使失效人不能继续操作。定义冻结后仍需每次动作检查当前资格。

管理恢复独立使用 `bpm.monitor.update`，当前成员必须有效；操作要求 operationId、state revision 和非空原因，并写入不可变操作审计。`resolve_node` 仅对未封存节点指定有效人员及其成员身份，仍使用原节点的 ANY/ALL；`replace_assignee` 只替换尚未提交意见的固定人员，已排队或已执行的意见均不可覆盖；`resume` 重新排队原阻断任务，不转移执行主体。ANY 保留同一共享任务与其他候选人，ALL 保留已完成意见和剩余人数。原成员已删除时，旧排队意见不能由新成员身份执行；需要撤销并按正常业务入口重新提交，而不是补写同意。

terminal state 只能是 APPROVED/REJECTED/WITHDRAWN/FAILED，RUNNING/BLOCKED 不可发布。APPROVED 仅在流程规定的完整批准条件达成后生成；任意普通 end event/completed 不可自动解释为批准。技术暂态停在执行异常，只有不可恢复且明确结束时才 FAILED；拒绝意见必须有值。

#### 15.7.3 可靠结果与撤销竞态

事件类型 `workflow.approval.terminal.v1`，使用既有 IntegrationEventEnvelope schema `1.0`；payload schema 独立版本化。字段包括 submissionId、businessRef、snapshotHash、instancePid、definitionRef/version/hash、terminalState、resultId、stateRevision、decidedAt、decisionReasonCode。tenant 及 source 来自 provider；orderingKey 绑定 tenant+submission，eventId=resultId，sequence=stateRevision，correlationId=submissionId。

BPM 在同一业务事务内保存不可变 terminalResult 并 enqueueIdempotent；因插件事务管理器或数据源不同无法证明同一事务时，安装能力必须报不支持，不能改为内存通知。公告消费者以原可靠框架 receipt 与业务唯一键双重防重，同事务校验绑定并修改申请/发布任务；不得另启事务吞异常。原事件重放必须保留 eventId、occurredAt、sequence 与 payload，不重新生成 UUID。

同实例只能有一个业务终态。重复完全相同结果返回已有消费；不同终态/绑定/hash冲突进入显式错误与运维处理，不覆盖。乱序 RUNNING 事件不能倒退终态。公告只允许准确 currentSubmission 的 APPROVED 创建有效发布任务；旧申请批准只能留历史，取消过的 releaseAttempt 不会复活。

公告提交事务先持久化 submission=pending_dispatch 与发起 outbox，再由受控 worker 调 start，避免跨上下文调用破坏本地提交原子性。结果可能早于 start 返回：消费者可按 submission 绑定 instance 并确认相同哈希，之后 start 响应不得覆盖终态。超时使用同键 status/start 核对；查询明确未创建时仍以同 submission 重试。

撤销请求先设 cancel_requested 并关闭本地发布许可，再按同 submission 排队调用 withdraw；如果 start 尚未派发，可原子取消未领取请求。已派发但无响应时不能本地宣称已撤销，需核对准确实例；若 provider 已批准，保留批准历史但本地取消栅栏仍禁止发布，页面说明“审批已结束，发布安排已取消”。若发布先已提交，则返回冲突，另走撤回。等撤销/终态明确后才允许新申请，避免两个运行实例。

结果同步异常由原 outbox 重试及授权 reconcile 命令核对；人工只能触发重放/核对，不手填 approved。缺 provider/不支持新契约/结果未知始终阻止审批发布。组合 readiness 必须检查新操作族、严格策略、可靠结果事务能力及前端贡献，不能只用旧 registry.available(start)。

### 15.8 S1–S3 交付切片与不扩大范围的出口

| 切片 | 应产生的实现（未来） | 退出条件（尚未执行） |
| --- | --- | --- |
| S1-A 数据与迁移 | 主体/草稿/版本/控制、幂等/审计、legacy 明确来源 | 旧状态迁移明确；无伪造审批/阅读；唯一约束与revision生效 |
| S1-B 单写入口 | handler owns mutation、准确命令字段守卫、REST适配、通用读写旁路封堵 | 从真实菜单保存/发布；直接 API/动态CRUD/导入/其他命令不能改受保护状态 |
| S2-A 受众与授权 | 管理范围解析、全成员/部门/角色、预计与实际名单、受控附件 | 无员工成员不遗漏；越权拒绝、角色限定可解释、跨租户及附件泄漏被阻断 |
| S2-B 查询与身份 | 接收者/管理/审批快照视图、PID映射、准确版本导航 | 管理预览不记阅读，非接收人看不到正文或摘要 |
| S3-A 发布执行 | 免审依据、立即/定时、租约栅栏、封存名单及outbox、取消/超窗/恢复 | 单任务一次发布；权限收回、取消竞态、重启和晚执行符合状态机 |
| S3-B 分发接线 | host resolver、批次意图、渠道结果、幂等、撤回停止 | watcher 不能扩大受众；重复消费不重复发送/建任务；失败可追踪 |
| 公共契约前置 | 本节语义形成公共 DTO/校验候选，BPM实现排入S4–S6 | 纯OSS缺新能力明确禁用审批，不存在伪批准替身 |

与 §12 使用同一验收分母；本节仅细化未来断言，不新增并行验收报告。Inbox 待处理计数和知悉动作、催办/导出仍按 S7/S8 交付，数据与契约前置固定，不用将其“预留”描述为本期整体完成。


## 16. 剩余设计闭环、实施就绪与自查

本节将 §1–§15 的剩余语义闭合为可实施决定。范围仍为本期公告及 BPM 嵌入；不进入业务代码、实际安装或运行验证。所有拟接口延续 §15 的版本/权限/幂等约束，不产生第二套业务入口。

### 16.1 页面、导航与操作反馈闭环

| 页面/入口 | 默认展示与主要动作 | 状态及边界 |
| --- | --- | --- |
| 我的公告 | 当前有效、本人接收；全部/未读/待确认，关键词和重要程度；置顶、publishedAt、PID稳定排序 | 不显示草稿或其他人受众；“待确认”包含截止后尚未确认且公告仍有效者；列表不记阅读 |
| 公告详情 | 正文、附件、发布人与日期、重要/知悉标签、确认按钮 | 显示知悉截止与公告到期两个时间；确认完成和逾期完成分别说明；不强制弹窗、不以滚动到底冒充确认 |
| 公告管理 | 有管理权的草稿与各状态版本；搜索/状态/模式；当前问题与下一步操作 | 服务端 allowedActions 含 enabled/reason；无动作权限隐藏，状态不允许时禁用并解释；不能只靠前端限制 |
| 编辑页 | 四组字段、受众清单和去重人数、流程预览；保存/预览/提交 | incomplete 草稿允许保存，必填/附件完成仅在提交校验；失权禁止保存但提供复制未保存正文的方式，不能写回越权记录 |
| 管理详情 | 内容、审批、发布、接收/知悉、渠道、操作历史 | 统计/明细/导出逐项授权；未发布人数是预计值并标时间，不显示为已送达 |
| 铃铛 | 全部/公告，默认最近5条，查看全部通知/公告 | 条目点击成功打开对应详情后可将该条通知已读；加载下拉不自动全部已读；通知已读与公告阅读各自调用对应动作 |
| Inbox | pending 事项，区分审批与知悉；最近5条、查看全部 | Badge 使用 pending count；按重要/逾期提示但不靠颜色表达；截止后未确认仍 pending，过期/撤回才关闭 |
| 审批视图 | 已冻结快照、受众限定/预计名单、时间、流程版本与历史 | 本人有任务资格才可操作；任务完成后去掉可执行按钮，异步结果显示“处理结果同步中” |
| 工作台 | 近期有效公告及更多入口 | 点击进入与铃铛一致的版本详情；不在卡片摘要里确认 |

每个列表/详情包含初始加载、空数据、筛选无结果、部分刷新失败、无权限、失效版本及网络重试状态；失败保留可安全保留的查询/输入，不能变成空列表。提交按钮防连点，网络不确定显示原 operation 核对入口，不引导用户新建重复申请。

同一租户内筛选和分页可保留；切租户后计数、列表、草稿上下文与订阅重新绑定，异步旧响应以 tenant/request generation 丢弃。审批详情/管理详情不被接收者视图缓存复用。页面离开确认、键盘焦点、Escape关闭浮层、弹层焦点返回、窄屏侧栏及读屏标签遵循既有设计系统；重要/错误/已完成均有文本，不依赖红点。

导航解析结果固定业务类型+announcementPid+versionPid，审批再带submission/task；禁止任意跳转URL。旧通知不自动跳新版并记为已读/已确认；只有新版仍对本人可见时提供“查看新版”链接。失权后不返回旧标题/正文/附件，历史提醒投影做权限过滤或脱敏说明，原始审计事实仍保留。

UI 文案 i18n；本期公告正文由发布者填写，不自动翻译或复制成多语言公告版本。时间用当前租户时区显式展示，原始UTC不裸露为技术文本。

### 16.2 接收、知悉与待办状态的连续性

- 发布时固定接收事实；部门/角色变化不撤销同租户有效成员的既有接收权。停用/退出租户优先阻止访问和催办；同一成员恢复有效后，可恢复尚未失效公告的既有接收权与未完成知悉，不自动补发已错过提醒。
- 退出后重新加入形成新的 membership incarnation 时，不自动继承旧收件资格；不能只凭相同 userPid 将旧租户成员记录当成当前成员。保存 memberPidAtPublish 的目的之一就是区分这一情形。
- 确认必须绑定当前有效版本/哈希和本人；公告有效时截止后允许确认。服务器决定是否逾期（acknowledgedAt > ackDueAt），恰好截止时不算逾期；now >= ackDueAt 停止自动催办。
- 截止日期不写到 Inbox expires_at；知悉任务的失效时间使用公告有效期。否则会在截止时被通用清理误关，破坏截止后手动催办与人工确认。
- 当前公告仍有效时，未完成知悉事项不允许 dismiss/通用mark_done/批量acted。全部已读仅更新 item.isRead；业务 acknowledge 成功后才关闭该项。无知悉要求不创建知悉事项。
- 暂时失效成员的待办不参与可操作pending计数；资格恢复时从公告权威事实重建/重新激活未完成投影，不覆写已确认历史。退出重加新membership不激活。
- 通知、Inbox 是投影，公告接收/确认才是权威。投影消费者失败可重放；后台一致性核对只修投影，不伪造阅读/确认，不在页面打开或应用启动时自动写业务事实。
- 通用 Inbox 清理允许删除符合策略的已结束投影，不删除公告知悉事实和审计；活跃确认事项不会因created_at过久被回收。已有确认事实在投影重建时不再生成pending。

拟新增 `GET /api/inbox/pending-count` 与 pending-summary（按类型）：只计当前用户/租户可操作pending事项，保持原 unread-count API 行为。公告读/确认服务发布可追踪的投影更新，同一version/user使用稳定clientItemId；确认/撤回与创建投影乱序时，消费者查询权威终态，不能重新开启已结束项。

### 16.3 催办数据、时间与异常的精确规则

催办以版本为单位，逻辑模型新增“催办批次”和“批次收件执行”：批次记录AUTO/MANUAL、目标UNREAD/UNACKNOWLEDGED、policyHash、租户日期、操作者、资格快照时间和状态；收件执行记录user/member、预期理由、去重窗口、发送/跳过/失败及原因。批次与收件具有唯一键，自动使用 `(tenant,version,localDate)` 防重，技术重试沿用原batchId。

自动计划每天执行（包含周末；本期不引入工作日历），默认09:00，以发布时冻结的租户ZoneId和政策计算。租户后来换时区不追溯改变既有版本；编辑页/管理详情明确显示冻结时区，新版本使用新时区。夏令时缺失时刻顺延到当天首个有效时刻，重复时刻只执行一次；每日唯一键避免双触发。

首次触发是 publishedAt+24h 后（含相等）的首个允许09:00；若租户免打扰覆盖该时刻，则顺延到同一天允许窗口，超出当地日期或知悉/有效期限则跳过，不跨日积压补发。没有免打扰配置时不假造日历，不宣称平台已有接线；公告运营配置须显式保存该策略或显示未配置。

每个有效自动批次必须至少持久化一个满足资格、未被限流的收件执行，才占用三次额度；空候选或全部被限流的扫描记为skipped，不消耗额度。所有原收件都在消费前完成而被跳过时，已形成的有效批次仍占一次，避免反复重建。渠道失败重试留在原批次，不额外计次。

手动预览返回previewToken、版本/revision、过滤、人数和evaluatedAt；提交时重算并返回实际变化，不接受客户端完整名单。默认同版本/用户一小时内不重复创建提醒意图，手动/自动共用窗口；过滤切换也不能规避。未读和未确认同时命中只形成一条本次提醒。批次通用operationId防重复提交，单人并发由数据库唯一栅栏裁决。

全员公告发布者失去管理/催办范围后，手动和后续自动批次均停止并报blocked；不会改由系统身份扩大权限。自动批次使用持久授权主体并每次复核范围。管理员可在自己授权范围内手动催办并留痕；本期不新增自动运营主体转移功能，不自动挑管理员。原主体权限恢复后可显式恢复原计划，仍不回补已错过周期、不恢复撤回/过期公告。

截止后只允许手动知悉/阅读催办，且公告必须仍有效；不要将“停止自动催办”等同“完成知悉待办”。渠道不可用/单条失败分开记录，管理页显示queued/sent或completed/skipped/failed的真实语义，站内持久化成功称“提醒已生成”，不称用户收到或已读。

### 16.4 统计分母与导出契约

以一个准确version的封存接收快照为分母，不将历史多个版本混合求和。定义 N=实际发布接收人数，R=有首次阅读事实人数，A=有确认事实人数。历史阅读率R/N，知悉率A/N（有知悉要求时），N=0显示不适用；A<=R<=N。成员失效不减少N/R/A。legacy迁移人群不计作历史真实发送分母，显示独立迁移口径。

当前有效且未完成的人数为运营分母，与历史率分别展示。未读=N-R，未确认=N-A，逾期未确认=超过截止且没有确认的历史接收人，其中当前失效者单列；“可催办”仅包含当前有效、同membership且公告有效的人。既已逾期确认者计入A并单列lateCompleted，不能继续留在逾期未确认。无知悉要求的A/知悉率/逾期项均为不适用。

渠道分母为该批次实际计划的接收人×已启用渠道，不把未配置小程序计为发送失败。发布提醒、催办、BPM任务消息分别统计；不能用渠道日志条数推断接收人数或确认人数。各统计响应包含versionPid、snapshotOrigin、asOf、filters及分页total，同一次页面汇总与明细使用一致读取时间。

导出策略确定为受控异步任务，即使小结果也走同一任务/下载生命周期，避免两套授权。拟命令 `announcement:export_statistics` 输入operationId、versionPid、筛选与asOf token；返回exportTaskPid/accepted。查询和下载复用平台导出任务能力，公告适配器负责准确业务权限。

CSV每个接收人一行，列顺序固定：公告标题、版本号、发布时间、接收者展示名、发布时部门/角色展示快照、阅读状态/首次时间、知悉要求/截止、确认状态/确认时间/是否逾期、快照时成员资格、站内通知状态。汇总可作为同任务独立CSV文件列出N/R/A及过滤口径，不在明细前塞不同列数的说明行。部门/角色快照仅含匹配受众规则及授权展示所需内容，不导出其他敏感角色或完整个人目录。未来小程序字段通过导出schema版本增加，不泄露openid、token或内部numeric ID。

导出文件UTF-8（含BOM便于常用表格工具打开），正确CSV转义换行/引号/分隔符，对以=、+、-、@及可触发公式的前置控制字符开头的非数值文本做公式防护。时间附明确时区；无要求、未知legacy、未发送、发送失败分开表达，不能都导出空值。

生成时固定数据快照、字段schema、筛选、行数和内容摘要；完成后的数据自然变化不重写文件。下载在当前认证下检查同租户、任务归属/明确管理员权限、export+stats、仍能管理该公告及访问快照中的每一行；任一行权限被收回则拒绝整个旧文件，提示重新导出，不能返回部分已生成敏感文件。接收者阅读变化或成员姓名变化不等于权限收回。

默认文件保存24小时（复用P0所见平台策略），到期重新导出；不得公开裸对象存储地址。任务失败可见，重新生成是新operation而不是改写旧快照。下载再次校验checksum/状态/过期，审计记录操作者、过滤、version、asOf、行数及成功/拒绝；正文附件不混进统计包。统计与导出接口不能成为人员目录越权查询接口。

### 16.5 政策配置、分发可用性与运行控制

拟租户公告政策记录包含revision、发布策略、默认流程绑定、延迟窗口、自动催办时刻/次数、手动去重窗口、免打扰设置及导出保留策略；流程定义仍归BPM。修改需policy_manage，流程管理员具备被明确授予的对应权限；当前记录CAS并留前后值，发布者仅看到可选能力和说明。

政策变更分两类：安全政策（强制审批/免审资格/发布范围）在实际动作时实时复核，收紧立即阻断未执行任务；体验/时间政策（提醒时刻、时区、次数、延迟窗）按版本冻结，修改不追溯重排已有公告。放宽政策不会自动发布旧blocked任务，仍需授权恢复。流程绑定更换不改变运行实例定义，新提交使用新绑定。

Web站内提醒是本期公告基础渠道；接收者不能用个人偏好关闭权威公告接收与知悉待办，但可以按既有能力调整外部可选渠道。该规则需在通知偏好UI显示“公告站内提醒始终开启”，不借用系统消息类别掩盖来源。首期不调用浏览器原生推送、不强制弹窗。

发布前校验站内分发桥接、公告host resolver与持久队列就绪；未配置/被禁用则阻止新发布并指出配置项。已经提交成功的发布若随后分发故障，公告继续有效，显示渠道/投影异常并在原意图恢复，不能撤销真实发布事实或宣称全部已送达。未接小程序只能显示未启用且不可选，不能提交无效渠道快照。

运行默认：发布worker lease 120秒、30秒续租（可配置并基于真实规模复核）；业务执行技术暂态最多重试3次，退避5/30/120秒，始终不超过latestAllowedAt；永久输入/权限/终态冲突不自动重试。底层可靠消息按平台既有重试/死信机制执行，其次数不计为新发布或新催办。部署前需按容量验证租期，超期worker即使恢复也不能用旧fencing token提交。

监控至少区分scheduled overdue、blocked、retry exhausted、provider unavailable、approval result lag、recipient expansion lag、delivery failure、inbox projection lag和export failure；告警是运维信号，不发送给所有公告受众。管理详情展示稳定错误原因、最后尝试时间与有权恢复动作，普通用户不看到内部栈/SQL。

### 16.6 安装、升级与资源生命周期补完

两种组合沿用 §3；§14.5 保留 P0 盘点。组合聚合入口现已实现：Core catalog → BPM `--composition announcements` → 校验后的 application lock、22 个 staged artifacts、client/SSR 与公开部署脚本。所有 artifact 字节可离线校验；本地 `--skip-oci` 构建不能当成 OCI 镜像验证。实际入口见 §18.26。

安装顺序确定为：冻结平台候选 → BPM对候选API构建 → 验证聚合catalog及依赖图 → Core/BPM迁移 → 准确进程装配 → 显式导入基础/公告/BPM配置 → 授权发布默认流程并绑定租户 → 开启并核验公告host分发策略 → 两种组合各自真实旅程。流程配置缺失允许草稿，强制审批租户不允许发布。只为安装新租户显式执行该流程，不在每次登录/启动时重导。

升级预检包含：运行中申请及definition版本可读、未执行发布/催办任务、队列兼容schema、附件引用、导出存活文件、租户策略与新权限映射。旧事件按版本解码，不能在还有未消费旧事件时移除旧契约；默认不自动升级固定实例定义或修改已批准快照。

Core migration向前执行；已改变schema时不承诺直接回滚旧jar。回退需匹配的兼容构件或部署前备份恢复方案，明确可能丢失部署后业务事实，由发布批次决定，文档任务不执行回滚。

BPM禁用/卸载必须显式preflight：存在运行中申请/待处理任务/未消费结果/不可用历史读取时阻止；业务先撤销或完成再核对。已经完成批准的已发布公告继续可查知悉事实；依赖BPM审计的历史保留可读构件或授权归档投影，不能删插件后让管理详情无解释报错。纯OSS不自动查询远程BPM兜底。

草稿删除是逻辑删除；有发布或审批历史使用撤回/归档。本期以只读历史列表承载归档查阅，不新增独立“归档”状态转换或批量归档功能；任何视图整理都不替代撤回，不改变生效公告可见性。历史业务事实及附件审计引用不因Inbox/通知清理而级联删除。附件内容被审计版本引用时禁止普通删除/覆盖；正常未引用上传垃圾走既有文件生命周期，不能在文档任务中清理真实文件。

### 16.7 后续阶段接口补齐与授权归属

以下是已实现入口的语义目录，复用§15的ApiResponse、当前租户/actor、operationId、revision及错误语义。准确路径以本表及 §18.26 的 Controller 为准；管理读接口与接收读接口分开，不通过传入任意userPid切换身份。

| 操作 | 当前入口 | 权限与关键语义 |
| --- | --- | --- |
| 公告能力/有效限额 | GET `/api/announcements/capabilities` | 返回本人可用发布模式、渠道就绪、实际上传限制及稳定原因；不返回密钥或无权流程名单 |
| 审批就绪 | GET `/api/announcements/approval-readiness` | submit+publish，解析租户唯一默认绑定及准确定义；缺BPM返回可解释未就绪，不自动fallback |
| 撤销审批申请 | 命令 `announcement:withdraw_approval` | 申请人或授权管理者，submissionId/revision/reason；先关本地发布许可，再按§15.7撤销，不只改Inbox |
| 核对审批结果 | 命令 `announcement:reconcile_approval` | 授权管理者，准确submission；只从可信provider恢复事实，不能手填结果 |
| 置顶调整 | 命令 `announcement:set_pinned` | edit+记录管理权，主体revision；单独审计，不改正文快照或发新通知 |
| 统计快照/汇总 | POST `/api/announcements/records/{recordPid}/statistics/snapshots` 或 `/api/announcements/versions/{versionPid}/statistics/snapshots`；GET `/api/announcements/statistics/snapshots/{snapshotPid}` | stats+记录权，固定N/R/A、asOf、版本及筛选 |
| 接收明细 | GET `/api/announcements/statistics/snapshots/{snapshotPid}/recipients` | stats+记录权，分页；字段按导出同等最小化 |
| 催办预览 | POST `/api/announcements/records/{recordPid}/versions/{versionPid}/reminders/preview` | remind+记录/当前范围，只创建有界预览引用，不创建催办批次 |
| 手动催办 | 命令 `announcement:remind` | remind，version/revision/previewToken/目标过滤；重算、逐人去重和实际结果 |
| 催办/渠道记录 | GET `/api/announcements/versions/{versionPid}/reminders/batches`、`/api/announcements/versions/{versionPid}/deliveries` | 对应stats或remind权限，准确版本/批次；不返回外部身份凭据 |
| 统计导出 | 命令 `announcement:export_statistics`，既有任务查询/下载适配器 | export+stats+数据范围，固定快照，下载重新授权，24小时过期 |
| 政策查看/修改 | GET `/api/announcements/policy`；命令 `announcement:update_policy` | policy_manage，expectedPolicyRevision；绑定新流程还需其读取/使用资格及provider验证 |

只读预览POST不生成发布、申请或催办事实。asOf/previewToken为服务端有界有效引用，不能当成绕过实时授权的签名凭据。expired token明确要求刷新预览，不能静默用新规则提交。动作响应返回本次实际结果与跳过/冲突原因，操作查询不泄漏其他actor详情。

### 16.8 全范围行动点与验收设计对账

本表是需求设计的覆盖索引，不声称可执行测试已存在或运行。实施时在既有测试catalog/profile中将这些需求映射到实际行动点、后端断言、文件和截图证据，未测试项留在分母。

| 需求组 | 设计落点 | 后续最少可证伪场景 |
| --- | --- | --- |
| D01 草稿/版本/重发 | §5、§15.2–15.3 | 草稿不完整可存；已发布不可覆盖；重发新version；重复操作无新事实 |
| D02 受众/权限/全员 | §4、§15.4 | 无员工成员纳入全员；部门子树越权拒绝；角色限定；退出重加不继承旧成员权限 |
| D03 免审/政策 | §4、§15.5、§16.5 | manage不等于exempt；强制审批拒绝免审；政策收紧阻断旧任务 |
| D04 定时/取消/撤回 | §7、§15.3/15.6 | 到时批准未就绪不补发；取消与发布唯一胜者；旧worker租约失效不能提交 |
| D05 BPM发起/定义/终态 | §9、§15.7 | 同键同实例；定义固定；同步完成先于响应；process_ended不能批准；旧申请不改新版本 |
| D06 人员/角色ANY/ALL | §14.3、§15.7 | 发起人排除；无人阻断；全部同意/任一驳回；失效后原任务不能处理 |
| D07 通知/渠道 | §8、§15.6、§16.5 | watcher不增加受众；禁用桥接阻止新发布；持久化不算已读；失败原意图恢复 |
| D08 铃铛/待办/工作台 | §6、§16.1–16.2 | 工作区待办可达；全部已读不消知悉；历史链接不跳新版；切租户无旧响应泄漏 |
| D09 阅读/知悉 | §6、§15.4、§16.2 | 管理预览不读；确认首次时间不覆盖；截止后可确认；过期拒绝；乱序投影不重开 |
| D10 催办 | §7.2、§16.3 | 空批次不计额；每日/三次/一小时；截止停止自动；手动保留；失权停止；跨DST不双发 |
| D11 统计/导出 | §6.4、§16.4 | N/R/A一致；legacy未知分开；下载时撤权拒绝；阅读变化不废历史快照；CSV公式防护 |
| D12 附件/富文本 | §5、§15.2/15.4、§16.6 | 未完成上传不能提交；跨租户/猜filePid拒绝；已冻结文件不能覆盖；HTML脚本不执行 |
| D13 组合/升级 | §3、§14.5、§16.6 | 纯OSS不含BPM产品；缺操作族fail-closed；校验摘要；升级保留旧实例与事件；有未结项拒绝卸载 |
| D14 异常/可观测性/UX | §10、§16.1/16.5 | 加载失败不伪空；超时原键核对；队列异常可定位；i18n/键盘/窄屏及原图复核 |
| D15 小程序扩展 | §8、§16.5 | 未配置不可选、不伪报发送；未来provider复用同一version与知悉事实 |

单元验证纯集合、时间、规范化摘要及状态机；real-stack IT验证迁移、事务回滚、唯一键、队列receipt、插件上下文和真实BPM结果；browser从公告菜单、Header和待办精确入口触发并断言payload及最终状态；文件验证真正下载并解析。重启/超时/乱序/撤权须有可控故障注入与不产生副作用断言，不能只靠 happy path 或全mock。

### 16.9 本轮设计自查结论与实施输入

> 本节保留设计冻结时点的自查，表中“尚未编码/测试”是历史状态；当前实现和验证见 §18.26。

| 自查项 | 处理结果 | 证据边界 |
| --- | --- | --- |
| 需求范围 | §2与D01–D15覆盖查看、发布、受众、审批、免审、定时、知悉、催办、导出与扩展 | 设计对账，非运行覆盖率 |
| 所有权 | 公告事实归OSS，流程实现归BPM，公开契约和组合依赖明确 | 未进行实际构件装配 |
| 状态一致性 | 补技术failed、取消许可/撤销实例区别、截止不等于过期、一次published与渠道异步分离 | 状态机尚未编码 |
| 权限一致性 | 当前成员/固定membership、管理范围/接收权、通知摘要/文件/导出统一复核 | 待real-stack负向验证 |
| 幂等与时序 | 冻结版本/申请/任务身份、提前终态、撤销竞态、投影重建和重试次数明确 | 尚未故障注入 |
| 统计与文件 | 分母/失效/late/legacy/渠道分开，固定CSV与下载重授权确定 | 未生成或下载真实文件 |
| 默认值 | 09:00每日含周末、24h起点、3次、一小时、30分钟、24h导出保留；运行重试有上限 | 部署可配置值仍需核验 |
| 导航与视觉 | 铃铛/Inbox/工作台/审批/管理入口、状态反馈和无障碍均有契约 | 未生成UI原图 |
| 文档边界 | 全部更新同一文档和原PR；阶段为未来实施依赖，设计不再逐阶段等“继续” | 本轮无业务代码/安装/功能测试 |

目前没有需要用户再选择的产品分叉。已知剩余工作是实施与实测，不是待补一层设计：物理DDL与DTO按仓库约定落实、公共契约候选构件、动态权限接线、附件部署限额、免打扰配置、锁定制品安装和容量测量。附件P0复核补充：`platform/platform-app/src/main/resources/application.yml` 通用multipart默认100MB/101MB请求上限；同文件的AI storage 50MB和AI attachment 10MB是其他域配置，不能误当公告附件限额。公告显示实际平台有效限制，部署override优先，上传/提交/下载均不得绕过。

容量不以未测吞吐值宣称达标：实施前记录目标租户最大有效成员、峰值并发公告、平均/最大正文附件量、待办/导出积压，形成批准的验收负载；无数据时做受控测量并报告适用上限。此项工程输入不要求重新讨论已定产品范围，不因缺测量就把“设计完整”说成“功能上线可用”。


## 17. S1 实施契约与验证边界

> 本节是 S1 时点契约。S2–S8 已接通发布、审批和运营能力，不沿用 S1 的“尚未启用”作为当前产品状态；当前入口见 §18.26。

本节记录已进入实现的 S1 基础切片；§1–16 仍是完整产品目标，不代表这些功能已经全部交付。S1 仅覆盖内容草稿、受控写入、历史版本迁移及其撤回/修订、接收事实读取基础。人员/部门/角色受众编辑、免审/审批提交、定时任务、新公告发布分发、铃铛/待办接入、阅读/知悉写入、催办和统计导出按 S2–S8 实施，不能因预留字段而计为完成。

### 17.1 物理模型及安装顺序

新增 Core Flyway 迁移 `V20261024190000__announcement_controlled_lifecycle.sql`，由同源迁移生成 `db/snapshots/schema-current.sql`。升级必须先停写并运行迁移/validate，再部署同版本后端，最后显式导入 `core-announcement` 元数据；新建环境加载生成快照。导入失败时不得开放旧的可写入口。S1 无 aura-bpm 代码、表或依赖；OSS 单独安装可保存草稿，发布操作明确返回“尚未启用”。组合安装仍按 §14–16 的 Core → 公共审批契约 → aura-bpm provider → 业务绑定顺序推进，安装 BPM 本身不会自动启用尚未实现的公告发布链。

| 表/字段 | S1 权威含义 | 后续边界 |
| --- | --- | --- |
| `ab_announcement` | 一个主体及当前草稿；`owner_user_id` 为内部用户关联，外部仍用 PID；`row_version` 为所有写入的 CAS，`editor_revision` 为内容草稿修订，`lifecycle_revision` 为撤回/修订/删除控制修订 | S1 可编辑字段仅 title/content/announcement_priority/pinned/expires_at；`draft_settings` 是保留容器，客户端不能任意提交；完整受众、附件、审批和政策按各切片引入强校验 |
| `ab_announcement_version` | 不可变 JSON 快照及 schema/hash；同租户主体内 version_no 唯一 | 新公告冻结/送审由 S2/S3/S4 接入；S1 仅为非草稿存量建立 `legacy-v1` 快照 |
| `ab_announcement_version_control` | 正文之外的审批/发布控制；legacy 依据为 `legacy_existing_content`，不伪造 approved | S1 支持 legacy published → withdrawn；其余审批/调度转换尚未开放 |
| `ab_announcement_recipient_snapshot` / `recipient` | 版本/用户/原 membership 的接收事实；存量使用 `migration_cohort` 并记录迁移时解析时间、人数和封存标记 | 不称历史真实收件名单；不补历史已读/知悉；仅迁移时有效成员及启用用户进入；新成员不自动补入 |
| `ab_announcement_operation` / `audit` | 前者是租户、操作人、命令、operationId 的唯一操作结果；后者为同事务追加的修订审计 | 失败整笔回滚；重放不再次写审计；操作身份独立于缓存生命周期 |

主表当前/生效版本引用具有 `(tenant,announcement,version)` 外键，不能引用另一租户或另一公告的版本。版本和审计由数据库触发器禁止 UPDATE/DELETE。active/archived 迁移为 published/withdrawn，high/urgent 迁移为 important；不制造知悉政策、批准、投递成功或阅读事实。未知历史状态直接阻断升级，不能猜测映射。迁移时无可恢复 owner 的记录只允许当前具有租户记录管理范围且具备独立动作权限者管理。

### 17.2 命令、状态和权限

| 命令 | 输入/前置 | 结果 |
| --- | --- | --- |
| `announcement:create_announcement` | 当前有效成员和启用用户、edit、operationId；允许不完整内容 | 新 draft，三种 revision 初值 1；无冻结版本和消息副作用 |
| `announcement:update_announcement` | edit + 管理归属 + draft + expectedVersion；仅五个草稿字段 | row/editor revision +1；历史正文不变 |
| `announcement:delete_announcement` | delete + 管理归属 + draft + 无历史版本 + expectedVersion；业务 payload 为空 | 软删除，row/lifecycle revision +1；保留操作和审计 |
| `announcement:archive` | withdraw + 管理归属 + published + activeVersion + expectedVersion；空 payload | 主体 withdrawn，清 activeVersion，版本控制 withdrawn；无渠道撤回承诺 |
| `announcement:republish` | edit + 管理归属 + withdrawn/expired + currentVersion + expectedVersion；空 payload | 基于历史内容建立 draft，保留历史版本，不直接发布；row/editor/lifecycle revision +1 |
| `announcement:publish` | S1 不开放 | 明确业务拒绝；无状态、版本、收件或通知变化 |

兼容投影的 published_by/published_at 表示最近一次实际发布，修订草稿仍保留历史；页面明确标注“上次发布人/上次发布时间”，不能把它误读为新草稿已发布。

普通编辑者只能修改本人主体；`announcement.announcement.manage` 可扩展为当前租户全部记录的管理范围，但每个写动作仍需独立 edit/delete/withdraw。旧 manage 不自动授予任何新动作。`publish_all` 只扩大将来发布受众，不能代替记录管理范围，更不能代替 edit/exempt。普通接收者通过专门接收接口授权；`model.ab_announcement.read` 是管理模型读取能力，应只授予管理/观察角色，不能因授予业务 read 就自动授予该管理能力。

`ModelMutationGuard` 新增模型级 `extension.commandWriters`，create/update/delete 三组准确命令白名单同时保护动态 CRUD、批量、导入和递增入口；避免在全局共享的 title/content/status 字段上设置公告专用写白名单而误伤其他模型。仅有 CommandPermit 或管理员身份不能绕过该守卫。

Spring 的 `CommandTargetAuthorizer` 是受控领域目标授权扩展：仅完整拥有持久化的处理器可声明，命令权限先校验，再由其重查当前身份/动作和存储中的归属，最后才进入幂等结果读取。公告在此读取同租户 tombstone 做重放授权；普通动态读取仍不展示软删除记录，撤权者不能读取旧结果。该扩展不是 BPM API，也不改变其他命令的默认目标范围校验。

`requiresDslPersistence=false` 的完整写入拥有者同时跳过 FIELD_MAP、computed-field 写入和 handler result 写回，返回的 status/row_version 仅作为结果，不能引发第二次更新。默认 DSL 处理器保留原行为。公共 handler context 透传 clientRequestId 与 expectedVersion。

### 17.3 HTTP 与 DSL 请求

命令统一使用既有 `/api/meta/commands/execute/{commandCode}`：`clientRequestId` 是 operationId，已有记录携带 `targetRecordPid`、`expectedVersion`。同键同意图返回原结果，同键不同内容冲突；并发编辑只有一个 observed revision 能写成功，不自动取最新版本覆盖用户修改。

既有 REST 路由保留为受控适配：POST `/api/announcements` 仅创建草稿，PUT `/{pid}` 仅改草稿，DELETE `/{pid}` 仅删除无历史草稿。POST/PUT 传 operationId，更新另传 expectedRevision；DELETE 用 Idempotency-Key 与 If-Match。priority 可作为 announcement_priority 的兼容别名，二者同时出现拒绝。路径和结果使用公共 PID，不猜测数字内部 ID；写结果为 pid/recordPid、row_version、operationId、status。旧客户端必须迁移新增的幂等/版本要求及结果结构；status/publishedBy/publishedAt 等输入明确拒绝。

GET `/api/announcements` 在 S1 保留受限 limit（1–100）读取，依据当前租户、启用用户、有效且相同 membership、sealed 接收名单、activeVersion 和未到期判断，不把 draft 或撤回内容返回读者。列表返回公开 PID 及发布人展示信息，不写阅读；分页、筛选及显式阅读/知悉仍按 S2/S7 扩展。

DSL 页面通过可选 `extension.commandRequest` 配置 idempotent、versionField 和 payloadFields。表单保留同一意图在不确定网络结果后的请求标识，版本取用户打开的记录；行操作只提交声明的业务输入。公告行操作显式指定 UPDATE/DELETE，避免从按钮翻译文案猜测操作类型。用户界面不展示这些技术参数。共享字段必填规则由模型 binding 覆盖，与现有运行时 assembler 一致；公告草稿不通过降低全局 title 的 required 影响其他业务。

### 17.4 当前验证与后续接入闸门

S1 的验收分母包括迁移新建/升级、不可变和跨租户引用、草稿新增/保存/删除/重放、并发冲突、直接字段/动态写旁路、legacy 撤回修订、当前权限复查、菜单驱动表单/详情/过滤/空态/错误及原图复核。实际执行结论以 [S1 验收报告](./2026-10-24-announcement-s1-testing-gate-acceptance-report.md) 为准；静态配置合法、编译、单测、API、浏览器和视觉分别裁决，不能互相替代。

进入 S2 前须补齐受众与政策编辑/解析、授权范围证明、附件身份与强类型快照 schema。进入 S3 前须接入发布事务、任务租约、封存名单、可靠 outbox 及撤回清理；S1 的发布拒绝必须保留到这些链路真实验收。BPM 进入 S4–S6，严格遵循 §15.7 公共版本/申请/终态契约，不让 OSS 引入引擎实现依赖。

## 18. S2–S8 实施追踪与页面蓝图

> §18.1–18.25 是各准确源码时点的历史记录，保留失败与修正，不作为当前待办。当前状态统一见 §18.26 和验收报告。

用户已授权本节全部剩余切片的业务代码与验证，交付至已验证 PR，暂不合并。仍不开发小程序发送端，仅保留渠道接入契约；不执行生产发布。沿用 task `announcement-management`，OSS 与 aura-bpm 分仓实现；同一设计是唯一需求真源，下面的状态仅依据实际证据更新。

| 切片 | 本轮行动点与退出条件 | 当前状态 |
| --- | --- | --- |
| S2 | 人员/部门子树/管理范围内角色预览、政策编辑、附件授权、管理/接收身份分离；越权和成员边界真实验证 | 原生政策弹窗与无员工档案人员选择已真实验证；预览页面、上传/下载及完整边界仍待完成 |
| S3 | 冻结版本、免审、立即/定时、持久任务与窗口、取消/恢复、封存收件与可靠分发 | 免审立即/预约/取消、实际调度与 outbox 双投影已真实验证；恢复、撤回清理与完整异常仍待完成 |
| S4 | 中立 approval.v1 操作及只读申请解析、准确绑定、可靠终态契约 | binding、来源、首次/延迟启动/撤销授权、参与预览和终态核对已接通；真实集成定向通过；PF4J 组合安装已核验，审批业务 UI 待验 |
| S5 | BPM 严格人员/角色、排除自审、ANY/ALL、驳回终止、异常分配、申请撤销 | 十条真实 PG/引擎场景已覆盖基本决定、成员重建、空节点恢复及 ANY/ALL 替换；管理恢复服务已验证，原生恢复页面待验；正式组合安装已核验 |
| S6 | 公告发起/终态消费、同步结束和超时核对、明确组合清单与安装验证 | 七条真实业务组合场景通过，包括晚结果、晚同步和无管理权审批人的冻结预览；正式构件组合已核验；附件下载和审批 UI 仍待验证 |
| S7 | 我的公告/版本详情、显式阅读/确认、铃铛与待办、独立待处理数 | 后端定向集成及管理员/普通接收者的九条真实浏览器旅程通过；截图发现的问题已修正，当前修订版重验尚未全部完成，完整错误/附件边界待补 |
| S8 | 每日最多一次/最多三次自动催办、截止停止与手动催办、统计快照和授权 CSV 下载 | 政策、统计页面、统一命令导出及实际浏览器CSV下载已有定向证据；网络丢响应后刷新重放返回同一任务；催办核心已有定向真实证据；剩余恢复/权限页面、批次历史、观测容量、上传取消并发、清理和完整异常仍待完成 |

页面优先复用既有 DSL、字段选择器、附件控件、工作台与 BPM 贡献，不另建手写业务页面。蓝图及状态验收沿用 §16.8 的 D01–D15：

| 页面 | 角色/主要任务 | 分组与主要动作 | 状态与输入指导 |
| --- | --- | --- | --- |
| 公告编辑 | 发布者创建政策完整的公告 | 内容；通知对象；发布与审批；知悉政策。保存草稿、预览、免审发布或送审 | 不完整草稿可保存；角色范围说明；人员/部门/角色用结构化选择器；附件显示完成状态；版本冲突保留输入 |
| 公告管理详情 | 发布者核对内容、审批与执行 | 对象摘要；冻结内容；发布安排；审批进展；接收统计与审计。取消、撤回、修订、恢复 | 分开显示审批与发布状态、同步异常、过窗及权限阻断原因；不显示内部 PID/hash |
| 我的公告与详情 | 接收者阅读并按要求确认 | 全部/未读/待确认；正文附件；知悉与截止。查看、确认 | 列表不写已读；完整正文成功显示后显式标记阅读；逾期确认可用且有说明；过期/撤回立即失效 |
| 统计与催办 | 有权限管理者跟踪固定版本 | 固定分母、已读/知悉/逾期、失效成员与投递状态。筛选、催办、导出下载 | 零分母不显示虚假百分比；催办预览与限流可解释；导出使用固定快照并在下载重授权 |

每个新增行动点关联 §16.8 行动 ID、服务端断言及真实浏览器证据。S1 历史验收保留，不作为新切片已执行证据。S1 提交 `1f95b77b1f0d6400a0f71afa203bfe89ed47c2f6` 的远端 18 项 checks 已通过。

### 18.1 当前实施补充与未完成边界

公告内容沿用原生表单，政策使用列表/详情的原生“通知设置”分组弹窗；不新建业务 React 页面。选择器从后端当前有效权限范围加载，重要公告的知悉默认值按当前内容计算，显式取消优先。预约输入使用用户有效时区解析，夏令时不存在时间拒绝保存。附件元数据来自服务端已完成文件，客户端展示字段不参与授权。

中立审批契约执行排除申请人、空分配/解析失败阻断和驳回终止。BPM 严格模式按已发布、无循环的人工节点与标准网关校验；人员与角色保留完整选择。通用引擎实例结束不能替代明确的通过决定。provider、任务动作守卫、可靠进度、终态和公告消费已经获得定向真实证据，正式组合安装和完整页面仍待验证，不能把前者等同于后者。

九条浏览器旅程已从真实菜单覆盖新增/编辑/删除、权限拒绝、legacy 撤回修订、预约取消、立即发布后的实际通知/知悉待办，以及管理员和普通接收者的正文阅读、独立确认和刷新保留首次事实。全部标为已读不会关闭未完成知悉。逐图复核确认定时状态和历史时间列宽修正有效，另发现接收列表把 datetime 语义当成格式模板、详情带有无关通用操作和权限截图拍在加载中；修正已进入候选源码。后续修订版有一次表头可访问名称定位错误，属于测试驱动问题，不能声称该次九条全部通过；当前完整重验和其余图片审阅仍待完成。

### 18.2 审批集成当前实证与缺口

截至 2026-10-25，OSS `1733c9fbd` 构件与 BPM `d9c53e8` 的定向验证包含七条真实公告组合、十条真实引擎场景和三十五条相关单元场景，无失败、无跳过。组合测试使用实际公告业务来源、BPM provider、SmartEngine、持久工作器和可靠事件，证明批准后才封存实际收件名单并生成提醒/知悉待办；驳回、两类撤回、晚批准和批准后的晚同步均不能误发。无公告管理授权的审批人可以查看准确冻结正文，非参与人被拒绝。结果事件仅唤醒同步，业务方再次拉取固定 provider 的不可变终态并核对完整绑定。

引擎场景证明空节点可经审计指定人员后显式恢复而不自动批准；ANY 替换保留共享任务和其他候选人，原人员失去任务资格；ALL 替换保留已有通过意见和原通过规则。排队意见不能由重新创建的成员身份执行。业务启动工作器额外复核原申请人的当前成员及业务权限，恢复不借管理员身份代执行。审批历史将已接收但尚未执行的意见显示为待处理，不提前显示通过。

真实导入暴露并修复了重复权限与通用校验器未识别原生输入弹窗的问题；真实撤回暴露并修复原 status VARCHAR(16) 容量不足。升级夹具现在从原字段长度开始，确切验证新撤回状态可保存。失败证据保留，不能以此前编译成功代替这些运行结果。

后续已完成 PF4J 正式组合安装和设计器入口验证，尚未完成严格审批的完整业务浏览器验收。冻结预览/历史及管理恢复已有后端和定向组件证据，原生恢复配置已进入候选，前端组合已实际安装；严格审批菜单操作和受控附件下载仍待完成。S2 附件/预览边界、S3 完整恢复/过期清理、S7 剩余页面边界以及 S8 剩余持久运营/导出仍在同一任务内实施，不需要阶段性继续授权。S1 历史报告和远端旧 checks 不能代替本轮确切 head 的验收与提交。

### 18.3 显式组合构建与配置导入契约

Core 构件目录提供可选 `config/core-announcement`，默认 Core 应用不隐式导入它。BPM 默认 `app.yaml` 保持独立；通过 `--composition announcements` 显式选择 `app.announcements.yaml`，将公告配置、BPM provider、前端贡献及迁移集锁定到同一应用构件。两种 BPM 清单均先导入 org-management 再导入 platform-admin；组合清单在 BPM 配置之前导入 core-announcement。公告表仍属于 Core migration set，审批表属于 BPM migration set，不创建第三份公告迁移副本。

部署启动器按已验证 application.lock 的 composition.nodes 配置顺序及对应构件路径导入，不依赖构件目录的字母排序。组合构件才启用 Core 的插件投递消费；普通 BPM 安装不隐式启用。导入辅助脚本包含在构件校验收据中。构建使用自己的确切版本 Core catalog，不读取旁边 OSS 源码目录；Mac 仅执行 `--skip-oci` 文件构件验证。当前已完成正式 PF4J 安装与设计器入口验证，完整业务浏览器验收仍待完成。符号链接入口问题已修复；Java 包装器的命令摘要封存时机已在候选停止器修正，需随新构件再验证正常停止。私有加密和协议签名密钥不进入构件或公开收据。

### 18.4 S8 政策与正式组合的当前实证

正式组合使用 OSS `111771c63` catalog 与 BPM `f2e2933`，实际执行1,655条后端测试，无失败、无跳过；生产前端、源码/构件图及单 React runtime 校验通过。独立应用经过迁移、初始化、按锁定顺序导入与产品验证，PF4J 实际登记 `aura.bpm.runtime`，Workspace 对源码、provider 构件、PID/cwd/端口与应用锁核验通过。两条菜单驱动设计器浏览器用例通过并逐张复核原图。这些结果证明组合可安装和贡献可进入，不能代替严格审批、冻结预览及恢复的业务验收。

Core 新增 `V20261025030000__announcement_operating_policy.sql`，由145条迁移生成快照。政策编辑 CAS 使用 revision；未执行任务的授权证据使用独立 security_revision，升级时取旧 revision 以保持既有证据含义。提醒时刻、次数、去重间隔、免打扰和默认流程变更不推进安全代次；强制审批规则改变推进安全代次。新版本冻结提醒政策及租户 ZoneId，不改写旧版本。

政策 GET、流程选项及 `announcement:update_policy` 要求独立 policy_manage、准确启用成员、expectedPolicyRevision、严格输入与 operationId 账本；CAS 更新与不可变前后值审计同事务提交。失效的既有默认流程显示人类可读说明，可更换或清空，不自动移除。免打扰开始/结束同时填写或清空。自动上限允许1–3次，共用去重60–1440分钟，技术延迟1–120分钟；默认仍为三次、60分钟和30分钟。

提醒日历只考虑当天，首次不早于发布满24小时，截止/有效期相等停止；夏令时缺失取首个有效时刻，重复取首次。免打扰支持跨午夜，但不积压到下一天。持久计划、批次额度、逐人执行、统计快照与 CSV 仍待接入，不能把日历单测视为自动催办已可用。

当前定向证据为11条政策单元、10条日历单元及3条真实 PostgreSQL 政策场景，无失败/跳过；覆盖 CAS、重放、安全代次、准确成员、审计不可改写、清空和日期边界。原生政策页有5条流程解释器/生产请求构造单测；详情地址用例先复现追加 current 的错误，再用已有 detailEndpoint 修正。页面保存、取消、非法输入、重载及无权访问已纳入浏览器测试，当前尚待完整重验。公告删除空态测试改为先筛选本次目标，保留旧数据；有效期列增加几何检查，避免被固定操作列遮挡。


### 18.5 统计历史分类底座与复验边界

新增迁移为实际接收人及送审预计名单增加私有 organization_snapshot；旧行默认 captured=false，不按当前目录回填。新快照区分私有部门/团队分类与可展示部门名称，仅记录发布者获授权展示的部门名称及命中受众规则的角色，不保存无关角色名称。预计名单在提交时采集，实际接收名单在发布时采集，两者可以不同。API 的受众预览与接收正文不返回该私有分类。

统计、催办和导出分别检查动作权限，并复用同一份当前发布管理范围配置；它们不隐式要求 publish 动作权限。范围未配置及旧 membership 上下文拒绝；部门/团队历史行按封存分类判定，不按接收者当前所属目录漂移。未知历史分类只允许已获租户全员范围或本人范围访问，不能声称具备当年组织事实。后续统计/导出仍须按当前行权限过滤展示名称并在下载重新授权，当前底座不代表统计或 CSV 已完成。

数据库阻止改写接收身份、组织快照及已封存分母，保留独立阅读/知悉写入。活动时间线的 public record PID 列扩展至36字符，兼容遗留 UUID 公告目标；不改写已有迁移。

本轮保留数据的浏览器证据已证明九条生命周期旅程（含两类接收者确认）与政策保存/清空/回显/审计。非法时间响应已拒绝，页内与 toast 同文案导致驱动定位冲突，驱动修正后仍待复验。原图发现接收列表发布时间截断及政策虚拟详情的无效返回链接，候选已调整列宽并隐藏不适用返回入口；撤回说明明确重新发布须建立修订草稿和重新提交。以上新增底座与布局修订尚待本轮单元、真实数据库及浏览器验证，不计为完成。


封存边界进一步收紧：先创建未封存名单头，写入完整接收事实，核对实际行数等于分母后最终封存，再在同一事务发布并生成 outbox。数据库接收人 INSERT 锁定同一名单头；封存后拒绝追加新成员，最终封存也不能接受与行数不符的分母。新增真实反例先在已有数据的147版数据库上观察到“未抛出预期异常”，故148迁移补齐该缺口；修订后仍待真实数据库和浏览器复验。


### 18.6 公开后台入口与提醒投影的权限补强

真实反例在保留数据的148版环境中证实：原成员退出并通过 IAM 重建 membership 后，旧铃铛和 Inbox 全部/单项读取仍返回公告标题；公开异步任务接口也接受命令执行身份输入，并可读取其他用户的任务。候选补强所有公告投影读/计数与用户标记入口的原成员、有效版本/有效期过滤，底层历史事实仍保留。公告知悉项不走带标题的通用提交前 WebSocket 广播，Web 沿用30秒计数轮询及按需鉴权读取。

公开异步提交只接受执行器显式声明的安全输入入口，命令型及 SQL 型后台任务须由已授权业务入口创建；平台内部 bootstrap/插件调度调用保持受控内部路径。公开任务查询、取消和删除要求持久化 tenant 与 owner 同时匹配。以上改动已有真实失败反例，尚待修订后的定向及浏览器验证；不把这项补强当作催办或 CSV 已实现。


提醒投影与公开异步入口补强已通过34项相关单测及10项真实数据库/HTTP场景，包含真实 membership 重建、其他用户任务读/取消/删除拒绝及本人任务读取正例。后续 UI 复验补充铃铛与待办的准确版本深链、接收空态，以及始终返回“我的公告”的原生按钮；不再使用未实现的 backUrl 扩展属性。通知来源按与客户端一致的大小写归一规则过滤。新增 UI 与大小写兼容修订仍待本轮验证。


### 18.7 发布任务租约的执行边界

领取、续租和成功提交均以数据库实时钟核验租约；只有当前 fence 且未过期的执行者可续租或完成。执行前过期直接放弃，受众解析后及每500名接收者写入前续租，提交前再校验；执行中失租则回滚整个发布事务，由既有有界暂态重试处理，不能保留部分封存名单或发布事件。原执行者持有任务行锁时仍不得越过租约时限，行锁不替代租约。

真实数据库反例已证明旧实现会在过期租约下发布；修复后11项真实数据库/HTTP集成用例通过，覆盖过期续租/成功提交拒绝、新 fence 领取和最终发布。97504e7f8 的12项浏览器旅程通过，包含铃铛/Inbox准确版本跳转、返回“我的公告”、通知全部已读不代替知悉、政策CAS/清空/错误反馈。41张原图已逐张复核，两张政策刷新截图需等待说明区稳定后重采；不能以行为通过替代这两张视觉验收。此项不代表 S3 恢复界面、S8 持久催办或统计导出已完成。


### 18.8 统计快照的候选实现与验收边界

统计通过受控POST生成30分钟有效的查看快照，汇总与分页读取同一asOf；为已接受导出保留25小时私有数据，文件仍按24小时策略。历史N/R/A保持固定当前授权范围内的封存接收人分母，姓名/未读/未确认等明细筛选仅改变明细与导出行数，不重写N/R/A；页面必须标明范围和筛选人数，避免将局部口径误称为全租户总数。成员失效保留历史分母；无知悉要求及零分母的知悉率不适用。migration_cohort不伪造历史发送人数，迁移后阅读单列，未知阅读不能冒充未读。

快照的原成员身份、历史组织分类与展示标签授权证明仅在私有表保存；公开明细不返回内部ID、memberPid或完整组织JSON。再次读取及导出使用时复查stats/相应动作权限、记录管理权、当前原membership、全部捕获行与已展示部门标签的当前权限；撤权拒绝旧快照，阅读/确认自然变化不改写已捕获数据。封存前核验总行数及筛选行数，封存后拒绝追加或更新；保留期内拒绝删除。

候选新增接口：POST `/api/announcements/versions/{versionPid}/statistics/snapshots`（query/state/departmentPid筛选）；GET `/api/announcements/statistics/snapshots/{snapshotPid}`；GET 同路径 `/recipients?pageNum=1&pageSize=20`。仅统计权限的管理者可用，均校验当前租户与固定所有者。默认单版本统计容量50,000人，超限明确拒绝，性能适用上限须以受控测量验证，不能把配置上限当吞吐验收。

统计底座已有实际HTTP、Service/Mapper JSONB往返、状态、撤权和不可篡改证据，见下节；原生统计页面、持久催办和受控CSV任务下载仍未完成。


### 18.9 异步导出恢复所需的平台执行身份

统计底座已有真实HTTP/数据库证据：四项统计用例与十一项公告回归通过，包含撤权后的HTTP403，以及退出重加且恢复相同有效角色后仍拒绝继承旧快照。原生统计页与CSV尚未完成。

为受控导出复用平台异步任务时，实际验证发现：没有请求租户上下文的持久任务派发会停留pending。任务登记表的读取、领取、进度与完成必须由服务器指定的持久化tenant/id及有效执行令牌约束，不依赖启动或恢复线程的登录态；作用域豁免仅覆盖这些登记表语句，不能传入业务执行者。新增宿主执行上下文从已领取记录固定task/tenant/creator/token，客户端输入不能设置它；业务适配器仍须按原成员身份重新建立当前授权。

该底座修复已有无请求上下文的真实失败反例，17项单测及5项真实集成验证通过；后续原子完成钩子补强已有18项单测及6项真实集成验证，覆盖结果写入与队列完成同事务、异常回滚。此处不能将原来仅支持command-handler的恢复清单称为任意执行器自动恢复。公告CSV数据源见下节；持久导出类型、私有文件保留、受控下载及浏览器旅程仍须接通和验证。

### 18.10 固定 CSV 数据源与后台当前权限复核

公告适配器基于已封存统计快照生成UTF-8 BOM文件，复用平台CSV安全工具；列严格固定为15列（部门、匹配角色各一列），无内部numeric ID、memberPid、版本PID、openid、凭据或正文附件。语言标签在接受时固定，所有日期使用发布时区并带UTC offset和Zone ID；当前翻译变化及接收者后续阅读/确认不改写已接受快照。未知历史、未发生、无要求、不具备展示权限、通知未生成及发送失败分别表达。输出默认限制50MiB，未知状态或缺少翻译明确失败；这一上限尚未作吞吐测量。

后台从持久所有者和原membership恢复当前角色，执行及完成授权入口复查export+stats、记录管理权和完整捕获行权限；不继承登记表的租户豁免。真实集成发现有效成员的显示用户名可为空，已修正为依赖稳定user/member身份而非可选显示名。数据源只写入宿主预分配的临时文件；宿主持久链路见下节，原生业务命令、统计页面及完整导出验收仍未完成。

验证范围：CSV格式4项单测（精确BOM/RFC4180中文换行/公式防护、纽约冬夏时区、未知与不适用、隐藏部门、字节限制及符号链接拒绝）；原成员上下文4项单测；统计真实集成6项中的5项在9e807489c通过，剩余文件生成用例仅修正测试对默认知悉期限的错误预期后于c6d89b49f通过。业务代码在这两次真实运行之间未变。真实文件用例覆盖无请求上下文生成、自然阅读/确认后保持旧快照、翻译变更不重写、当前撤权后生成前拒绝且文件零字节。该数据源轮次未执行浏览器下载或独立CSV解析，后续宿主独立解析见§18.11。任务运行证据见稳定runtime的`s8-statistics-csv-*`日志与JUnit；失败证据保留并区分实际身份恢复缺陷与两次fixture断言错误。

### 18.11 持久导出宿主与实际文件证据

公告数据源复用`ab_export_task`及`ab_async_task`，不伪装成命名查询，也不新增第二套任务队列。宿主创建的`domain-export`是明确加入恢复白名单的类型，公开异步提交仍不允许客户端直接创建。队列输入仅含导出任务PID和服务端恢复标记；tenant、creator及执行令牌来自已领取行。每个尝试在写入敏感CSV前登记私有临时路径与唯一对象key；同一执行令牌内的重试也使用不同尝试标识，避免失败对象覆盖新结果。

生成及上传在数据库事务外完成；最终复查原所有者当前权限后，文件可下载状态与队列完成在同一有效fence事务提交。状态及下载接口为GET `/api/exports/domain/{taskPid}`、GET同路径`/download`；当前所有者及源业务授权是每次请求的前提。原有导出查询和数据交换任务列表也重新执行源授权，不能从旁路泄露已撤权统计。下载先完整校验字节数及SHA-256，再返回受控HTTP内容，并写独立审计，审计“已授权下载”不等于客户端已收完字节。云对象存储须显式确认私有访问策略，不能返回裸对象URL；实际验证当前仅覆盖本地文件提供者。

过期尝试以持久清单清理对象及临时文件，失败记录稳定错误码并继续保留；成功清理后也保留扫描记录以处理失去fence的迟到上传。该清理和取消并发尚未真实验收。新增第150条迁移已通过新库、149→150保留库升级及生成快照漂移检查；旧任务数据库现场已完整备份后，原runtime以新快照与5条BPM迁移重建。backend-only启动的Workspace身份核验通过；三层产品构件验证因为未启动Web/BFF而失败，不能记为完整产品身份通过，浏览器运行前仍须补齐。

真实JSONB往返暴露了转换差异：现有精确`JsonNodeTypeHandler`正确保留`0.0`，随后默认bean转换将其变成`0`，破坏摘要。现按强类型字段恢复描述符并直接保留精确证据树，2项回归单测及后续真实链路通过；没有绕过TypeHandler或降低摘要校验。另一个真实反例为默认拒绝的未声明HTTP入口，现按既有`AuthenticatedAccess`机制显式声明所有者入口，由源服务继续执行export+stats、记录、原成员及全部快照行授权。

80f0c7e39上的定向回归：33项单测、24项真实集成（7项公告统计/CSV、17项原有导出）通过，无skip或retry。真实队列用例包含制品与队列完成、HTTP状态/下载、阅读/确认后的文件字节不变、撤权后两个HTTP入口403、旧导出入口拒绝及全局任务列表隐藏。真实文件由Python标准库独立解析，核对15列、1条接收记录、UTF-8 BOM、时区、固定阅读/知悉状态、字节数及持久SHA-256；这不是浏览器下载证据。原生统计/导出命令和页面、取消并发、过期/失败清理、跨所有者/成员及完整文件异常仍待补齐，不据此声明S8完成。

### 18.12 原生统计与导出页面实施蓝图

以下蓝图已落为原生 DSL 候选页面，尚未计为浏览器交付。用户从公告管理详情进入统计，默认选择当前有效发布版本；无有效版本时选择最近一次有封存接收名单的历史版本，并在摘要明确版本号。切换历史版本仍使用准确版本的独立快照，不在请求内合并历史版本。没有已发布名单时给出“发布后可查看接收统计”的反馈，保持当前公告页，不生成空的伪统计。

| 行动点 | 数据与行为 | 既有能力及需要补齐的接缝 | 真实验收要求 |
| --- | --- | --- | --- |
| 从管理详情查看统计 | POST生成固定快照后进入快照详情 | 原生toolbar的flow/api.request及target状态；后端补记录到已封存版本的受控选择 | 菜单→公告详情→统计；准确版本/asOf，非GET写入 |
| 阅读汇总 | 同一快照metadata/summary；范围说明及筛选人数独立 | metric-strip的dataSource/valueField、空值映射；原生详情分组 | N/R/A、零分母、不适用、legacy未知与成员失效逐项断言 |
| 筛选与切换版本 | 姓名、全部/未读/待知悉/逾期、已授权历史部门；每次生成新快照 | 原生dialog.form/select及API数据源；部门选项须来自固定展示证明，不借用需要publish的受众目录入口 | 保存和取消、空结果、清空筛选、分母不变；完整payload与快照结果 |
| 分页接收明细 | 使用固定snapshotToken、准确分页total | 原生table显式server分页与pageNum/pageSize；拒绝缺失/矛盾的分页元数据，状态字典和时间格式化 | 翻页不漂移；显示名单标签，不泄露成员标识或组织JSON |
| 接受导出 | announcement:export_statistics，稳定operation及snapshotToken | 既有命令管道、幂等请求状态和新业务处理器 | 真实命令HTTP与DB审计；不确定网络重试只创建一个任务 |
| 查看导出进度 | 同一任务的状态、进度、版本/asOf/筛选、行数、格式和到期 | 原生虚拟详情、显式受控GET；补最小公开任务来源描述 | pending/running/completed/failed/canceled/expired及刷新后状态 |
| 下载CSV | 只使用服务端受控URL和Content-Disposition | 优先复用既有文件链接及downloadWithAuth；需要时补通用文件下载action，不写公告专用React页 | 从原生按钮/链接实际下载，独立解析文件及权限拒绝 |

| 页面 | 角色与任务 | 一屏布局、主次动作及字段顺序 | 引导与重要状态 | 截图场景 |
| --- | --- | --- | --- | --- |
| announcement_statistics | 有stats权限的当前管理者核对准确版本与接收事实 | 公告标题/版本/统计时间/授权范围；N/R/A及阅读率/知悉率；筛选人数与失效/逾期信息；接收明细。主动作“导出当前明细”，次动作筛选/切换版本/重新统计/返回公告 | 姓名输入给出可搜对象；状态/部门使用选择器；解释筛选不改历史分母。加载、空结果、无权限、快照过期、源异常、legacy未知均有清楚提示 | 正常名单、筛选后空、零分母/无需知悉、legacy、失效成员、撤权与过期 |
| announcement_statistics_export | 有export+stats权限的原任务所有者取得固定文件 | 公告/版本/asOf/筛选摘要；任务状态与进度；行数/格式/保存期限；文件与失败说明。完成后主动作下载，次动作刷新/返回统计 | 刷新只读取当前任务，不重新生成文件；失败时说明回到统计后创建新导出。当前权限收回后拒绝旧文件；不显示key、hash、PID或原始异常 | 待处理/运行、完成下载、失败/取消、过期、撤权拒绝及实际文件解析 |

原生导出命令在eeec4c958已通过两项真实集成（业务接受/幂等/绑定/撤权重放，以及既有持久队列HTTP下载回归）。接受操作只写任务、操作账本及同版本审计，不修改公告内容或增加编辑revision；重复操作先重新授权完整快照，再读取原结果。该轮仍是领域服务和下载HTTP证据，新命令的统一命令HTTP入口、插件导入和页面旅程须随本蓝图实际验证。


原生页面键为`announcement_statistics_detail`与`announcement_statistics_export_detail`，分别使用`/p/announcement_statistics/view/{snapshotPid}`及`/p/announcement_statistics_export/view/{taskPid}`。管理详情先POST生成快照再进入统计页；导出页以受控任务PID读取状态及文件事实。公开标题来自业务标题，版本、统计时间、名单来源及授权范围单独展示；内部PID、对象key、摘要及组织JSON不作为用户字段。指标与口径直接消费同一主记录，翻页只读取固定快照。部门选项来自已封存的展示证明，版本列表只含有接收名单的版本。导出按钮使用统一命令入口，只有受理成功才清除本次会话请求键；下载动作复用带认证的文件下载，并校验CSV类型、附件文件名及非空内容。

通用表格新增显式`pagination.mode=server`契约，消费真实records/total/pageNum/pageSize；失败重试保持准确请求参数，瞬时失败保留上一页并显示重试，401/403隐藏旧名单和分页。无server配置的既有表格维持原行为。该接缝在d05580d80的48项定向单测通过；94597c9d1的146项定向单测和独立7项下载动作单测通过，类型检查通过。插件校验0错误、4条既有详情块类型建议警告；页面UX静态检查8页0错误0警告。静态、类型和单测均不替代页面旅程。后续真实浏览器已覆盖菜单进入、固定口径、筛选空结果与清除、统一命令HTTP、实际下载及不确定网络重放；完整状态、权限和视觉矩阵仍未关闭。导航表达式曾在真实FlowRunner两项反例先红，修正后连同相关模块272项定向单测通过；后续数据源、路由、表格的104项定向回归通过，不能将重叠用例相加作为覆盖率。


数据源显式声明`adaptor=raw`，避免平台默认下拉适配丢失records及分页元数据；分页契约校验继续拒绝伪空页。标准详情路由使用带`_detail`后缀的页面键，并按页面/记录身份创建独立渲染实例，防止把新快照PID送入上一公告页的正文/审批请求。原生目标授权器在权限检查后、幂等缓存读取前接收原始payload用于快照绑定验证；payload本身不授予权限，授权器保持只读。该公共接缝已由真实统一命令入口验证，不要求OSS依赖BPM实现代码。

导出状态仍依赖有效期内的队列事实，因此手动删除`domain-export`终态队列须等待共同定义的24小时保留期，缺少创建时间时拒绝删除。真实事务在任务执行前取消导出，随后HTTP删除返回拒绝，取消状态仍可查询且没有生成尝试；这不等于上传中取消或清理并发已通过。50项相关单测及2项真实数据库/HTTP用例在a1b827a69通过。

同一源码的实际浏览器旅程从菜单进入统计，经统一命令创建任务并下载CSV；另一次将服务端成功响应在网络边界丢弃，刷新后保持原快照重试，断言请求身份及任务PID相同、命令返回幂等重放，数据库只找到一条绑定该快照的导出。实际文件由Python标准库独立解析：15列、3名接收人，阅读/知悉状态及时间与固定事实一致，冻结时区为Asia/Shanghai，字节数和持久摘要一致。CSV列遵循§16.4，不把任务元数据asOf误称为明细中的独立列。网络提示本地化、空部门/角色说明及时间列折行已在efaa3e65c修正，并通过245项定向前端单测、类型检查和真实浏览器重验。9张原图已逐张复核；时间文本保持单行，表格横向滚动后成员/投递列可见，网络丢响应重放与实际CSV校验继续通过。完整状态与权限矩阵仍未关闭，本节不声明S8或完整公告验收完成。定向证据、未执行项及完整范围分母见[持续验收记录](./2026-10-25-announcement-s2-s8-testing-gate-acceptance-report.md)。


原生旅程已固化为`web-admin/tests/e2e/announcement/announcement-statistics.spec.ts`，自行通过正式API创建仅面向当前用户的发布/已读前置数据，统计、筛选、导出与下载使用实际页面按钮；完成后走正式撤回命令清理前置公告。cd5ce8296上该用例与既有12条公告/政策回归全部通过，共13条、无跳过或重试，认证setup不计入业务数量。仓库用例下载为15列1行，并核对固定状态、时区、持久摘要及同快照仅一条任务；8张合成数据原图已逐张复核，见[公开验收裁决](./evidence/announcement-s2-s8-2026-10-25/manifest.json)。这些证据只关闭D11/D14的上述行动点，不替代剩余催办、BPM组合、附件及权限/异常矩阵。


### 18.16 持久催办候选与精确接口

催办资格与既有日历在55f63c026通过19项单测（9项资格、10项日历，无跳过）。资格层明确：迁移未知状态不作未读；OUTSTANDING合并未读与未确认；两种模式从意图受理时共同去重；受理后完成或成员失效在消费时跳过；消费自己的意图不再次被窗口拦截；知悉截止相等停止自动，公告有效时仍可手动。此为规则层证据，不代表持久催办或页面验收完成。

持久候选新增Core151迁移：`ab_announcement_reminder_plan`绑定原发布任务、原主体与自动额度；`preview`绑定当前用户/member、版本、root revision、筛选、人数和30分钟有效期；`batch`固定AUTO/MANUAL、当地日期、目标、筛选、policy/scope hash、受理人数及预览差额；`guard`以tenant/version/user主键存最后意图时间。批次身份不可变，只有accepted→blocked控制转换；自动日唯一键包含skipped扫描，零受理不消耗三次额度。新发布同事务建立计划；升级只为有正式发布原主体及冻结政策的活跃publication版本登记计划，不迁移未知阅读事实、不补发历史提醒。

预览接口为`POST /api/announcements/records/{recordPid}/versions/{versionPid}/reminders/preview`，请求复用`query/state/departmentPid`；返回previewToken、revision、filters、eligible、throttled、evaluatedAt、expiresAt，不接受客户端名单。受理走统一命令`announcement:remind`，只接收previewToken，root锁内复核当前动作/范围、原member、有效版本、revision和资格，再返回previewCount/actualCount/throttled、batchPid及accepted/skipped。`announcement:resume_reminders`只接收versionPid，仅原主体当前同member可显式恢复blocked计划；达到额度/截止或失效时不可恢复。两命令operationId沿用现有事务账本和审计，重放前仍检查当前身份与范围，重放不增加提醒与额度。

自动扫描复用根锁、原发布主体和冻结时区/政策，只处理当天到期时间；恢复记录resume_after，早于恢复时点的周期不追补。当前管理范围必须覆盖整个固定受众；消费复查当前原member、动作和精确scope hash，范围收缩、变化或失权阻断原批次，不能悄悄缩小受众或切为系统身份。管理员可用自己的权限创建手动批次，但不能接管原自动主体。接收执行使用既有`ab_announcement_delivery`，500人分页扩展至同一可靠outbox，催办不重复创建知悉Inbox任务；通知持久化成功显示“提醒已生成”，不是已读或用户收到。

Core151已从空库实际执行151条迁移并生成快照；增量升级、独立drift、真实受理/消费、自动并发额度、撤权/恢复和原生页面证据仍在本任务内补齐，当前候选不得记为S8完成。BPM职责和公共审批契约不变，催办运营不进入流程引擎。


### 18.17 催办页面蓝图与验证边界

沿用统计详情的当前版本/筛选，新增“预览催办”次要入口，不直接发送。预览详情使用既有DETAIL家族、只读指标与record-inspector，顶部说明30分钟有效、提交重算、手动/自动共享去重窗口及“提醒已生成”的语义；展示预计可催办/限流人数、评估时间、筛选、知悉截止和冻结时区。主动作“确认催办”先打开原生确认对话框，再以previewToken和稳定operationId提交统一命令；成功进入批次详情，丢响应保留原预览身份重试。零人数/已过期隐藏提交并提供重新预览；返回统计/公告均由授权版本导航，不暴露内部PID。

批次详情沿用DETAIL家族，主信息是实际受理人数与完成/排队/跳过/失败数量，明确completed仅代表通知持久化。展示预览/实际差额、自动/手动、受理时间、冻结时区和状态说明；刷新同一批次，不能重发。自动计划详情显示三次额度、当前active/blocked/completed/canceled及冻结政策，blocked提供原主体受控“恢复自动催办”确认动作，已到截止/额度或公告无效不可恢复；管理员可以手动催办但不能接管主体。版本失效后状态页仍供当前授权管理者查看历史，提交始终拒绝。

仅新增DSL配置页及受控API，不新增业务React。页面必须覆盖实际菜单→统计→预览→打开确认/取消→受理→批次刷新，以及零人/限流、失效/撤权、网络重放和恢复边界，逐张复核原图；尚未执行的矩阵不得用后端通过代替。

b258e9c21上19项真实PostgreSQL/HTTP/可靠outbox集成通过，无skip，包括原有统计/导出回归与9项催办场景。自动场景使用仅测试注入的主机时钟，数据库与系统时间不变；实际验证每日一次/三次正批次额度、手动共享窗口零批次不计数、截止相等停止自动但手动有效、两线程同时扫描只创建一个批次/一条意图，以及受理后撤权消费跳过。该证据不替代正式调度入口、恢复、全部权限与页面原图验收。


管理详情的发布信息改用受控`GET /api/announcements/records/{pid}/management-summary`：必须有read及记录管理权，仅返回publisherName和publishedAt，名字来自该租户成员账户，不能将内部published_by数值作为显示文本。根页面用原生只读事实表展示，未发布/不可显示名字有明确空文案。统计、导出及三类催办页统一返回`/p/ab_announcement/view/{pid}`；详情schema键不能代替路由表键，否则会再次拼接_detail。18625d766上22项真实集成通过，新增人名投影HTTP断言；五类返回按钮仍须真实浏览器复验，不能只依据配置审计。


c1921cd6b上已执行全部四份公告浏览器spec，共14条通过，无skip/retry；认证setup单独1条。真实按钮验证催办预览→确认/取消→受理后丢响应→同预览刷新重放→同批次→通知持久化→刷新保留→自动计划→去重窗口0人，且统计、导出及三类催办页均能正确返回管理详情。当前限流状态明确显示“去重窗口内，暂无新增提醒”，零待办显示“当前无需催办”；原正预览的请求重放仍可使用，不能因自己的意图触发限流而隐藏重放入口。管理页上次发布人显示Admin等可读人名，不显示内部数字ID。22项真实PostgreSQL/HTTP/outbox集成及类型检查在同一源码通过；Core151空库、150增量及独立drift证据保留。

本轮8张催办/发布信息与8张统计/导出原图已逐张复核，见[催办与统计的公开裁决](./evidence/announcement-s2-s8-2026-10-25/reminders/manifest.json)。旧只读权限浏览器夹具因跨租户同名权限而失败后，改为按新建角色的tenant精确查询并断言两项权限；保留其他租户数据和原失败，不清库规避。完整批次/渠道历史、恢复与成员替换的全部UI状态、调度观测/容量、导出清理/并发、附件及严格BPM组合仍在D01–D15分母内，本轮不声明完整S8或公告需求已交付。


### 18.18 批次历史与计划终态补齐

自动计划详情新增同版本批次历史，按受理时间倒序，分页20/50/100；展示自动/手动、受理状态、实际人数与queued/completed/skipped/failed，行操作进入准确批次。它用于旧预览失效后的既有提醒核对，刷新/翻页/查看不重发。接口为`GET /api/announcements/versions/{versionPid}/reminders/batches?pageNum=1&pageSize=20`，remind+记录管理权及整个封存受众当前范围检查；历史公告仍可授权查阅，元数据不输出操作者内部ID、scope/policy hash。列表总数和当前页在同一只读快照计算，防止新增批次导致分页元数据自相矛盾。

管理详情的“自动催办计划”先取`GET /api/announcements/records/{recordPid}/reminders/plan`，仅选择该记录最近一次真实发布的计划；新申请尚未发布时不将其未封存version当成计划，也不默默切换到另一公告。不存在时在原页解释“尚无已发布计划”，不跳错误页。统计入口仍绑定准确历史版本，不能切到新版。

撤回和到期在原根锁/事务内停止active或blocked计划；已完成额度保持历史完成事实。扫描失败的阻断写入必须同时匹配active与当次resume_after；过时的失败不得覆盖已撤回、已完成或显式恢复后的新计划。此处复用既有恢复时标作为控制栅栏，不新增自动主体接管或隐式修复。

19fce7258的催办历史浏览器验证通过：26个持久批次，第一页20条、实际翻页后6条，历史行进入最初批次且仍仅1条提醒意图；既有生命周期/政策12项同版回归通过。统计旅程在产品代码相同的6ab56e451通过，分别保留轮次。25项真实集成在原安全源码通过；刻意移除active/resume_after栅栏时2项反例均产生断言失败，恢复命令返回同源码的Gradle缓存结果，不记为重新执行。707da9c92新增置顶后实际执行26项真实集成及98项hermetic单测，均无失败或跳过，包含前述恢复保护场景。历史原图已逐张复核；第一页缺底部分页器取景，补证仍待完成，不声明完整视觉验收。

### 18.19 已发布置顶与审批核对实施契约

D01/D05管理入口补齐：`announcement:set_pinned`只接受布尔pinned，edit+记录管理权、根revision和operationId；仅有效已发布根可用，变更根排序元数据及审计，不修改封存正文/版本/hash/接收者，不产生新通知。接收列表使用当前有效根的置顶排序，历史冻结快照保留发布时值。

`announcement:reconcile_approval`需要manage及当前记录权、根revision和operationId，不接受审批结果。仅当前pending_approval的准确submission可请求重新核对；在根锁→申请锁内递增租约栅栏，重新排队同一申请并审计。异步处理仍通过固定provider及公共STATUS契约核对，不创建新审批、不改写终态，不绕过原操作者/成员/范围复核。提供方不完整或更换时拒绝；旧worker不能提交。两项原生按钮均提供确认及刷新后的持久结果；候选实现和验证分别记录。

能力查询`GET /api/announcements/capabilities`要求当前publish成员，返回实际可用模式、稳定原因、全员资格、站内渠道就绪、小程序未配置及平台当前上传字节/数量限额；它仅提供引导，不能替代命令鉴权。`GET /api/announcements/approval-readiness`要求submit+publish，只解析租户默认定义，缺提供方/缺默认值/定义不兼容返回可解释未就绪，成功仅输出准确定义pid/version/name，不输出BPMN、凭据或摘要。发布者只能使用租户默认流程，不可选择单条覆盖；审批目录仅供租户流程管理员维护政策绑定。

### 18.20 通知桥接的执行时可用性

D07执行边界补强：已受理任务不能沿用受理时的宿主开关。发布worker在当前根锁/租约复核后、任何封存受众或通知写入前，重新检查通知桥接就绪；已停用则保留原任务的阻断事实，不能发布不可分发公告。审批同步仍可查询和处理已有真实终态、执行撤销；尚未派发的申请在调用START前同样复核开关。恢复不得自动更换原主体、申请或正文。

实际反例5276c7749在受理后关闭开关，旧执行器仍提交发布；测试按预期断言失败，原任务随后通过正式撤回清理，原始失败与发布审计保留。候选修复及更晚验证分别登记，不将反例运行记为正常通过。

5cfd5fcd0实际执行104项单测、28项真实集成及15条仓库浏览器场景，均无失败、跳过或重试。新增置顶旅程经过确认取消、准确载荷、刷新后保留、取消置顶及快照/通知/审计核对；两张新增原图逐张复核。BPM首次派发开关仍需最新严格组合链路验证，不能以纯OSS集成替代。

### 18.21 发布任务状态与显式恢复

D04/D07管理详情补充当前准确版本的发布执行状态、原计划时间、最晚执行时间、累计尝试数、上次尝试结束时间及可解释的上次异常。恢复保留上次尝试结束时间，不把历史失败当成当前成员仍然失效。`GET /api/announcements/records/{pid}/publication`要求read+记录管理权，不输出原操作者内部ID、授权摘要、租约或底层堆栈。无发布任务时返回空记录，不伪造成功。

`announcement:retry_publication`以当前根revision及operationId定位准确原任务，不接受新正文、范围、时间、主体或批准结果；因此不另接受可替换的releaseAttemptId。仅blocked/retryable_failed且仍在有效执行窗口内可恢复，当前调用者须publish+记录管理权，通知渠道须就绪。原job/version/actor/member/批准或免审依据全部保留，重排队时递增栅栏并清除旧租约，不重置累计尝试或延长窗口；原执行器不能再提交。恢复受理不保证发布，worker仍复核原主体、成员、完整权限、受众和附件。终态、超窗和已发布任务不重开。

页面在原详情中显示执行记录，仅可恢复行提供确认；取消不写入、网络不确定保留原请求身份，成功后刷新根与任务事实，不以toast替代实际发布。临时停用后恢复同一用户/成员可重试，退出重加或授权依据变化不能通过恢复自动接管，须按正常流程重新提交。

### 18.22 默认审批绑定与发布方式可用性

依据§6.3及§9.2的既定产品决策，公告发布者不能选择任意流程。`/policy/approval-options`仍由policy_manage保护并用于租户默认绑定；发布者的`/approval-options`仅提供`tenant_default`这一明确意图和当前默认流程名称/不可用说明，不返回目录、流程证明或其他流程。新草稿policy中的非空definitionPid以及普通表单任意流程覆盖均拒绝；历史已冻结snapshot继续可读。历史草稿如存在覆盖值，必须在通知设置中明确选择租户默认后保存，服务器再清空该覆盖，不静默代换。审批提交同时复核无覆盖并固定当时租户默认DefinitionRef；后续政策变更不改写既有申请。

`/approval-mode-options`由publish保护，使用同一当前成员/权限/政策/提供方/默认流程就绪判断，原生segmented展示两种方式及本地化不可用原因，不可用方式不可选择。未接入BPM、未配置默认流程或默认流程失效不会广告为审批就绪；缺免审权限或强制审批不会广告为免审就绪。已有审批值不自动降级为免审；通知设置无法提交失效选项，正文草稿仍可独立保存。该查询只做引导，提交命令继续完整鉴权。

1fc8cb42b上本节默认绑定/能力查询通过111项hermetic单测及33项真实PostgreSQL/HTTP集成，无失败/跳过；原任务恢复浏览器1条与其余15条是此前2e248b1c9的准确证据，不借用为新页面验证。BPM组合与本节原生模式控件仍待配套复验。审批组合测试改为通过政策入口配置默认流程，继续覆盖默认变更不影响已受理申请和首次派发阻断后的原申请核对恢复。

### 18.23 操作核对、渠道历史与监控

操作账本的唯一空间是tenant+actor+commandCode+operationId，查询必须携带准确命令，不能仅凭UUID猜测动作。查询复核当前有效成员与对应动作，submit另需publish、export另需stats；仅本人原成员可核对，当前成员创建时间晚于操作则不继承旧操作。结果仅给found、命令、操作身份、受理时间、accepted/completed和记录/版本/任务引用，不回传requestHash、scope、member或原始result。事务未提交、回滚或不存在统一unknown，不能推断失败或触发新操作；accepted也不表示异步发布/导出已完成。已删除自有草稿仍能核对最小操作结果。统计页“核对导出请求”只读取本会话原UUID，未存在则解释无待核对；已受理则打开原导出任务，未知则保留请求，不分配新键或清除原意图。

渠道页蓝图：现有DETAIL家族，接收统计→渠道记录→固定version；指导文案、刷新/返回公告、原生服务端分页表。`GET /api/announcements/versions/{versionPid}/delivery-summary`提供标题/版本/公告引用；同版本`/deliveries`使用20/50/100分页，准确版本与每次当前stats范围筛选，count/rows/asOf同一只读快照。名单来自不可变接收事实，按受理时间倒序，展示人名、in_app、发布/催办、queued/completed/skipped/failed/canceled及时间、稳定原因。历史已撤回/到期可授权查阅；刷新不重发，缺记录不是伪报成功，未配置小程序没有发送记录。completed统一显示“提醒已生成”，表示通知持久化，不能称设备送达或已读。内部异常一律归稳定公开原因；UI字典不回显SQL/堆栈。

监控复用Micrometer及可靠outbox，不另建队列。公告固定stage标签的pending/failures/oldest_pending_age_seconds及24小时已完成发布平均延迟来自持久事实；区分发布、审批同步、通知处理、催办阻断、发布展开、分页展开、通知投影、知悉Inbox投影和催办展开。失败指标为当前故障存量，不是历史失败计数；等待审批人处理不等于同步故障。跨租户仅输出宿主聚合数，不输出租户、人员、公告或流程标识；数据源失败显示NaN，不能伪报0。可靠传输DLQ及租约复用平台既有指标。导出失败/容量测量仍在D11/D14分母内，不能凭这些新增指标宣称全部观测已完成。

Core152仅新增渠道历史与待处理年龄索引，不改写公告事实。空库152迁移、独立快照drift及原任务auraboot_1的151→152增量（另含5条BPM迁移，共157条）已执行成功并validate。3bf2fdfd1上117项hermetic、35项真实集成、17项前端单测通过；原生通知设置/统计导出及新增操作核对/渠道页2条旅程通过，无skip/retry。渠道原图列换行已修正，c5b1fe1dd全部16条公告浏览器回归（2.8分钟，无skip/retry）、单行断言及新原图复核通过；后端与3bf相同。容量、附件、最新BPM组合及剩余D01–D15仍未闭合。静态校验不当浏览器证明。

### 18.24 附件、阅读反馈与容量补强的当前实证

正文编辑复用平台富文本组件；附件遵循服务端当前上传上限、最多10项，保存稳定fileId。接收人通过准确版本的受控链接下载冻结文件。图片加载失败显示错误与重试，不记录已读或开放知悉；恢复后才提交准确snapshotHash。知悉按钮位于正文与附件之后，键盘取消返回原触发按钮。

8a8349e0c上的17条真实公告浏览器旅程全部通过（认证另计，无skip/retry，3.0分钟），包含原生上传、富文本持久化、普通接收人下载、保留文件删除拒绝、正文故障与恢复、刷新幂等及390像素确认交互。5张原图已逐张复核，见[附件证据](./evidence/announcement-s2-s8-2026-10-25/attachments/manifest.json)。铃铛错误反馈、焦点和富文本相关19项前端单测及类型检查通过；原生铃铛故障仍待验证。

10a8b2aaa上4项真实数据库/HTTP用例通过，包含501名成员跨500人展开批次、统计分页与CSV全名单，缺失/短/长/同长篡改文件拒绝，以及取消后迟到上传与保留清理重试。501人测量：发布335ms、投影5526ms、统计163ms、导出282ms、CSV127920字节；不推断50000人吞吐。首次清理反例暴露PostgreSQL nullable参数类型错误，修正后通过；首次失败保留。此前26项容量/正文/指标单测通过，新增上下文的完整后端回归尚待执行。

受众分页、最新BPM组合及原生严格审批、生命周期保护和完整D01–D15覆盖审计仍待完成，不据此声明全部S2–S8交付。

### 18.25 分页选择、共享提醒入口和组合应用生命周期

受众选择复用DSL多选表单的分页能力，`GET /api/announcements/audience-option-page`接受kind、query、pageNum、pageSize与可选values。默认25、最大50项；query最多100字符，values最多50项，已选项分批回显。搜索与回显均重新核对当前租户、有效成员、发布权限和管理范围，不返回越权名称。旧audience-options仅保留kind模式选项，人员/部门/角色改走分页契约；部门必须整棵子树获授权，角色仍只覆盖当前管理范围成员。跨页选择保留，身份变化清除旧查询/名称，迟到请求不能覆盖当前状态。

854ecf0ec上133项hermetic、39项真实数据库/HTTP集成通过，无skip/retry，包含501成员目录分页完整遍历、最多50项限制和授权回显。528d9799d上的161项定向前端测试通过；其首次后端测试因新增测试括号错误未执行，修正后才形成上述后端证据。49f74a882上3条定向浏览器旅程通过（1.1分钟，无skip/retry）、23项前端测试与类型检查通过，包含正文/附件、受众搜索及重开回显、定时、全员投递、普通成员铃铛故障恢复、实际全部已读及知悉待办独立。新增3张窄屏原图已逐张复核，见[提醒入口证据](./evidence/announcement-s2-s8-2026-10-25/attachments/manifest.json)。待办按请求代次过滤迟到结果，全部已读后重新读取当前事实；Escape只在面板打开时归还焦点。1e9298341上新增27成员原生分页反例及全部17条公告浏览器回归通过（3.0分钟，无skip/retry，认证另计）：实际翻页、跨页勾选、清空、保存与重开回显均有断言；26个临时成员经正式batch-status接口停用，未用SQL制造结果。分页原图已逐张复核。

OSS不包含BPM业务代码。宿主识别通用ApplicationModuleExtension，以及导入manifest的metadata.lifecycleScope=composition：此类共享应用不能通过租户插件入口停用、卸载、热停止或热替换；拒绝发生在前端资源、注册表、数据库状态等第一次变更之前，不能把拒绝写成FAILED。BPM声明composition生命周期；普通主机/整栈停止保留。模块维护与升级使用组合部署流程，移除仍需业务停用、在途处置及历史可读/迁移证明，租户插件面板不提供强制绕过。dc24c5a14上的134项定向测试通过，包含实际JAR拒绝热卸载且正常cleanup仍能停止。最新正式BPM组合和真实HTTP停用保护尚待验证。

### 18.26 当前交付、组合安装与证据边界

本节取代历史切片中的未完成描述；验收事实统一来自[当前报告](./2026-10-25-announcement-s2-s8-testing-gate-acceptance-report.md)与[同一证据索引](./evidence/announcement-s2-s8-2026-10-25/manifest.json)。没有待用户决策的本期产品取舍。三个 PR 开放待审，不合并、不执行生产发布。

#### 实施结果与所有权

| 切片 | 当前实现 | 验证入口 |
| --- | --- | --- |
| S1–S2 | 受控命令、不可变版本/成员身份、人员/部门子树/角色/全员权限、分页选择、富文本与保留附件 | 公告生命周期及附件 browser；133 项单元与 39 项真实集成的准确源码证据 |
| S3 | 冻结受众、立即/定时、租约 fencing、取消/撤回/修订、原发布任务显式恢复、可靠 outbox | 生命周期/催办 browser；发布竞态及 501 成员真实集成 |
| S4 | 引擎中立五操作族、可信绑定、冻结业务预览、可靠终态及原申请核对 | platform-plugin-api；BPM 组合 9 项真实集成 |
| S5 | 人员/角色 ANY/ALL、自审排除、无人阻断、驳回必填、撤销、原节点恢复、原任务恢复和剩余审批人替换 | 严格引擎 10 项集成；3 条严格公告 browser |
| S6 | 纯 Core catalog、显式公告组合、校验锁/摘要、导入顺序、共享应用生命周期保护和正常整栈停机 | 正式组合构建；真实 HTTP 保护；正常停机退出 0 |
| S7 | 我的公告、准确版本正文、首次已读/知悉、铃铛与待办独立、工作区可达、身份切换代次与窄屏/键盘反馈 | OSS 全部 17 条公告 browser；共享组件单元与原图 |
| S8 | 手动/自动催办、每天一次/最多三次/截止停止、去重与撤权、原计划恢复、版本渠道历史、固定统计快照、异步 CSV 与下载重新授权 | 催办/统计 browser；真实时序/并发/文件/授权集成；原计划强化复验 1 条 |

OSS 只拥有公告领域、通用通知/待办和公开 API/SPI，不包含 BPM 引擎、BPM 表查询、产品前端或产品迁移。BPM 只通过公开提交来源和查看资格读取冻结视图，决定审批事实；公告独立决定是否仍可发布。CRM、EDU 可以实现自己的提交来源及只读投影，消费同一五操作族，不能复制公告 mapper 或把 BPM 产品源码放入 OSS。

严格审批恢复使用 BPM 的既有 DSL 页面和成员选择器：`resolve_node` 只处理未封存节点；`resume` 重排原阻断任务；`replace_assignee` 只替换未提交意见者。操作均要求当前有效管理员、`bpm.monitor.update`、operationId、state revision 和理由。已接受的 ALL 意见、原定义、原快照与原申请保持不变；恢复不会补写批准。任务查看与已办历史只读，决定入口单独打开审批表单。

#### 可执行组合安装

先在冻结的 OSS checkout 产出 Core catalog：

```bash
node scripts/application/stage-core-only-artifacts.mjs \
  --repo-root . --output /absolute/path/to/new-core-catalog --skip-oci
```

再在冻结的 Aura BPM checkout 消费该构件目录：

```bash
node scripts/build-application.mjs \
  --platform-artifacts /absolute/path/to/new-core-catalog \
  --output /absolute/path/to/new-announcements-release \
  --composition announcements --skip-oci --offline
```

构建前按 BPM README 提供显式 TEST 数据库参数；数据库须为本任务的开发/验证数据库。不得把示例目录直接照抄为共享目录，不从兄弟源码隐式重建 Core。输出包含 manifest、catalog、lock、SBOM、client/SSR 和 `bin/aura-bpm-env.sh`。本轮 Core 为 152 条迁移，组合增加 5 条 BPM 迁移，共 157 条；迁移按版本正常前进，不开启 out-of-order，也不手工修 schema。

复用产品公开 `preflight` → 部署迁移 → `start` → 新租户显式 `init-core` → `publish` → `verify` 生命周期；准确参数与凭证注入见 BPM README/公开脚本。`init-core` 只用于明确初始化，不在每次启动或登录时执行。租户流程管理员发布受支持的人员/角色审批流程，公告政策管理员在“公告政策”绑定唯一默认流程，随后核验 readiness。普通发布者不维护流程目录；单条公告不能覆盖租户默认定义。缺提供方、缺绑定或能力不全时草稿保留、审批不可用；不自动免审。

升级使用停止前预检、停止受管整栈、验证新锁与兼容性、向前迁移、显式发布配置、启动核验的顺序，保留原定义/申请/结果/附件/导出事实。共享应用不允许从租户插件面板热停用、卸载、回滚或替换；拒绝在第一次变更前发生。应用整体停机可用。移除组合仍需在途处置及历史可读/迁移证明，不能删除 BPM 后留下失联历史。

#### 当前公共入口与操作核对

命令目录为 `plugins/core-announcement/config/commands.json` 的 16 个命令，页面目录为同插件的 12 个页面。领域写入全部经过统一命令校验，接收者阅读/知悉使用独立受控 HTTP 入口；所有接口复核当前租户、成员身份和领域权限。

| 接口族 | 当前入口/实现 |
| --- | --- |
| 能力、政策、受众、原操作、发布、渠道 | [AnnouncementController](../../platform/src/main/java/com/auraboot/framework/workbench/controller/AnnouncementController.java)；§16.7，另含 `/audience-option-page`、`/operations/{operationId}`、`/records/{pid}/publication` |
| 接收、阅读、知悉、附件 | [AnnouncementReaderController](../../platform/src/main/java/com/auraboot/framework/workbench/controller/AnnouncementReaderController.java)；`/received`、`/versions/{version}/recipient`、`/read`、`/acknowledge`；附件区分接收版本与送审申请 |
| 固定统计及分页 | [AnnouncementStatisticsController](../../platform/src/main/java/com/auraboot/framework/workbench/controller/AnnouncementStatisticsController.java)；先捕获 snapshot，再读取汇总、部门选项和 recipients，不用实时读数拼接历史 CSV |
| 催办预览、批次和计划 | [AnnouncementReminderController](../../platform/src/main/java/com/auraboot/framework/workbench/controller/AnnouncementReminderController.java)；预览不扣额度，正式命令重算；`resume_reminders` 保留原主体/版本/额度，不补发错过的日期 |
| 公共审批 | [ApprovalContract](../../platform/platform-plugin-api/src/main/java/com/auraboot/framework/plugin/extension/approval/ApprovalContract.java)；readiness/start/status/withdraw/viewer 五操作及 `workflow.approval.terminal.v1`，DTO 不含引擎类型 |

超时先按 command+原 operationId 查询，不生成新键掩盖不确定结果。发布恢复、自动计划恢复及审批恢复是不同领域动作；均先重查原身份与当前资格，取消确认不写事实。导出与催办的成功不等于已读或知悉，发送日志不等于小程序实际送达。

#### 验证裁决及未执行边界

Core 实际 17 条公告 browser 通过；强化后的原计划恢复另跑 1 条，不与 17 相加。BPM 可选 profile 固定 4 条（3 条严格公告与 1 条既有项目审批），实际 4/4、单次执行、无 skip/retry，collection/result 对账通过；认证各自另计。本轮 BPM 正式构建为 `41c1ffc44022e1ef3a3634e69e850c90ff66c3e4`，消费 Core `6763dee6f2ef82c8d0a80eca4066309c378f0e1a` catalog，22 个构件 lock 为 `sha256:7b6f443f8f05137d53ad4686556b54df2bc0a6972319823946db93c4df1c773b`。完整命令、JUnit、源等价、原图及失败轮次见报告。

以下不由本轮绿色外推：小程序真实发送/真机，跨服务远程 BPM，50000 人容量，BPM 独立产品 340 条 corpus，OCI/Linux 镜像、Jenkins 和生产发布。真实容量只测到 501 成员。全仓静态覆盖登记 executed=0，不能改成执行通过；公告组合的人工语义绑定也不宣称已自动执行需求级全覆盖裁决。

BPM Workspace 核验仍有 `RUNTIME_MANIFEST_MISSING` 警告；本轮通过独立产品 verify、源码/构件摘要、listener/PID/cwd、BFF 实际请求及数据库身份共同证明该独立产品栈，不能写成“零 warning”。Workspace CLI 的 Gradle cache 路径前缀匹配缺陷尚未修改，两个 runtime 的生命周期和构建必须串行；此前因此中断的构建不算通过。两个任务环境已正常暂停，DB、构件和证据保留；本地地址为 OSS Web 5101/Backend 6401、BPM Web 5401/Backend 6501，暂停期间不提供在线页面。

小程序快速接入的具体边界是平台公开 `NotificationDeliveryAccessor.Request(tenantId, resolutionReference, channel)` 与 host `NotificationDeliveryPlanResolver`：公告已经提供 `ann1` 准确版本/接收人解析，渠道只接受宿主解析的名单、内容与来源，不能信任客户端自报。当前 `WECHAT_SUBSCRIPTION` 明确返回未配置，capabilities 中 `wechat_mini` 不可选。后续接入仍需实现账号/订阅授权、模板与实际发送 provider，并接通渠道就绪和可选项；复用现有可靠队列、幂等键、结果审计、版本深链接及跨端 read/acknowledge，不重做公告状态机。本期没有交付微信 SDK、配置即用的真实 sender 或真机验证。

### 18.27 授权吸收 main 后的当前集成裁决

本节取代 §18.26 的“当前验证版本”，不改变公告业务契约。用户明确授权在当前任务分支吸收最新 main、完成集成回归并更新 PR，暂不合并。OSS 任务分支已合入 main `9aab94663e73deaf9aab4594f27a5c4fa9bf11a3`，merge commit `8b81ed4591ee`；未追随验证期间的新 main。三个冲突分别是中英文语言资源和生成的内部 API 类型：语言键保留双方并集，类型从合并后的 OpenAPI 正式重生成并通过一致性校验。上游 `HandlerPhase`、表单呈现、可靠队列和文件租户隔离的自动合并也已纳入影响检查。

| 当前输入 | 确切身份与结果 |
| --- | --- |
| OSS 源码 / Core catalog producer | `5f11717d0a55dfd70c8988045055430cd6c3ce07`；后续交付仅更新文档/证据及等价注解 import 写法（见下文） |
| Core catalog | `core-catalog-main-5f11717d0`；15 artifacts / 18 catalog entries；lock `sha256:4b2aed4bcd046413456a1c0a5608b29e43bae31c6429a88e7f4647268ef22d92`；graph `sha256:fb308ab0b0ed17c3cf2d8c7410b8cfad954cee1905cf303de78419389fd1b136` |
| BPM producer / browser source | `3861a3146caf07a16b0937971e4cba5c2433e8de`；组合显式消费上行 Core catalog，不隐式构建兄弟源码 |
| BPM 组合 | `bpm-composition-main-5f11717d0-scheduler`；22 artifacts；lock `sha256:e1532f66c916ffd4eee31324aa818482f02825ab9c09f99735e6bc83fc8fb898`；graph `sha256:58fcb7f322a353d3a78ecb1192753a45648ad237b0a876633688b984741c4baa` |
| 当前数据库 | OSS 公开 fresh reset 后纯 Core 153 条迁移；BPM 新构件公开 reset 后 158 条；独立保留空库对照 `aura_announcement_main_schema_5f11717` 正常迁移 153 条、389 表、无产品表/行且 schema drift 匹配 |
| 生命周期 | 原有 OSS/BPM runtime、端口及数据库复用；两者 business_exit=0 / stop_exit=0，现已 suspended，保留 DB、构件、备份和证据 |

安装方法沿用 §18.26 的冻结 catalog → 显式 announcements composition → 公共迁移/reset/init-core/publish/verify 生命周期，版本与迁移数以上表为准。Core 启动使用公开启动器明确选择 `--plugin-profile core --plugin core-announcement`，仅 `core` profile 不代表已导入公告。新组合在正式 reset 后重新初始化本任务租户、发布应用配置并通过产品核验；没有手工修表、迁移历史回填或 out-of-order。开发库备份保留，不涉及生产环境。

集成回归结果：Core 后端 292 单元；应用后端 93 项（31 单元、62 真实 PostgreSQL/HTTP/worker/插件集成）；相关前端 184 项和 typecheck；内部 API foundation 38 项、类型重生成一致性；Core 17 条原生公告 browser。BPM 正式构建通过 97 Node 契约、1657 后端测试（224 份 JUnit，含 9 公告组合 IT、10 strict IT 和 27 SLA 测试）、Web typecheck/client/SSR/摘要/单 React/图一致性；同一确切组合 4 条 native browser 通过。两端 browser 均无 skip/retry，认证各 1 条另计，collection/result 的身份集合一致。结构静态门通过但 executed=0，不是业务执行覆盖。不同面和历史重复轮次不累加。

主干表单现在同时在页头和字段显示标题；OSS 两份浏览器 spec 的 5 处标题断言改为字段作用域。BPM 仅补充测试配置：后台重试定时器初次触发延后至一天，SLA 测试仍主动驱动真实队列；生产定时器逻辑与默认值未变。加速定时器反例稳定得到 `RETRY_PENDING` 被后台推进成 `DEAD_LETTER` 的断言失败，同一反例在修正配置后通过，随后整个 1657 项正式构建通过。该测试竞争与首次构建 exit 137 是不同事实；137 原因未证明，不能写为 OOM 或成功。

保留的失败记录包括：未导入公告导致的首轮环境无效、首次 16/17 browser 标题重复定位、Redis 随暂停关闭导致应用测试启动失败、私有文件路径未注入导致的两项 IT 失败，以及首次 BPM 构建的 SLA 失败和中断。更正前提与断言后才运行上述最终绿色轮次；原日志不覆盖、不改标。

12 张本次原图已逐张复核，位于[同一证据清单](./evidence/announcement-s2-s8-2026-10-25/manifest.json)的 `mainIntegrationValidation.visualReviews`；对应表单、接收版本附件、窄屏确认/铃铛错误态、默认流程、冻结送审、决策、撤销原因及恢复替换。图片只裁决捕获状态，不外推全部排列。

验证边界维持 §18.26：未追加小程序实际发送、远程 BPM、50000 人容量、独立 BPM 340 条 corpus、OCI/Linux/Jenkins 或生产验证。BPM Workspace 仍有 `RUNTIME_MANIFEST_MISSING` warning，产品独立 verify 与真实 BFF/DB/进程身份另行通过；共享 CLI Gradle 路径前缀匹配问题未修，本轮串行构建与生命周期操作。上游全部 147 个文件不等于都经本次业务回归。当前仅提交开放 PR，不合并、不部署；完整账本见[验收报告](./2026-10-25-announcement-s2-s8-testing-gate-acceptance-report.md)。


本轮推送触发的 GitHub Controller authz 扫描曾失败：`DomainExportController` 原本使用完整类名 `@com.auraboot.framework.permission.annotation.AuthenticatedAccess`，扫描器只匹配短注解名。改为显式 import 同一个类型并使用 `@AuthenticatedAccess`，注解值、映射、方法体和实际授权行为未变；精确文本归一化可还原被测 `5f11717d0` 文件。扫描器自身 4 项 fixture 和全仓 0 未决授权审计均通过，不通过修改 baseline 或放宽扫描规则解决。公开 source-bound 回归仍绑定原 catalog producer；未把新文档/import 提交冒称重新构建的二进制。推送新触发的远端 Checks 按 PR 当前结果独立裁决，尚在运行不算通过。

### 18.28 CI 后续修正与当前集成裁决

本节取代 §18.27 的当前版本指针；业务范围与“PR 保持开放、暂不合并、不发布”约定不变。main 在上一轮验证后合入其他任务，新增文档索引和翻译种子冲突；本轮固定吸收 `463515bb730bcdad966c10d69665b8203194a8e7`，merge `6ee39382a`。翻译按 key 三方合并，保留双方新增及各自更新，无重复键；文档同时保留公告入口与上游发布工具说明。未在运行中的源码上追随 main。

| 修正 | 结果与裁决 |
| --- | --- |
| 测试环境契约 | 公告四份 spec 的 6 处环境别名读取统一改用 `PG_CONN.database`；原任务数据库断言保留，env-drift baseline 未放宽 |
| 存储日志 | LOCAL、MinIO、OSS、S3 上传日志仅保留字节数，不输出路径、bucket、key 或完整对象 URL；存储地址返回值与实际上传行为不变，不抑制 CodeQL 规则 |
| 收件箱测试 | mock 与断言改为真实 `getPendingCount` / “3 项待处理”；不把未读与待办数混为一个事实 |
| 公告 DSL | 我的公告两个筛选补显式 `EQ`；详情返回按钮使用 schema 支持的 `default`。沿用原列表、详情和导航能力，无新 React 页面 |
| 内部 API | 从确切任务栈实时采集 1554 个操作，更新快照并正式重生成类型；contract `19da7877889dfc0fb73f6b5b131fdf15293b3256143acac8323415f7d8978eb6`，生成类型 digest `863d53e01891d573fb16ef7cea2938252e2033273596ffba50d0f01dc2d07f92`；live drift 一致 |

本次沿用的 Page UX Blueprint：`announcement_reader_list` 面向接收者，以全部/未读/待确认三标签筛选有权查看的公告，列顺序保持标题、重要程度、阅读、知悉、发布时间，行内查看；`announcement_reader_detail` 以冻结正文、附件和阅读/知悉事实为主体，主要动作仍是确认知悉，返回我的公告为普通次要导航。只规范筛选运算符与按钮样式枚举，字段、分组、权限和异常反馈保持原契约；列表空态、只读与无权限说明沿用 §16。原有附件旅程实际从菜单进入接收列表及详情，宽屏原图可见普通返回按钮；窄屏收件箱、实际待确认事实与底部确认布局均复核。静态 UX 审计 12 页、9 个引导字段，0 错误/警告；不以静态结果代替操作证据。

当前 Core browser producer 为 `8837ec03c2be416d524e7a8d1339bea52d6d0915`。正式 catalog producer 为 `6eae14ad872da86a453d1379a107aa17dc971253`，相对 browser 输入仅更新实时契约快照与其生成类型，后端业务和 DSL 相同。catalog 为 `core-catalog-followup-6eae14ad8`，15 artifacts / 18 entries，lock `sha256:24aec55ba20fec7ba1905394e19fbda79744cb916410e52fc7d89f970ba360a6`，graph `sha256:fb308ab0b0ed17c3cf2d8c7410b8cfad954cee1905cf303de78419389fd1b136`。交付末尾提交仅同步文档/公开证据，不冒称新二进制。catalog 正式 typecheck、client/SSR、摘要与单 React 检查通过。

本轮验证：前端全量 822 文件、8774 项通过；Core 后端 285 项定向单元通过；日志改动另有 82 项存储模块/文件服务测试通过（与后轮文件服务范围有重叠，不相加）；API foundation 38 项、完整 TypeScript 和实时 drift 通过；OSS 原生公告固定 17 条全部单次通过，身份集合与 collection/result 对账一致，fail/skip/retry 为 0，认证另 1。两项原失败修正后先定向 33 项通过，再由全量单测覆盖，不能累计成额外功能数。

BPM source `3861a3146caf07a16b0937971e4cba5c2433e8de` 未改代码；针对新 catalog 复验 `AnnouncementApprovalCompositionIT` 9 项与 `StrictApprovalRuntimeIT` 10 项，真实 PostgreSQL、引擎与 worker 共 19 项通过。使用受管 `announcement-management-bpm-contract`（control，slot 2，DB `aura_bpm_2`、Redis DB8）对照新 Core，原 `aura_bpm_1` 与已验证正式产品栈保持保留。对照 namespace 初始化前核对分配、精确源、无连接并备份原库且验证备份目录；由 Flyway 从空库按序执行 160 条（155 Core + 5 BPM），未手工改 schema/history 或开启 out-of-order。对照测试结束正常 close，allocation、DB、备份和证据保留。该对照没有启动新浏览器服务；§18.27 的完整 BPM 1657 项与 4 条 native 只对原 5f117/3861 正式组合有效，本轮没有为新 catalog 重跑整套 BPM 或重建新的 22 件产品组合。

**初始化证据口径更正：** OSS `oss-golden-stack --fresh-db` 应用 canonical schema 快照，当前 `auraboot_1` 为 388 表且没有 `ab_flyway_schema_history`；“对应 155 个 Core 迁移文件”不等于该环境实际执行 155 条 Flyway。§18.27 的“Core fresh reset 后 153 条迁移”应按同样原则理解为快照来源，不能算逐条迁移通过；其中独立空库的 153 条 Flyway/drift 证据仍有效。原正式 BPM reset 的 158 条与本轮对照空库实际 160 条是独立的真实迁移执行。当前安装使用上述新 Core catalog 时应正常执行迁移；已有正式 BPM 组合身份仍以 §18.27 为准，不能仅替换目录名即声称它已消费本轮 catalog。

保留的反例与失败：旧远端 Frontend env-drift 6 处、Docker Quickstart 的 live OpenAPI drift、CodeQL 4 项路径日志告警；本地全量 8752 pass/2 fail 后修正收件箱测试与无效 DSL；发布工具检查首轮 35 pass/1 runner error，缺 TS loader 的 1 文件使用 tsx 后 2 项通过；API foundation 首次拒绝 dirty source，提交快照后 clean checkout 的 38 项才通过。原日志不覆盖或重标。当前原 PR 的准确 head 与 Checks 是远端 CI 的裁决入口，运行中不算通过；本节记录本地测试、真实捕获与已发生失败，不预报后续 CI 结果。

本期未新增小程序真实发送、远程 BPM、50000 人、独立 BPM 340 corpus、OCI/Linux/Jenkins 或生产验收；本机未运行容器。源代码改变后由 GitHub 自动触发的 Docker/CodeQL 等结果只按其准确 head 单独记录。原 Workspace `RUNTIME_MANIFEST_MISSING` warning 与共享 Gradle 路径前缀问题未修改；构建与生命周期保持串行。详情见[同一验收报告](./2026-10-25-announcement-s2-s8-testing-gate-acceptance-report.md)与[同一 manifest](./evidence/announcement-s2-s8-2026-10-25/manifest.json)的 `ciFollowupValidation`。

### 18.29 已合并版本与截图验收（2026-10-26）

本节取代 §18.28 的当前交付指针。用户已明确授权合并并截图验证；OSS [#2396](https://github.com/AuraBootTeam/auraboot/pull/2396)、BPM [#60](https://github.com/AuraBootTeam/aura-bpm/pull/60)、EE [#1589](https://github.com/AuraBootTeam/auraboot-enterprise/pull/1589) 已合并，准确 merge commit 分别为 `7c5c1a4951b9d44a5282a0273b939762cd999d3a`、`1b4b702285dc26afb416fc600174a2efc0244542`、`562f8df415a0f6fb357969c03e34cc6ef019b432`，均已证明进入各自 origin/main。不涉及生产发布。下方及旧节的开放 PR/暂不合并表述只记录当时授权状态。

合并后冻结准确源重新构建：Core catalog 15 artifacts/18 entries，lock `sha256:74f491cdc6f290c946b5ac082c1e543edfd2fb015db0c8a4a948a38f983ae736`；BPM 显式消费该 catalog 生成 22 件 announcements 组合，lock `sha256:45cfca8872588debb44a9e8ae6c3ca5d751f33d83b828df242a374023d8fe4ca`。两端正式 Web typecheck/client/SSR、摘要、图一致性和单 React 检查通过，OCI 明确跳过。组合构建 97 Node 契约和 1657 后端项通过（224 JUnit，fail/error/skip=0）。原受管 BPM 开发库备份后正常 reset，从空库顺序执行 161 条 Flyway（156 Core + 5 BPM）；Core browser 使用 canonical schema 快照，不冒充逐条 Flyway。

Core 原生 browser 17/17、BPM profile 4/4（含 3 公告与 1 原项目审批）通过，均单次、无 fail/skip/retry，认证各 1 另计，collection/result 准确身份集合对账通过。实际 Java classpath、artifact root、producer 和 lock 在 BPM browser 前验证。先登记的 32 张选定原图已逐张查看并记录九维结论，涵盖列表、表单、无权限、定时/撤销/删除、冻结附件、窄屏待办/知悉、统计导出入口、催办、ANY/ALL 审批与恢复替换；只裁决捕获状态，不外推全部产品排列或按钮。详见[本轮原图及观察](./evidence/announcement-s2-s8-2026-10-25/postmerge-2026-10-26/manifest.md)与[同一 manifest](./evidence/announcement-s2-s8-2026-10-25/manifest.json)的 `postMergeValidation`。

视觉存在非阻断 R-UI-01：催办预览摘要与统计行之间留白较大，数据和主操作仍清楚可达；本次不扩大到页面重设计。未验证读屏器。功能、视觉、交互和证据信任独立记录，截图不代替权限/冻结字节/投票事实断言。

本轮排除首轮 Core 依赖启动失败、误用旧组合的 BPM browser、未执行 browser 的 Web 依赖升级拒绝；旧图片不重标。公开 update-web 完成原运行时的依赖升级后才运行上述有效 BPM 轮次。适配器生成受 evidence 软链物理路径影响，一次失败启动覆盖了旧轮次部分生命周期日志；旧 browser/results/images 保留，不能宣称该轮生命周期证据完整。有效 BPM business_exit=0，首次停止却为 stop_exit=2（进程所有权探针异常，原因未定位）；随后经同一所有权校验器核对已退出进程并成功公开 suspend。该异常单列，不能写成初次正常退出。Core business/stop 均为 0。

OSS/BPM 原 runtime 已暂停，DB、构件、备份与证据保留；地址仍为 Core Web 5101/backend 6401、BPM Web 5401/backend 6501。BPM Workspace 的 RUNTIME_MANIFEST_MISSING warning 与共享 Gradle cache 路径前缀问题未修，产品 verify 独立通过，生命周期/构建串行。OSS/BPM source checkout 与引用因原 runtime 仍绑定准确 root/branch 而保留，不能强删或宣称源码资源全部回收；EE 工作区及分支已收口。任务注册中的历史 removed Workspace 行没有 PR/head 证明，阻止整任务自动 finish；当前任务负责后续受支持的 artifact handoff/登记凭证对账，未为本次验收修改 Workspace 业务代码。保留环境不等于生产部署。

验证范围仍不包括小程序真实 sender/真机、跨服务远程 BPM、50000 人、独立 BPM 340 corpus、OCI/Linux/Jenkins 或生产验收。历史前端 8774 和既有 Core 单测不冒称本次重跑；本次新增绿色只按上述准确源与执行面解释。


## 19. Command-scoped exact-version acknowledgement proof

This follow-up adds a public plugin bridge for business commands that require a particular effective regulation version. It reuses the existing announcement version, sealed recipient and acknowledgement facts; it does not create a second acceptance table, write acknowledgement automatically or change the official reader UI. Existing announcement delivery evidence above remains tied to its original producer. The provider now has targeted local Spring/MyBatis/PostgreSQL transaction evidence and a paired Core/API build; product command/UI integration remains pending. Exact producers, failures and remaining limits are in the [targeted acceptance record](../retro/2026-10/2026-10-27-announcement-command-proof-acceptance.md).

`CommandContext.announcementAcknowledgementAccessor()` returns the optional host `AnnouncementAcknowledgementAccessor`, or null when that context has no installed host bridge. Consumers require a paired admitted Core runtime/API that includes this accessor; this does not establish binary compatibility with pre-accessor hosts. Business handlers must fail closed when absent. `checkForCommand` accepts one to thirty-two unique `Requirement(versionPid, snapshotHash, memberPid)` values. The host derives tenant, actor user and current member ID from authenticated `MetaContext`; payload-supplied tenant or actor identities cannot select another recipient. Installed-integration/background settings do not expose this bridge.

Identity locking now delegates to the shared [command membership capability](command-transaction-isolation.md#exact-tenant-identity-locks): sorted user rows before sorted member rows, with physical datasource checks and complete-batch/subset rules. The earlier producer65eaa evidence remains historical; the paired follow-up producer31caa adds this shared capability. The call requires an existing writable, synchronized Core transaction (`MANDATORY`). It locks the enabled current user/member and announcement roots, ordering root PIDs consistently. Publication control is re-read after taking the same root lock used by publication, withdrawal, replacement, expiry and recipient actions. Locks remain until the caller transaction completes. Every satisfied proof rechecks member identity, active version, visibility, acknowledgement and database expiry before commit. This also catches changes made by the caller itself inside the same transaction; row locks alone cannot exclude its own writes. Any invalidated proof makes that transaction fail. This does not promise a wall-clock deadline beyond the database commit-check boundary.

`Check` returns requested/active version PIDs, snapshot hash, status, effective/visible/member-incarnation flags, acknowledgement requirement, acknowledged time/hash, checked database time and validity limit. Only `SATISFIED` is proof of explicit acknowledgement. Missing, withdrawn, expired, superseded, changed snapshot, invisible recipient, changed membership or incomplete acknowledgement must prevent the corresponding business action. `NOT_REQUIRED` describes a version without an explicit-ack policy and cannot silently satisfy a regulation gate that requires acknowledgement. Reading is never acknowledgement. Consumers persist the exact version/member/hash proof with their application revision and recheck at later actions required by their versioned policy.

Business permissions, verified vessel binding, approval, scheduling eligibility and resource reservation remain the consuming domain's responsibility. Knowing a public version PID confers none of those rights. This API supplies no legal authority, site safety thresholds or device quality guarantee. Actual end-to-end application and vessel-portal acceptance remains a separate product responsibility.

## 20. Sealed content proofs for original business commands

`AnnouncementSealedContentAccessor.requirePublishedContentForCommand(ExactVersion)` is a publisher proof, separate from recipient read/acknowledgement. A supporting host supplies the typed accessor through `CommandContext.announcementSealedContentAccessor()`; missing support returns null. Issuance requires the original synchronous authorized human command, its exact business target, authenticated tenant/user/member and existing writable physical Core transaction. Async, nested, impersonated, installed-integration, read-only or changed-connection use is unavailable. The handler scope permits one exact announcement/version requirement; repeating that requirement revalidates it, while growing the lock set is refused. Handler close prevents further calls; an already registered immutable proof remains subject to beforeCommit verification in the same transaction.

The request contains only announcement PID, version PID, expected full snapshot hash and the registered projection schema. The host reuses official publish functionality plus owner-or-manage scope, shared identity locks, the announcement root lock, exact active published control, database expiry checks and `AnnouncementApprovalProofService.requireValid`. The approved submission, business root/version, full snapshot hash, definition PID/version/hash and accepted terminal result must agree. Exempt, legacy, unapproved, scheduled, withdrawn, superseded, expired or missing-seal versions do not become approved content proofs. Publisher access does not require a recipient row, and recipient acknowledgement does not grant publisher authority. Ordinary recipient acceptance continues to use its existing acknowledgement bridge and the independent document consent digest.

### Official seal evidence and storage boundary

Official approval submission and exemption freezing capture a new `ab_announcement_content_seal` row in the same transaction as the new official version. The sidecar's tenant/root/version association and snapshot hash are fixed; its PostgreSQL guard refuses later inserts for an already committed version, UPDATE and DELETE. Official release verifies existing new evidence; historical ordinary announcement release behavior does not backfill a seal. A proof read never writes missing evidence, including for historical versions without attachments. Existing `announcement.v1` JSON and hashes are not changed. The generated schema snapshot comes from the same forward migration through the official fresh-database generator.

Each seal observes retained finalized attachments through the official storage provider, under tenant-specific file row locks in public-PID order. It records exact original name, media type, observed byte length and SHA256. `sourceIdentityKind` is explicitly `SEAL_OBSERVED_CONTENT`; it is not a provider generation or a promise of permanently immutable external storage. Its opaque `sourceVersionIdentity` is SHA256 of UTF8 `auraboot.seal.observed-content.v1\n`, the locator hash, a newline, decimal observed length, a newline and the lowercase byte SHA256. The locator hash itself is SHA256 of UTF8 storage-type enum name, U+0000 and the private storage key. Keys/paths are not returned. Changed keys, bytes, length, metadata, storage provider, finalization or retention make the proof unavailable.

New sealing, release and proof commit checks reread the real streams; they never substitute file PID, URL, declared size, caller-provided metadata or a current hash for missing seal-time evidence. Identity locks precede the announcement root and then ordered file locks. Caller code must declare its complete identity set before obtaining membership locks. The bridge does not introduce another identity or file authorization engine.

SQL row locks protect the platform's database mutation/delete paths. They cannot stop an external actor from changing an object after the last byte observation or make external storage atomic with the SQL commit. A successful proof means the observed source matched the official seal at the checks performed. A storage backend without readable matching evidence is unavailable; this component does not fabricate a provider generation or certify an external immutable object version.

### Shared document canonicalization

The only implementation is `AnnouncementDocumentCanonicalizer` in the public API source; the host directly consumes that class. Schema `operating-policy-document.v1` uses custom versioned canonical JSON, not RFC8785/JCS. The fixed field order is `schemaId,title,bodyFormat,body,attachments`; body format is `announcement-html-raw.v1`. Title and sealed content are exact raw strings: no trim, NFC, HTML rendering/reordering or newline normalization.

Attachments are unique uppercase public ULIDs, sorted by ASCII PID. Their fixed field order is `filePid,sourceIdentityKind,sourceVersionIdentity,name,mediaType,byteLength,sha256`. Integers use ordinary nonnegative Java-long decimal notation without leading zeros. Strings escape quote and backslash; U+0000 through U+001F use lowercase `\u00xx`. Other Unicode scalars remain literal UTF8; unpaired surrogates are rejected. No optional URL or audience/authorization/estimate/approval fields enter this document encoding. The digest is lowercase SHA256 of UTF8 `auraboot.announcement.document.v1\n` followed immediately by the canonical JSON UTF8 bytes.

The independent full announcement snapshot hash still uses the existing official codec and includes policy/authorization/estimate facts. It must not be forced equal to the document digest. [Shared fixed vectors](../../platform/platform-plugin-api/src/test/java/com/auraboot/framework/plugin/extension/AnnouncementDocumentCanonicalizerTest.java) demonstrate raw Unicode/CRLF and A/B snapshot-hash divergence with the same document digest. They are controlled serialization fixtures, not approved announcements. Consumers use the shared class; a different prior digest scheme requires a new consumer version rather than rewriting an immutable old digest.

### Fresh publisher permission reads

`PermissionSnapshotCache.withFreshReads(Supplier)` is an explicit host-only thread scope. Within it, that component's normal loaders bypass every cache read/write, including nested catalog, user-role, baseline-role and effective-permission loads. Default paths remain cached. Nesting and exceptions restore the prior mode, and a worker thread does not inherit the scope. The seal service wraps the existing official publisher access check in this scope, including beforeCommit revalidation, so uncommitted permission observations are never published into shared caches. It does not globally evict caches, change authorization rules, add IAM locks or grant access.

This scope does not override the original transaction isolation or independent contributor/runtime-catalog caches. BeforeCommit checks obey that transaction's visibility; global linearizable revocation by arbitrary concurrent IAM writers is not established here. Root/member locks and exact official publication facts retain their own existing authority. Qualification must distinguish same-transaction revocation, actually serialized root writers, and external timing guarantees.

The [Root integration record](./2026-10-27-maritime-sealed-content-integration-report.md) records scoped host tests, PostgreSQL/local-storage checks and the real PF4J/registry/CommandExecutor issuance and runtime-primary role-removal cases. Approval bindings remain controlled fixtures. Actual BPM publication, consumer immutable applicability mapping, public HTTP and browser acceptance still require their own evidence; these results do not establish full product or production acceptance.

Runtime-primary publisher authority also comes from the active tenant-bound release role catalog and the current exact member-role association. Deleting fallback `ab_role_permission` rows is not revocation of a catalog grant. The rich command tests remove the publisher association through the existing typed tenant-role API while retaining the independent invocation/read role, then verify both committed denial and same-transaction rollback. No production permission rule or privileged plugin-owner whitelist is broadened.
