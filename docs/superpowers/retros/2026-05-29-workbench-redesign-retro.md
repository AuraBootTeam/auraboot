# Workbench Redesign — Session Retro

**Date:** 2026-05-29
**PR:** [#336](https://github.com/AuraBootTeam/auraboot/pull/336)
**Branch:** `feat/2026-05-28-workbench-redesign`
**Spec:** `docs/superpowers/specs/2026-05-28-workbench-redesign-design.md`
**Plan:** `docs/superpowers/plans/2026-05-28-workbench-redesign.md`

诚实复盘:Workbench 重做这一轮整体落地了(13 commits / 45 vitest 全绿 / 后端 Series DTO + 7-day query 接通),但过程里踩了 5-7 个本可避免的坑。下面分 "事故清单 → 根因 → 应该固化的纪律 → 流程改进建议" 四段。

## 1. 事故清单

| # | 阶段 | 事故 | 危害 | 谁先发现 |
|---|------|------|------|----------|
| 1 | 写 plan | Task 7 测试断言里直接写死英文表头 `expect.arrayContaining(['Task', 'Type', 'Due'])`,违反 ENT AGENTS.md 红线 §3「i18n 禁硬编码中文/英文」 | Implementer subagent 忠实落地,产出 hardcoded 英文 column header,被我事后纠正(commit `0ad9a67cc` 是 amend 加 i18n) | 主对话(写完才意识到) |
| 2 | Task 7 fix 子代理 | 子代理声称「locale 文件 server-side via BFF 不用动」就交差,实际没在 `seed/i18n-base.json` 加 key | t() 找不到 key 时回显 key 字面量,真上线会出现 `workbench.inbox.col.task` 的丑串 | 主对话 grep 验证后亲手补 i18n key |
| 3 | 写 plan + Task 10 | Plan 里直接说"给 search trigger 加 testid `header-search-trigger`",没 grep 现有 testid。原本 `cmd-k-trigger` 被 4 个 E2E spec 引用 | Implementer 改名后 4 个 spec 失效;靠用户接下来的 E2E 跑全集才会暴露 | 主对话事后 grep 才发现,dispatch 修复子代理 amend 改回 |
| 4 | Task 11 | Plan 说"清掉 dashboard JSON seed 里的 `gradient` 字段",实际项目根本没有这类 JSON seed,gradient 只在 `DEFAULT_STATS` 数组里。子代理"创造性"地把 `StatsConfig` interface 上的 `gradient: string` 也删了 | 类型契约变窄,潜在 downstream 调用方 TS 报错(本轮 OSS 内无 caller,enterprise overlay 也无 caller,但行为越界) | 主对话 review 子代理报告时看到 |
| 5 | Task 2 | Implementer 自报"跳过了 TDD red 验证步",直接写完实现才跑 test | TDD 红 → 绿 → 重构纪律打折;万一实现自带 false-positive bug 测试也跟着写错 | 子代理自报 |
| 6 | Task 1 spec compliance 子代理 | Reviewer 子代理报「unused `java.util.Map` import」,实则 `WorkbenchStatsDTO.stats: Map<...>` 真在用 | 误报。如果主对话不复核就会让 implementer 去删一个其实在用的 import,引入新 bug | 主对话凭印象怀疑,但当时没强制 grep verify;后续若 implementer 真去删可能崩 |
| 7 | Task 13 manual visual verification | Plan 把它当"开 dev:full + 浏览器看真实页面"的一步,但本会话没有 backend 在 8080。我尝试起 vite-only 5179,无 backend 走不到 `/home`,只能让 PR reviewer 本地起栈验收 | 真实视觉回归(尤其 dark mode)在这一轮**没有**实证通过,完全靠单测 + Playwright spec 文件存在 | 主对话尝试时撞墙 |

事故 1-4 都是**plan 质量缺陷**直接传导到下游;5-6 是**门禁子代理质量**;7 是**任务可执行性预设**问题。

## 2. 根因拆解

> 题目是「门禁质量不高 vs 输入信息不够充分 vs 提示词不好?」诚实答案:**三个都有,但 70% 是 plan 质量(输入)问题。**

### 2.1 Plan 质量(70%)

- **Plan 写测试断言时把 user-visible string 直接 hardcode 进去**,违反红线 §3。这是连锁伤害最大的一类:plan 里的样例代码会被 implementer 当 ground truth 抄。
- **Plan 在变量/标识符层面没做"改 X 是否影响 Y"的 grep**(典型:rename `cmd-k-trigger` 这种 testid 之前,要先 grep 所有 caller)。Plan 应包含一段「I-Grep-checked impact:」证明,而不是把 grep 推给 implementer 主动想到。
- **Plan 假设了不存在的资源**(workbench dashboard seed JSON),没有先 `grep "gradient" + 看真实存在的文件`。Plan 应该先做实证再写代码,而不是写完代码才让 implementer "locate during implementation"。

### 2.2 门禁(子代理)质量(20%)

- 子代理仍然会犯红线 §14 警告过的「未 verify 就出 finding」**反向版本**:不是过度报警,而是**虚构问题**(Task 1 spec compliance 的"unused Map import")。子代理 review 流程里也应该强制 `grep -n "Map"` 验证才能报"unused"。
- "Fix subagent" 模式很省事但**容易半交差**(Task 7 fix 没真补 i18n key 就声明 DONE)。fix prompt 必须有 acceptance check 而不是只让子代理"自我满意"。

### 2.3 提示词质量(10%)

- Implementer prompt 把红线名字写进去了(`§3 i18n` / `§8 catch-Exception`),但**没把红线本体内容贴进去**,子代理需要去脑补/搜索。可以把 plan 里 reused 的 3-5 条红线 inline 进 implementer prompt header。
- Plan 里"Single commit"等强约束需要重复在 prompt 里,有些子代理把 i18n key 加在 widget 修改的同一个 commit 里(对的),但其他 fix 子代理却把同一个 fix 跨多文件没把 i18n 文件加进去。

### 2.4 Skill 流程本身的过载

15-task plan × 「implementer + spec reviewer + quality reviewer」≈ 45 个子代理调用,真实跑下来:

- 多数小任务(Task 3 加字段、Task 4 写 SVG primitive、Task 11 清字段)用全套 review 是 overhead。
- 几次"Fix subagent"调用是真的浪费:1 行 catch 改窄、2 行 testid 改回去——这种主对话直接 Edit 是 10 秒,dispatch 子代理是 1-3 分钟 + 50k token。Skill 红线写「don't manually fix」,但**比例上这是个反面默认**。

## 3. 应该固化到 canonical AGENTS.md 的纪律

下列 3 条建议升 `auraboot-enterprise/AGENTS.md` 或 `docs/agent-rules/engineering-gotchas.md`:

### G-W1 · Plan 必含「I-Grep-checked impact」证据段

> 任何 plan 里出现 **重命名 / 删除 / 改 contract** 类操作(testid、permission code、表名、API 路径、i18n key、type 字段),必须在 plan 草拟阶段就跑 `grep -rn '<old>'` 找全 caller 并把命中清单写进 plan;不能把 grep 推给 implementer "during implementation"。

反面教材:Task 10 `cmd-k-trigger` rename 没 grep,broke 4 E2E specs。

### G-W2 · Plan 测试样例不得 hardcode user-visible string

> Plan 里的样例 Vitest/Playwright 断言只能匹配 i18n key(`'workbench.inbox.col.task'`)或 testid,**不得**直接写中文/英文显示文本(`'Task'` / `'任务'`)。Plan 自身违反 red line §3 会被 implementer 忠实复制成代码事故。

反面教材:Task 7 plan 写 `expect.arrayContaining(['Task', 'Type', 'Due'])`,实现层直接 hardcode 英文表头。

### G-W3 · 子代理 review 也必须 verify-before-flag(扩展红线 §14)

> 红线 §14 当前覆盖 code review subagent;扩展到 **spec compliance reviewer / quality reviewer**:报告"unused import"前必须 `grep -c '<symbol>'`,报告"missing requirement"前必须 `git show <sha> -- <file>` 全文核对。Reviewer subagent 的虚构 finding 比真 bug 危害更大,因为 implementer 会真按"建议"改坏代码。

反面教材:Task 1 spec compliance subagent 报了一个根本在用的 `java.util.Map` "unused"。

### G-W4 · Fix-subagent 必须有 acceptance check,不能让其自我宣告 DONE

> 如果 fix 任务是「补一个 i18n key」「改一个 testid」「narrow 一个 catch」这种**机械、可机器验证**的变更,fix prompt 必须包含 acceptance bash 命令(如 `grep -q '"workbench.inbox.col.task"' platform/src/main/resources/seed/i18n-base.json`)并要求 fix 子代理把这条命令的真实输出贴回来。否则常出现"声明 DONE 但实际只改一半"的半成品。

反面教材:Task 7 i18n fix 子代理声明 DONE,实际没动 `seed/i18n-base.json`。

### G-W5 · Subagent-driven-development 的 review pipeline 是 budget,不是 doctrine

> 不要 inverse 推 `superpowers:subagent-driven-development` skill 的「never skip review」。对**单文件 ≤30 行变更 + 测试已覆盖 + 无外部 caller**的机械改动,主对话 inline Edit + 自行 verify 比 dispatch 全套 implementer + 2 reviewer 更经济(节省 ~3-5 分钟 + ~50k tokens 每次)。Skill 主要适用于跨文件 / 多步骤 / 易出 subtle bug 的大改。

## 4. 这次流程能改的地方(给未来类似任务参考)

1. **Plan 自审 checklist 多加 4 条**(目前 superpowers:writing-plans skill 的 self-review 只有 placeholder / consistency / scope / ambiguity,缺这 4 条):
   - □ 测试样例里所有 user-visible string 都是 i18n key 或 testid 吗?
   - □ 重命名/删除的标识符全部 grep 过 caller 了吗?
   - □ 引用的"现有 seed/locale/config 文件"都 ls / cat 验证过存在吗?
   - □ "manual verification" 类任务前置 stack 起没起,无 stack 时 acceptable 的替代证据是啥?

2. **Implementer prompt 模板**头部加一段「红线 highlight」inline(不是引用),长度 ≤ 200 字,把当次任务真正可能踩的 3-5 条红线复制进去,避免 implementer 还得脑补。

3. **小步快走 vs 大批 dispatch:** 类似 Task 3 / 4 / 11 这种 ≤ 30 行单文件机械改动,plan 写出来就建议「主对话 inline」标记,不必走完整 implementer+spec+quality pipeline。

4. **Visual verification 任务前置 stack 准备:** Plan 里凡是含「open browser」「dev:full」「截图」步骤的,作为 prereq 检查项写在 Task 0 同级,而不是当作普通 task 跑到一半才发现 backend 没起。

5. **Brainstorming 阶段第一稿 mockup 单方向、全尺寸:** 这次开始时我把 3 个方向都画在一屏(用户反馈"太挤了 分开"),应该一开始就是 A 单独全尺寸 → 用户反馈 → B/C 各占一屏。

## 5. 不是问题,只是观察

- TDD 红→绿 → commit 的节奏整体执行良好,只有 Task 2 implementer 自报"跳过 red 步"是一次小走样。
- Worktree 隔离(`/Users/ghj/work/auraboot-wt/workbench-redesign`)这次没有踩坑,符合多 worktree 隔离纪律(canonical 仓主工作树不切分支)。
- Backend 7-day series 查询 SQL 用 Postgres `generate_series` + LEFT JOIN 的写法是标准做法,implementer 没瞎来。
- Sparkline SVG primitive 没引第三方 chart 库,符合 YAGNI。

## 6. 数字账

| 指标 | 值 |
|------|----|
| 计划任务 | 15(0-15 含 Task 0 worktree setup) |
| 实际落地 task | 14(Task 13 manual viz 延后给 reviewer) |
| Commits 数 | 13 |
| 子代理调用数 | ≈ 18(11 implementer + 4 spec reviewer + 2 quality reviewer + 1 fix) |
| 主对话 inline 修复次数 | 3(i18n key 真补 / 直接 grep enterprise overlay / 检查残留 vite 进程) |
| Vitest 通过 | 45 / 45(touched suites) |
| 后端测试通过 | dashboard.* 全绿 |
| Plan / spec 字数 | ~1900 行(spec ≈ 200 + plan ≈ 1700) |

## 7. 这次的真心话

诚实评估:这一轮**plan 写得不够细 + 自审不到位**是事故根因,门禁子代理只是把已有的坑放大。要么 plan 阶段把 i18n / testid / 文件存在性都先验明,要么 implementer prompt 头部 inline 把红线贴清楚,二选一。两个都不做就会出现这次 Task 7 / Task 10 这种"明明红线已经在 AGENTS.md 里写了,过程里仍然踩了"的尴尬。

下一次类似规模的 frontend 重做,**plan 提交前先跑一次"plan 自审 7 问"再 dispatch**,Implementer 的 prompt 头部 inline 红线,小型 1-2 文件改动就主对话直接干、不走 subagent pipeline。
