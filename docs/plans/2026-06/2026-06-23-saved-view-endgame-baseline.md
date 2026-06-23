---
type: plan-design
status: active
created: 2026-06-23
owner_lane: saved-view
relates_to:
  - /Users/ghj/work/auraboot/.worktrees/enterprise-saved-view-vnext/docs/assets/mockups/saved-view-vnext-mockup.html
  - docs/plans/2026-06/2026-06-22-saved-view-feishu-parity-requirements.md
  - docs/backlog/2026-06-22-saved-view-feishu-parity-gaps.md
  - docs/backlog/2026-06-23-saved-view-post-pr-follow-up-gaps.md
  - web-admin/tests/e2e/saved-view/FEATURE_MATRIX.md
---

# SavedView Personal-only 终局 Baseline

## 文档目的

本文档用于重置 SavedView 后续开发基线。它把最初的飞书体验对齐分析、enterprise 长期 mockup、当前真实开发进展、截图暴露的问题、以及 2026-06-23 新确认的 Personal-only 本期范围统一到一份 baseline 中。

本文档比之前 P0/P1/P2 收口记录更严格。之前的文档可以作为实现历史参考，但不能再作为“SavedView 长期 UX 已完成”的发布结论。

## 信任重置

之前的状态汇报混用了三个来源，导致完成度判断失真：

| 来源 | 当前状态 | 问题 |
| --- | --- | --- |
| enterprise 长期 mockup | 1261 行，hash `ced199e9a85d69a2db92c1125c8e750fc4f571d22d9b5a1c26f8b509e2c0d130` | 这是 UX 真正源头，但前面没有被持续作为唯一验收标准 |
| OSS/current mockup 副本 | 227 行，hash `a9d4178e91918b20fd84e236f7a5cf46ee08643f501976590c7850d21e423552` | 只是简化/过时副本，不足以支撑长期方案验收 |
| 当前实现 | 底层能力有进展，但管理链路 UX 不完整 | 后端和部分入口能力真实存在，但用户可见链路明显没有达标 |

新的执行规则：

- enterprise mockup 是 UX 源头，除非后续显式替换并更新 baseline。
- 任何功能不能只因为 API、组件测试或 pass count 就标记完成。
- 一个功能要算完成，必须同时有代码实现、功能测试、浏览器 E2E、截图复核，并且能对齐本文档。
- 旧文档中的 DONE 行如果只覆盖后端/API/组件行为，必须降级为“能力完成但 UX 未完成”。

## 源头与入口

当前 canonical UX mockup：

`docs/assets/mockups/saved-view-vnext-mockup.html`

说明：该文件已从 enterprise 长期稿同步并增强为当前仓库 canonical mockup。2026-06-23 后的本期开发只验收 Personal-only 场景：数据视图、个人视图选择器、个人管理中心、新建个人视图、个人高级视图诊断。团队/全员仅保留在“后续路线”场景中，不进入本期开发或验收。

## SOT Updates

本 baseline 是执行地图，不是长期唯一真相。稳定规则已沉淀到企业版 system-reference：

- `/Users/ghj/work/auraboot/auraboot-enterprise/docs/system-reference/subsystems/01-用户视图SavedView.md`：SavedView scope、Personal-only UI 边界、页面级隐藏、`hideSavedViews` / `hideQuickFilters` 行为边界。
- `/Users/ghj/work/auraboot/auraboot-enterprise/docs/system-reference/core/09-DSL能力边界完整参考.md`：列表页多视图开关、SavedView selector 隐藏边界、当前 OSS release 只验收个人视图 UI。
- `/Users/ghj/work/auraboot/auraboot-enterprise/docs/standards/meta/documentation-governance.md`：`plans/` / `backlog/` 不得承载唯一真相，完成态过程文档必须有 `SOT Updates` 或 `no-precipitation`。
- `/Users/ghj/work/auraboot/auraboot-enterprise/docs/agent-rules/doc-precipitation-and-closure.md`：SoT-first 沉淀闸门和 PR / commit 收口检查。

## 2026-06-23 本期范围收口

用户已确认：当前只需要支持个人视图，团队视图和全局视图暂时不用管，后续再开发。

本期范围：

- 个人视图：新建、切换、重命名、复制、删除、设为默认。
- 个人视图变更：保存当前筛选/字段/排序/密度/视图类型配置，或放弃本地变更。
- 快捷筛选：`我的记录`、`今日新建`、`本周修改` 继续作为工具栏轻量入口，并支持另存为个人视图。
- 高级视图能力判断：看板、甘特、日历、画册、树视图等只保存为个人视图；blocked 类型不可保存。
- 配额：个人视图上限为 10 个；达到上限前给出明确计数，达到上限后禁用新建并提示清理。
- 验收：只按 Personal-only 用户路径验收，不再要求团队/全员 diff、协作者、audit、team pid 选择等路径。

本期非目标：

- 团队视图保存、团队成员选择、团队默认视图。
- 全员/全局视图发布、全局默认视图。
- 协作者管理、共享视图 ACL、共享保存 diff 确认。
- `ab_team` / `ab_team_member` 驱动的 UI 入口。
- 团队/全员配额 20 的 UI 验收。

长期保留：

- 团队/全员共享仍是长期方向，但只能作为 roadmap 和架构预留，不能出现在本期用户可操作主链路里。

原始需求文档：

`docs/plans/2026-06/2026-06-22-saved-view-feishu-parity-requirements.md`

当前主要实现位置：

| 模块 | 文件 |
| --- | --- |
| 标题旁视图选择器 | `web-admin/app/framework/smart/components/view/ViewSelector.tsx` |
| 管理/新建面板（共享/audit 后续） | `web-admin/app/framework/smart/components/view/ViewManagePanel.tsx` |
| 高级视图能力判断和推荐字段 | `web-admin/app/framework/smart/utils/savedViewCapability.ts` |
| 快捷筛选 preset 条 | `web-admin/app/framework/meta/rendering/pages/list/PresetViewBar.tsx` |
| 列表页编排 | `web-admin/app/framework/meta/rendering/pages/ListPageContent.tsx` |
| E2E 功能矩阵 | `web-admin/tests/e2e/saved-view/FEATURE_MATRIX.md` |

## 产品目标

SavedView 是 DSL 列表页的日常视图状态系统。本期只负责保存和恢复个人维度的列、筛选、排序、密度、视图类型和高级视图字段映射；共享范围属于后续团队/全员能力。

目标体验对齐飞书/多维表格：

- 日常使用只需要标题旁 selector 和列表工具栏。
- 管理面板是低频二级能力，本期只用于个人视图的新建、复制、重命名、删除、默认设置和高级配置。
- 高级视图必须先判断数据是否支撑，不能创建打开后空白或半配置的视图。
- 团队/全员视图保存、协作者和 audit 不进入本期。
- 如后续接入插件/系统预置视图，本期只允许复制为个人视图，不允许写回源视图。
- 用户看到的是业务语言，不是内部 id、内部后缀、英文工程文案或测试数据噪声。

## 用户与角色

| 角色 | 主要任务 | 期望体验 | 权限行为 |
| --- | --- | --- | --- |
| 业务用户 | 切换视图、筛选记录、保存个人状态 | 标题旁 selector + 工具栏即可完成日常操作 | 可保存个人视图，可复制快捷筛选为个人视图 |
| 受限用户 | 达到个人视图上限或创建 blocked 高级视图 | 禁用原因清楚，不出现神秘不可点按钮 | 不能保存超过 10 个个人视图，不能保存 broken view |
| 团队负责人/管理员 | 发布和维护团队视图 | 后续能力，不进入本期 | 未来依赖 `ab_team` / `ab_team_member` |
| 全局管理员 | 发布全员预置视图 | 后续能力，不进入本期 | 未来需要全局管理权限 |

## 交互定位

SavedView 不是独立 CRUD 管理后台，而是列表页状态工作台的一部分。

主要交互类型：

- 日常列表工作台：查询、筛选、排序、字段、快捷筛选、当前视图。
- 视图选择下拉：快速切换我的视图、本地变更、新建/管理入口。
- 新建视图向导：视图类型能力判断、字段映射；本期作用域固定为个人视图。
- 高级视图诊断：`available/degraded/blocked` 状态、原因和建议动作。

## 终局 UX 契约

| 区域 | 终局决策 |
| --- | --- |
| 入口 | SavedView selector 固定在页面标题旁；日常切换不进入管理页 |
| 默认加载 | 页面打开后立即加载当前/默认视图，用户先看到业务列表和工具栏；隐式默认视图显示为"默认视图"，不作为普通个人视图管理 |
| 默认恢复 | selector 必须提供返回"默认视图"入口；恢复默认时清除 `view` 及临时 `sort`/`keyword`/`preset`/`filters`/`filter_*`/`pageNum` URL 状态 |
| 快捷筛选 | `我的记录`、`今日新建`、`本周修改` 保持在 toolbar，只能出现一处；可保存为个人视图 |
| selector dropdown | 支持搜索，只展示“我的视图”；展示类型、默认、本地变更、需配置状态 |
| 本地变更 | 用户修改个人视图设置时，显示 dirty 标识和操作：保存到当前个人视图、另存为新个人视图、放弃变更；放弃必须恢复已保存配置并清除 URL 临时态，不能只隐藏 banner |
| 新建流程 | `新建个人视图`：类型选择 -> 字段映射 -> 保存；作用域固定为个人视图；blocked 类型不可保存 |
| 高级能力判断 | 后端声明 `available/degraded/blocked + reason codes + suggestedConfig + fieldOptions`，前端只展示后端声明的可行动作 |
| 字段推荐 | 看板分组优先 status/enum/dict/boolean/reference/user，再到 category-like 字段，最后才允许普通 string；标题优先 title/name/code |
| 共享保存 | 后续能力，本期不展示可操作入口 |
| 团队依赖 | 后续能力，本期不接 `ab_team` / `ab_team_member` UI |
| 审计 | 后续共享治理能力；本期只要求保存/删除/默认设置有用户可见反馈 |
| 管理面板 | 低频入口，全中文、可搜索/分组，不被长测试数据刷屏 |
| 页面级隐藏 | 非日常列表工作台可以设置 `hideSavedViews: true` 隐藏标题旁 selector 并停止 SavedView 自动加载；quick filters 需单独设置 `hideQuickFilters: true` |
| 响应式 | selector、dropdown、新建/共享流程在窄屏可用，无文字重叠 |
| 可访问性 | 按钮有稳定名称/test id；禁用控件有原因；Escape 可关闭 dropdown/panel |

## Enterprise Mockup 场景拆解

enterprise mockup 定义了四个场景。四个场景都被实现和验证前，不能声称长期方案平齐。

| Mockup 场景 | 用户含义 | 必须具备的产品行为 | 当前状态 |
| --- | --- | --- | --- |
| 数据视图 | 日常列表使用 | 标题旁 selector、toolbar quick filters、本地变更条、表格内容、视图影响范围 | 本期已覆盖：标题旁 selector、toolbar quick filters 去重、个人 dirty 保存条、表格主链路均有浏览器截图和 E2E 证据 |
| 新建视图向导 | 只创建可用视图 | 类型卡展示 available/degraded/blocked；blocked 不可保存；映射表单包含名称、scope、字段、动作 | 本期已覆盖：个人作用域固定，类型卡/映射表单中文化，blocked 不可保存，degraded 明示降级原因 |
| 后续团队/全员路线 | 受控发布共享视图 | 通过 team pid 选择团队、展示权限状态、保存前确认 diff、保存后 audit | 本期不开发；仅作为 roadmap 保留 |
| 数据能力诊断 | 解释高级视图为什么不能渲染 | 展示记录指标、问题表、建议动作、降级预览 | 本期已覆盖可行动诊断：blocked/degraded 均为中文业务文案；完整记录指标/问题表作为后续增强 |

## 当前进展重新归类

| 能力 | 之前口径 | 重新归类 | 原因 |
| --- | --- | --- | --- |
| SavedView pid-only DTO 清理 | Done | SavedView 范围内完成 | SavedView DTO/audit/role/member 清理是真实的；dynamic record pid-only 属于外部任务 |
| 标题旁 selector | Done | 本期完成 | 主入口固定在标题旁，dropdown 只展示个人视图并支持搜索、新建、管理入口 |
| quick filter 去重 | Done | 针对重复问题完成 | 当前截图中标题右侧重复 quick filters 已消失 |
| quick preset 生命周期 | Done | 本期完成 | 快捷筛选保留在 toolbar，可另存为个人视图；保存、已编辑、重置路径保留历史覆盖，当前个人视图另存路径有 E2E |
| 团队共享后端 | Done | 后续路线能力 | `ab_team`/`ab_team_member` 不阻塞本期 Personal-only |
| 协作者管理 | Done | 后续路线能力 | 本期不展示协作者管理入口 |
| personal/team/global quota | Done | 本期只验收 personal 10 | team/global 20 属于后续路线，不进入本期 UI/E2E |
| 高级视图 capability gate | Done | 本期完成 | 个人新建路径展示 available/degraded/blocked；blocked 不能保存，degraded 说明限制；API 语义校验保留 |
| ViewManagePanel | Done | 本期完成 | 管理链路已改为中文个人视图中心，支持搜索、配额、重命名、复制、删除、默认设置和个人新建 |
| E2E 矩阵 | Done | 本期完成 | `saved-view-management.spec.ts` 和 `showcase/view-management.spec.ts` 覆盖 Personal-only golden；历史 team/global 行改为 roadmap |

## 关键 Gap

### P0：源头与 UX 一致性

| Gap | 当前处理 | 证据 |
| --- | --- | --- |
| 验收使用了错误 mockup | 已修复 | 当前 canonical mockup 为 `docs/assets/mockups/saved-view-vnext-mockup.html`，FEATURE_MATRIX 指向本文档 |
| 管理面板不本地化、不专业 | 已修复 | 管理面板、新建向导、能力提示、dirty bar 均为中文/i18n 文案；旧英文链路进入 code review grep |
| 新建向导不符合 mockup | 已修复 | 个人新建流程改为类型卡 + 字段映射 + 保存；作用域固定为个人 |
| 看板字段推荐粗糙 | 已修复到本期标准 | capability 单测覆盖语义排序；degraded 看板明确只读限制 |
| blocked/degraded 原因是 raw string | 已修复 | 前端展示本地化 reason，blocked 不可保存，degraded 可保存但限制可见 |
| 旧 E2E 保护错误 UI | 已修复 | `saved-view-management.spec.ts` 和 `showcase/view-management.spec.ts` 改为中文 Personal-only golden |

### 后续：共享视图成熟度

| Gap | 证据 | 必须修复 |
| --- | --- | --- |
| 共享保存影响范围不明确 | 当前 UI 缺少 mockup 里的 diff 确认 | 后续做团队/全员前展示影响范围和 diff summary |
| 协作者面板暴露 raw pid | 当前可能把 principal pid 当主展示 | 后续主展示姓名/邮箱，pid 最多作为辅助信息 |
| audit 面板过于技术化 | 当前使用英文和 raw operation label | 后续本地化为“谁在什么时候改了什么” |
| team/global scope 选择不成熟 | 当前 create scope UI 不完整 | 后续增加 personal/team/global 作用域和权限敏感禁用原因 |

### P1：数据与测试夹具治理

| Gap | 当前处理 | 后续进入条件 |
| --- | --- | --- |
| 管理列表被 quota 测试数据刷屏 | 已修复到本期标准 | 管理面板只列个人视图，支持搜索过滤，quota 压力截图验证 `10/10` 禁用状态 |
| 内部/生成名泄漏到 UX | 本期收敛 | 当前新 golden 数据使用中文业务名；历史 runtime 遗留英文/测试名通过管理搜索降低干扰，系统预置展示名规范留后续 |
| 长名称破坏层级 | 本期收敛 | 面板列表采用紧凑元信息和截断；若后续开放团队/全员长名，再补 tooltip/detail 策略 |

### P2：延期增强

| Gap | 范围说明 |
| --- | --- |
| AI recommendation badges | 已有 skipped 测试提到，但不属于 baseline parity 必需项 |
| 插件贡献 preset catalog | 基础 preset lifecycle 清干净后再做 |
| platform-wide dynamic record pid-only migration | 明确不属于当前 SavedView UX baseline，已由其他窗口处理 |

## 实施蓝图

### Phase 0：Canonical Baseline 收口

1. 将 enterprise mockup 同步到当前 OSS docs，或在当前 repo 明确链接为 canonical。
2. 修改旧 tracker，不能再声明完整 UX parity 已完成。
3. 将本文档加入 `FEATURE_MATRIX.md`，作为发布验收源头。

退出标准：

- 当前 repo 中只有一个明确的 canonical mockup/baseline 路径。
- 旧文档不再无条件宣称 SavedView 长期 UX 已完成。

### Phase 1：个人管理面板重构

主要文件：

- `web-admin/app/framework/smart/components/view/ViewManagePanel.tsx`
- 必要时拆分：
  - `ViewCreateWizard`
  - `ViewCapabilityNotice`
  - `PersonalViewList`
  - `PersonalViewQuotaNotice`

必备行为：

- 标题是 `管理视图`，不是 `View Management`。
- 新建入口是 `新建个人视图`，不是单个虚线 `New View` 按钮。
- 类型卡展示：表格、看板、甘特图、日历、画册、树视图、时间线、表单；状态为 `可用/可创建/需补字段/不适合`。
- 按钮是 `取消`、`保存视图`、`不可保存`，不是 `Skip/Done`。
- 作用域固定为 `我的视图`，不展示团队/全员选项。
- 管理列表支持搜索/过滤，避免历史/fixture 行刷屏。
- 个人视图配额展示为 `n/10`，达到上限时禁用新建。

退出标准：

- 浏览器截图中管理面板没有英文管理/控制文案。
- 类型卡信息层级接近 enterprise mockup。
- 长 generated view name 不破坏布局。

### Phase 2：能力判断和字段语义

主要文件：

- `web-admin/app/framework/smart/utils/savedViewCapability.ts`

必备行为：

- capability result 包含稳定 reason code。
- reason 展示为本地化业务文案。
- suggestedConfig 使用语义排序：
  - 看板分组：status/enum/dict/boolean/reference/user -> category-like -> string/text fallback。
  - 看板标题：title/name/subject/summary/code；除非没有替代，否则避开 id/pid/internal timestamp。
  - 甘特/日历/时间线：start/end/due/expected/completed 等日期语义。
  - 画册：image/avatar/photo/cover/file/attachment。
  - 树视图：parent/path/level/reference。
- blocked 高级视图不能从 UI 或 API 持久化。
- degraded 视图只有在渲染行为诚实时才能保存，例如只读看板禁用拖拽。

退出标准：

- `e2et_order` 看板默认分组是 `订单状态` 或等价状态字段，标题是 `订单标题` 或等价标题字段。
- 甘特/画册/树视图 blocked 时展示可行动中文原因。
- 单测覆盖语义排序和 reason code。

### Phase 3：selector、dirty state 和 preset

主要文件：

- `ViewSelector.tsx`
- `PresetViewBar.tsx`
- `ListPageContent.tsx`

必备行为：

- selector dropdown 支持搜索和分组。
- 本地变更显示源 scope 和动作：
  - 有权限时保存到源视图
  - 复制为我的视图
  - 放弃变更
- 后续 plugin/global preset 展示业务名时，必须允许复制为个人视图，不能展示生成后缀；本期不实现源视图写回。
- quick filters 只保留在 toolbar，定位为轻量日常动作。

退出标准：

- 截图显示标题旁 selector + toolbar quick filters，没有重复。
- locked preset/local draft 状态用户能看懂。

### Phase 4：后续团队/全员共享路线

本阶段不进入当前 Personal-only 开发。

保留为后续路线的条件：

- 团队选择来自 `/api/views/my-teams` 的 pid-backed team list。
- 共享保存确认展示团队/全员影响范围和 diff summary。
- 协作者行展示姓名/邮箱，权限文案中文化。
- audit 行展示 actor/time/change summary，公共契约仍然 pid-only。

进入后续开发前必须重新开 scope 文档、mockup 和 E2E 矩阵，不能复用本期完成口径。

### Phase 5：E2E 和 truth gate

主要文件：

- `web-admin/tests/e2e/saved-view/FEATURE_MATRIX.md`
- `web-admin/tests/e2e/saved-view/saved-view-follow-up-golden.spec.ts`
- `web-admin/tests/e2e/showcase/view-management.spec.ts`
- 其他断言旧 UI 文案的 SavedView specs。

必备行为：

- 移除断言旧英文管理 UI 的测试。
- 为 Personal-only mockup 场景补 golden user-path tests。
- 声称用户路径证据的测试必须从真实 UI 入口进入。
- 只有 URL 契约本身是验收目标时，才允许直接打开带查询参数的 `/p/...`，例如 `SV-PER-003b` 验证 `view + sort` 共享链接点击"放弃变更"后会清除临时排序。
- API setup 只能作为 setup/readback，不能作为 UI 证明主体。
- 任何 completion claim 前必须跑 `e2e-truth`。

退出标准：

- Feature matrix 每个 baseline row 都有 Covered/Partial/Open 和证据。
- selector、新建个人视图、个人 dirty bar、能力诊断、个人管理面板都有 golden screenshots。
- target-scope 测试没有 skip/fixme/fixed wait/API-only fake pass；direct route 只能作为明确 URL 契约回归，不得冒充普通用户路径覆盖。

## 功能验收矩阵

| 功能点 | 用户路径 | 后端/集成证据 | 前端/组件证据 | Web E2E 证据 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 日常 selector 入口 | 从菜单打开列表页，使用标题旁 selector 切换 | accessible/default views API | `ViewSelector` tests | `SV-PER-001` + screenshots `01/02` | Covered |
| 默认视图恢复 | selector 选择"默认视图"后回到系统基线，隐式视图不出现在普通个人视图列表 | implicit default persistence contract | `ViewSelector` / persistence tests | `SV-PER-001` selector 覆盖 + 本轮浏览器截图 | Covered |
| toolbar quick filters | 点击 quick filters 并验证刷新状态 | List API filter readback | preset/unit tests | quick filter E2E + `SV-PER-004` | Covered |
| 本地 dirty bar | 修改个人视图设置后看到 dirty 状态 | SavedView update/copy contract | List page state tests | `SV-PER-003` + screenshot `04` | Covered |
| 放弃本地变更 | 从带 `view` + 临时 `sort` 的 URL 打开个人视图，点击"放弃变更"后恢复保存态并清除 URL 临时排序 | SavedView readback 确认服务端 viewConfig 未被污染 | `viewConfigFiltersToRuntimeFilters` / persistence tests | `SV-PER-003b` | Covered |
| 新建 table view | selector -> 新建视图 -> 表格 -> 保存 | SavedView create API | Create wizard tests | `SV-PER-002` | Covered |
| 新建/degraded kanban | 新建视图 -> 看板 -> 映射字段 -> 只读拖拽提示 | Capability/create validation | Semantic ranking tests | `SV-PER-005` + screenshot `06` | Covered |
| blocked gantt/gallery/tree | 新建视图 -> blocked type -> 不可保存 | Capability/create rejects invalid mappings | Capability notice tests | `SV-PER-005` + screenshot `05`，gallery/tree specs | Covered |
| 个人配额 | 新建个人视图前看到 n/10；达到 10 后禁用新建 | SavedView count limit tests | Quota component tests | `SV-PER-005` + screenshot `07` | Covered |
| 团队共享保存 | 后续路线，不进入本期 | Team membership/action/audit tests | Shared panel tests | 不纳入本期 | Out of current scope |
| 协作者管理 | 后续路线，不进入本期 | ACL validator/audit | Component tests | 不纳入本期 | Out of current scope |
| audit 查看 | 后续路线，不进入本期 | Audit DTO pid-only tests | Audit panel tests | 不纳入本期 | Out of current scope |
| fixture hygiene | 大量视图下管理面板仍可读 | N/A | search/quota component behavior | `SV-PER-001` 管理搜索 + `SV-PER-005` quota 压力截图 | Covered |

## 非目标

- 本 baseline 不处理 platform-wide dynamic record pid-only migration。
- Personal-only 核心管理/新建流程完成前，不做 AI recommendation badges。
- 团队/全员视图、协作者、共享保存 diff、audit 不属于本期。
- 未显式纳入 Android/iOS 时，不声明移动端 parity。
- 不允许把阻塞 enterprise mockup 的缺口降级成 P2 逃避验收。
- `hideSavedViews` 只表示整页不展示 SavedView 入口，不是“保留切换但隐藏新建/管理/配置”的只读模式；如后续需要只读 SavedView，需要新增独立开关。

## Definition Of Done

只有满足以下全部条件，才能称 SavedView Personal-only 本期方案达到 release-candidate：

1. enterprise mockup 源头已在当前 repo 文档中 canonical 化。
2. 当前 runtime 截图在产品行为层面对齐 Personal-only 场景。
3. 管理/新建个人视图 UI 全中文，无旧英文控制文案。
4. 高级视图能力 gate 同时在 UI 和 API 阻止 broken views。
5. 字段推荐有语义排序，并在 `e2et_order` 风格字段上验证。
6. 快捷筛选可以保存为个人视图；本地变更可以保存、另存为、放弃。
7. 隐式默认视图不作为普通个人视图管理；用户可以从 selector 恢复默认视图。
8. 个人管理面板在 quota/fixture-heavy 数据下仍可读，且只验收 personal 10。
9. Feature matrix 更新，每个 delivered row 都有用户路径证据。
10. target SavedView 文件通过 e2e-truth audit。
11. 最终汇报包含精确命令、截图、已知风险，不再使用没有证据的“全部完成”口径。

## 本轮完成证据

本轮 Personal-only 实现收口后，发布判断以以下证据为准：

1. 当前 `docs/assets/mockups/saved-view-vnext-mockup.html` 是 SavedView canonical mockup。
2. `ViewManagePanel` 已按 Personal-only mockup 重构为中文管理链路。
3. capability reason、blocked/degraded 状态和个人新建流程已有单测与 E2E 覆盖。
4. 旧英文管理链路不再作为当前 golden 断言目标。
5. 浏览器截图覆盖：
   - `web-admin/test-results/saved-view-personal-golden/01-data-view.png`
   - `web-admin/test-results/saved-view-personal-golden/02-personal-selector.png`
   - `web-admin/test-results/saved-view-personal-golden/03-personal-management.png`
   - `web-admin/test-results/saved-view-personal-golden/04-personal-draft-save.png`
   - `web-admin/test-results/saved-view-personal-golden/05-capability-blocked.png`
   - `web-admin/test-results/saved-view-personal-golden/06-capability-degraded-create.png`
   - `web-admin/test-results/saved-view-personal-golden/07-personal-quota.png`
6. `FEATURE_MATRIX.md` 和本文档同步为 Personal-only 当前完成口径；team/global/collaborator/audit 保留为 roadmap。
7. 本轮新增默认视图/放弃变更回归证据：隐式默认视图不再作为普通个人视图展示；从 `?view=...&sort=...` 打开个人视图后点击"放弃变更"会恢复已保存配置、清除临时 `sort` URL，并保持服务端 viewConfig 不被污染。

## 2026-06-23 收口回归

本轮最后一次 scoped 回归只覆盖当前 Personal-only release，不包含 shared/team/global 或 platform-wide pid-only migration：

| Gate | Command summary | Result | 解释 |
| --- | --- | --- | --- |
| SavedView scoped E2E | `PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:5186 BACKEND_URL=http://127.0.0.1:6486 BE_PORT=6486 BFF_PORT=6186 PW_WORKERS=1 npx playwright test tests/e2e/saved-view/{saved-view-button-field,saved-view-calendar,saved-view-column-settings,saved-view-conditional-format,saved-view-follow-up-golden,saved-view-form-view,saved-view-gallery,saved-view-gantt,saved-view-kanban-grouping,saved-view-kanban,saved-view-lookup-field,saved-view-management,saved-view-quick-filters,saved-view-row-height,saved-view-system-fields,saved-view-table,saved-view-timeline,saved-view-tree,saved-view-ux-optimization}.spec.ts --project=chromium --no-deps` | `101 passed, 4 skipped` | 覆盖个人视图主链路、快捷筛选、默认视图恢复、放弃本地变更、隐藏列自动保存和高级视图回归；4 个 skipped 均为历史 fixture/AIR deferred，不计入完成证据 |
| Frontend focused unit/component | `pnpm vitest run app/framework/meta/rendering/pages/__tests__/ListPageContent.test.ts app/framework/meta/rendering/pages/list/__tests__/PresetViewBar.test.tsx app/framework/meta/rendering/pages/list/__tests__/ListToolbar.i18n.test.tsx app/framework/smart/components/view/__tests__/ViewSelector.test.tsx app/framework/smart/hooks/__tests__/useSavedViews.test.tsx app/framework/smart/utils/__tests__/savedViewPersistence.test.ts` | `6 files passed, 83 tests passed` | 覆盖 selector、preset、toolbar i18n、persistence 和列表页 URL 状态 |
| Diff hygiene | `git diff --check` | PASS | 没有 whitespace error |
| e2e-truth audit | skip/fixme/direct-route/wait/retry/threshold grep + 实际 Playwright 输出核对 | PASS with known exceptions | 只声明 Personal-only scoped 完成；direct `/p/` 仅保留 URL 契约回归；历史 API-heavy specs 不冒充 UI 完成 |

新增/修复的关键行为：

- `view=` 优先于 `preset=`。当 URL 同时携带个人视图和快捷筛选 preset 时，页面会移除 `preset`，避免重复按钮和双重状态。
- quick filter toggle 使用 ref 同步最新 active preset，避免回调闭包拿到旧状态导致 toggle-off 失效。
- "放弃变更"恢复当前个人视图已保存配置，并清除临时 URL 排序/筛选/search/preset 状态，不污染服务端 viewConfig。
- 默认/隐式视图不作为普通个人视图管理，但 selector 提供恢复默认入口。
- 隐藏列的 header menu 操作继续走 SavedView autosave，不因默认视图重构而丢失保存。
- SavedView E2E helper 清理或复用当前 spec 前缀生成的个人视图，避免 personal 10 配额被长生命周期 runtime 污染。

## 经验固化

这次会话暴露的可复用经验已经按层级沉淀：

| 经验 | 固化位置 | 后续执行规则 |
| --- | --- | --- |
| plan/backlog 不能承载唯一真相 | 企业版 `doc-precipitation-and-closure.md` 已要求 SOT-first，本轮继续在 system reference 反链 | 改 DSL/API/UX/scope/配额/隐藏开关时，必须先更新 system reference，再说文档已完成 |
| 同一需求多 worktree 会导致认知分裂 | 企业版 `engineering-gotchas/worktree-multirepo.md` | 一个需求只保留一个活跃实现 worktree；历史 mockup/design worktree 只能作为引用源，不能继续承载新实现 |
| SavedView E2E 不能只看单 spec pass | 企业版 `engineering-gotchas/e2e-playwright.md` | 涉及 URL 状态、默认视图、quota、preset 的改动必须跑当前 SavedView scoped regression，并做 e2e-truth |
| mockup 与 runtime 必须同源 | 本文档 + `FEATURE_MATRIX.md` | enterprise 长期 mockup 被同步为当前 repo canonical 后，验收只看当前 repo canonical，不再在两个 file URL 间来回切 |
| shared/team/global 与 personal release 必须隔离 | 企业版 SavedView system reference + 本文档 | shared/team/global、协作者、audit、team/global quota 和 pid-only migration 均不能阻塞本轮 Personal-only |
| 生成数据污染会制造假失败 | `helpers.ts` fixture policy + E2E gotcha | 所有创建个人视图的 spec 必须清理自己的前缀或复用同配置历史视图 |

## 当前 Baseline 结论

当前 SavedView 不能称为“长期团队/全员飞书平齐方案”已完成；长期共享能力仍需单独 scope、mockup 和 E2E 矩阵。

但按 2026-06-23 用户确认的 Personal-only 本期范围，当前实现已经具备 release-candidate 证据：个人视图新建、切换、保存当前、另存为、重命名、复制、删除、设默认、快捷筛选另存、个人 10 个配额和高级视图 capability gate 均有当前代码、单测/E2E 和截图证据。后续不能再把 team/global、协作者、共享 audit 当作本期阻塞项。
