---
type: handover
status: closed
created: 2026-06-07
---
<!-- no-precipitation: session handover; all G2 slices merged into plugins PRs #53-#59 + OSS #450; no independent reusable lesson -->

# Session Handover - 2026-06-07 — AMOS 数字线程 G2(/goal)

## Session Summary
本会话先把 AMOS 智能制造愿景(`amos/docs/all-in-one-amos.md`)做成 grounded 终局+gap+方案文档(6 份),再在 `/goal` 自主模式下从零把**数字线程缝(G2)**推进到「后端血缘 + 双向追溯查询 + 前端可视化 block」并合并 **8 个 PR**(plugins #53–#59 + OSS web-admin #450),每个 TDD + 真栈/单测验证 + 自合并。`/goal` 全集(G1–G5 + UX-G1–G5 + 11 域 + NFR)是数月程序,本会话只完成数字线程这一条缝的大部分。

## Tasks Completed
- [x] **amos 文档体系(6 份,`/Users/ghj/work/auraboot/amos/docs/`)**:`amos-endgame.md`(终局蓝图,产品功能/旅程/架构,M1–M4)、`amos-backend-gap.md`(后端根因 G1–G5)、`amos-ux-gap.md`(UX-G1–G5 + 三组「双端同缝」)、`design-digital-thread-g2-uxg4.md`(本缝方案)、`mockup-review.md`(交互稿评审+backlog);`all-in-one-amos.md` 顶部加了指向 endgame 的勘误。endgame 经深挖校正了 3 处早版误判(Agent 非硬编码 / APS 引擎在平台内 / Kafka bridge 默认关)。
- [x] **G2-P0a 物料消耗血缘(写侧)** — plugins PR **#53** `29ed4c6`。新模型 `pe_material_consumption` + `ValidateMaterialBindingHandler` 写消耗记录。9 单测 + **真栈集成验证**(命令管道→psql 断言行,happy/sad/edge)。
- [x] **G2-P0b 追溯树读实际消耗(读侧)** — plugins PR **#54** `cf1cb54`。`BuildTraceTreeHandler`(pcba-warehouse)改用 `pe_material_consumption`(此前 `inv_lot WHERE product_id` = 假追溯)+ `workOrderId` scoping。3 单测 + 全套件绿。
- [x] **G2-Link3 SN 谱系** — plugins PR **#55** `c397b67`。新模型 `pe_sn_genealogy` + handler 写 finished↔component SN。11 单测 + 真栈验证(psql 断言谱系行)。
- [x] **G2-Link3b operator FK** — plugins PR **#56** `4039669`。`pe_wr_operator_id`(nullable ref→org_employee)给报工记录,config-only,真栈验证列已建。
- [x] **G2-P0b 追溯查询层(2 named-query)** — plugins **#57/#58**(`pe_consumption_trace_by_lot` 反向 lot→工单 + #58 修 dataType)、**#59**(`pe_genealogy_trace_by_finished_sn` 正向)。均放 `quality/config/named-queries.json`,真栈 SQL 验证返回血缘行,经 namedQuery datasource 可查(无需新 controller)。
- [x] **G2-P0c 前端 trace-graph block** — OSS auraboot PR **#450** `08a7288ae`(派 fresh-context subagent 实现,我 verify)。`TraceGraphCanvas`(@xyflow)+ `TraceGraphBlockRenderer`(读 namedQuery 行→nodes/edges via 纯函数 `buildTraceGraph`)+ BlockRegistry 注册(21 blocks)。18 vitest(happy/sad/edge/corner + render smoke)我已亲自复跑绿,full suite 1971 无回归。

## Tasks In Progress / Remaining(本缝剩余)
- [ ] **trace-graph 浏览器黄金** — DEFERRED:jsdom 渲染 ReactFlow 为 0px,需**全栈 headed**;且 g2dt 前端是 #450 之前构建的,要先用 #450 重建前端再 headed 验证(挂载 block 的页面 + 真数据 → 断言节点/边)。
- [ ] **插件侧页面挂载 block 配置**(小):把 `trace-graph` block + namedQuery dataSource 加到某 pcba detail 页 DSL(work-order / SN detail),block JSON 示例见 PR #450 描述。
- [ ] **G2-P0b build_trace_tree 全链真栈** — handler 用 `pe_product`(非 `prod_product`)命名,pre-existing,需厘清后才能造 finished-lot/template fixture 链跑端到端。
- [ ] **Link2 IoT(`pe_iot_reading`)** — 依赖 backend **G3**(IoT→业务命令最后一跳 `IotActionSink` 生产实现)未建,阻塞。
- [ ] **可选端点 `/api/pcba/trace/graph`** — 方案里写了,但 block 直接吃 namedExceptionQuery 已够;如要服务端组图再补 platform controller。

## Key Decisions
| Decision | Chosen | Rationale |
|---|---|---|
| 消耗血缘载体 | 关联表 `pe_material_consumption`(非 inv_lot FK) | 一卷料跨多工单 = M:N,裸 FK 必错 |
| validate_material_binding 命令类型 | `type:update`+inputFields → 改 `type:custom` 去 inputFields | update+inputFields 让管道把 handler 参数当 modelCode 列写 → bad-SQL;custom 无 inputFields 让 payload 直达 handler(对齐 apply_schedule/generate_calendar)。这也是它此前「未接 UI」的根因 |
| 追溯查询落点 | `quality/config/named-queries.json`(已有 loader) | 避免给 pcba-manufacturing 新建 named-queries.json 的 resourceDirs 摩擦;跨域只读 SQL 无碍 |
| trace-graph 组件 | 新建 `TraceGraphCanvas`,**不复用** LineageGraph | LineageGraph 硬绑语义 API + 3 列布局,不通用 |
| trace-graph 取数 | namedQuery datasource(flat 行)+ 客户端 `buildTraceGraph` 组图 | 不必先建服务端 graph 端点;复用已建的 2 个 named-query |
| P0c 实现方式 | 派 fresh-context subagent + 我 verify-don't-trust | 本会话上下文极深,前端是新代码域;§14 我亲自复跑 18 测 + 查分支/注册再合并 |

## Files Changed(本会话;均已合并)
PR 落点见上;新代码主要文件:
- plugins `pcba-manufacturing/config/{models,fields/pe_mc_*,fields/pe_sg_*,fields/pe_wr_operator_id,bindings,commands/pe_validate_material_binding}` + `backend/.../ValidateMaterialBindingHandler.java` + 测试
- plugins `pcba-warehouse/.../BuildTraceTreeHandler.java` + `BuildTraceTreeHandlerTest.java`
- plugins `quality/config/named-queries.json`(+2 trace 查询)
- OSS `auraboot/web-admin/app/components/trace/TraceGraphCanvas.tsx` + `.../blocks/TraceGraphBlockRenderer.tsx` + `BlockRegistry.ts` + tests
- 进度文档 `pcba-manufacturing/G2-DIGITAL-THREAD-PROGRESS.md`(已并入 main,⚠️ 内容略旧:写于执行前,说 P0a「未 merge」——以本 handover + PR 为准)

## Pitfalls & Workarounds
1. **预构建 plugin-jars 过期(stale crm jar)** → 整个 pcba-agent profile import 级联失败(`[S-EXT-HANDLER] crm:enroll_journey...` 未注册,因并发会话并入 #48/#49/#50)。**修法**:从 worktree root `gradle jar -x test -Dmaven.repo.local=/Users/ghj/work/m2-smartmfg/repository` 重建**全部** 17 jar → 覆盖 `/tmp/g2dt-jars` → restart backend → 重导。⚠️ crm 会被并发会话反复 staled,每次全栈 import 前先全 jar rebuild。
2. **`type:update` 命令经管道驱动报 "column X does not exist"** → 见 Key Decisions:改 `type:custom` + 删 inputFields。
3. **named-query field `dataType:"datetime"` 非法** → `S-NQF-TYPE-VAL` 让整个 quality 插件 import fail。只允许 `array/number/json/date/string/boolean`。用 `string`。
4. **我在未核 import 结果前就 merge #57** → 短暂 break quality 导入,#58 修复。**教训:merge 必须 gate 在 import success**(脚本里别把 import 与 git commit/merge 无条件串联;#59 已正确 gate)。
5. **import-plugins.sh 路径** → 必须在 `/Users/ghj/work/auraboot/auraboot`(OSS 子目录)下跑,不是 `/Users/ghj/work/auraboot`(我曾因此两次空跑 import)。
6. **命令管道驱动 custom 命令的 body** → `{"payload":{...}}`(custom 不带 targetRecordId);seed 的 tenant_id 必须 = admin JWT 的 tenantId(本栈=`321688087036039168`,非 1);`docker exec` heredoc 要 `-i`。

## Lessons Learned
- **真栈 seam gate 不可省**:新模型 + `db.create` 单测全 mock,只有 import+建表+命令管道+psql 才证伪 §2.2 假绿。本会话 4 个 backend slice 都做了真栈。
- **merge 必须 gate 在 import 成功**(#57→#58 教训)。
- **深上下文派 subagent 做新代码域 + 自己 verify**(P0c)是 context 约束下的好模式;但仍要 verify-don't-trust(亲自复跑测试 + 查分支/oid/注册)。
- **/goal 结构性不可达 + Stop-hook 反复**:本会话锁「持续安全交付真增量」,真用户「继续」才推 heavy(P0c 等真「继续」才做);bare 自动 hook 上 minimal-hold,别元振荡。

## Current State
### Git
- plugins `origin/main` = `276dd0c`(含本会话 #53–#59)。worktree `plugins/.claude/worktrees/g2-digital-thread` 干净,在已合并的 `feat/g2-genealogy-namedquery`(可删/复用)。
- OSS `auraboot` `origin/main` = `08a7288ae`(含 #450)。
- 所有 feat 分支已 squash-merge + delete;无堆积分支。

### Running Services
- **隔离栈 g2dt 仍在跑**(健康):backend `localhost:6446`、postgres、redis(COMPOSE_PROJECT_NAME=`auraboot-g2dt`)。ENTERPRISE_PLUGINS_DIR=该 worktree,ENTERPRISE_PLUGIN_JARS_DIR=`/tmp/g2dt-jars`。停:`auraboot/scripts/dev/stop-isolated.sh --slug=g2dt`。
- ⚠️ g2dt 前端是 #450 之前构建的(不含 trace-graph block);浏览器黄金前需重建前端。

### Database State
- g2dt DB(`aura_boot`,user/pw `auraboot`/`auraboot_dev`,tenant `321688087036039168`)已建 `mt_pe_material_consumption` / `mt_pe_sn_genealogy` / `pe_wr_operator_id` 列;seed 了 g2-* fixtures(g2-plan-1/g2-mat-1/g2-lot-1〔HL0603-2〕/X7-000892↔COMP-SN-7)供追溯验证复用。

## Next Steps(优先序)
1. **trace-graph 浏览器黄金**(用户明确要):重建 g2dt 前端含 #450 → 加 trace-graph block 到一个 pcba detail 页(plugin DSL,小)→ headed 浏览器断言节点/边(happy/sad/edge/corner)。可再派 subagent。
2. **build_trace_tree 全链真栈**:先厘清 handler 的 `pe_product` vs `prod_product` 命名(pre-existing),再造 finished-lot/template fixture 链端到端。
3. **backend G3**(IoT→业务命令最后一跳)→ 解锁 Link2 `pe_iot_reading`(参数血缘),是「实时安灯/OEE」双端同缝的后端半。
4. 然后转下一条缝(按 `amos-backend-gap.md` ROI:G2 已大半 → 接 G4 事件 bridge / G1 多工厂 / 或 UX 缝)。

## Context for Next Session
- **方案/总纲**:`amos/docs/design-digital-thread-g2-uxg4.md`(本缝)+ `amos-backend-gap.md`(G1–G5 根因 ROI)+ `amos-ux-gap.md`(UX + 三组双端同缝)+ `amos-endgame.md`(终局/里程碑)。
- **本缝逐 slice 续作清单**:`pcba-manufacturing/G2-DIGITAL-THREAD-PROGRESS.md`(已并入 plugins main;内容略旧,以本 handover 为准)。
- **bringup 复用要点**:全 jar rebuild(`gradle jar -x test -Dmaven.repo.local=/Users/ghj/work/m2-smartmfg/repository` @ plugins worktree root)→ cp `*/backend/build/libs/*.jar` 到 `/tmp/g2dt-jars` → `docker restart auraboot-g2dt-backend` → `cd auraboot/auraboot; scripts/import-plugins.sh --slug=g2dt --profile=pcba-agent --edition=enterprise` → 命令管道 `POST /api/meta/commands/execute/<cmd>` body `{"payload":{...}}`(tenant=321688087036039168)。
- **并发检测**:`git ls-remote '*digital-thread*'` + `git -C plugins log origin/main` + `git -C auraboot log origin/main`(origin/main 移动快,多并发会话)。
- memory active-work 条目「AMOS 智能制造终局 + 数字线程 G2」已更新到 #450。
