---
type: handover
status: shipped
created: 2026-06-21
---

# Session Handover - 2026-06-21 · telemetry merge + docs-gate restore

## Session Summary

纯收口/治理会话:把统一遥测平台的两个 OPEN PR 合并到 OSS main,顺手修复合并时发现的 main docs-governance 门禁存量债,并把过程中踩到的「docs 门禁跨仓 distilled_to 假阳性」陷阱固化到 enterprise canonical。三件事全部闭环并实测绿,无 in-flight 工作。

## Tasks Completed
- [x] **合并遥测 PR #909 + #910**(OSS):#909 OTel trace correlation(squash `56659b508`)、#910 telemetry SoT 文档(squash `2bf43948f`)。合并前用 `merge-tree` 验干净合入、零文件重叠、Flyway 无版本撞;boundary+gitleaks 绿。两 worktree(obs-p1 / unified-telemetry-platform)收口。
- [x] **修复 OSS main docs-governance 门禁(PR #948,squash `79150a86c`)**:main 既有 11 个 frontmatter error(门禁全红、对新 doc PR 无信号),纯 frontmatter 修复 7 文件(3 handover 补 frontmatter、4 backlog 修 status/type/`date→created`),零内容改动。
- [x] **固化跨仓门禁陷阱(ENT PR #618,squash `73eb6198f`)**:`engineering-gotchas/worktree-multirepo.md` 新增「docs-governance 跨仓 distilled_to 解析依赖 worktree 与 sibling 仓同级」一节 + README 关键字表/锚点。
- [x] **memory 更新**:`MEMORY.md` 统一遥测条目 PR 状态 OPEN→MERGED。

## Tasks In Progress
无。本会话三条任务全部 MERGED,无未完成项。

## Key Decisions
| Decision | Chosen Approach | Rationale | Alternatives |
|----------|----------------|-----------|--------------|
| #910 docs 门禁 6 error 是否阻塞合并 | 不阻塞,正常合并 | 6 error 全是别的文档的存量债(`pre-existing-stale`),#910 自身 2 文件零违规,main 本就红 | 先修门禁再合 #910 → 不必要耦合 |
| 修 docs 门禁的 status 取值 | 已合并完成用 `shipped`、待办用 `active` | `shipped` 不触发 precip 门禁(只 `closed` 触发 distilled_to 要求);`active` <60d 不触发 stale | 全用 `closed` → 需逐个补 no-precip 标记 |
| 用 `--admin` 绕过 branch protection | 是 | 本仓 Actions 已关、3 个 required check 永不上报、`enforce_admins=false`;合并全靠本地门禁(memory `project-actions-disabled-billing`) | 等 CI → 永不会绿 |

## Files Changed
本会话改动均已 MERGED,按 PR 归类:
### OSS #948 (docs frontmatter)
- `docs/backlog/2026-06-19-chatbi-dashboard-renderer-convergence-slice.md` — status done→shipped
- `docs/backlog/2026-06-19-dynamicdata-relations-unwired-coverage-finding.md` — type finding→backlog、status open→shipped、date→created
- `docs/backlog/2026-06-19-ux-golden-derived-followups.md` — status open→active、date→created
- `docs/backlog/2026-06-20-command-handler-extension-duplication-and-dslpersistence-cleanup.md` — status open→active、date→created
- `docs/handover/HANDOVER-2026-06-18-export-perm-and-owner-reference.md` — 补 frontmatter (active)
- `docs/handover/HANDOVER-2026-06-18-ux-t4-t6-interaction-golden.md` — 补 frontmatter (shipped)
- `docs/handover/HANDOVER-2026-06-19-aurabot-viz-convergence-complete.md` — 补 frontmatter (shipped)
### ENT #618 (gotcha 固化)
- `docs/agent-rules/engineering-gotchas/worktree-multirepo.md` — 新增跨仓门禁陷阱区段 + 关键字行
- `docs/agent-rules/engineering-gotchas/README.md` — 关键字表行 + 锚点目录条目

## Pitfalls & Workarounds
1. **docs 门禁在非同级 worktree 报 5 个跨仓 `S-DOCS-DISTILL-UNRESOLVED` 假阳性**
   - **Root Cause**: `check-docs-governance.mjs` 的 `resolveDocPath` 跨仓 `distilled_to` 用 `repoRoot/../<target>` 解析;detached worktree 在 `/Users/ghj/work/<name>`,其 `..` 没有 sibling 仓 → 解析失败。canonical/CI 同级布局不受影响。
   - **Solution**: 把验证 worktree 移到 `/Users/ghj/work/auraboot/<name>` 下复跑 → 0 error。终极验证在 `git worktree add --detach /Users/ghj/work/auraboot/<verify> origin/main` 上跑。
   - **Prevention**: 已固化(ENT #618)。跑 docs 门禁的 worktree 必须与 sibling 仓同级。
2. **ENT gotcha 分支 base stale**:worktree 基于 `e0d20170e`,带 5 个 docs error;并发会话 #617 已修。`git rebase origin/main` 后归零。
   - **Prevention**: AGENTS §git-workflow「worktree off stale base」已有红线;本会话正确处理(rebase)。
3. **`gh pr merge` 瞬时 `graphql: EOF` 网络错误**(#948、#618 各一次):重试即成功,非真失败。

## Lessons Learned
- docs 门禁全树扫描会暴露 `--git` changed-files 模式掩盖的 main 存量债;main 可能早已 drift 红而 PR-scoped 检查全绿。
- 跨仓 `distilled_to` 解析对运行位置敏感——同一 commit 不同 checkout 位置结论相反,见到一批「全指另一仓」的 unresolved 先 `ls repoRoot/../<target>` 证伪,别盲改别人的文档。

## 反思与经验固化 (Reflection & Codify)
### 本会话弯路 / 返工 / 翻车
1. **docs 门禁跨仓假阳性绕了一圈** — 代价:~1 个验证周期(在非同级 worktree 跑出 5 error → 诊断 → 移位复跑) — 本可如何更早避免:第一次就把验证 worktree 建在 `/Users/ghj/work/auraboot/` 下 — 根因:`[B 输入/工具]`(门禁解析对运行位置的隐式依赖无文档),已正确按 `[D 验证]` 处理(证伪而非盲改)并固化。
2. **ENT 分支 base stale** — 代价:微(rebase 一次) — 根因:`[B 输入]`(并发会话推进 main);已有红线、正确处理。
- 其余顺畅,无重大弯路。

### 为什么会发生(根因归类小结)
主要是 **B(工具/输入)**:docs 门禁的跨仓解析依赖运行位置这一隐性行为此前无文档。已通过 ENT #618 固化消除。验证纪律(D)本会话执行到位——假阳性被证伪而非盲改。

### 应该有哪些改进
- 已落地:把跨仓门禁陷阱写进 `worktree-multirepo.md`(症状/根因/处理)+ README 关键字,弱 agent grep 可达。
- (可选,留 owner)docs 门禁可在检测到「worktree `..` 无 sibling 仓」时打印一行提示「跨仓 distilled_to 将无法解析,请在 canonical 同级位置复跑」——避免下次再绕。未做,ROI 偏低。

### 已固化 / 待固化(更新文档)
- [x] 已写入 `auraboot-enterprise/docs/agent-rules/engineering-gotchas/worktree-multirepo.md`(ENT #618 MERGED `73eb6198f`):跨仓 distilled_to worktree-location 假阳性陷阱。
- [x] 已写入 `auraboot-enterprise/docs/agent-rules/engineering-gotchas/README.md`(同 PR):关键字表行 + 锚点。
- [x] 已更新 `MEMORY.md`:统一遥测条目 PR OPEN→MERGED。
- [ ] (可选,留 owner)docs 门禁脚本加 worktree-location 提示——ROI 低,未做。

## 运行态快照 (Operational State)
### 分支 / Worktree / PR
- **当前分支**:本 handover 在 worktree `chore/handover-2026-06-21-telemetry-docs`(base main);canonical OSS/ENT 均在 `main`。
- **Worktree**:本会话开的 obs-p1 / unified-telemetry-platform / auraboot-docs-gov / auraboot-ent-docsgotcha 及各临时 verify worktree **全部已 remove**。残留 worktree 均属其它会话:`auraboot-redis-bean` / `auraboot-behavior-analytics-spec` / `auraboot-cov6` / `auraboot-form-record-source`(未触碰)。
- **本会话关键 commit(均已 squash 到 main)**:OSS `56659b508`(#909)/ `2bf43948f`(#910)/ `79150a86c`(#948);ENT `73eb6198f`(#618)。
- **PR**:OSS #909 MERGED / OSS #910 MERGED / OSS #948 MERGED / ENT #618 MERGED。全部远端分支 auto-delete-on-merge 已清。
- **未提交改动**:无(本 handover 文件除外,待 PR)。

### Runtime / 端口
- **本会话未 allocate 任何 runtime、未起任何后端/Vite/BFF/隔离栈**(纯 git + docs 操作)。`.workspace/allocations.tsv` 未触碰。无端口占用归属本会话。

### Database / Seed 状态
- 未碰任何 DB / seed / reset。

## Next Steps
1. (本 handover PR 合并后)无强制后续——三条任务已闭环。
2. 遥测平台剩余多周重型项(各自独立、适合新会话专门起):前端 SDK + dashboard、Kafka 解耦、A-G4 跨语言、P2-5 OTel/ClickHouse/Flink、M2-4。起点见 SoT `docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md` §12。
3. 其它 active work(与本会话无关,见 MEMORY.md):commerce Phase3 tokens、OSS 覆盖率→80 等。

## Context for Next Session
- 遥测 SoT(已 live 在 main):`docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md`
- 跨仓门禁陷阱(新固化):`auraboot-enterprise/docs/agent-rules/engineering-gotchas/worktree-multirepo.md` §「docs-governance 跨仓 distilled_to…」
- docs 门禁正确跑法:co-located worktree(`/Users/ghj/work/auraboot/<name>`)+ `bash scripts/check-docs-governance.sh`;`--admin --squash` 合并(本仓 CI 已关)。
