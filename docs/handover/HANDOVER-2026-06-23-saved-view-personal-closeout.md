---
type: handover
status: shipped
created: 2026-06-23
slug: saved-view-personal-closeout
distilled_to:
  - auraboot-enterprise/docs/system-reference/subsystems/01-用户视图SavedView.md
  - auraboot-enterprise/docs/system-reference/core/09-DSL能力边界完整参考.md
  - auraboot-enterprise/docs/agent-rules/engineering-gotchas/e2e-playwright.md
  - auraboot-enterprise/docs/agent-rules/engineering-gotchas/worktree-multirepo.md
---

# Session Handover — 2026-06-23 · SavedView Personal-only Closeout

## Session Summary

本轮把 SavedView 从“长期飞书平齐但范围混杂”的状态收束到 Personal-only release-candidate：当前只做个人视图，不做 shared/team/global，也不做 platform-wide pid-only migration。代码侧修复了 `view + preset` 重复入口、quick filter toggle 旧状态、放弃变更不彻底、默认视图管理边界、隐藏列 autosave 和 E2E fixture 污染；文档侧补齐 baseline、feature matrix、core concepts 和企业 system reference。

## Tasks Completed

- [x] 修复 URL 状态：`view=` 优先于 `preset=`，避免进入个人视图时仍显示 active preset。
- [x] 修复 quick filter toggle：使用最新 active preset ref，防止 callback stale state 导致二次点击无法取消。
- [x] 修复 personal dirty discard：从 `?view=...&sort=...` 打开后，点击"放弃变更"会恢复已保存配置并清除临时 URL 状态。
- [x] 修复默认视图边界：隐式默认视图不作为普通个人视图管理，但 selector 可恢复默认。
- [x] 修复隐藏列 autosave 回归：header menu 的 Hide Column 继续保存到当前/default SavedView。
- [x] 收敛 E2E fixture：创建个人视图的 specs 清理或复用自身前缀，避免 personal 10 quota 被长生命周期 runtime 污染。
- [x] 更新文档：baseline、requirements 历史矩阵、post-PR gap tracker、FEATURE_MATRIX、core concepts、handover。
- [x] 更新企业 system reference / gotchas：SavedView scope 边界、DSL SavedView 入口、URL/quick filter 规则、E2E 与 worktree 经验。

## Explicitly Out Of Scope

| Item | Treatment |
| --- | --- |
| shared/team/global UI | 后续路线，本轮不做 |
| 协作者、共享保存 diff、共享 audit | 后续路线，本轮不做 |
| team/global quota 20 UI | 后续路线，本轮不做 |
| platform-wide dynamic record pid-only migration | 其他会话处理，本轮不做 |

## Verification

| Gate | Result |
| --- | --- |
| SavedView scoped E2E | `101 passed, 4 skipped` on local runtime `5186/6486/6186` |
| Frontend focused Vitest | `6 files passed, 83 tests passed` |
| `git diff --check` | PASS |
| e2e-truth audit | PASS with documented exceptions: skipped rows are fixture/AIR deferred; direct `/p/` only for URL contract regression |

## Lessons Learned

1. **不要让 plan/backlog 代替 SOT。** SavedView 这轮真正稳定的是 scope、URL 状态、默认视图、隐藏开关和配额，这些必须进 system reference；过程文档只能反链。
2. **不要在同一需求里继续分裂 worktree。** enterprise mockup、system reference、OSS implementation 必须明确谁是引用源、谁是实现源；实现只保留一个活跃 worktree。
3. **SavedView 是状态系统，单 spec pass 不够。** URL、preset、default、quota、fixture 数据会跨 spec 互相污染，必须跑 scoped regression 和 e2e-truth 后才能说完成。
4. **默认视图不是普通个人视图。** UI 可以显示"默认视图"入口，但不能把 implicit autosave 行暴露成可重命名/删除/设默认的普通个人视图。
5. **快捷筛选不是 SavedView selector 的替代。** toolbar chips 只能出现一处；保存为个人视图后应切换到 `view=`，不能保留 `preset=` 叠加。

## Operational State

- OSS worktree: `/Users/ghj/work/auraboot/.worktrees/saved-view-discard-default`
- OSS branch: `codex/saved-view-discard-default`
- Enterprise docs worktree: `/Users/ghj/work/auraboot/.worktrees/enterprise-saved-view-personal-closeout`
- Enterprise docs branch: `codex/saved-view-personal-closeout-docs`
- Runtime used for final scoped E2E: frontend `5186`, backend `6486`, BFF `6186`
- Generated Playwright auth storage files under `web-admin/tests/storage/` were refreshed by local E2E and should not be staged unless a test deliberately changes canonical storage.

## Next Steps

1. Commit OSS code/docs and enterprise system-reference docs as separate PRs.
2. Run targeted code review before merge; findings must cite file/line and distinguish code risk from roadmap scope.
3. Admin merge both PRs to `main`, push, then pull/update local main worktrees.
