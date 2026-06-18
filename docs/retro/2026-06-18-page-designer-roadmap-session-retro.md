---
type: retro
status: shipped
created: 2026-06-18
---

# 复盘:2026-06-18 页面设计器 roadmap 全清零会话(过程 / 根因 / 改进 / 固化)

> owner 复盘三问:**门禁质量不高?输入信息不够充分?提示词不好?** —— 本文逐一诚实回答,不粉饰、不假自责。

## 0. 会话弧线
接 `HANDOVER-2026-06-18-page-designer-coverage-ux-campaign.md` 剩余 roadmap。owner 用极简指令(`后续任务` / 反复 `继续` / `页面设计器后续任务`)逐轮 steer,把 9 个 roadmap 项全部清零(E2/C4/D2/D4/E1/A7/A11/A12/B3,14 PR 全 MERGED)。中途两次用 AskUserQuestion 如实把 E1/A7/A11/A12/B3 标为"低/负价值"并建议收尾;owner 明确选"磨完"。最后 owner 选"结束"。

**先摆正一个事实**:本会话**不是"很多 bug"**,而是 (a) 一批被纪律(§15 verify-before-claim / 改契约后跑全量受影响测 / §20 隔离)**当场抓住并修掉的小 tooling/test 坑**——这是系统在工作;加 (b) **一个真正的低效**:在我自己已判定为低/负价值的 5 个 roadmap 尾项上仍闷头交付。下面把两类都列全。

---

## 1. 全过程返工/摩擦清单(逐条根因)

### A. 被纪律当场抓住的"差点出错"(系统工作,非翻车)
| # | 现象 | 根因 | 抓手 |
|---|------|------|------|
| A1 | 继承 handover「E2 余 12 块」偏乐观 → 实际 10(monthly-grid 结构型 null / text 是 description 别名) | 上一会话 handover 的 quantifier 未验 | §15「继承 quantifier 必重新实测」:读真渲染器 + 运行时 BlockRegistry 取证后剔除 |
| A2 | 以为后端只需补 3 个 BlockType → 实际 4(rich-text 之前只在 ChartType enum,我的 `grep -qE "\"$b\""` 误命中) | grep 未 scope 到正确 enum(sample≠真) | §15:读真 `DslRegistry.BlockType` enum 全列,纠正为 4 |
| A3 | D2 permission 选择器源 `/api/permissions` 无 bare GET(会永远 fallback) | 从前端用法假设端点,未读后端 controller | §15:读 `PermissionController` 发现只有 `/tree` → 改 `/api/permissions/tree` + unwrap 递归展平 children |
| A4 | E2 preview `configured` 用 `getBlockLabel(block)`(兜底返 blockType,永远 truthy)→ empty 态永不显示(3 测红) | 照抄 stat-card 模板的谓词,没意识到对纯数据绑定块 empty 不可达 | 跑新测当场红 → 谓词改为仅数据绑定(dataSource/fields)决定 |

### B. Tooling / 环境坑(门禁/工具的真实缺陷)
| # | 现象 | 根因 | 处理 |
|---|------|------|------|
| B1 | E2 host-first backend bootRun 首次失败:`Could not find smart-engine-extension-storage-mysql:4.0.1` | `dev.sh runtime` 注入的 **per-runtime m2**(`.workspace/m2/<name>`)是空的,缺 SmartEngine **release** 依赖 | 改用**共享 `~/.m2`**(有 release 依赖,无 SNAPSHOT clobber 风险),保留 per-runtime GRADLE_USER_HOME 隔离 daemon |
| B2 | `oss-reset-and-init.sh` 含 `pkill -f vite/pnpm dev/concurrently` → 会杀**并发会话**在 :5173 的前端 | 该脚本是"共享单栈"模型,与并发会话不兼容 | 不跑它;改**外科式无 pkill 起栈**(`reset-db.sh` pkill-free + 手动 bootRun/vite/bff 指隔离端口) |
| B3 | chrome-devtools MCP browser profile 被并发会话锁,无法连 | 共享 chrome profile + 并发会话 | 改 **Playwright 自带 chromium**(`chromium.launch()`)+ 自铸 `__session` cookie(`createCookieSessionStorage` + 默认 SESSION_SECRET + API login JWT)做真浏览器 golden |
| B4 | B3 后端单测首跑:`GRADLE_USER_HOME` 设成空目录 → gradle 重下 distribution + 插件走远程 plugins.gradle.org TLS 握手失败 | 我把 GRADLE_USER_HOME override 成 fresh 空目录(只需修 m2,不该动 gradle home) | 用**默认 `~/.gradle`**(已缓存 dist+插件)+ 默认 `~/.m2` |
| B5 | `./gradlew test --tests X` → `:platform-plugin-api:test` 报 `No tests found for given includes` | 多模块 build 把 `--tests` filter 应用到**所有**模块,子模块没这个测就 fail | 用 **`:test`**(前导冒号,只 root 项目 test 任务) |

### C. 改共享契约的兄弟测试回归(纪律有效但每特性一轮 red→fix)
| # | 现象 | 根因 |
|---|------|------|
| C1 | D2 改 `permissionCode`/`queryCode` 字段 type → `v3-utils.test` 硬编码 `toBe('text')` 断言碎 | 改字段 type,兄弟测试硬编码旧 type |
| C2 | C4 加 kind 选择器 option「表单」→ `kindPolicy.test` 的 `getByText('表单')` 多匹配歧义 | 新增 UI 文本与既有 canvas band 文本撞,裸 getByText 不再唯一 |
| C3 | B3 改块级路径 → `hasMajorChanges` 精确匹配 `"blocks"` 不再命中(回归)| 改 diff 路径,精确匹配旧路径的逻辑漏判 —— **我主动发现并修**(加 `blocks[` 前缀) |
| C4 | vi.mock factory 引用顶层 `mockGet` → hoisting 报 `Cannot access before initialization` | vitest vi.mock 提升 → 用 `vi.hoisted` |
| C5 | `within` 运行时可用但 tsc 报未导出 | `@testing-library/react` 类型不导出 within → 用 `toHaveTextContent` |

> C1-C3 都是"**改共享渲染器/inspector/契约后必跑全量受影响测**"这条已知纪律在生效——每次都跑了全量、当场抓回归、按 analog 精确化断言。代价是每特性一轮 red→fix,但**没有一个漏到 merge**。

### D. 真正的低效(非 bug,是过程)
- **D1(核心)**:E1/A7/A11/A12/B3 这 5 项,我**自己两次如实判定为低/负价值**(E1 被 E2 的 chart 块削弱;A7 flaky;A11/A12 纯广度;B3 与 C3 决策冲突),并用 AskUserQuestion 建议收尾。owner 明确选"磨完",我按意愿逐项真做真测交付。**这不是翻车(每项都有真实现 + 真测 + B3 还有架构价值),但是把工时投在边际收益极低的项上。**

---

## 2. owner 三问的诚实回答(根因加权)

### Q1. 门禁质量不高? —— **是,主要贡献因素**
- **无 CI**(Actions billing 关闭)→ 每个回归只能靠我本地 `vitest`/`gradle :test` 跑出来。纪律有效(没漏到 merge),但代价是**每特性一轮 red→fix**(C1-C5)。有 CI 的话这些在 PR 上自动暴露,不占主对话回合。
- **host-first 后端测试 tooling 有尖角**(B1/B4/B5):per-runtime m2 不预置 release 依赖、GRADLE_USER_HOME footgun、多模块 `--tests` 须 `:test` —— 这些每次起后端栈都要重新踩。
- **共享单栈 harness(`oss-reset-and-init.sh`)与并发会话不兼容**(B2):pkill 会误杀并发前端,逼我每次手搓外科式起栈。
- **结论**:门禁/工具确实是**多数摩擦的来源**,但都是"起栈/跑测的工序坑",不是"产品 bug 漏网"。→ 固化 host-first 后端测试 recipe 可消除复发。

### Q2. 输入信息不够充分? —— **次要**
- 继承的 handover 有**乐观 quantifier**(A1「余12块」/ A2 推断「3 backend」)→ §15 当场验掉。
- gap doc 的 **「NOT-MET roadmap」框架没区分「真缺口」与「故意 defer 的低 ROI」**→ 让尾项看起来像"待完成 backlog",诱发 completionism(D1 的结构诱因)。
- 但信息**是可验证的**,§15 都补上了。不是"信息不够",是"继承信息默认乐观、需要主动验"。→ 固化:roadmap/gap doc 必显式分层 真缺口 vs 故意defer低ROI。

### Q3. 提示词不好? —— **次要,且部分是有意的**
- owner 的 `继续`/`后续任务` 极简,叠加 roadmap 框架 → 容易被当成"清 backlog"。
- 但 **§19「敢说够了」纪律基本生效**:我两次主动 AskUserQuestion 把价值分层摆出来、建议收尾。owner 坚持磨完是其**明确决策**(delegation/test pushback 都合理)。
- **真正可改的不是提示词**,是:generic `继续` 落在**已分层为低ROI的defer项**上时,主对话应**先复述价值分层再动手**(我做到了),且 roadmap 文档本身要把"defer 低ROI"和"真缺口"标清楚,别让下个会话当完成清单闷头清。

### 一句话根因
> **不是"很多问题",是 (a) 一批起栈/跑测工序坑被纪律当场抓修(系统工作,但有 CI / host-first recipe 可消除复发),加 (b) 一个过程低效:在我自己已分层为低ROI的5个尾项上按owner意愿闷头交付。** 三问里"门禁/工具质量"是主因,"输入信息(继承乐观+roadmap未分层)"是次因,"提示词"基本被纪律兜住。

---

## 3. 做对了什么(不只自我批评)
- **§15 verify-before-claim 抓住 4 个 would-be bug**(A1-A4):口径、后端 enum 数、permission 端点、preview empty 态——任一漏掉都是 production/假绿。
- **§20 全程零事故**:never 碰共享 `aura_boot` / 并发会话 :5173;每 PR `gh pr view --json headRefOid` 核对 head=我的 commit;隔离 runtime 用完 destroy。
- **诚实分层**:E1/A7/A11/A12/B3 如实标低/负价值、两次建议收尾,不假报"高价值"凑数。
- **B3 推荐方案有架构判断**:选后端块级 diff 而非前端 drill-down——diff 单一服务端真源 + 贯彻 C3「UI 只渲染 REST 真实响应」,契约增强非绕过。
- **真验证分层**:E2 后端真栈(save+publish+persist code:0)+ 真浏览器(10 块全渲染截图);B3 真 ObjectMapper 走生产代码路径;全部真组件单测 + tsc。

---

## 4. 改进项
1. **CI 是结构性缺口**:无 CI → 回归只能本地跑。短期:固化"改共享契约后跑全量受影响测 + 按 analog 精确化兄弟断言"已是习惯;长期:恢复 CI(billing)能把 C1-C5 这类自动化掉。
2. **host-first 后端单测/IT 起栈 recipe 固化**(B1/B4/B5):消除每次重踩 m2/gradle 工序坑。→ 见 §5 固化。
3. **roadmap/gap doc 价值分层**(D1/Q2):NOT-MET 必显式分「真缺口(有价值)」vs「故意 defer(低/负ROI)」,后者标明"非完成 backlog"。→ 见 §5 固化。
4. **改共享 inspector/registry 字段 type/option/契约**:兄弟测试常硬编码旧 type/文本 → 改后必跑全量受影响测 + 把裸 `getByText`/`toBe('text')` 精确化(scope 到容器 / 断新 type)。已是习惯,固化为一行 gotcha。

---

## 5. 固化决策(哪些进 canonical,哪些只留本 retro)
**进 canonical(通用、会复发)**:
- ✅ **host-first 后端单测/IT gradle/m2 recipe** → `engineering-gotchas/test-infra.md`(per-runtime m2 缺 release 依赖用共享 ~/.m2 / 不 override GRADLE_USER_HOME 成空目录 / 多模块 `--tests` 用 `:test`)。
- ✅ **roadmap/gap doc 必分层 真缺口 vs 故意defer低ROI** → `decision-defaults.md`(§产品定位/§19 邻近,一句 + 关键字)。
- ✅ **改共享 inspector/registry 字段 type/option → 兄弟硬编码断言碎,跑全量+精确化** → `engineering-gotchas/frontend-ssr-build.md` 一行。

**只留本 retro(本特性专属,代码已自带)**:
- A3 permission `/tree` 展平、A4 preview configured 禁 getBlockLabel、B2 外科式无 pkill 起栈 + 自铸 cookie golden、C4/C5 vitest hoisted/within —— 都在已合并代码/本 retro,够后续照查,不升 AGENTS.md(避免膨胀,memory 记 AGENTS slim-ceiling)。
