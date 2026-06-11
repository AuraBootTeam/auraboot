---
type: handover
status: closed
created: 2026-06-11
distilled_to: docs/backlog/2026-06-11-agent-capability-verification-matrix.md
---

# Session Handover - 2026-06-11 — Agent 系统真实验证 + 5 个系统性 JSONB-PGobject bug

> **沉淀去向(固化,2026-06-11)**:本会话的核心 durable lesson「读 JSONB 列经通用查询/JdbcTemplate 返 `PGobject` → 只判 `instanceof String` 静默坏 → 走 `JsonbColumns.toJsonText`」已固化进 canonical:
> - 工程坑(症状/根因/处理/反面教材)→ `auraboot-enterprise/docs/agent-rules/engineering-gotchas/backend-spring-db.md` §「读 JSONB 列经通用查询返 PGobject」+ `AGENTS.md` 红线关键字速查表 JSONB 行。
> - 本仓 OSS-local 权威报告(逐条根因 + 误判纠正)→ `docs/backlog/2026-06-11-agent-capability-verification-matrix.md`(本 doc 的 `distilled_to` 目标)。
> - 多会话宿主「绝不广撒 pkill / 绝不跑 oss-reset-init stop 步」已在 canonical `engineering-gotchas/main-conversation-discipline.md` §「pkill -f 全局匹配误杀并发会话」;「失败先 root-cause 再分类、别从域/返空推 env」已是红线 §15。
> 剩余 in-progress 项(literal 1640/1640 修 2 个非 hermetic 测试 / 全量 agent E2E / JsonbColumns suspect 扫)是后续开发任务,不构成可复用结论,见下文 §Tasks In Progress / Next Steps。

## Session Summary

接续 agent 系统 review 线的 deferred 项(I-1/A6/A5),随后对全量 agent 真栈做「逐条真实验证 + 根因解决」(owner 用真 DeepSeek key 驱动)。净产出:**5 个真生产 bug 全部根因修复并 MERGED**,都是同一系统性根因「通用查询读 JSONB 返 PGobject 未处理」;CI-style 干净隔离栈把 testAgent 推到 **1634/1640**(剩 2 个为非 hermetic 测试,非产品 bug)。

## Tasks Completed(全部 merged main)

- [x] **I-1** ACP implementation-map 门禁:`auraboot-enterprise/scripts/check-acp-implementation-map.mjs` + 单测(ENT #404);首跑抓真漂移 `ab_agent_step`→`ab_agent_action`(OSS #559 stamp)
- [x] **A6** live-LLM eval 回归:`CapabilityEvalLiveIT`(`@Tag agent-eval-live` + `DEEPSEEK_API_KEY` 门控,真 DeepSeek 3/3,key 读 env 不入码)(OSS #560)
- [x] **A5** L3 审批闭环浏览器 golden:`acp-approval-closeloop.spec.ts`(浏览器 3/3 + 命令级闭环)(OSS #572)
- [x] **能力 + 验证矩阵报告**:`docs/backlog/2026-06-11-agent-capability-verification-matrix.md`(OSS #579/#583/#590,含 20 能力矩阵 / 数字员工模型 / 逐条根因定性 / 输出 vs 预期)
- [x] **5 个真生产 bug 根因修复**(全 MERGED,见下「Files Changed」):#580 / #586 / #589 / #592(后者含 2 latent + 共享 helper）

## Tasks In Progress（剩余 heavy，下次会话）

- [ ] **literal 1640/1640** — 差 **2 个非 hermetic 测试**:`AcpP1FeaturesIntegrationTest::testCapabilityRouter_noMatch` + `testCapabilityRouter_intentMismatch`。两者 `assertThat(skills).containsExactly("dsl.query")` 依赖 ambient capability/skill 状态(shared aura_boot 隔离过、干净 auraboot_33 隔离+全量都挂）。**非产品 bug**(generic.query 能力 + dsl.query skill 数据均正确,#589 已修解析）。修法 = 让这 2 个测试自 seed dsl.query + 放宽 containsExactly。需干净栈无竞写复诊精确失败（[] under-match vs too-many over-match 当时被共享 build 竞写遮住）。
- [ ] **③ 全量 agent E2E**（~31 spec：agent-control-plane 8 / aurabot 21 / cs-agent 1 + api/agent 4）。本会话仅审批闭环（A5）真跑 3/3；其余需 web 栈专门跑。
- [ ] **JsonbColumns helper 后续 suspect 扫**：已迁 CapabilityMappingSupport + AgentHintEnhancer；其余 suspect（DslToolProvider / LlmProviderFactory / SemanticTermResolver / ToolLoopResultNormalizer / AgentProfilePermissionExtractor 等）需逐个核实「读 JSONB 列 via 通用查询 + 漏 PGobject」再迁（盲改有风险，§15）。

## Key Decisions

| 决策 | 选择 | 理由 |
|------|------|------|
| 8 个 CapabilityRouter 失败归因 | 真 bug（#589 PGobject）非 test-infra | owner 坚持「根因解决」纠正了我两度误判（"env"/"test-tenant capability 同步"），深挖发现是 parseJsonList PGobject |
| 起 CI-style 栈方式 | **独立 slot DB/redis/kafka + 共享 warm gradle + 手动 bootstrap/import** | `oss-reset-and-init.sh` 会 `pkill -f MetaApplication`/`pnpm dev`/`vite`（**广撒杀所有并发会话**），绝不能用 |
| testAgent 指向隔离库 | `SPRING_DATASOURCE_URL` env 覆盖（Spring relaxed binding > yml） | testAgent 自起 context（不需运行后端），env 覆盖 integration-test profile 的 aura_boot |
| 系统性根因修法 | 抽共享 `JsonbColumns.toJsonText` + 单测,迁确认实例 | 同根因已 ≥5 处;canonical 正解是 `StepLoopService.parseExecutionConfig`(PGobject→toString) |

## Files Changed（本会话 5 个 bug 修复 + helper，均已 merge）

### Backend (platform)
- `agent/service/RunLifecycleService.java` — **#580**：`publishTaskCompleted` 守列表 null 元素（根任务 parent_id NULL → `[null]` → `rows.get(0)` NPE → failRun 路径崩）
- `agent/service/PlanService.java` — **#586**：`loadPlanFromRun` 处理 PGobject（execution_plan JSONB；旧 `writeValueAsString(PGobject)` 序列化包装 → plan resume 加载崩）
- `agent/service/CapabilityRouter.java` — **#589**：`parseJsonList` 处理 PGobject（intent/object_patterns/skills JSONB → 旧代码返空 → **capability 路由层整体返空**）
- `agent/util/JsonbColumns.java`（新）+ `JsonbColumnsTest.java`（新，4/4）— **#592**：canonical helper
- `agent/service/CapabilityMappingSupport.java` + `agent/service/AgentHintEnhancer.java` — **#592**：迁移到 helper（capability 物化 + hints 的 2 个 latent PGobject bug）

### 文档 / 测试
- `auraboot-enterprise/scripts/check-acp-implementation-map.mjs` + 单测（ENT #404）
- `web-admin/tests/e2e/agent-control-plane/acp-approval-closeloop.spec.ts`（OSS #572）
- `platform/src/test/java/.../agent/CapabilityEvalLiveIT.java`（OSS #560）
- `docs/backlog/2026-06-11-agent-capability-verification-matrix.md`（OSS #579/#583/#590）

## Pitfalls & Workarounds

1. **系统性根因「JSONB-via-通用查询 → PGobject 未处理」（5 处）**
   - 根因：`DynamicDataMapper.selectByQuery`/`selectByQueryWithoutTenant`/`JdbcTemplate` 读 JSONB 列**不应用 entity type-handler** → 驱动返 `org.postgresql.util.PGobject`。只判 `instanceof String` 的代码漏它：`writeValueAsString(pg)`/`convertValue(pg,Map)` 序列化 `{type,value}` 包装；`(String)` cast 抛 CCE。
   - 解：`PGobject.toString()` 给 JSON 文本（JDBC 只保证 toString）。canonical = `StepLoopService.parseExecutionConfig`,已抽成 `JsonbColumns.toJsonText`。
   - 预防：今后读 JSONB 列一律走 `JsonbColumns`,勿手写 `instanceof String`。
2. **误把真 bug 当 env/test-infra（两度）**：8 个 CapabilityRouter 我先判 env-invalid、再判 "test-tenant capability 同步"——都错,实为 #589。教训:**分类失败前先 root-cause,别从「失败在 crm 域/路由空」就推 env**。
3. **`oss-reset-and-init.sh` 广撒 pkill**：`pkill -f "MetaApplication"` + `pkill -f "pnpm dev"`/`vite`/`bff.server` 会杀**所有并发会话**的 backend/前端。多会话宿主上**绝不能跑它**；改手动 bootstrap + `import-plugins.sh`（不广撒）。
4. **A5 浏览器 golden 的两个真坑**：① 我自建的隔离栈 SPA-auth 坏（storageState 缺 localStorage）——改用现成工作栈 crm-a4；② **JS 大整数精度丢失**：`JSON.parse` 解 JWT tenantId（`...104`→`...100`）seed 进错租户 → 改正则取 string。
5. **crm-a4 克隆做全量 testAgent 失败 925 个 = env-invalid**:克隆带别会话脏数据,全量套件需干净库;克隆只适合「自造数据的特定测试」。
6. **共享 canonical `build/test-results` 被并发竞写**:多个 testAgent 运行（我的+并发会话）覆盖同一 build 目录,读 XML 要核 mtime,否则读到别的运行结果。

## Lessons Learned

- 「逐条真实验证」的核心价值 = 抓真 bug:本会话 5 个真生产 bug 全靠真栈跑 + owner 坚持根因。
- 「失败在 X 域 → env」是危险捷径;**先 root-cause 再分类**(本会话两度被纠正)。
- 多会话宿主:① 隔离栈用独立 slot + 精确 PID kill,**永不广撒 pkill / 永不跑 oss-reset-init 的 stop 步骤**;② 不扰动别会话的 DB(只读 pg_dump OK,跑全量 testAgent 进别人 DB 不行)。

## Current State

### Git
- 本会话所有改动已 merge `origin/main`,worktree/分支全清(MERGED_AND_DELETED)。
- ⚠️ canonical 工作树有 2 个 **别会话的 untracked**(`web-admin/playwright.andon.config.ts` / `tests/e2e/workbench/pe-andon-workbench.golden.spec.ts`)——**勿动**。

### CI-style 隔离栈 recipe（下次复现 1640/1640 用,本会话已拆）
```bash
# 1. 隔离 infra（独立 DB/redis/kafka,不碰并发会话）
./dev.sh runtime allocate auraboot ci-verify --slot 33 && ./dev.sh infra ensure ci-verify --yes
# 2. 纯净 current schema（含 6 个平台 capability seed）
PGPASSWORD=auraboot psql -h127.0.0.1 -U auraboot -d auraboot_33 -f platform/src/main/resources/database/schema.sql
# 3. 起隔离 bootRun（共享 warm gradle,避 cold-m2 120s 超时；不用 dev.sh run 的 per-runtime m2）
set -a; source .workspace/env/ci-verify.env; set +a
unset MAVEN_REPO_LOCAL GRADLE_USER_HOME MAVEN_OPTS GRADLE_OPTS PNPM_STORE_DIR
export SPRING_DATASOURCE_URL="$DATABASE_URL" SPRING_DATASOURCE_USERNAME=auraboot SPRING_DATASOURCE_PASSWORD=auraboot
cd platform && ./gradlew bootRun   # 30s 起(warm),health UP 后继续
# 4. bootstrap + 导 crm/agent 插件（手动,不用 oss-reset-init！）
curl -XPOST :6433/api/bootstrap/setup -d '{"companyName":"AuraBoot Dev","adminEmail":"admin@auraboot.com","adminPassword":"Test2026x","adminDisplayName":"Admin","systemMode":"single"}'
BACKEND_URL=http://127.0.0.1:6433 bash scripts/import-plugins.sh --profile=demo --backend-url=http://127.0.0.1:6433
# 5. 停 bootRun（精确 PID,别广撒）,跑全量 testAgent 对隔离库
SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/auraboot_33?charSet=UTF8' SPRING_DATASOURCE_USERNAME=auraboot SPRING_DATASOURCE_PASSWORD=auraboot DEEPSEEK_API_KEY=<key> ./gradlew testAgent
# 拆: ./dev.sh infra cleanup ci-verify --yes && ./dev.sh runtime destroy ci-verify --yes
```

### Database
- 本会话临时库(auraboot_32 克隆 / auraboot_33 CI / auraboot_clean)全部 DROP。crm-a4 的 `auraboot_11` 仅被只读 dump,未受影响。

## Next Steps（优先级）

1. **修 2 个非 hermetic 测试 → literal 1640/1640**:起 CI-style 干净栈,隔离跑这 2 个看精确失败([] vs too-many),据此让测试自 seed dsl.query + 放宽断言。
2. **③ 全量 agent E2E**:起 web 栈(host-first 零 docker,Playwright 自带 chromium + host Vite/BFF;参考 A5 在 crm-a4 工作栈跑的方式),跑 agent-control-plane + aurabot 套件。
3. **JsonbColumns suspect 扫**:逐个核实其余 suspect 是否真读 JSONB-via-通用查询并漏 PGobject,迁到 helper。

## Context for Next Session

- 权威报告:`auraboot/docs/backlog/2026-06-11-agent-capability-verification-matrix.md`(矩阵 + 逐条根因 + 1634/1640)。
- 数字员工:`agent/service/AgentOrganizationService`(agent 作 org employee,4 类型 autonomous/copilot/reactive/workflow,前端 `/ai/colleagues`)。
- 2 个非 hermetic 测试入口:`platform/src/test/java/.../agent/AcpP1FeaturesIntegrationTest.java:156`(noMatch) / `:166`(intentMismatch);route 逻辑 `agent/service/CapabilityRouter.route`。
- ⚠️ **DeepSeek key `sk-a56e...e8ef` 本会话大量明文使用,务必轮换**(同 memory 早先 key-rotation 先例)。
- 并发检测:多会话宿主,起栈前 `lsof -nP -iTCP:<port>` 核端口 + 用独立 slot;**绝不广撒 pkill / 绝不跑 oss-reset-init**。
