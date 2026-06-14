# Gerber Viewer Admin Main Merge Record

日期: 2026-06-14

## 合并结论

- `aura-quote` 提交 `60edcf9 feat: render real gerber board artifacts` 已在 `origin/main`。
- `auraboot` 原提交 `4e5d2941b feat: support gerber board svg artifacts` 已通过 admin/direct merge 方式合入 `main`。
- `auraboot` main 合入后的提交为 `31f79b655 feat: support gerber board svg artifacts`。

## 合并方式

直接 merge `codex/add-workbench-followup-regressions` 分支时出现大量与本次 Gerber viewer 无关的历史冲突。为避免把无关变更合入 `main`, 本次改为精准 cherry-pick Gerber viewer 提交 `4e5d2941b` 到 `main`。

## 合入范围

- `web-admin/app/framework/meta/rendering/blocks/GerberViewerBlockRenderer.tsx`
- `web-admin/app/framework/meta/rendering/blocks/__tests__/GerberViewerBlockRenderer.test.tsx`

## 后续动作

- `main` 推送前需要执行 targeted Gerber viewer 测试。
- 原开发 worktree 中的未提交本地改动保持原样, 不纳入本次 main 合并。
