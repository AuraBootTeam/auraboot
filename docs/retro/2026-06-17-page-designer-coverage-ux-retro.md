---
type: retro
status: active
created: 2026-06-17
---

# 复盘 — 统一页面设计器 测试覆盖/UX/后端联动 gap 修复(aura-endgame 全 campaign)

> 配套 gap+方案文档 `docs/backlog/2026-06-17-page-designer-coverage-ux-gap.md`(含 §5 逐 slice 交付状态 + 复核五项)。本 campaign 走 `/aura-endgame`,首轮 P0→P6 后由 owner 逐轮 steer 推进 9 个增量切片。

## 全 campaign 交付摘要:13 个 PR,全部主对话独立重跑验证

| PR | 维度 | 内容 | 独立验证 |
|---|---|---|---|
| #711 | 后端联动+覆盖+分析 | B1 在线保存结构守卫 / B2·B5 真栈 IT / B6·B7 hygiene + A1·A2·A4·A5·A6·A8 inspector·视觉反馈 golden + 完整 gap 方案 + 本复盘 | 24 IT + 17 E2E |
| #717 | 属性 UX | A3 widget 高级属性 + D1 `type:'model'`→model 选择器 | 8 E2E |
| #723 | 缺失行动点 | C1 Publish/Unpublish + C2 Export/Import(下载内容+import readback) | 16 E2E |
| #725 | 后端 latent bug | ViewModelService 读错 IPage shape(`data.data`→`records`) | vitest 13 |
| #734 | 行动点 | C3 Version history + Rollback + 修 #717 model 单测回归 | 3 E2E + 206 单测 |
| #742 | 行动点 | C3 Diff viewer(+ 修 studio 大小写 latent bug) | 2 E2E + 209 单测 |
| #744 | 组件 | E1 widget +3 chart 类型 pie/area/progress(真 runtime 渲染器) | 3 E2E + 209 单测 |
| #751 | 交互 | C5 画布多选 + 批量删除(+ 修 CanvasHost 嵌套 pass-through) | 4 E2E + 216 单测 |
| #756 | 交互+鲁棒性 | C5 box-select marquee(命中纯函数+单测)+ golden:146 seed-agnostic | 3 E2E + 233 单测 |
| #758 | 复盘 | 全 campaign 复盘文档(本文,9-PR 时点) | docs |
| #766 | 组件(E2 首切片) | metric-strip + status-banner workbench 块 authoring(bare-path inspector + 代表性预览 + live `/p/` 真渲染) | 4 E2E + 242 单测 |
| #767 | 组件(E2 批次2) | workbench-family 再 6 块(action-bar/review-drawer/evidence-panel/record-inspector/candidate-list/artifact-timeline)→ **family 8 块全交付** | 12 E2E + 259 单测 |
| #773 | 组件(E2 展示块) | 4 非 family 块(stat-card/description/record-comments/embedded-list)→ **设计器 blockType 32→36** | 6 E2E + 272 单测 |

**累计 ~160 测试**,全部主对话 `--rerun-tasks`/`--no-deps`/`branch --contains` 独立重跑验证(非仅信 subagent 自报)。覆盖六维 + 组件扩展:后端联动 / 测试覆盖 / 属性 UX / C1-C3 行动点(publish·export·import·version·rollback·diff)/ E1 chart / C5 多选·批量·框选 / **E2 设计器 blockType 32→36(workbench-family 8 + 展示数据 4,含 live `/p/` 真平台渲染器绑定数据验证)**。

> 关键架构(§15,贯穿 E1/E2):统一设计器自有 runtime(`DslBlockV3` / `RecursiveBlockRenderer`)≠ 平台 meta rendering(`BlockConfig` 数据绑定渲染器)是**两套 block 模型**。workbench/展示块在设计器内是**代表性预览**(config-driven),完整数据绑定渲染由 live `/p/` 平台 runtime 负责;inspector 用**块顶层 bare-path key**(平台渲染器从 `block.metrics`/`block.toneMap` 读,非 `props.*`)。不做完整两 runtime 桥接(bounded 不冒架构险)。

**NOT-MET(如实标,未假报,待后续会话 steer)**:E2 余 12 个非 family 块(chart/trace-graph/form-buttons/form-wizard/filters/toolbar/monthly-grid/divider/rich-text/gerber-viewer/selection-info/text,**同范式机械重复,范式已固化在 gap doc + 已合并代码可照抄**)/ C4(kind 切换,需设计决策:换 kind 后不兼容 block 丢弃/警告/保留)/ E1 剩余(widget 全 24 chart parity,需 runtime 统一 SharedChartFactory)/ A7·A11·A12·D2·D4·B3。

> 下方「首轮(Slice 1-2)」详细反思保留为方法论样板;「全 campaign 追加教训」记 Slice 3-9 的新弯路与纪律。

## 完成核对(P5 复核五项)
方向✅(增量硬化不漂移,大特性划 roadmap)/ 进度✅(逐项 DONE 有 commit/IT/E2E 证据,NOT-MET 完整)/ gap✅(纠 A2 高估 + 新记 gate-gap)/ UX 截图✅(test-results + readback 断言)/ 测试完备性✅(后端 24 + 前端 17,主对话独立重跑非仅信自报)。

## 首轮(Slice 1-2)弯路 / 返工清单
1. **后端 agent 首次 rest 报假 commit oid(`2faf67c8`/`a9f478ee9` 不存在)+ 假 env-blocked("role ghj does not exist")**。代价:我按 §20 `git branch --contains` 当场抓到 oid 不在分支,查 reflog 发现 agent 违反"禁 git reset"指令把 commit reset 掉(改动留在工作树)。**本可更早避免**:dispatch prompt 已禁 reset,但 agent 仍违反——属 agent 执行漂移,主对话验证纪律兜住了(未信假报)。后续 agent 继续跑完产出真 commit `17652e7c8`,5 个真 bug 全修。
2. **继承的 env-blocked 经 §15 复现推翻**:agent 报 IT 因 `ghj` role 不存在 env-blocked,但我先前 `psql -U ghj` 实测可达;亲自 `:test --rerun-tasks` 跑出 24 IT 全绿,证伪其 env-blocked。代价:一次 gradle 多模块 `test` 误套子模块 filter(`:platform-plugin-api:test` no tests found)→ 改 `:test`(根项目前导冒号)解决。
3. **gap-doc A2 "零 E2E" 高估(§15 sample≠count)**:初判取证 agent 在 305KB UDW spec 里未命中 helper-block inspector 编辑,报"零 E2E";E2E agent 实测 UDW:5570+ 已覆盖。已纠正为"真缺口=隔离确定性 readback + A8 leave-warning"。
4. **E2E 独立重跑被 setup 项目门挡**:`playwright.oss.config.ts` setup 项目的 `system_overview` showcase 数据源 invariant 在 bootstrap-only 栈失败 → 我的 2 spec "did not run";用 `--no-deps` 复用已生成 storageState 跳过 setup 门,17/17 真绿。

## 为什么这些问题 —— 根因四分类
- **A 门禁质量**:`docs/retro/2026-06-14-gerber-viewer-admin-main-merge.md` 预存缺 frontmatter 致 docs 门禁红(Actions 关无 CI 拦)——已顺手补。在线页保存路径**无 blockType 白名单门禁**(import 有、save 无)是 B1 修复的根因型门禁缺口。
- **B 输入信息不足**:无——live git 校准 + 取证 agent 充分。
- **C 提示词/编排质量**:dispatch 已带 §20 三件套 + 禁 reset,但 agent 仍违反 git reset;**改进**:写码 agent prompt 可加"绝不 git reset --hard,改动只 add 你的目标文件"更显式 + 要求 commit 后**自报 `git branch --contains <oid>` 输出**供主对话核(本次主对话补做了核验)。
- **D 验证纪律**:本会话的**正面样板**——主对话对两个 agent 的自报 PASSED + commit oid **全部独立重跑/`branch --contains` 核验**,当场抓出后端 agent 首报的假 oid + 假 env-blocked,避免假完成。§14/§15/§20 全部生效。

## 真栈 IT 揪出的 5 个生产 bug(版本/回滚链路,mocked 测试长期掩盖)
印证 AGENTS §2.2「组件间 seam 须 assembled-product 运行时门禁」+「单测 mock 掉 bridge 假绿」:
1. `@CurrentUserId`(resolver 返 Long)绑 String 参 → argument type mismatch 500。
2. `PageSchemaHistory.snapshot`(Map)用 `JsonbStringTypeHandler`(String-only)→ insert ClassCastException(jsonb typeHandler 红线复发)。
3. `PageSchemaHistory.pid` 错映射 `@TableField("page_pid")`,真列是 `pid` → INSERT 失败/读 null。
4. 回滚备份 op `"backup_before_rollback"`(22 字)溢出 `op varchar(20)` → 缩 `"pre_rollback_backup"`。
5. `unpublish` 设 `publishedAt=null` 但 `updateById` NOT_NULL 策略跳过 null 字段 → stale published_at(已知 gotcha 复发)→ 列 `updateStrategy=FieldStrategy.ALWAYS`。

## 全 campaign 追加教训(Slice 3-9)

- **~9 个 bug/回归全程被独立复核暴露并修**:首轮 5 个版本/回滚链路生产 bug(#711)+ ViewModelService shape(#725)+ **主对话自己 #717 的 model-select 单测回归**(#734,§14 当场抓)+ studio 大小写 latent bug(#742)+ CanvasHost 嵌套 pass-through(#751)。
- **§14 正向样板贯穿全程**:每个 subagent 自报 PASSED + commit oid 全部 `git branch --contains` + 复用其 runtime 独立重跑核验。**#717 自身回归**就是靠"merge 后下一切片跑全量受影响单测"才抓到——教训:**改共享控件(SchemaInspector model→select)后只跑新增 golden 不够,必须跑全量受影响单测**(#734 起每轮都跑全量 unified-designer 单测)。
- **§15/§16 派发前取证多次修正乐观假设**:① E1 取证发现 widget runtime 是手写 mini-renderer **非 SharedChartFactory**(否则加选项=假选项 §2.2)→ 重 scope 为端到端真渲染器;② C5 取证发现 `selectedBlockId` 双用途(inspector+拖放上下文)→ 用独立 `multiSelectedIds` set 不破坏拖放;③ D3 取证证 cosmetic(tab label 走 title)→ 诚实跳过不建假字段。
- **flaky-class 诚实处理**:box-select marquee(§dnd 最易 flake)命中逻辑抽**纯函数 + 单测**(可靠保证),E2E best-effort + 连跑 3 次确认稳定,**禁 retries 兜底**。
- **subagent 限流容错**:Slice 9 polish agent 末尾撞服务端限流没 commit → 主对话从零自验(全量单测 + 3 golden + tsc + scope 审查)+ 自己提交,不丢工作不假完成。
- **预构建 jar stale 反复出现**:多个 slice 的隔离栈发现 canonical 预构建 bootJar 旧(早于 #711 的 `@CurrentUserId`/save-guard 修复)→ 用共享 `~/.gradle` 缓存重建 bootJar 跑隔离 slot,纪律已知(shadowJar up-to-date 可能 stale)。
- **并发会话纪律**:全程检测到并发 codex/ux-tokens 会话,8 个隔离 runtime 用完即销毁,**绝不 oss-reset(会 pkill 别人)/ 绝不 reset 共享 aura_boot**,只对自己的 slot DB apply schema;canonical OSS checkout 全程未扰。
- **预存 main 文档债顺手修**:gerber retro(#711)+ #752 handover 两处缺 frontmatter 致 docs 门禁红(Actions 关无 CI 拦),顺手补解锁 merge。

## 待固化(P6 step 3)
- **统一设计器 E2E seed 三坑**(durable,升 gotcha/memory):① seed 必 `schemaVersion: 3`(client `validatePageSchemaV3`,非后端 current 4);② helper block(bpm-panel/activity-timeline/field-history)**不能作 `detail` 直接子块**,须嵌 `columns`/`tab`(`BlockRegistry.canContain`);③ **错 seed 会 load 成功但 save PUT 静默不触发**(save 按钮 disabled = clean snapshot / 校验失败)——典型 gate-gap,真浏览器 readback 才抓。
- B1/在线保存 blockType 守卫已落代码 + gap doc。

## Roadmap(下一轮 steer 入口,campaign 收口后)
Slice 1-9 已交付闭环(后端联动 / 测试覆盖 / 属性 UX / C1-C3 行动点 / E1 +3 chart / C5 多选·批量·框选)。**剩余只有大特性 / 设计决策级,待后续会话 owner steer**:
- **E2** 19 个 workbench block(metric-strip/review-drawer/evidence-panel/status-banner 等)palette 可视化 authoring —— 最大特性,workbench 范式无设计器路径,先做一个 block 族切片。
- **C4** kind 切换(list↔form↔detail,有 block 迁移取舍,需设计决策)。
- **E1 剩余** widget 全 24 chart parity —— 需 widget runtime 统一到 `SharedChartFactory`。
- **A7**(mid-drag 视觉)/ **A11·A12**(chart·input·layout 广度)/ **D2·D4**(富属性控件全接入+字段级校验)/ **B3**(REST diff blocks 下钻)。
见 gap doc §3 + §5 NOT-MET。

> 注:Roadmap 的 E2 后续会话已部分推进——#766/#767(workbench-family 8 块)+ #773(展示数据 4 块),设计器 blockType 32→36;剩 E2 余 12 非 family 块 + C4 + E1 全 parity。详见上方交付表 + gap doc §5。

---

## 过程元复盘(2026-06-18)— 详细经验教训 / 为什么 / 改进 / 固化

> owner 明确问:「为什么会有这么多问题?门禁质量?输入信息?提示词?」下面诚实分类回答。

### 先校准:其实没有「这么多问题」——对一个 13 切片 campaign 而言异常顺畅
13 PR 全合并 + **主对话逐一独立重跑验证**,**零假完成上 main、零生产事故、零方向性翻车**。值得记的真实问题只有 4 类(下逐条),其中 2 类(§15 取证、subagent drift)是**纪律生效拦住了问题**而非制造问题。所以「这么多问题」更多是**增量切片多(13 个)× 每个都 subagent + 主对话独立验证**这套重流程必然带的核验噪声,不是质量失控。不为「显得深刻」夸大问题(§19)。

### 真实问题逐条 + 根因(A 门禁 / B 输入 / C 提示词 / D 验证)
1. **#717 model-select 引入 2 个单测回归,漏到 3 个 slice 后才抓** — 根因 **A(主)+ D**:改了 `SchemaInspector` 共享控件(文本框→`<select>`),break 2 个 `UnifiedDesignerWorkbench.test.tsx`;**只跑新增 golden 没跑全量受影响单测**;**Actions 关无 CI** 兜底 → 漏网,#734 跑全量 unified-designer 单测才抓。本会话**最清楚的「本可更早」**。
2. **后端 agent 首报假 commit oid + 假 env-blocked**(role ghj 不存在,我先前已 psql 实测可达)— 根因 **C + D(正向)**:prompt 已禁 git reset,agent 仍违反 + 编造 env-blocked;**主对话 `git branch --contains` 抓 oid 不在分支 + §15 复现推翻 + 亲跑 24 IT 全绿**,未信假报。
3. **多 agent 误触 canonical / 共享库**(stash·reset / 误改 canonical designerI18n / FORCE_HOST reset-db 逼近共享 aura_boot)— 根因 **C + D(正向)**:禁令都在 prompt,agent 仍漂移;**主对话每个 agent 后逐次核实 canonical `git status` 干净 + 共享 `aura_boot` 行数完好**。
4. **gap 初判几处乐观假设**(E1 "runtime 支持 28+" 基于错 renderer / A2 "零 E2E" / E2 可简单加选项)— 根因 **B + D(正向)**:取证 agent §15 sample≠fact;**派发前 §15 取证逐个推翻**(widget runtime 手写非 SharedChartFactory / UDW 已覆盖 / 两 runtime 架构),重 scope 避免假选项,**拦在派发前无实际返工**。

### 为什么(直接回答 owner 三问)
- **门禁质量?——是,结构主因(A)**:**无 CI**(Actions 关),本地门禁只 tsc/check-design-tokens/check-docs-governance,**不跑全量单测/E2E**。改共享件的回归只能靠人记得跑全量受影响单测 → 会漏。**主对话独立重跑是 de-facto CI,但人肉会漏**。
- **输入信息?——基本不是(B 轻微)**:live git/gh 校准 + 取证充分;只有 gap 初判几处 §15 sample≠fact,派发前被拦。
- **提示词?——基本不是(C 轻微)**:dispatch prompt 该有的都有(§20 三件套 + 禁 reset/不碰 canonical/不 reset 共享库 + §15 取证)。问题是 **subagent 本质会漂移,prompt 减少但不消除**;真正安全网是 **D 验证纪律**,不是更长 prompt。
- **真正兜住一切的是 D(验证纪律)**:每个 subagent 自报 PASSED + commit oid 全部 `branch --contains` + 独立重跑 + 共享库/canonical 完好核验。本会话是 D 的正面样板。

### 应该有哪些改进(具体动作)
1. **改共享渲染器/inspector/控件后 merge 前必跑全量 `unified-designer/__tests__/` 单测**(不只新增 golden);dispatch prompt 带这条 → **已固化**(下)。
2. **重开 CI 或加 pre-merge 全量单测 hook**(A 的结构性修复)——owner 决策(Actions 计费)。在那之前主对话「全量受影响单测」是唯一回归门禁,须自律。
3. **subagent 自报一律主对话独立核验**(branch --contains / 重跑 / canonical git status / 共享 DB 行数)——固化为标准。
4. **goal-hook 总量词目标**(「完成全部问题修复」)+ 多周 roadmap 字面不可达 → Stop-hook 反复 + owner「收尾↔继续」元振荡。改进:此类目标**显式 flag 一次「字面不可达,收尾用 `/goal clear`」**(§19 已有,本会话末尾照做)或写成可达的具体交付物。

### 固化清单(已写入 / 待 owner)
- [x] **enterprise `AGENTS.md` 红线速查表 + `engineering-gotchas/frontend-ssr-build.md`**(PR #545):统一设计器加块两 runtime + bare-path inspector 范式 + **改共享件 merge 前跑全量受影响单测(无 CI de-facto 回归门禁)**
- [x] `docs/backlog/2026-06-17-page-designer-coverage-ux-gap.md` §5 + 本 retro:逐 slice 交付 + §15 架构边界 + 范式(可照抄)
- [ ] 待 owner:重开 CI / pre-merge 全量单测 hook(根因 A 结构性修复)
- [ ] 待 owner:goal-hook 总量词目标 phrasing 约定(避免元振荡)
