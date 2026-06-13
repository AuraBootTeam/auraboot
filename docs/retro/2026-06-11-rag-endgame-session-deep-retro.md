---
type: retro
status: closed
created: 2026-06-11
owner: platform
topic: RAG review→G1-G10 endgame session — full process reflection, root-cause taxonomy, precipitation decisions
---

# RAG Endgame Session — Deep Retro (2026-06-11)

Covers the whole arc: RAG system review → gap tracker → /aura-endgame G1-G9 remediation
(PR #545) → residuals incl. Phase-2 eval harness (PR #547). Supersedes the short
`2026-06-11-rag-remediation-retro.md` with a complete problem inventory and root-cause
taxonomy, per owner's ask: why so many problems — gate quality, input sufficiency, or
prompt quality?

## 1. What went right (keep doing)

- **Review-first with parallel evidence agents**: 4 Explore agents produced a gap list where
  every P0 was independently re-verified by the main conversation before being written down
  (grep exit codes, line numbers). Zero gaps later turned out to be misdiagnosed.
- **Slice ordering as a design decision**: doing G9 (consolidate 4 mirrored ingest paths)
  *before* G2 (CJK) meant the segmenter landed in exactly one INSERT site. This avoided a
  4-site shotgun change.
- **Subagent prompts with hard contracts worked**: the golden-query agent (existence-assert
  every expected page id; no commit) and the code-review agent (verify_protocol; actually
  compiled and executed CjkBigramSegmenter to prove the surrogate bug) both delivered
  zero-rework output. The review agent found 1 real P0 + 2 real P1 with no false positives.
- **Measuring instrument paid immediately**: the Phase-2 eval harness, on its very first
  keyword-only run, produced a *new* product gap (G10: 0/10 no-answer queries correctly
  rejected — no relevance floor) plus a quantified baseline (Path B 0.985/0.909, Path A
  keyword leg 0.600/0.454) that turns the future embedding-key rerun into a measurable lift.
- **Honest claim discipline**: /e2e-truth 8/10 with explicit "KB E2E is smoke-grade; golden
  depth lives in the 208-test backend suite" wording — no inflated claims to walk back.

## 2. Complete problem inventory (nothing omitted)

### A. Self-inflicted, caught by my own red-green loop (cheap)

| # | Problem | Detail | Cost |
|---|---------|--------|------|
| A1 | `List.of(null)` NPE in Mockito stubbing | UnfinishedStubbingException leaked into *other* tests, producing misleading failures | 2 iterations |
| A2 | Mockito varargs matcher ambiguity | `update(contains(...), any(), any())` failed to match a 2-vararg call; typed `anyString()` fixed it | 1 iteration |
| A3 | Mock primitive default ≠ null | `KnowledgeBase` mock returned 0 for `getChunkOverlap()` → pipeline used overlap=0 → `eq(50)` stub mismatch → embedBatch "zero interactions" mystery | 1 diagnosis round |
| A4 | Assumed `ab_kb_document.updated_at` exists | Wrote seed SQL from pattern memory; column doesn't exist | 1 iteration |
| A5 | 2-dim test vector vs `vector(1536)` column | Real pgvector rejected it — which is exactly why real-stack ITs matter | 1 iteration |
| A6 | MetaContext cleared after MockMvc request | Direct service calls after a `mockMvc.perform` need `applyTestMetaContext()` re-applied | 1 iteration |

### B. Self-inflicted, caught only by code review (would have shipped)

| # | Problem | Detail |
|---|---------|--------|
| B1 | **P0: API shape change broke CommandPalette silently** | `/retrieve` → `{results, warnings}`; I updated the consumers I *remembered* (playground, E2E) instead of grepping the path. `resp.data?.length` became undefined → palette doc search silently empty; tsc green; companion E2E only counted requests |
| B2 | **P1: surrogate-pair bigrams** | char-indexed slicing of a codepoint-filled buffer → lone surrogates → runtime `?::tsquery` cast error for supplementary-plane CJK (U+20BB7). My test matrix had zh/mixed/punctuation but no supplementary plane |
| B3 | **P1: missing migrations file** | I was *confident* schema.sql was the only schema source and even hand-ALTERed the shared dev DB. The repo has `database/migrations/` with 15 dated files — review's repo-convention check caught it. My P0 "开场 30 秒" didn't include `ls database/migrations/` |
| B4 | P2: query-side tsquery not lowercased (index side is) | Pre-existing, but I rewrote buildTsQuery wholesale and didn't notice |
| B5 | P2: `keyword_sql_failed` metric promised in javadoc, never wired | Doc-code drift inside a single commit |

### C. Mechanical-operation failures (process, not knowledge)

| # | Problem | Detail | Cost |
|---|---------|--------|------|
| C1 | **Conflict-marker regex disaster** | `re.sub(r'=======\n', count=1)` ate the tail of a `// ==== MODEL permissions ====` header (a 20-`=` run *contains* `=======\n`), leaving the real marker in place. My verification grep `grep -c "<<<\|>>>\|======="` then matched section headers (45 hits) and I misread it as noise. Compile failed post-rebase; two layered errors | 3 iterations |
| C2 | Background task reaping SIGTERMed bootRun | Backend started as a bg-task child died when the task tree was cleaned ~25 min later; cost a confused ECONNREFUSED diagnosis. Fix: `nohup … & disown` for any long-lived server started from a background shell | ~15 min |
| C3 | Guessed `dev.sh runtime allocate --path` flag | Doesn't exist; usage is `<repo> <name> --slot <n>` | 1 retry |

### D. Environment/tooling friction (pre-existing, re-confirmed)

| # | Problem | Detail |
|---|---------|--------|
| D1 | `oss-reset-and-init.sh` multi-worktree guard predates the dev.sh runtime model | Refused host mode despite slot-namespaced env; needed `FORCE_HOST=1` + manual env mapping because the script reads `PG_DB`/`BE_PORT` while `dev.sh` env files export `POSTGRES_DB`/`SERVER_PORT` — **variable-name contract mismatch between the two generations of tooling** |
| D2 | psql helper without `PG_DB` silently hit shared `aura_boot` | Produced a false "Business Tenant missing" invariant failure (env, not product). Known memory gotcha, re-confirmed |
| D3 | `localhost` resolved to ::1 | Backend listens IPv4 only → ECONNREFUSED; always use `127.0.0.1` in E2E env |
| D4 | `oss-test.sh` positional glob does NOT filter | Comment in the script says so ("CLI positional filtering is unreliable") but the Usage header still advertises `<glob>...`; ran the full 1147-test suite twice (~20 min) before reading the body. Targeted runs need `npx playwright test -c playwright.oss.config.ts <file>` + full env contract |
| D5 | Raw `npx playwright test <file>` loads pcba spec which throws at import | Enterprise fixture path read at module load; another reason the oss config exists |
| D6 | `check-schema-sql.sh` needs docker; daemon not resident | Gate effectively unavailable locally; compensated with real-Postgres ITs + migration applied to shared DB; must rerun under docker pre-release |

## 3. Root-cause taxonomy — 门禁? 输入? 提示词?

**Verdict: 主因是"主对话对自己写的代码没有执行它强加给 subagent 的取证纪律",次因是两处门禁空洞;输入信息从来不是瓶颈。**

### 3.1 输入信息(基本无罪)

A4/B3 的信息都在仓里(`\d ab_kb_document` 一条命令、`ls database/migrations/` 一条命令)——不是信息不充分,是**没看**。整个会话只有一处真实信息缺口:embedding key 是否存在,而这点第一时间实查了 `ab_cloud_config` 得到确定答案。

### 3.2 提示词/编排(subagent 侧无罪,主对话侧有罪)

- 给 subagent 的 prompt 全部带三件套 + ground-truth 断言 + verify_protocol,结果是 **0 返工、0 假发现**。
- 反讽的是:**主对话自己 inline 写代码时跳过了同样的检查**——B1(没 grep 消费方)、A4(没查 DDL)、B3(没盘 migrations 惯例)。如果这三个动作出现在我派出去的 prompt 里,我一定会写进去。
- 结论:问题不是"提示词不好",是**主对话写码没有 checklist,而 subagent 有**。改进 = 把同一份纪律对称地施加给自己(见 §4 固化项 1)。

### 3.3 门禁质量(两处真空洞 + 一处文档漂移)

- **空洞 1:API 响应结构无契约门禁**。tsc 对 `fetchResult<any>` 的消费方完全失明;配套 E2E 只数请求不验结果。B1 这类问题会一再发生,值得一个轻量约定:**改 shape 的 PR 必须附消费方清单**(grep 输出贴进 PR body),长期可考虑 BFF/前端共享 DTO 类型。
- **空洞 2:schema.sql 与 migrations/ 无一致性检查**。对已存在表的 ALTER 类改动若只动 schema.sql,fresh-reset 绿、存量库每 5 分钟报错。一个 ~20 行的 check 脚本(diff 触及已有表的列/约束时要求同 PR 出现 migrations/ 新文件)能机械地堵住 B3。
- **文档漂移:oss-test.sh Usage 头部仍宣传不生效的 glob 参数**(D4)——本次已修文档。
- 已有门禁表现好的:validate-permission-codes(抓住 billing drift)、真栈 IT(抓住 A5/CHECK 约束)、code-review verify-before-flag(抓住 B1-B5 全部)。

### 3.4 为什么总量看起来多

22 个问题里 6 个(A 类)是 TDD 红绿循环的**正常成本**(每个 ≤1 次迭代),6 个(D 类)是**存量环境摩擦**(本会话只是撞上+记录)。真正"本可避免"的是 B 类 5 个 + C 类 3 个,集中在两种行为:**凭记忆写代码不实查**、**用宽松文本操作做精密事**(C1 regex)。

## 4. Precipitation decisions(固化去向)

| # | 教训 | 去向 | 理由 |
|---|------|------|------|
| 1 | **改 API 响应结构第一动作全仓 grep 该路径列消费方清单;消费方断言验结果非验请求** | enterprise `engineering-gotchas/frontend-ssr-build.md`(已升)+ memory(已记) | B1 是 P0 级、模式必复发 |
| 2 | **schema.sql(fresh 真源)+ database/migrations/(存量增量)双轨必须同改**;对已存在表的列/约束改动只动 schema.sql = 存量库运行时炸 | enterprise `engineering-gotchas/backend-spring-db.md`(已升) | B3;同会话内 review 用"仓内惯例"才抓住,说明不写下来下个 agent 还会漏 |
| 3 | **冲突解决禁用宽松 regex**:marker 必须行锚定(`^=======$`);resolve 后验证 = `grep -E '^(<<<<<<<|=======|>>>>>>>)$'` + 编译,grep 模式含 `=======` 子串会被 section header 污染 | enterprise `engineering-gotchas/worktree-multirepo.md`(已升) | C1 双层错误,纯机械可防 |
| 4 | **从后台 shell 任务启动长驻服务必须 `nohup … & disown`**(任务树清理会 SIGTERM 子进程,延迟出现的 ECONNREFUSED 极难归因) | enterprise `engineering-gotchas/main-conversation-discipline.md`(已升) | C2 |
| 5 | **oss-test.sh 不支持 spec 过滤;定向单 spec 用 `npx playwright test -c playwright.oss.config.ts <file>` + 完整 env(BACKEND_URL 用 127.0.0.1 非 localhost,PG_DB 必传)**;dev.sh env 变量名(POSTGRES_DB/SERVER_PORT)与旧脚本(PG_DB/BE_PORT)是两套契约需手工映射 | enterprise `docs/agent-rules/oss-e2e-and-playwright.md`(已升)+ OSS `scripts/oss-test.sh` Usage 注释修正(本 PR) | D1-D5 一次写全,下个会话直接照抄命令 |
| 6 | CJK/Unicode 文本处理单测必含补充平面用例(U+20000+) | 并入固化项 2 同文件一行 | B2 |
| 7 | Mockito 细节(varargs 匹配、List.of(null)、mock 原始类型默认 0) | **不固化**——通用知识,红绿循环成本低,写文档性价比低 | A1-A3 |
| 8 | 主对话写码自检三问(改 shape?查 DDL 了?盘惯例了?) | 并入固化项 1/2 的 gotcha 行文,不另立条目 | §3.2 |

新 gap:**G10 拒答下限**(eval 实测 0/10 正确拒答)已记 gap tracker,评估 harness 即其回归网。

## 5. 改进清单(可执行)

1. ✅ 本 PR:修 `scripts/oss-test.sh` Usage 注释(去掉误导的 glob 用法,指向正确的定向跑法)。
2. ✅ 企业版 PR:固化项 1-6 落 4 个 gotcha 文件 + oss-e2e 文档。
3. ⏳ backlog:`check-schema-migration-pairing.sh` 门禁脚本(schema.sql 已有表变更 ↔ migrations 文件配对),记入 gap tracker §8。
4. ⏳ backlog:API shape 契约改进(PR 模板加"消费方清单"段,或前端 DTO 类型共享),记入 gap tracker §8。
5. ⏳ owner:embedding key 配置后重跑 `RagEvaluationPhase2IT`(live 模式)量化向量腿 lift;G10 score floor 作为下一切片。
