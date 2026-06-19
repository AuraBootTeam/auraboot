# Session Handover - 2026-06-19

## Session Summary

Drove the **AuraBot 对话式可视化收敛终局** to 100% (every slice merged + golden) plus the agent-quality campaign follow-ups (A3 codify / C1 F2 / B1 chat-bi reachability / Slice E save-as-dashboard / Slice D renderer convergence). The full funnel — ask AuraBot → governed `chat-bi` tool → chart → "存为看板" → persisted dashboard — and the 3-renderer collapse are done. Only **v2-advanced** (semantic auto-derive, a big optional feature) remains.

## Tasks Completed

- [x] **A3** — codified 2 grep-verified gotchas (ENT #580): live-IT seeds key into `ab_cloud_config` → MyBatis param log leaks it (redact + root-fix mask); `chk_dashboard_scope` is lowercase but DTO javadoc says uppercase.
- [x] **C1 (F2)** — §15 investigation → **correctly NOT done**: no real `plugins/crm` complaint plugin exists; the demo `mt_crm_complaint` table is the agent CS-archetype eval fixture (`AgentArchetypeEvalCases`). Deleting it breaks the eval baseline with no replacement. Premise disproven.
- [x] **B1** — chat-bi reachable in default AuraBot chat + browser golden. The golden caught a real Slice C gap (chat-bi registered but unreachable). DDR (ENT #582, decision A = pin) → implemented. PRs: #842 (gap repro + testids), **ENT #582** (DDR), **#845** (pin + routing fix), #846 (doc). Self-introduced regression #830 (skill name `chat_bi` underscore broke `AuraBotSkillRegistry` context load) fixed in #832.
- [x] **Slice E** — ad-hoc → persisted bridge (#853, doc #855): ChatBiSkill payload carries the aggregate spec; ChatBiResultCard "存为看板" button → `POST /api/dashboards`. Verified e2e + real DB (`ab_dashboard` row created).
- [x] **Slice D** — renderer convergence (#886; scoped as #882 first). ChatBiResultCard dropped its own ECharts + `CHART_COLORS`, now a thin adapter on `SharedChartFactory` via a `{type:'static'}` dataSource. Root-cause fix: `useChartData` static path made **synchronous**. Verified: chat golden bars correct (screenshot) + dashboard/workbench golden **26 passed** + chart unit tests 18 passed.

(Earlier in this same session/campaign, already merged: S5/S6 workbench+dashboard goldens #810; agent-gate follow-ups RuntimeAuth/S7/quality CAPA #817 + auraboot-plugins #95; convergence Slices A/B/C/v1-retire/coverage-migration #822–#838. SOT = the endgame doc below.)

## Tasks In Progress / Remaining

- [ ] **v2-advanced** (the ONLY remaining convergence item): meta-model→semantic auto-derive generator (`ab_semantic_model` = 0 rows, no generator; `chatbi/v2` engine exists but needs `semanticModelPid`) + raw-vs-semantic routing + governance + UX. Big optional feature, **needs independent design** — not started. Full explanation in the endgame doc §"v2 进阶".

## Key Decisions

| Decision | Chosen | Rationale | Alternatives |
|---|---|---|---|
| chat-bi tool exposure | **A: pin always-on** in `ChatToolResolver` | chat-bi is a universal data-query *primitive* (safe structured sibling of the already-pinned `execute_sql`), not a domain tool; industry hybrid = pin core + retrieve tail | B: grounding candidate (UX-cleaner but LLM-driven/flaky, mis-classifies a primitive as a domain tool). DDR-2026-06-19. |
| C1 (F2) | **Don't delete demo table** | §15: no real plugin to unshadow; demo IS the agent-eval fixture | Delete (breaks eval baseline) |
| Slice D fix | **B: sync `useChartData` static** | minimal, root-cause (async data → echarts cached empty scale) | A: extract shared option builder (bigger refactor of all Smart*Chart) |

## Files Changed (this session's recent work)

### Backend
- `platform/.../aurabot/service/ChatToolResolver.java` — `PLATFORM_CHAT_BI_TOOL` pinned in `ensurePlatformTools`; cache code+readOnly only (NOT agent-def) so it routes `AURABOT_SKILL` → real `SkillToolExecutor`, not the synthetic `platform` provider stub.
- `platform/.../aurabot/skill/builtin/ChatBiSkill.java` — name `chat_bi`→`chat-bi` (#832); payload carries `dimensions`+`metrics` (Slice E).
- `platform/.../agent/ChatBiToolIntentLiveIT.java` (new) — live DeepSeek chat-bi param-extraction (coverage migration).

### Frontend
- `web-admin/app/framework/smart/hooks/useChartData.ts` — **static branch now synchronous** (`useMemo`, gated on `enabled`); fetch sources unchanged.
- `web-admin/app/plugins/core-aurabot/components-internal/ChatBiResultCard.tsx` — thin adapter on `getChartComponent` + static dataSource (deleted own `EChartsChart`/`CHART_COLORS`); "存为看板" button; `data-testid` chatbi-result-card/chart-area/save-dashboard/saved-dashboard.
- `web-admin/tests/e2e/aurabot/chat-bi-render-golden.spec.ts` (new) — stub-marker tool_use → ChatBiResultCard renders + save-as-dashboard.

### Docs
- `docs/backlog/2026-06-18-aurabot-conversational-viz-convergence-endgame.md` — §7 execution log (SOT).
- `docs/backlog/2026-06-19-chatbi-dashboard-renderer-convergence-slice.md` — Slice D (now status: done).
- `auraboot-enterprise/docs/standards/decisions/DDR-2026-06-19-aurabot-chat-tool-exposure-pin-vs-retrieve.md`.

## 反思与经验固化 (Reflection & Codify)

### 本会话弯路 / 返工 / 翻车
1. **Slice D 第一次 render tiny bars(green-but-broken)** — 代价:~6-8 轮调试(多次跑 golden + dump series/data)。本可更早避免:不可能更早——canvas 断言 + 单测全绿,**只有 §2.2 视觉截图复核抓得出**;这次正是截图抓到的,流程对。— 根因:`D 验证纪律`(正面:§2.2 起了作用)+ 一个新的可固化 frontend 陷阱(下方)。
2. **#830 自引入 regression**(skill 名 `chat_bi` 下划线破 `AuraBotSkillRegistry` context load)— 代价:~2 轮(写 coverage IT 时炸出 + #832 修)。本可更早避免:ship 薄组件前跑一个真 context IT(单测无 Spring context 抓不到注册期校验)。— 根因:`D 验证纪律`(§1 薄组件也要真 context 测)。
3. **B1 chat-bi 注册了但默认对话够不着** — 代价:0(live golden 第一次跑就抓到,正是 golden 的价值)。— 根因:`A 门禁`(正面:浏览器 golden 抓到 static/单测漏的可达性 gap)。
4. **3 次 §15 拦截**(A1 差点批量改 record.→row. / A2 非 bug / C1 前提垮)— 代价:每次先取证省下错改。— 根因:`B 输入`(deferred backlog 前提多是弱快照,动手前必取证)。

### 为什么会发生(根因小结)
本会话主要不是"卡",而是**几道验证门正确地拦住了会翻车的改动**:§2.2 视觉 golden(Slice D)、真 context IT(#830)、live golden(B1)、§15 取证(A1/A2/C1)。唯一新增可固化的是一个 echarts frontend 陷阱。

### 应该有哪些改进
- 固化 echarts async-static 陷阱到 frontend gotcha(下方),让下次做"复用看板图组件渲染 records"的人不再踩 ~6-8 轮。

### 已固化 / 待固化(更新文档)
- [x] 已写入 `auraboot-enterprise/docs/standards/decisions/DDR-2026-06-19-aurabot-chat-tool-exposure-pin-vs-retrieve.md`:chat-bi 工具暴露 pin-vs-retrieve 决策(本会话已 merge ENT #582)。
- [x] 已写入 `auraboot-enterprise/docs/agent-rules/engineering-gotchas/frontend-ssr-build.md` + 速查表:echarts async static dataSource → 小/动画容器缓存错 scale,柱子 tiny 但数据对,只截图抓得出,修=static 同步(本 handover 流程内固化,见下方 commit)。

## 运行态快照 (Operational State)

### 分支 / Worktree / PR
- **当前分支**:`feat/slice-d-renderer-convergence`(已 merge #886,remote `[gone]`;本地 2 ahead / 1 behind origin/main = 正常 post-squash-merge 残留,可弃)
- **Worktree**:`/Users/ghj/work/auraboot/auraboot-s5s6-golden`(本任务)。另有 ~9 个并发 worktree(别的会话:unified-telemetry / quoteops / bom-followups / convention-cmd / cov6 / card-grid 等)——**不要碰**。
- **本会话关键 commit**:`c5be30fe9`(#886 Slice D)、`2623cd874`(#853 Slice E)
- **PR**:全 MERGED。本段:#842/#845/#846 + ENT#580/#582 + #853/#855 + #882/#886。声称已合均已 headRefOid 核对 + `git log origin/main` 确认。
- **未提交改动**:无(Slice D 已全 commit+merge)。

### Runtime / 端口(host-first slot,零 docker)
- **Runtime**:`s5s6-workbench-dashboard-golden-52` · repo `auraboot` · slot `52` · env `.workspace/env/s5s6-workbench-dashboard-golden-52.env`
- **端口**:backend `6452` · web/vite `5152` · bff `6152`
- **命名空间**:Postgres DB `auraboot_52` · Redis db `3` prefix `aura:auraboot:52:`
- **依赖 broker**:Postgres :5432(user/pass `auraboot`/`auraboot`)+ Redis :6379。chat-bi golden 不需 Kafka/ES/MinIO。
- **当前在跑(本 checkout)**:backend `java pid 67225` :6452 **UP**(`java -jar build/libs/AuraBoot-1.0.0-SNAPSHOT-boot.jar` + `-Dagent.llm.stub-mode=true`,STUB 模式);vite `node 98674` :5152;bff `node 98685` :6152。**convergence 已全收口,这套栈不再需要**——接手者若不续 v2 可停:`kill 67225 98674 98685` + `./dev.sh runtime destroy s5s6-workbench-dashboard-golden-52`(先 `--dry-run`)。
- **接手者起栈命令**(若要重启验证):后端 `cd platform && set -a; source .workspace/env/s5s6-...-52.env; set +a; java -Xmx2g -Dagent.llm.stub-mode=true -jar build/libs/AuraBoot-1.0.0-SNAPSHOT-boot.jar`(bootJar 已构建,memory-safe);前端 `cd web-admin && pnpm dev:full`(env 注入 PROXY_TARGET/SPRING_BOOT_URL=:6452)。

### Database / Seed 状态
- `auraboot_52` 已 seed + 全插件导入;admin tenant `325894234785845248` 有 crm_lead 模型 + 90 行(5 状态:contacted 29 / new 23 / qualified 18 / converted 11 / lost 9)。golden 走 `tests/storage/admin.json`(auth.setup 自动重生)。**接手 chat-bi/dashboard golden 无需 reset**。

## Next Steps

1. **(若续 convergence)v2-advanced** — 起独立设计:meta-model→semantic 自动派生生成器(读 `ab_meta_model`+fields+reference+dict 推断 dimensions/measures + 注册 `ab_semantic_model`)+ chat-bi 裸聚合 vs v2 语义路由 + 治理 + UX。详见 endgame doc §"v2 进阶"。多周级。
2. **(若收尾)** 停运行栈 + `runtime destroy`(见上)。
3. **门禁**:OSS Actions 已停 billing,本会话全靠本地 golden + 单测;push 前已跑相关 check。无 CI 待核。

## Context for Next Session

- **SOT**:`auraboot/docs/backlog/2026-06-18-aurabot-conversational-viz-convergence-endgame.md`(§7 = 全执行账,每片状态 + 证据)。
- **决策**:`auraboot-enterprise/docs/standards/decisions/DDR-2026-06-19-aurabot-chat-tool-exposure-pin-vs-retrieve.md`。
- **chat-bi 链路**:agent 工具 `aurabot:chat-bi`(`ChatBiSkill`)→ `AggregateQueryService` raw 聚合 → `{records,columns,chartType,dimensions,metrics}` payload → `AuraBotChat.tsx:178` → `ChatBiResultCard`(薄适配器 → `SharedChartFactory`)。pin 在 `ChatToolResolver.ensurePlatformTools`。
- **并发检测**:续任何 viz feature 前 `git ls-remote --heads origin '*chatbi*' '*chart*' '*semantic*'` + `git worktree list`(本仓此刻 ~9 并发 worktree)。
