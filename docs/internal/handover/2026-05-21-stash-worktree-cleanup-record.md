# 2026-05-21 Stash / Worktree 清理记录

## 背景

本记录用于避免后续再次误判“是否还有代码没有合并”。2026-05-20 至 2026-05-21 对 OSS 与 Enterprise 两个仓库做了本地 stash、backup refs、worktree、临时远端分支清理。

清理原则：

- 已确认在 `main` 的内容直接删除对应 stash / backup ref / worktree。
- 明确无产品价值的 stash / 测试生成物不合入，例如 token/env/storage-state 残留；当前 Agent 分支不处理仓库中已有的 tracked storage fixture。
- Designer / Page Designer 相关内容暂不处理，等待 `feat/unified-designer-workbench-v3` 收口后统一判定。
- 不在 canonical 仓库切 feature 分支；复杂后续改动走 `.worktrees/*`。

## 当前仓库状态

| 仓库 | canonical 路径 | 当前分支 | 工作区 | 说明 |
|---|---|---|---|---|
| OSS | `/Users/ghj/work/auraboot/auraboot` | `main` | clean | 已推送至 `origin/main`，最新提交 `1bb94a657 fix(meta): preserve virtual model capabilities` |
| Enterprise | `/Users/ghj/work/auraboot/auraboot-enterprise` | `main` | clean | stash 已清空 |

保留中的开发 worktree：

| worktree | 分支 | 处理策略 |
|---|---|---|
| `/Users/ghj/work/auraboot/.worktrees/unified-designer-workbench-v3/auraboot` | `feat/unified-designer-workbench-v3` | Designer 正在开发，暂不清理、不合并、不删 stash |
| `/Users/ghj/work/auraboot/.worktrees/unified-designer-workbench-v3/auraboot-enterprise` | `feat/unified-designer-workbench-v3` | Designer 正在开发，暂不清理、不合并、不删 stash |
| `/Users/ghj/work/auraboot/.worktrees/agent-non-designer-hardening/auraboot` | `followup/agent-non-designer-hardening` | 本记录及后续 Agent 非 Designer hardening 使用 |

## 已删除内容

### Backup refs

| 仓库 | 已删除内容 | 判定依据 |
|---|---|---|
| OSS | `refs/backup/worktree-cleanup-20260520-232145/*` | 每个 ref 都已确认是 `main` ancestor |
| Enterprise | `refs/backup/worktree-cleanup-20260520-232145/*` | 每个 ref 都已确认是 `main` ancestor |

### Enterprise stash

Enterprise stash 已清空。

| 类型 | 数量 | 判定依据 |
|---|---:|---|
| exact-merged stash | 5 | patch 已由 `main` 覆盖 |
| remaining stash | 6 | 逐个盘点后确认已合并、无产品价值或不应继续保留 |

被清理的剩余 stash hash：

- `ef8b3cf...`
- `b5aeb5...`
- `c68078...`
- `394201...`
- `8143de...`
- 另 1 个当时按 `stash@{0..5}` 顺序删除，删除后 Enterprise stash list 为空

### OSS stash

| 类型 | 数量 / 内容 | 判定依据 |
|---|---|---|
| exact-merged stash | 4 | patch 已由 `main` 覆盖 |
| `web-admin/tests/storage/*.json` / token / env 残留 | 多个 | 用户确认无价值，且属于本地认证状态垃圾；这是指 stash / 测试生成 diff，不代表当前 Agent 分支删除 tracked fixture |
| CommandFieldMapExecutor 相关 | 1 类 | 已在 `main` 覆盖或失去独立合并价值 |
| ga-e2e redis 相关 | 1 类 | 已在 `main` 覆盖或环境临时项 |
| AuraPluginManager / TestSeed partial | 1 类 | 已在 `main` 覆盖或后续另有正式修复入口 |
| Shadow Runs helper/menu | 1 类 | 已在 `main` 覆盖 |
| semantic model metadata | 1 类 | 已在 `main` 覆盖 |
| large agent/tool-loop covered stash | 1 类 | Agent runtime 重构已覆盖 |
| showcase/view-management E2E covered stash | 1 类 | 已在 `main` 覆盖 |
| memory-promotion covered stash | 1 类 | 已在 `main` 覆盖 |
| gap260 covered stash | 1 类 | 已在 `main` 覆盖 |
| BPM execution-scope vars covered stash | 1 类 | 已在 `main` 覆盖 |

### 临时远端分支

| 分支 | 状态 | 说明 |
|---|---|---|
| `fix/cleanup-stash-virtual-model-capabilities` | 已删除 | 该分支仅用于保护规则拒绝直推时兜底；`1bb94a657` 已经推入 `origin/main` 后删除 |

## 当前保留的 OSS stash

以下 stash 仍保留，原因是包含 Designer / Page Designer 相关修改，按用户要求等待 `feat/unified-designer-workbench-v3` 收口后再统一判定。

| 当前 ref | hash | stash message | 主要内容 | 保留原因 |
|---|---|---|---|---|
| `stash@{0}` | `d923620e13937c7e10cdfa66d1e52f5a4c11882d` | `On main: untracked aurabot dev changes` | 混合 AuraBot、Vite、Designer list-config；非 Designer 片段当前 `main` 已有同等或更新实现 | 含 Designer list-config 修改，暂不拆删 |
| `stash@{1}` | `67b1fdf606a73a36df13fdf5c2a78d6183ea0965` | `On main: gap261-pre-merge` | Page Designer multi-view/list-config DSL 片段 | Designer 相关，等 unified designer 收口后判定 |
| `stash@{2}` | `4a5b5e9d2cab6eef3c758d235d78ac59946a00b4` | `On feat/acp-memory-scope: WIP-bpm-merge-handoff: preserve feat/acp-memory-scope dirty before merge bpm-real-e2e to main` | `schema.sql` Page Designer 测试适配；未跟踪的 Agent approval outbox / virtual model 测试已单独盘点 | 非 Designer 有价值项已合入 `1bb94a657`；剩余含 Page Designer E2E 片段，暂留 |
| `stash@{3}` | `fcd536e7c0c11ac48cc14d50910809c5e962cd7e` | `WIP on main: 2960320f fix(announcement): add confirm keys to state actions, stabilize E2E tests` | Page Designer core spec、BlocksDesigner testid、workflow demo 文档、test fixture alias | 非 Designer 有价值项已合入 `1bb94a657`；剩余 Designer 测试/组件片段暂留 |

## 已从保留 stash 拆出并合入的非 Designer 内容

提交：`1bb94a657 fix(meta): preserve virtual model capabilities`

| 来源 | 合入内容 | 验证 |
|---|---|---|
| `stash@{2}` untracked | `ModelControllerCreateVirtualModelIntegrationTest` | `./gradlew :test --tests com.auraboot.framework.meta.controller.ModelControllerCreateVirtualModelIntegrationTest ...` pass |
| `stash@{3}` | `FixtureRequest.fixture` 兼容别名 + 单测 | `./gradlew :test --tests com.auraboot.framework.test.dto.FixtureRequestTest ...` pass |
| 现场修复 | `ModelCapabilities` builder 添加 `@JsonPOJOBuilder(withPrefix = "")`，修复 request JSON capability 布尔字段丢失 | 同上集成测试先失败后通过 |
| 现场修正 | `workflow-demo` 设计文档按当前 12 节点真实流程更新 | `git diff --check` pass |

## 后续处理规则

1. Designer / Page Designer 相关 stash 只在 `feat/unified-designer-workbench-v3` 收口后处理。
2. 处理 Designer stash 时必须先对比 unified worktree 最新实现；已覆盖则删除，未覆盖则拆分成独立 patch 合并。
3. 非 Designer Agent 后续任务在 `followup/agent-non-designer-hardening` worktree 完成，不复用 Designer worktree。
4. 清理任何 stash 前先记录 `git stash show --stat` 和 `git stash show --name-status` 的结论；有业务价值但未覆盖时先恢复到隔离 worktree。
5. 任何直接推 `main` 的 admin bypass 都必须恢复 branch protection，并在记录中说明原因。
