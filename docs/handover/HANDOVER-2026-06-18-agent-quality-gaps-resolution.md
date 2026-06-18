---
type: handover
status: active
created: 2026-06-18
---

# Session Handover - 2026-06-18 agent-quality gap resolution

## Session Summary
续 S1/S3 业务金标 → owner 要求**全面解决所有场景 gap;平台 gap 先设计方案确认再建,测试/基建 gap 直接执行,F2 暂忽略**。本会话交付 S3 自动 CAPA 整链 + S1 客服环全部金标,并**取证分类 + 确认后实装两个真平台 gap(F3 record 级 SLA、S5 NL→dashboard skill)**,沿途修 2 个真 bug。剩余=纯测试/基建 gap(4 moderate 后端 + 3 heavy 浏览器 golden/真插件)。

## Tasks Completed(全部 merged 到 origin/main)
- [x] **S3 自动 CAPA 整链 golden**(#775 S3-1 命令链+负向 gating / S3-2 BPMN→SLA→升级 / #778 S3-3 approve→on_bpm_event→create_capa 异步)— 5 测真栈
- [x] **S1-3 邮件→投诉字段抽取 live IT**(#781,真 DeepSeek 5/5 字段 100%+E6 不瞎编)
- [x] **平台 gap ②F3 — record 级 SLA 激活**(#783):`targetType="RECORD"` + `SlaActivationListener.onRecordCreate` + `DynamicDataServiceImpl` 钩子;`RecordLevelSlaActivationIT` 2/2 RED→GREEN;NODE 路径回归 2/2
- [x] **平台 gap ③S5 — dashboard:create 生成 skill**(#784):`DashboardGeneratorSkill`(schema 约束 + 12 列自动布局 + persist);确定性 IT 1/1 RED→GREEN + live IT 1/1(真 DeepSeek NL→4-widget 看板端到端持久)
- [x] **S1-2 投诉闭环 golden**(#785):create→自动指派 automation(异步)+ 响应 SLA(F3 同步),`ComplaintLoopGoldenIT` 1/1
- [x] **取证分类 + 设计方案**:3 agent 并行取证证伪「S4 部署编排」「RuntimeAuth」已存在;确认 F3/S5 为真平台 gap

## Tasks In Progress / 剩余 gap(全部纯测试/基建,无需再设计)
> SOT 真源:`docs/backlog/2026-06-18-platform-and-test-gaps-resolution.md`(每项 recipe + 依赖)

**Moderate 后端 IT(快,有模板)**
- [ ] **S7 多 agent 真模型收敛测** — `AgentCollaborationService` 已验三模式分发+子任务,缺「真模型多步收敛不空转/死循环」独立测。模板:agent-eval live IT(真 DeepSeek)
- [ ] **ApprovalGate 超时真栈 IT** — 现仅 `AgentApprovalGateServiceConcurrencyTest`(mock 超时)。补真栈:建 pending approval→过期→scheduled expire→run fail。入口 `ApprovalGate*Service`
- [ ] **S4 `platform.create_model` 端到端验证** — agent 部署路径已存在(`PlatformToolProvider.createModel` 串 generate+apply,L3)。补 IT 验 NL→model→table 物化(并发 #782 也在收 nl-modeling apply)
- [ ] **RuntimeAuth enforcement 测** — 已实装(`ToolLoopService` 每调用 `authorizeIncremental`)。补 IT 验 forbidden effect→deny(默认 DefaultRuntimeAuthorizationService grant-all,需 stub 一个 deny 或验 incremental 拒绝路径)

**Heavy(不同模式,建议 fresh focused session)**
- [ ] **S6 工作台浏览器 golden** + **S5 图表渲染浏览器 golden** — host-first Vite+Playwright 零 docker(`auraboot/scripts/oss-test.sh`,Playwright 自带 chromium + host Vite/BFF + auth.setup)。KPI 出数/metric-strip 筛选/review-drawer/0 console exprError;S5 验生成的 dashboard 真渲染
- [ ] **quality 真插件可达 host-first golden** — 起隔离 runtime + import 真 crm/quality 插件 + 端到端,抓 green-but-broken。**CRM-complaint 部分被 F2(owner 暂忽略)阻塞**;quality/CAPA 部分可做(`mt_qc_capa` 现未物化)

## Key Decisions
| Decision | Chosen | Rationale | Alternatives |
|---|---|---|---|
| gap 分类先取证盘自家底 | 3 并行 Explore agent 验候选平台 gap | §16 勿重造;果然证伪 S4/RuntimeAuth | 直接按设计文档判(会误建已有能力) |
| F3 record SLA 接线点 | `DynamicDataServiceImpl` record-create 钩子 lazy 调 `SlaActivationListener.onRecordCreate`(同步非阻塞) | 镜像现有 automation trigger 注入;复用 deadline 引擎 | 发 Spring 领域事件(改动更大) |
| S5 skill 的智能边界 | paramsSchema=widgets[type/title/dataSource],LLM 填,execute 自动布局 | LLM 不管几何;schema 约束保证合法 chartType | skill 内部自己调 LLM(更重) |
| live IT 不持久 vs 持久 | S1-3/dashboard-gen 持久端到端(软删可复跑);邮件抽取纯测量不持久 | 端到端证「gap 闭环」 vs 纯智能测量 | — |

## Files Changed(本会话,均已 merge)
### 平台产品代码(2 个真特性)
- `platform/.../bpm/listener/SlaActivationListener.java` — +`onRecordCreate`(F3)
- `platform/.../meta/service/impl/DynamicDataServiceImpl.java` — record-create 钩子 +SLA 激活(F3)
- `platform/.../bpm/entity/SlaConfigEntity.java` — targetType 注释 +RECORD
- `platform/.../aurabot/skill/builtin/DashboardGeneratorSkill.java` — 新 skill(S5)
### 测试(真栈 + live)
- `RecordLevelSlaActivationIT` / `QualityAutoCapaChainGoldenIT` / `QualityCapaBpmnSlaChainGoldenIT` / `QualityCapaFullAssemblyGoldenIT` / `ComplaintLoopGoldenIT`(framework/automation+bpm)
- `CsComplaintEmailExtractionLiveIT`(framework/agent)/ `DashboardGeneratorSkillIT` + `DashboardGenerationLiveIT`(aurabot/skill/builtin)
### 文档
- `docs/backlog/2026-06-18-platform-and-test-gaps-resolution.md`(SOT)/ `docs/backlog/2026-06-17-s1s3-business-loop-golden-gap-and-plan.md` / `docs/retro/2026-06-17-...-acceptance-report.md`

## Pitfalls & Workarounds
1. **bash cwd 不持久到 platform**:worktree 操作把 cwd 留在仓根,`./gradlew` not found → `| tail` 吞错误假绿(exit 0)。**根因 D**。**对策**:gradle 命令一律显式 `cd .../platform &&`,且 `> log 2>&1; echo "exit=$?"` 取真退出码,不靠管道。
2. **S5 dashboard create 撞 `chk_dashboard_scope`**:scope="PERSONAL"(大写)被 DB check 约束拒;真值小写 `personal`(DashboardCreateRequest javadoc 写大写是 stale)。**根因 B**(DTO 文档 stale)。**对策**:skill 用 `personal`,真栈 IT 当场抓(static/compile 抓不到)。
3. **真 DeepSeek key 泄 MyBatis DEBUG SQL 日志**(每 live IT 复发):seed `ab_cloud_config` 的 INSERT 明文记 key。**对策**:每轮跑后 `sed` redact `$DEEPSEEK_API_KEY`(build + 任务输出),核残留=0。**根因 A**(日志层缺脱敏,见下待固化)。

## Lessons Learned
- **取证分类 > 按文档判 gap**:设计文档列的「平台 gap」一半已存在(S4 create_model / RuntimeAuth);先 grep 自家底省下重造。
- **真栈 IT 抓静态门禁漏的 drift**:scope 大小写约束、SLA BPM-node 耦合,都只在真 insert / 真链路暴露。
- **平台特性补齐用「镜像现有钩子 + 复用引擎」最稳**:F3 record SLA 复用 NODE 路径的 deadline/scan/escalation,改动面极小。

## 反思与经验固化 (Reflection & Codify)
### 本会话弯路 / 返工 / 翻车
1. **bash cwd 不在 platform 致测试假绿**(上述坑 1)— 代价:1 轮重跑 — 本可:命令自带绝对 `cd` + 取 `exit=$?` — 根因:`D 验证纪律`(管道吞退出码)。
2. **S5 scope 大写撞 DB 约束**(坑 2)— 代价:1 轮 RED 调试 — 本可:写 DTO 前查 DB check 约束/schema enum 真值 — 根因:`B 输入`(DTO javadoc stale)。
> 其余顺畅:F3/S5/S1-2/S3 链一次设计到位,RED→GREEN 干净;3-agent 取证分类准确(证伪 2 项避免重造)。无其它重大弯路。

### 为什么会发生(根因小结)
两类小弯路:**D**(管道退出码,已是已知红线,执行不到位)+ **B**(DTO 文档 stale 误导)。无门禁/提示词结构性缺陷。

### 应该有哪些改进
- gradle 跑测试统一封装 `cd <platform> && ./gradlew ... > log 2>&1; echo exit=$?`(已在本会话后半段执行)。
- 改 DTO/写记录前查 DB check 约束真值(枚举大小写),不信 javadoc。

### 已固化 / 待固化(更新文档)
- [x] 已写入 SOT `docs/backlog/2026-06-18-platform-and-test-gaps-resolution.md`:gap 分类 + F3/S5 done + 剩余 recipe + 2 findings。
- [ ] 待固化(owner 决策)`engineering-gotchas/test-infra.md`:「真 key live IT seed ab_cloud_config 泄 apiKey 进 MyBatis DEBUG 日志→跑后 redact;根治在 CloudConfig 日志层脱敏」(高频复发,跨多 live IT;根因 A)。
- [ ] 待固化(owner 决策)`engineering-gotchas/backend-spring-db.md`:「ab_dashboard scope check 约束要小写 global/personal/team;DashboardCreateRequest javadoc 大写 stale」。

## 运行态快照 (Operational State)
### 分支 / Worktree / PR
- **当前分支**:canonical `/Users/ghj/work/auraboot/auraboot` 在 `main` @ `6946e5bfc`(本会话全 merge,无未提交)。
- **本会话 worktree**:`/Users/ghj/work/auraboot/auraboot-gaps`(现在 `docs/agent-quality-gaps-handover`,接手可复用:`git checkout -b <new> origin/main`)。其它 worktree(bom-followups / bpm-remaining-gaps / pd-e2-blocks / ux-design-tokens / sqlpath-21-gate)= **并发会话,勿动**。
- **PR**:全 MERGED_AND_DELETED — #775 #778 #781 #783 #784 #785。
- **未提交改动**:无(本会话)。

### Runtime / 端口
- **未分配 dev.sh runtime**。所有 IT 走 `:test` / `:testBpm` / `:testAgent` / `:testAi` + **integration-test profile → 共享 `aura_boot:5432`**(user `ghj` 无密码)、Redis `localhost:6379`,`@Rollback`/NOT_SUPPORTED+自清 suffix 表(非破坏)。
- **DEEPSEEK_API_KEY 已在 shell env**(len 35)。live IT(`@Tag("agent-eval-live")`)靠它 + tenant seed;**跑后必 redact**。
- 跑单个 IT:`cd <worktree>/platform && ./gradlew :test --tests '*XxxIT*' -PspringTestContextCacheMaxSize=4`(bpm 用 `:testBpm`,agent 用 `:testAgent`,aurabot 用 `:testAi`)。

### Database / Seed
- 共享 `aura_boot` 由别的流程维护;本会话 IT 非破坏。接手不需 reset。

## Next Steps(按 ROI)
1. **Moderate 后端 4 测**(快):S4 create_model 端到端验证 / RuntimeAuth enforcement / ApprovalGate 超时真栈 IT / S7 收敛 —— 模板齐,每个 1 IT。
2. **Heavy(fresh focused session)**:S6 工作台 + S5 图表渲染浏览器 golden(host-first Vite+Playwright);quality 真插件 host-first golden(CRM 部分待 F2 解禁)。
3. **owner 决策**:2 条待固化 engineering-gotchas(redact / dashboard scope);F2 是否解禁(解禁后 CRM 真插件 golden 才能做)。

## Context for Next Session
- **SOT**:`docs/backlog/2026-06-18-platform-and-test-gaps-resolution.md`(剩余 gap + recipe)+ 上游 `2026-06-17-platform-capability-map-and-test-scenario-design.md`(S1-S8 场景)。
- **真栈 IT 模板**:S3 系列(framework/automation)+ live IT 系列(CsComplaintEmailExtractionLiveIT / DashboardGenerationLiveIT)。
- **并发检测**:开工前 `git worktree list` + `git ls-remote --heads origin '*agent*'`;并发会话多(bpm/pd/ux/sqlpath),共享 aura_boot IT 并发上限 2。
