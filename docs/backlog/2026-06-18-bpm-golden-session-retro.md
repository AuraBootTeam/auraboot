---
type: retro
status: closed
created: 2026-06-18
distilled_to:
  - auraboot-enterprise/docs/agent-rules/engineering-gotchas/e2e-playwright.md (OSS host-first golden bring-up runbook)
  - auraboot-enterprise/docs/agent-rules/engineering-gotchas/backend-spring-db.md (BusinessException no-cause-chain)
  - auraboot-enterprise/AGENTS.md (§15 BLOCKED-UPSTREAM 自估悲观重判 — already codified this lineage)
---

# 复盘 — BPMN 设计器剩余 gap 完成 + 真栈全量回归会话(2026-06-18)

> 关联:`2026-06-18-bpmn-remaining-gaps-final-report.md`(最终报告)、`2026-06-17-bpmn-designer-golden-gap.md`(tracker)、`2026-06-18-sequential-mi-countersign-analysis.md`(SEQ-01)。
> 范围:owner「继续完成全部 gap → 真机跑完 → 最终测试报告」+ 追加「补 G-T5 真浏览器 golden」。

## 0. 结果先行(诚实定性)

**本会话交付质量高、零 ship 缺陷**:SEQ-01(SmartEngine 4.0.2)/ GAP-252 receiveTask message / G-B3 / G-B4 / G-T5 全部真栈验证 + 全 MERGED;全平台 11410 测试回归 99.55%,51 失败逐类核验**无一可归因于本轮改动**;1 项(inclusiveGateway completionCondition)带书面理由有意识保留。

但**迭代过程中的摩擦不少**(≈13 处 retry / 返工)。**关键澄清:这些几乎全是「测试迭代环 + host 栈搭建」的操作摩擦,不是 ship 出去的 bug**。下面逐条记清,并做根因四分类回答 owner 的问题「为什么这么多问题」。

## 1. 摩擦 / 返工逐条清单(不遗漏)

| # | 现象 | 代价 | 根因类 |
|---|------|------|:---:|
| 1 | `./gradlew` 在 worktree **根目录**跑 → 不存在(gradle root 是 `platform/`) | 1 次空跑 | A + 我未先确认 |
| 2 | fresh worktree 缺 `gradle-wrapper.jar`(gitignored)→ 需从 canonical seed | 1 次失败 | A(已知坑) |
| 3 | `./gradlew test` fan-out 到 `:platform-plugin-api:test` → "No tests found" BUILD FAILED;须 `:test`(root scope) | 1 次失败 | A(已知坑 handover Pitfall#1)+ 我未先应用 |
| 4 | 自设隔离 `GRADLE_USER_HOME` → Spring Boot gradle 插件不在缓存 + 无 CN mirror init → 插件解析失败 | 1 次失败 | A(工具)|
| 5 | 给 `ProcessInstanceController` 加构造字段 → 漏 import + 既有 `ProcessInstanceControllerTest` 构造调用 arity 破 | 2 次编译失败 | D(未先 grep `new X(` 消费方)|
| 6 | G-B3 测试断言 `hasCauseInstanceOf` → AuraBoot `BusinessException` **不链 JVM cause** | 1 次测试失败 | D(假设未验)|
| 7 | 新测试文件重复 `import Test` | 提交前自查掉 | C(自身笔误)|
| 8 | 用控制字符 U+0001 作分隔符字面量 → Edit 工具无法表达 → Python 修 → 改 `'\t'` | ~2 turn | A(工具)+ 我选错分隔符 |
| 9 | 全量 `:test` 对**单个不 reset 的 DB** 跑 → 51 个隔离/env artifact 须分类 | 大量核验工时 | A(harness 无逐类 reset)+ owner「全量」要求 |
| 10 | `oss-reset-and-init.sh` 被 **dormancy 守卫**拦(7 个并发 worktree)→ G-T5 L3 seed 跑不了 | 改走 gt5 config 绕过 | B/环境(守卫**正确**生效)|
| 11 | G-T5 spec 对 `callactivity-process-key`(ProcessPicker)`.fill()` → 不是 input | 1 次测试失败 | D(假设未验)|
| 12 | bpm-regression config override testMatch → 直接传 spec path "No tests" | 1 次空跑 | A(config 结构未先读)|
| 13 | golden 环境契约缺 `BACKEND_URL`(光 `BE_PORT` 不够) | 1 次空跑 | A(契约知识不全)|
| 14 | **本复盘自身**:`git -C auraboot worktree add auraboot/.worktrees/...` 相对路径 → worktree 建到 3×-auraboot 嵌套路径,retro 文件写进 canonical gitignored `.worktrees/` 成孤儿 | ~3 turn 排查重建 | A(git -C 相对路径)+ D(建后没立刻 `rev-parse --show-toplevel` 验) |

## 2. 根因四分类(回答「为什么这么多问题」)

### A 门禁 / 工具质量(主因,≈7/14)
**这是绝对主因。** auraboot OSS 的**「host-first golden 全栈搭建 + 测试迭代环」有大量手动失败模式,且没有一个可复用的一键脚本/runbook**:
- gradle:worktree 根 vs `platform/`、wrapper jar gitignored、`test` vs `:test` scope、`GRADLE_USER_HOME` 隔离 vs 插件/mirror 解析 —— 4 个独立坑,每个都吃一次 retry。
- host 栈:bootJar→java -jar 的 SPRING_* env、bootstrap、frontend `dev:full` 的 `SPRING_BOOT_URL`/`BFF_PORT`/`VITE_PORT`、Playwright 的 `BACKEND_URL`/`PW_SKIP_WEBSERVER`、setup 链的 `02-test-pages` 看板 seed 依赖 —— 每一步一个失败面,没有一处「OSS host golden bring-up」脚本把它们串起来。
- 全量 `:test` 对单 DB 跑产生 ~0.5% 隔离/env 噪声(harness 无逐类 reset,设计是 GA reset 栈/逐套件隔离),核验成本高。
- `git -C <dir> worktree add <relpath>` 相对路径解析进 `-C` 目录 → 嵌套错位(#14);Edit 工具无法表达控制字符(#8)。

### B 输入信息(次要,≈2/14)
- host 栈 golden recipe **不在 handover/memory inline**(我从 `auth.setup.ts` + `playwright.bpm-regression.config.ts` + `bff.server.ts` + `oss-reset-and-init.sh` 逆推);上一轮 handover 仅留「auraboot slot 44」一句,env 契约靠现场读。
- 7 个并发会话的存在事前未知,靠 dormancy 守卫报错才发现(守卫本身是**对的**)。

### C 提示词 / 编排(几乎无)
- owner 三段指令清晰(完成全部 gap / 真机跑完 / 报告),aura-endgame skill 编排到位。无 C 类问题。1 处重复 import 是自身笔误,非编排。

### D 验证纪律(我的滑点,≈4/14)
- 4 处「**假设未先验**」:`BusinessException` 链 cause(#6)、`callactivity-process-key` 可 fill(#11)、gradle 目录/scope(#1/#3)、worktree 建后没立刻 `rev-parse --show-toplevel` 验路径(#14)。**这正是我一贯要求 subagent 做的 §15 verify-before-claim —— 我自己也该先 grep/读组件/验路径再动,而不是凭直觉做完再被打回。**
- **但 D 类同时也是本会话最大亮点**:verify-don't-trust 抓住 2 个假警(`@Disabled` 实为注释、`cat-file` fetch 抖动)、把 51 个全量失败重置干净 DB 逐类核验(真隔离 vs pre-existing vs env)、对 origin/main 实际内容而非汇报做终验。**净 D 是正向的。**

### 结论
**不是提示词问题(C≈0),不是输入根本不足(B 次要)。主因是 A —— OSS host-first golden 全栈搭建 + 测试迭代环缺一键脚本/runbook,手动步骤每步一个失败面。** 其次是我自己的 D 滑点(假设未先验 + 路径未验)。**代码质量高(零回归、全实证),摩擦集中在「跑起来」而非「做对」。**

## 3. 改进项

1. **(最高杠杆)写一个 OSS host-first golden 一键 bring-up 脚本/runbook**:allocate runtime → apply schema → bootJar → `java -jar`(SPRING_* env)→ bootstrap → `pnpm dev:full`(SPRING_BOOT_URL/BFF_PORT/VITE_PORT)→ Playwright 契约(PLAYWRIGHT_BASE_URL/BACKEND_URL/BE_PORT/BFF_PORT/PW_SKIP_WEBSERVER)+ 跳过无关 showcase seed 的薄 config。一脚本消掉 #1/#2/#3/#4/#10/#12/#13 ≈6 个坑。→ **已固化为 canonical runbook**(见 §4)。
2. **改码前先 grep 消费方 + 读目标组件 + 建 worktree 后立即验路径**(把要求 subagent 的 §15 应用到自己):加构造字段先 `grep -rn 'new X('`;写表单断言先读 editor 组件确认字段是 input/picker;写引擎/平台断言先确认契约(如 BusinessException 是否链 cause);`worktree add` 后立刻 `git -C <path> rev-parse --show-toplevel` 核对路径(别用 `git -C A worktree add A/rel` 相对路径)。
3. **全量回归用分类法 + 隔离重跑**,别把全量单 DB 噪声当回归:按签名分桶(DuplicateKey/Docker/LLM/pre-existing)+ 重置干净 DB 单跑确认。已实践,固化方法。
4. **host golden recipe inline 进 handover**:env 契约 + 端口 + 一键脚本指针,别只留「slot N」一句让下一轮逆推。
5. **门禁先跑后提交**:本会话 enterprise codify PR 先 commit 后才看 docs-governance gate(发现 1 个 pre-existing error,非我的)。应 commit 前先跑本仓门禁(§18 已有)。

## 4. 该固化到 canonical 的经验(精挑,precipitation gate)

> 只升真正高频可复用的,不原样把 retro 升 canonical。

| 经验 | 落点 | 状态 |
|------|------|------|
| **OSS host-first golden 全栈 bring-up runbook**(完整 env 契约 + setup 链 02-test-pages 绕过 + gt5 config 范式 + bpm-regression testMatch 坑) | enterprise `engineering-gotchas/e2e-playwright.md` | ✅ 已固化(ENT #560)|
| **AuraBoot `BusinessException(String,Throwable)` 不链 JVM cause** → 失败诊断 `log.error(...,e)` 直记 throwable,别 `hasCauseInstanceOf` | enterprise `engineering-gotchas/backend-spring-db.md` | ✅ 已固化(ENT #560)|
| **BLOCKED-UPSTREAM / 自估悲观下结论前先最便宜实证;owner 拥有上游 fork 必重判可修** | enterprise AGENTS.md §15 | ✅ 本 lineage 已固化(G-B5/v4.0.1/MI 三起) |
| 共享 host dormancy 守卫 + 多 worktree 走隔离 runtime | enterprise AGENTS.md §20 | ✅ 已有 |
| 全量单 DB 隔离 artifact / run-to-run 抖动归 pre-existing | enterprise `engineering-gotchas/test-infra.md` | ✅ 已有(并入 shared-DB flakiness)|
| 给 @RequiredArgsConstructor 组件加字段先 grep `new X(` 消费方 | 不单列(并进 main-conversation-discipline 已有「改 API 先 grep 消费方」) | — |
| gt5 config 范式(setup 缩到 00/01 跳 showcase seed) | 随 PR #802 committed(`playwright.gt5.config.ts` 带注释,自文档化) | ✅ |

## 5. 完成核对(verification-before-completion 五项)
- 方向:对齐 owner 三段指令 + G-T5 追加 ✓
- 进度:5 gap DONE + 1 带理由保留,全 MERGED,对 origin/main 实证 ✓
- gap:全量 11410 回归无新 gap;51 失败全非本轮 ✓
- UX:G-T5 真浏览器 3/3 + 截图 ✓
- 测试:真栈为主(SEQ-01/GAP-252/G-B3)、G-B4 wiring + 前端 vitest、全量回归 + 隔离核验,均如实标注 ✓
