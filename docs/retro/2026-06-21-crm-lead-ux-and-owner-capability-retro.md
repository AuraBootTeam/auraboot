---
type: retro
status: closed
created: 2026-06-21
slug: crm-lead-ux-and-owner-capability-retro
distilled_to:
  - docs/superpowers/specs/2026-06-21-record-ownership-owner-field-capability-design.md  # owner capability shipped
  - docs/core-concepts/models-and-fields.md  # §Record ownership + ownerselect render component
related:
  - docs/superpowers/specs/2026-06-21-record-ownership-owner-field-capability-design.md
---

# Retro — CRM 线索 UX 三轮 + 通用记录归属能力 (2026-06-21)

## 范围

单会话从「优化线索列表/表单的观感」演进到「沉淀一个平台级通用归属能力」。交付 4 个 PR(全 squash 合 main):

| PR | 内容 |
|---|---|
| OSS #987 | 线索列表:操作常显 · 状态 5 色 · 评分冷热条 · 来源图标 · 手机脱敏 |
| OSS #991 | 线索表单:居中限宽 · 3 段分组 · 占位符/辅助说明 · 评分 0–100 |
| OSS #998 | 通用 `owner_type`/`owner_id` 归属能力(`core-ownership` 插件 + `OwnerSelect` 组件 + `owner` cell renderer),`crm_lead` 首个消费者 |
| ENT #642 | 组件库参考登记 `SmartOwnerSelect` |

## 经验(可固化)

1. **DSL UX 打磨 = 配置 + 最小可复用渲染器改动,不写业务 tsx。** 列表/表单观感全靠 dict + page DSL + 渲染器小扩展;每个渲染器改动都做成**平台通用、向后兼容**(progress `render.thresholds`、`StatusDot` 可选 icon、`ListTable` 行操作常显、`FormPageContent` 居中限宽)。"克制升级"=守设计系统,把"代码偏离 canonical"(如行操作 `opacity-0` hover-reveal 违反 §3)就地纠正,而非堆花样。

2. **host-first golden 零 docker 的可复用套路。** worktree 独立 vite 指向共享 backend + Playwright 自带 chromium + 登录 + **DOM 断言(非只截图)**;命令/归属类断言用 **throwaway 记录**(建→命令→DB 反查→删),既证明真链路又不污染 demo 数据。

3. **通用能力的正确姿势(平台沉淀)。**
   - 字段定义一次放 core 插件(`core-ownership`),**跨插件 binding 复用**(实证:`crm_lead` 绑 core 定义的 `owner_*` → `mt_crm_lead` 长出列)。
   - 字段码**租户内唯一**(`ab_meta_field(tenant_id, code)`)→ 选通用裸名前先 grep 现有,**避撞**(`owner_*` 而非 agent 任务的 `assignee_*`,后者已被 `agent-control-plane` 占用且语义不同)。
   - 组件读表单 `context.record` 取 sibling 值(`owner_type`),cell renderer 读整行 → **一控件依赖另一字段**的两字段法(`ControlledFieldRenderer` 只给单字段 setter,不能 sibling-set)。
   - **不新增 dataType**(`DslRegistry` 13 类固定),用字段 `component` 覆盖 + `cellRenderer` 自定义键(`cellRenderer:"owner"` 绕过 `valueType` 白名单校验)。

4. **平台级能力 spec-before-build 有效。** brainstorm → 研究自家底(选人/团队/ABAC/字段约定**取证**)→ spec → owner 过目 → 实现。研究阶段实证推翻了假设:团队有两种(`ab_team` 功能团队 vs 部门树节点)、`assignee_*` 命名碰撞——这些若不查会做错方向。

## 坑(不用重复踩)

1. **🔴 验证依赖"未合并前端"的配置时,别 import 进共享 backend。** 把引用 `ownerselect` 的 crm 配置 import 进**共享** `:6460`,导致并发 codex 会话的 `:5160` crm 页报 `Unknown component: ownerselect`(它的前端 checkout 落后 main、没这组件)。**正解:这类"配置引用了尚未到该前端 checkout 的新组件/渲染器"的验证,用隔离 runtime(`dev.sh runtime`)起专属栈,不碰共享 backend。** 本会话用共享 backend 是为了贴合 owner 指定的 `:5160/:5161`,代价就是跨会话污染。

2. **canonical checkout 会被并发会话占用。** OSS + enterprise 的 canonical(`/Users/ghj/work/auraboot/{auraboot,auraboot-enterprise}`)都被 codex 会话切到 `codex/bom-quote-ui-completion` 且带未提交改动。任何 pull/切分支/写入前**必查 branch + dirty 状态**(§18/§20),不冲掉别的会话的 WIP;写文档也要起 off-`origin/main` 的独立 worktree。

3. **删/换 dynamic 字段要同步四处。** 解绑 `crm_lead_assigned_to` 后页面仍引用 → import 报 `S-PAGE-FIELD-REF`。改字段须同步 binding / field def / commands `inputFields` / pages 四处。

4. **design-tokens ratchet。** 从既有组件抄 `ring-blue-100`/`text-blue-900` 会抬高 palette-utility 基线导致门禁红 → 用语义 token(`ring-accent-weak`/`text-accent`)。

5. **跨插件加字段后投影。** column 建好后首次 list 投影可能不含新字段(启动期投影缓存);本次实测命令写+list 读均 OK(未触发),但 gotcha 存在——完成判定按 `import →(必要时重启)→ 命令 golden`。

6. **杂项。** `gh` graphql 偶发 `EOF` → 重试;`docs/superpowers/` 下设计稿 frontmatter `type` 用 `plan-design`(非 `design-spec`);`gh pr merge --delete-branch` 在 worktree 里易踩本地切分支坑,改为合并后单独删远端分支 + 收 worktree。

## 建议晋升 canonical(待 owner 决定)

- 坑 #1(共享 backend 污染)+ 经验 #3(通用能力跨插件字段复用 recipe)值得进 `auraboot-enterprise/docs/agent-rules/engineering-gotchas/`(plugins-import-overlay / test-infra),让所有 agent 可见。本 retro 先记录,晋升另起 PR。
