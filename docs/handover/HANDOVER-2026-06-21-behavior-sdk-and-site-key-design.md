---
type: handover
status: shipped
created: 2026-06-21
slug: behavior-sdk-and-site-key-design
---

# Session Handover - 2026-06-21 · behavior SDK + UV/PV dashboard + site-key design

## Session Summary
统一遥测平台前端线两块交付:① behavior SDK + UV/PV 看板的窄而深纵切(全建+golden,**MERGED #966**);② 匿名 collect 展开为 site-key 子系统并出分解 + SP1 注册表设计(**MERGED #976**,build 留 fresh 会话)。全部已合并,无 in-flight 代码。

## Tasks Completed
- [x] **behavior SDK + UV/PV 看板**(PR #966,squash `9af7a2ee8`):`@auraboot/track` 包(envelope/identity/tracker)+ http-client keepalive + BlockRenderer 打 `data-aura-element-id` + AdminLayout 接线 + 后端 analytics reshape(`ApiResponse<{records:[...]}>`)+ DSL `kind:detail` 看板(新 `core-dashboard` 插件)。9 个 TDD 任务 subagent-driven,每个 review;真浏览器 golden 4/4(SDK 采集→入库→看板真数据→UV=2、0 console error)。
- [x] **site-key 匿名遥测设计**(PR #976,squash `306d00fba`):子系统分解(SP1 注册表→SP2 匿名 ingestion+滥用防护→SP3 SDK 公开模式→SP4 golden)+ SP1 注册表 design spec。
- [x] **docs 门禁 frontmatter 修复**:`type:design`→`plan-design` / 给 plan 补 `plan-impl` frontmatter(门禁收紧了 `docs/superpowers/` 下的 type enum)。
- [x] **反思固化**:`feedback-rule-over-surface-precedent`(规则优先于表面先例 / 样本按类别选)+ MEMORY 索引。

## Tasks In Progress
无。两条线均 MERGED。**SP1 注册表 build 按约定留 fresh 会话**(见 Next Steps)。

## Key Decisions
| Decision | Chosen | Rationale | Alternatives |
|----------|--------|-----------|--------------|
| UV 语义 | 去重登录用户(看板线)| admin 控制台登录门控,无匿名访客;匿名留子系统 | 立刻做匿名 collect → 实为子系统,defer |
| SDK 传输 | `fetch + keepalive`(带 Bearer) | sendBeacon 不能设 `Authorization` header → 丢鉴权 | sendBeacon(对 authenticated 不可用)|
| 看板页类型 | `kind:detail`(workbench) | `kind:dashboard` 不可导入(PageSchemaValidator) | kind:dashboard(被拒)|
| dataSource | 同时声明 `url`+`endpoint` | chart 自取 `url`、DataSourceManager 取 `endpoint` | 单 `endpoint` → 静默无数据 |
| 匿名 tenant 解析 | 公开 site-key→tenant 注册表 | owner 选;GA measurementId 风格 | 单租户配置常量 / host 解析 |
| site-key 管理 UI | **DSL 配置优先**(dynamic model)| key 公开非机密=普通列=干净 CRUD;§7 默认 | React settings 页(我 self-review 误选,owner 纠正)|
| site-key id | **双 id**(id 雪花 + pid ULID)| 一等管理实体约定(ab_report/agent_eval_case)| BIGSERIAL(我误用事件表简化,owner 纠正)|

## Files Changed
均已 MERGED,按 PR 归类(详见 PR diff,不在此复制):
- **#966**:`web-admin/packages/track/*`(新包)· `web-admin/app/shared/services/http-client/{types,URLBuilder}.ts` · `packages/runtime-kernel/rendering/BlockRenderer.tsx` · `web-admin/app/routes/AdminLayout.tsx` + `shared/services/trackerInstance.ts` · `platform/.../behavior/controller/BehaviorAnalyticsController.java` + `dto/BehaviorAnalyticsRecords.java` · `plugins/core-dashboard/*`(新)· `web-admin/tests/e2e/behavior/*.golden.spec.ts` · 顺带修 `LlmProviderFactoryTest`(main 上坏的)。
- **#976**:`docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md` · `docs/superpowers/specs/2026-06-21-site-key-registry-design.md` + 修我之前 behavior spec/plan frontmatter。

## Pitfalls & Workarounds
1. **几个静态全绿、只 golden/真栈抓的 seam**(均已修):sendBeacon 丢鉴权 / kind:dashboard 不可导入 / dataSource `endpoint` vs `url` 静默无数据 / `SmartNumberCard` 多字段自动展开成 16 卡(golden 抓的真 product bug)。
2. **import-directory-sync 报 param/contract**:端点是 `@RequestBody DirectoryImportRequest{path,...}`(JSON body,非 query/form);菜单 `parentCode` 引跨插件 group 在隔离导入会 fail → 改自包含 group;`layout.type:flow` 不合法 → `grid`。
3. **schema.sql 缺 `ab_behavior_event`**:golden 栈 step-2 apply schema.sql 不含该表(只在 Flyway migration)→ golden 前手动 apply `V20260620000200`。
4. **handover worktree 差点建错仓**(本次,见反思):`cd /Users/ghj/work/auraboot`(workspace umbrella repo)后 `git worktree add` 建到了 auraboot-workspace 而非 OSS auraboot;remote/HEAD 不符当场发现并重建。

## Lessons Learned
- 静态门禁(validator/audit/单测)绿 ≠ 功能对;传输选型、页类型、dataSource 字段、shared renderer 行为这类只在真浏览器 golden 暴露。
- 设计 spec 的 §15 适用性:契约点要 grep 实证,且**先例的理由要查是否适用**(见反思)。

## 反思与经验固化 (Reflection & Codify)
### 本会话弯路 / 返工 / 翻车
1. **site-key spec self-review 反转过头**(主弯路)— 代价:1 轮往返 + owner 两次纠正 — 把对的 DSL-first 翻成 React、把双 id 写成 BIGSERIAL — 本可避免:核实先例时同时核实"先例理由是否适用 + 样本类别是否对" — 根因:`[D 验证纪律]`(查事实未查适用性)+ `[C 提示词]`(§7 是明文默认,被代码先例盖过)。
2. **handover worktree 建错仓**(末尾)— 代价:微(当场重建)— `cd` 到 workspace umbrella repo 后 worktree add — 本可避免:worktree add 前确认 `git remote get-url origin` — 根因:`[D 验证]`。
3. 其余顺畅(9 任务 subagent-driven + golden 栈 bringup 均一次到位)。

### 为什么会发生(根因归类小结)
主要 **D 验证纪律**:self-review 把"查到先例"当结论,漏了"先例理由/类别在这成立吗"。已固化成护栏。

### 应该有哪些改进
- 已落地:`feedback-rule-over-surface-precedent`(规则优先于表面先例 / 样本按类别选 / 查事实≠查适用性 / self-review 别为改而改)。
- 操作提醒:在 workspace 多 repo 嵌套结构下,`git worktree add` 前确认当前 repo 的 `origin` url。

### 已固化 / 待固化(更新文档)
- [x] 已写入 memory `feedback-rule-over-surface-precedent.md` + MEMORY 索引。
- [x] 已更新 MEMORY active-work「统一遥测与分析平台」条目:匿名 collect = site-key 子系统,SP1 spec 就绪待 fresh build。
- [ ](留 owner)是否把"static 绿≠功能对 / 真栈 golden 才抓 seam"再升一条 AGENTS 红线——已有 §2.2 大量覆盖,本会话未新增,ROI 低。

## 运行态快照 (Operational State)
### 分支 / Worktree / PR
- **本 handover 分支**:`docs/handover-2026-06-21-telemetry`(OSS auraboot,base `306d00fba`)。
- **本会话功能 worktree**:`auraboot-behavior-sdk-dashboard` / `auraboot-site-key-design` **均已 remove + 分支删**(MERGED_AND_DELETED)。其它会话 worktree 未触碰。
- **PR**:#966 MERGED(`9af7a2ee8`)· #976 MERGED(`306d00fba`),均已核对在 origin/main。
- **未提交改动**:无(本 handover 待 PR)。

### Runtime / 端口
- **本会话 golden 栈 `behavior-sdk-golden-60`(slot 60)已 destroy**(DB `auraboot_60` drop / Redis / Kafka 清 / runtime 删 / 端口 6460/5160/6160 释放)。当前**无本会话相关进程在跑**。
- 起栈样板(供 SP1 build 复用):`scripts/oss-golden-stack.sh up <name> --slot <free>`(allocate→infra→bootJar→bootRun→Vite/BFF→warm/auth)。

### Database / Seed
- 无残留;SP1 build 自起隔离栈。

## Next Steps
1. **SP1 site-key 注册表 build(fresh 会话)**:读两份设计文档接力。**第一步**按 spec §9 验证 dynamic model 能否对 `site_key` 加 unique + create 时 server-set(hybrid handler 生成);不行才退平台表(仍双 id + 注册成 model 走 DSL)。
2. SP2/SP3/SP4 按依赖序,各自 fresh 会话。
3. 遥测其它独立线(非本子系统):Kafka 解耦 / §5.4 身份治理 / trend widget / OTel-ClickHouse-Flink(SoT §12)。
4. (可选 fast-follow)behavior 看板补后端 MockMvc IT 断言 `$.data.records[0]` JSON 字段名。

## Context for Next Session
- **SP1 build 起点**(两份,绝对路径):
  - 分解:`/Users/ghj/work/auraboot/auraboot/docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md`
  - SP1 spec:`/Users/ghj/work/auraboot/auraboot/docs/superpowers/specs/2026-06-21-site-key-registry-design.md`(§9 = build 第一步验证点)
- 镜像参照:`platform/.../connector/airflow/secret/WebhookSecretService`(生成-密钥服务结构)。
- 行为采集后端契约:`BehaviorEventInput`(扁平 camelCase)/ `ab_behavior_event`(UV 已计 anon_id)/ `BehaviorCollectService:38`(tenant 来自 MetaContext,匿名需 SP2 改)。
- ⚠️ workspace 嵌套:`/Users/ghj/work/auraboot/` = auraboot-workspace umbrella repo;`/Users/ghj/work/auraboot/auraboot/` = OSS auraboot。worktree add 前确认 `origin` url。
