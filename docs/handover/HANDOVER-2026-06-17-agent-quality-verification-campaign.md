# Session Handover - 2026-06-17

## Session Summary
用真 DeepSeek 把 AuraBoot agent **决策智能质量**量化(把"3/10 全 stub 未验证"变成可复核数字),
据此盘出**平台能力大图 + 企业测试场景设计**,并开始**全方面验证 campaign**;过程中一次诚实自我证伪
(我的架构 finding 预判被实测推翻、已修正)。本会话纯 agent-eval 任务线,与并发 codex 会话(quote-bom)无交叉。

## Tasks Completed
- [x] **L4 在线 eval 回路激活**(#722):`OnlineEvalQualityGate` + `ScheduledOnlineEvalJob`(默认关)+ `GET /api/agent/eval/online`;9+5 单测。同 PR 对齐 OSS remediation tracker A5/A6 → DONE。
- [x] **ACP SoT §5 对齐**(ENT #521):A5/A6/C2 从 gap 移入已闭环,门禁 `check-acp-implementation-map.mjs` 绿。
- [x] **agent 智能真模型量化(4 个 live IT,真 DeepSeek)**:
  - `AgentArchetypeLiveQualityIT`(#732)— 工具选择 cs/pcba/competitive **5/5** 选对·安全·精确·0 幻觉;`CapabilityEvalLiveIT` 3/3 旁证。
  - `AgentFormFillLiveIT`(#737)— 参数抽取 5 填表场景 **值 100% · 必填齐 5/5 · F6 缺信息拒绝瞎编**。
  - `AgentFormFillHardLiveIT`(#739)— 对抗 **8/8**(中文数字/自我更正/单位/创建vs更新辨析)。
  - `NlModelingLiveQualityIT`(#750)— NL→model 自由生成 **CLEAN 5/5 + HARD 类型推断 9/9 · 0 非法 · 0 校验错**。
- [x] **平台能力大图 + 测试场景设计**(#741):6 层大图 + 7 级测试金字塔 + 8 企业场景×验证链 + 12 agent gate 矩阵 + gap 清单 + ROI 计划。
- [x] **开发者能力架构 finding**(#746)+ **实测后自我修正**(#750):见下「弯路」。

## Tasks In Progress
- [ ] **全方面验证 campaign(多批次)** — batch 1(NL→建模)完成。剩余批次见 Next Steps,均未开工。

## Key Decisions
| Decision | Chosen Approach | Rationale | Alternatives |
|---|---|---|---|
| 怎么测 agent 决策质量 | native tool-use + 自包含 schema/catalog + 真 DeepSeek + 诚实报告 + 宽松地板线 | 隔离"模型判断"与"插件是否加载",对齐 runtime 真链路;报告即信号 | `evaluateToolSelection(...,"llm")` 走 discoverTools(需 crm/qc 插件加载,env 重) |
| 测试用哪个 DB | 复用共享 `aura_boot:5432`(integration-test profile),非破坏性访客(seed+cleanup) | live IT 只 seed 1 行 cloud_config + 读;隔离 runtime 起 BaseIntegrationTest 成本高 | 隔离 dev.sh runtime(schema 需另 apply,重) |
| 实测推翻自己的 finding 后怎么办 | 全文修正 finding(§1/§3/§5/§9),不让过度断言留着 | §15 取证不推断:测量是权威,推断让位 | 悄悄保留(违反"我要求真实") |

## Files Changed(均已 merge 到 origin/main)
### 新增 live IT(可复用 T3 模板,`@Tag("agent-eval-live")` + `DEEPSEEK_API_KEY` gated,plain testAgent 跳过)
- `platform/src/test/java/com/auraboot/framework/agent/AgentArchetypeLiveQualityIT.java` — 工具选择
- `.../agent/AgentFormFillLiveIT.java` — 参数抽取(native tool-use 真链路:`provider.chat` + `tools[].inputSchema` + `tool_use.input`)
- `.../agent/AgentFormFillHardLiveIT.java` — 对抗参数抽取
- `.../agent/NlModelingLiveQualityIT.java` — NL→model 自由生成基线
### 新增产品代码(L4 eval)
- `platform/.../agent/eval/OnlineEvalQualityGate.java` / `ScheduledOnlineEvalJob.java` + `AgentRuntimeController.java`(加 `/eval/online`)
### 文档(backlog)
- `docs/backlog/2026-06-17-platform-capability-map-and-test-scenario-design.md` — 大图+场景设计(主交付)
- `docs/backlog/2026-06-17-agent-intelligence-live-quality-measurement.md` — 智能量化
- `docs/backlog/2026-06-17-dev-capability-schema-constrained-skill-generation.md` — 架构 finding(**已按实测修正**)
- `docs/backlog/2026-06-12-agent-testing-strategy-and-eval-loop.md` §4③/④ — 标记 archetype 真测落地 + L4 运营接通
- ENT `docs/standards/meta/acp-implementation-map.md` §5 — SoT 对齐
> 三份设计文档的可读副本已抽到 `/Users/ghj/auraboot-design-2026-06-17/`(canonical checkout 之前在别的分支,磁盘上没有)。

## Pitfalls & Workarounds
1. **真 key 泄进 integration-test 的 MyBatis DEBUG SQL 日志**(每次 live IT 都复发)
   - **根因**:live IT seed `ab_cloud_config`(apiKey 在 INSERT),integration-test profile SQL DEBUG 把 INSERT 参数明文记进 `build/reports/tests/.../*.html`。
   - **Workaround**:每轮跑完 `sed` redact `$DEEPSEEK_API_KEY`(build/reports + build/test-results + task outputs + tool-results 四处),复核残留=0。
   - **Prevention(待固化)**:在 CloudConfig 日志层对 apiKey 脱敏,从源头消除(与 capability 矩阵"加密存储"gap 相邻)。
2. **`evaluateToolSelection(...,"llm")` 的 catalog 来自 `discoverTools`(已发布 capability)**,fresh IT DB 无 crm/qc 插件 → catalog 空 → archetype accuracy 假 0。
   - **Solution**:用 `LlmToolSelectionService.selectTools(tenant, task, catalog, k)` 直接喂自包含 catalog,隔离"模型判断"与"插件加载"。
3. **gradle 任务名**:从 `platform/` 跑是 `:testAgent`(root project = platform),**不是** `:platform:testAgent`(歧义,有 `platform-mq-*` 兄弟)。
4. **共享 DB 的 `CloudConfigSeeder`**:启动时从 `DEEPSEEK_API_KEY` env provision 一个 **platform 级** deepseek config(`created_by` 空)。**这是设计内行为(OSS#662),不要删**——删了破坏共享环境 DeepSeek。我的 tenant 级 seed 才删。

## Lessons Learned
- **schema 约束 native tool-use = 强**(填表 100%/对抗 8/8);但**自由生成也实测很强**(NlModeling 5/5+9/9)——别凭直觉断言"自由生成弱",要测。
- agent 执行契约层(gate+管道)+ 业务引擎本就强且已验证;真正薄弱面是**端到端 golden 的洞**(gate 高危负向 + workbench/ChatBI),不是"模型弱"。

## 反思与经验固化 (Reflection & Codify)
### 本会话弯路 / 返工 / 翻车
1. **架构 finding 凭盘点估"自由生成 ~70% 弱"未测就写进文档** — 代价:写完 finding(#746)下一批就被自己的实测(#750)证伪,返工全文修正 — 本可如何更早避免:**finding 里任何 quantifier(~70%/弱/强)落笔前先跑一个最小真测**,没测的标"🟡 推断未验"而非当结论 — 根因:`D 验证纪律`(继承/自造结论未实测)+ 轻微 `C`(§15 已有规则,是执行不到位)。
2. **真 key 泄日志每轮手动 redact** — 代价:每轮多一步、有遗漏风险 — 本可避免:CloudConfig 日志脱敏从源头解决 — 根因:`A 门禁/基建`(日志层缺脱敏)。
> 其余顺畅:live IT 模板一次建好后复用顺滑,env 访客模式稳定,无其它重大弯路。

### 为什么会发生(根因小结)
主要一类:**D 验证纪律**——把未测推断当结论写进文档。已有 §15 规则但本会话主对话自己在"架构层 high-level"幌子下踩了 quantifier 红线。次要 **A**:integration-test 日志脱敏缺失。

### 应该有哪些改进
- finding/设计文档里出现 quantifier(X%/强弱/优于)→ 要么附最小真测,要么显式标"🟡 推断未验";本会话已用 #750 修正示范。
- CloudConfig(`ab_cloud_config`)写入/读取的 SQL 日志对 `apiKey`/secret 字段脱敏(backlog 项)。

### 已固化 / 待固化(更新文档)
- [x] finding 文档自我修正已写入 `docs/backlog/2026-06-17-dev-capability-schema-constrained-skill-generation.md`(§1/§2.3/§3/§4/§5/§9)。
- [x] memory 加 campaign active-work 指针(见下「Context」)。
- [ ] 待 owner 决策固化:`engineering-gotchas/test-infra.md` 加一行「真 key live IT seed ab_cloud_config 泄 apiKey 进 MyBatis DEBUG 日志 → 跑后 redact,根治在 CloudConfig 日志脱敏」(高频复发,但仅本会话这套 live IT 触发,先 handover 记,owner 定是否升红线)。
- [ ] 待 owner 决策:把 "T3 live-eval 模板(native tool-use+自包含 catalog+真 DeepSeek+诚实报告)" 升为 canonical 测试方法(目前已在测试场景设计 §B + 4 个 IT 里,够用;升不升看是否高频)。

## 运行态快照 (Operational State)
### 分支 / Worktree / PR
- **当前分支**:canonical OSS `/Users/ghj/work/auraboot/auraboot` 在 `main`(本会话所有产物已 merge,无未提交本会话改动)。
- **本会话 worktree**:**全部已收口删除**(agent-eval-l4 / agent-sot-reconcile / agent-eval-live / agent-eval-fill / agent-eval-hard / agent-eval-design / dev-cap-finding / agent-eval-b1)。其它 worktree(codex/bom-followups、smartengine-401、pd-multiselect、ux-design-tokens)属**并发会话**,勿动。
- **PR**:全 MERGED — OSS #722 / #732 / #737 / #739 / #741 / #746 / #750;ENT #521。origin/main 顶部 = `455f3bbf8`(#750)。
- **未提交改动**:无(本会话)。

### Runtime / 端口
- **未分配 dev.sh runtime**。live IT 直接 `cd <worktree>/platform && ./gradlew :testAgent --tests '*XxxLiveIT*'`,走 **integration-test profile → 共享 `aura_boot:5432`**(user `ghj` 无密码)、Redis `localhost:6379`。
- **DEEPSEEK_API_KEY 已在 shell env**(len 35,sk-)。live IT 靠它 + tenant 级 seed 跑真 DeepSeek;无 key 则 `assumeTrue` skip。
- **依赖 broker**:仅 Postgres(aura_boot)+ Redis。零 docker。

### Database / Seed 状态
- 共享 `aura_boot` 由别的流程维护;live IT 非破坏性(seed tenant cloud_config + AfterAll 删)。**接手不需 reset**,但跑 live IT 前确认 `aura_boot` 有 `ab_cloud_config`/`ab_capability_eval_run`/`ab_agent_observation` 表(`psql -U ghj -d aura_boot`)。

## Next Steps(全方面验证 campaign 剩余批次,按 ROI;起点 = `docs/backlog/2026-06-17-platform-capability-map-and-test-scenario-design.md` §C/§F)
1. **P0 真 build gap:X1 一句话生成 dashboard** — 当前无生产 agent 路径(不是弱、是没有)。补时直接走 schema 约束 skill(`dashboards.schema.json` 作 inputSchema)。
2. **P0 agent gate 高危负向 E2E**(owner 点名,最可能挖出真 bug):`AgentApprovalGateIntegrationTest` 扩 **超时 auto-expire + plan_hash 篡改**;**Context 跨租户拒** E2E;**ToolAclChecker allow/deny** 独立 E2E。
3. **批次:ChatBI intent 真测**(生成图表)— `ChatBiLlmParser`,沿用 live IT 模板,测 agg/groupBy/filter/chartType 解析正确率。
4. **业务闭环金标 S1/S3**(客服闭环接 automation+SLA;质量自动 CAPA 全链 automation+BPMN+SLA+decision→handler→DB)。
5. **workbench/ChatBI 真浏览器 golden S6**(host-first 零 docker)。

## Context for Next Session
- **可复制模板**:4 个 `*LiveIT.java`(`platform/src/test/java/com/auraboot/framework/agent/`)。新探针照 `AgentFormFillLiveIT` 写(seed DeepSeek → `LlmProviderFactory.resolveProvider(tenant,"deepseek")` → `provider.chat(req, apiKey, baseUrl)` → 读 `tool_use.input`)。
- **真链路 API**:`LlmChatRequest`(`agent.dto`)`.tools(List<Tool>).toolChoice("auto")`;`Tool.builder().name().description().inputSchema(Map)`;`LlmChatResponse.ContentBlock.getInput()`。
- **跑完必做**:redact `$DEEPSEEK_API_KEY`(build/reports + task outputs + tool-results)+ 确认 tenant seed `count=0`。
- **设计/进度真源**:`docs/backlog/2026-06-17-platform-capability-map-and-test-scenario-design.md`(§F ROI)+ 本 handover。
- **并发检测**:开工前 `git worktree list` + `git ls-remote --heads origin '*agent*'` 防撞别的会话。
