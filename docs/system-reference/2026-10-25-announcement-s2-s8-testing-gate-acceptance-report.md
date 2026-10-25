---
type: system-reference
status: active
created: 2026-10-25
updated: 2026-10-25
---

# 公告 S2–S8 本地分层验收记录

## 当前结论

本期 S1–S8 业务实现、OSS/BPM 组合和本地分层验证已完成，交付到开放 PR；未合并、未发布生产。需求唯一真源为[公告设计](./announcement-management.md)，实际契约以其 §18.26 为准。[同一证据 manifest](./evidence/announcement-s2-s8-2026-10-25/manifest.json)关联生成的行动点、具体断言、准确运行和逐图裁决。本报告取代此前各增量报告中的“最新”口径；设计 §18.1–18.25 和不可变原始日志仍保留历史失败。

**allowed_claim: implemented-and-local-validated-with-explicit-boundaries。** 这不等于所有状态排列、全产品 corpus、功能覆盖率 100%、OCI/Linux 或生产验收。小程序实际发送端不在本期；渠道适配与同一版本/知悉事实的公共边界已纳入实现。

## 运行与源码身份

| 输入 | 本轮身份 |
| --- | --- |
| 稳定工作区 | `.workspace/worktrees/announcement-management/{auraboot,aura-bpm,auraboot-enterprise}`；统一 `codex/announcement-management`，不吸收 main |
| Core 构件 | producer `6763dee6f2ef82c8d0a80eca4066309c378f0e1a`；`core-catalog-6763dee6f`，lock `sha256:86fb3b76fa41dfa6bda9b058d6d1f83cd085470033e80795eab2f2a1a0c957c8` |
| BPM 正式组合 | producer `41c1ffc44022e1ef3a3634e69e850c90ff66c3e4`；`bpm-composition-41c1ffc`；22 个 staged artifacts |
| 组合 lock / graph | `sha256:7b6f443f8f05137d53ad4686556b54df2bc0a6972319823946db93c4df1c773b` / `sha256:58fcb7f322a353d3a78ecb1192753a45648ad237b0a876633688b984741c4baa` |
| OSS 浏览器源码 | 完整 17 条为 `c545bc7a0`；随后仅强化渲染等待的原计划恢复在 `6a6b2f967` 定向复验 1 条；Core production 与上述 catalog 相同 |
| BPM 浏览器强化源码 | `68a4f347b91b9152fe42811a3daaf40adbcea17e`；相对 41c 仅文档和两处截图/状态断言，无 production 差异，复用原正式构件；最后 4/4 不与前轮 4/4 累加 |
| OSS runtime | `announcement-management`，backend 6401 / Web 5101 / BFF 6101，`auraboot_1`，Redis DB12 |
| BPM runtime | `announcement-management-bpm`，backend 6501 / Web+BFF 5401，`aura_bpm_1`，Redis DB7 |
| 数据模式 | 曾显式初始化同一任务环境；最新浏览器轮次重建认证、使用独有 UUID 夹具并断言本轮操作的持久结果，完成后走正式撤回/撤销和政策恢复；没有靠旧记录存在自证 |
| 凭证与私有证据 | 任务 evidence 内私有 env/认证状态，凭证 SET，不公开值；原始 CSV、下载对象、认证和 JVM 诊断不提交 |
| 结束状态 | 两个 runtime 正常 suspended；DB、缓存、allocation、源码和证据保留，不销毁环境 |

OSS retained DB 现在包含 152 条 Core 加 5 条 BPM 迁移，不能称为“纯 Core schema”。独立空库 Core 152、schema drift、原库增量和组合 157 validate 分别已执行；没有 out-of-order 或手工 ALTER。Core-only catalog 不包含 BPM 产品，BPM 构建只消费冻结 catalog。

BPM 每次浏览器门前均执行公开产品 `verify` 和 Workspace `verify`，登记 artifact、listener PID/cwd、source 及数据库身份。Workspace verdict ok、findings none，但仍有 `RUNTIME_MANIFEST_MISSING` warning；独立产品 verify 及真实 BFF/HTTP/DB 证明另行保留，不能改写为零 warning。最终文档/证据提交只更新文档；不冒充重新构建了相同 production bytes。

## 实际执行账本

所有路径以 Workspace 登记的 `.workspace/evidence/` 为根。计数按不同测试面和轮次分别列出，存在重叠时不相加；认证不是业务用例，collection 不是执行。

| 层次 / 四轴 | 实际命令或固定入口 | collected / executed / passed / failed / skipped | 退出与证据 |
| --- | --- | --- | --- |
| Core 领域；backend / hermetic / unit / test-function | 原受管 Gradle 公告定向套件，133 项 | 133 / 133 / 133 / 0 / 0 | exit 0；`announcement-management/s2-audience-paging-corrected-full-backend.log` 与 `s2-audience-paging-corrected-junit`；source `854ecf0ec` |
| Core 持久边界；backend / real-stack / it / HTTP+worker | 同轮 PostgreSQL/HTTP/outbox 39 项 | 39 / 39 / 39 / 0 / 0 | 同上，172 合计只用于该轮 JUnit 核对；不把单元当集成 |
| Core 共享应用保护；backend / hermetic / unit / test-function | `s6-direct-lifecycle-full-regression` 目标集 | 153 / 153 / 153 / 0 / 0 | 单元通过；该次 3 项 IT 中 1 项 classpath 夹具失败，不能把整轮算通过；原 XML 保留 |
| Core 真实插件保护；backend / real-stack / it / HTTP+PF4J | 修正 JAR 资源夹具后重跑相关 3 项 | 3 / 3 / 3 / 0 / 0 | exit 0；`s6-direct-lifecycle-corrected-it.log` / `s6-direct-lifecycle-corrected-junit`；source `6763dee6f` |
| Core 前端；ui / hermetic / unit / test-function | 已有相关组件 161 项；后续 Header/分页定向 23 项 | 分别 161、23 全通过，不累加 | 对应源码 `528d9799d`、`49f74a882`；最终 `final-core-typecheck.log` exit 0 |
| OSS 原生业务；ui / real-stack / journey / browser | `playwright.oss.config.ts`，`tests/e2e/announcement/` 五份 spec | 17 / 17 / 17 / 0 / 0；retry 0 | exit 0，3.1 分钟；`final-oss-regression-browser.log`；line reporter 逐条执行和最终计数，不虚构结果 JSON |
| 原计划恢复强化；ui / real-stack / journey / browser | 同配置同一原旅程定向复验 | 1 / 1 / 1 / 0 / 0；retry 0 | exit 0，49.5 秒；`s8-reminder-golden-browser.log`；与上述 17 重叠，不加成 18 |
| BPM 前端；ui / hermetic / unit / test-function | 冻结预览/任务入口 5 文件 | 45 / 45 / 45 / 0 / 0 | exit 0；`announcement-management-bpm/s5-frozen-entry-front-units.log`；后续该 production TS 未变 |
| BPM 构建契约/进程安全；script / hermetic+owned-process / unit / node | 正式 `build-application.mjs --composition announcements --skip-oci --offline` | 97 / 97 / 97 / 0 / 0 | exit 0；`final-41c-composition-build.log`，包含真实进程生成/停机反例 |
| BPM 后端；backend / mixed hermetic+real-stack / unit+it / Gradle | 同一正式构建执行的完整 backend test | 1657 / 1657 / 1657 / 0 / 0 | 224 份 JUnit，`final-41c-junit`；其中 9 公告组合 IT + 10 严格引擎 IT 是子集，不再相加 |
| BPM Web 构件 | 同构建的 typecheck、client/SSR、单 React、摘要与图一致性 | 不记业务测试数 | 全部 PASS，exit 0；正式 receipt 含 producer/lock/graph；不是 OCI 证据 |
| BPM 可选浏览器 profile；ui / real-stack / journey / browser | `playwright.announcements.config.ts` | collected 4、executed 4、passed 4、failed/skipped/retry 0 | exit 0，1.7 分钟；`final-visual-browser.log`，认证另 1；collection 单独 executed=0 |
| BPM 固定集合对账 | `verify-corpus-results.mjs collection results reconciliation announcements` | declared 4 / reported 4 / passed 4 / unresolved 0 | exit 0；`final-visual-reconciliation.json`，精确身份且每项仅一次 |
| BPM 正常生命周期 | 同一受管 TTY：resume→登记→publish→verify→browser→suspend | business_exit=0，stop_exit=0 | `final-visual-exit.log` / `final-visual-stop.log`；listener 已退出，未销毁数据库 |
| 静态登记与结构门 | `scripts/check-test-system.sh`，源 coverage generator | browser executed=0 | `final-test-system-with-reminder-recovery.log` PASS；不把全仓已有 untested 消除或折算为执行率 |

最后的文档治理和差异检查见同一 manifest 的 `documentationValidation`；仓库 PR Checks 以服务端当前 head 为准，本地 PASS 不等于 CI PASS。

## 业务分母与实际断言

D01–D15 保持设计 §16.8 的完整分母。机器行动点来自 `core-announcement` 的 16 commands / 12 pages，关联具体 browser/IT 断言；Header、受控附件、审批任务及恢复动作作为来源明确的补充面。生成器的静态 partial/untested 原样保留，组合审批三命令和通用 form 路由由人工语义审计绑定实际执行，不能修改静态 executed=0。

| 需求组 | 已执行的关键语义证据 |
| --- | --- |
| D01 草稿/版本 | 原生新增、编辑、删除、修订、置顶/取消置顶；实际 revision、冻结 hash、通知数量及刷新事实 |
| D02 受众/权限 | 原生 27 人翻页 25+2、跨页选择/清空/重开；部门子树/角色/全员及身份轮换授权；真实 501 成员跨 500 展开和统计页 |
| D03 政策/免审 | 实际政策菜单保存/取消/无效时间；exempt 与 manage 分离；缺 BPM 不降级；BPM 默认绑定真实 UI 保存，受理后变更默认不改原定义 |
| D04 定时/恢复 | 实际预约取消无 recipient；过窗拒绝、旧租约不能提交；原发布任务恢复保持 actor/version/window，取消确认不写入 |
| D05–D06 审批 | 原生 submit/reconcile/withdraw；角色 ANY 与人员 ALL；无关用户拒绝、驳回必填、首次意见不提前批准、修订后旧历史不变；原节点 resolve、原任务 resume、剩余人 replace 后旧人拒绝且意见保留 |
| D07 渠道 | 冻结名单与 outbox、投影幂等；准确版本渠道历史分页/刷新不重发；桥接受理后关闭阻止执行，显式核对原意图 |
| D08–D09 提醒/反馈 | 实际铃铛断网/写入不确定后恢复、全部已读仍有知悉待办；普通接收人阅读/确认、刷新保留首次时间；错图不记阅读；窄屏及 Escape 焦点 |
| D10 催办 | 原生预览/取消/接受/原键核对/批次分页/准确批次；真实集成验证每日、三次、截止相等、手动截止后、空批次、共用去重、撤权、并发与原计划恢复；日历/DST 为 hermetic 时钟规则，未改系统时间 |
| D11 统计/导出 | 原生固定快照与筛选/空态；丢响应后 GET 原操作和同键重放；真实 CSV 字节/摘要/行列/时区及独立解析；多页、历史组织、下载撤权、取消迟到上传、清理失败及篡改拒绝 |
| D12 正文/附件 | 实际原生富文本及上传、冻结下载字节、保留删除拒绝、越权/猜链接拒绝、HTML 防注入；审批人只读冻结正文和同一送审附件，预览不记接收阅读 |
| D13 组合/升级 | 冻结 Core → 正式产品构建、157 迁移 validate、锁摘要与依赖图；真实 disable/uninstall/hot-stop HTTP 全拒绝且插件状态不变；正常整栈停止通过 |
| D14 异常/UX | 26 类固定 stage 观测语义、501 人测量；真实故障注入、加载不伪空、原键恢复、政策/渠道/审批页面原图复核；不外推所有可能布局/网络排列 |
| D15 扩展 | 未配置渠道不可选择、不伪报发送；中立 adapter 与同一版本事实边界。实际微信 provider/真机 did_not_run，属于本期明确排除 |

需求级 runner 自动对账尚未实现：现有结构门与 BPM 固定 case 集合对账是机器门，需求到断言的语义绑定和视觉裁决是人工审计。该工具局限不能包装成“自动全覆盖已锁定”。

## 原图与真实文件

[根 manifest](./evidence/announcement-s2-s8-2026-10-25/manifest.json)复用已有统计、提醒、附件子清单，并补齐当前草稿/权限/发布与 BPM 原图。每图保留原 source/run/hash、打开原图后的具体观察和 agent-vision 裁决。旧截图不能换标签成为新版本截图；已有错误图和 skeleton 图保留在私有失败证据中，不作为已接受图。

BPM 最终恢复详情的“所有人通过”原图曾有三行换行，已把该 DSL 列从 100 调到 160，并增加真实文本单行断言；41c 正式构件上的新原图已复核，显示已通过、两个准确审批人、无继续替换按钮。节点选择截图是 picker 打开状态，部分理由区被正常浮层遮挡；不把它描述成全部确认字段同时可见。恢复及替换确认另有原图。

CSV 实际下载文件、摘要、字节数、15 列、冻结 Asia/Shanghai 与公式防护分别由 browser、独立解析及集成断言证明。501 成员实测发布 335ms、投影 5526ms、统计 163ms、导出 282ms、CSV 127920 字节；只支持该规模结论，不外推 50000 人吞吐。

## 失败、证伪与修正

- 原生网络故障在真实服务端受理后丢响应，或真实图片/铃铛请求中断；恢复仍访问真实服务，不伪造业务成功。首次 CSV 独立解析误假设 UTC，按冻结元数据修正解析器，没有改业务事实。
- 文件清理集成反例暴露 PostgreSQL nullable 参数问题；修复后真实集成通过。缺失、短/长、同长篡改文件均被拒绝，取消后的迟到对象仍被清理。
- 覆盖解析器曾漏掉执行型 DSL POST；修正后有正向、GET/文案/惰性 metadata 负向测试。原静态数据没有升级成执行 PASS。
- 第一次最终 BPM 构建被 Workspace CLI 的 Gradle cache 前缀匹配误停：Core suspend 匹配了 BPM cache。该次为 environment-invalid，不记测试通过。CLI 缺陷未在本任务改动，后续两个 runtime 生命周期/构建严格串行。
- 早前分离启动会话发生 JVM 非正常消失，原因未证明；不认定 OOM。最终受管 TTY 全周期使用明确 2GB 测试堆上限、loopback no-proxy 与独立私有诊断路径，当前全周期通过不能反推历史故障原因。
- `final-cycle` 早前虽 4 个业务通过但 `stop_exit=2`，保留失败。新增真实 SIGTERM 排空与 tsx 启动代次反例，修正为信号前完整归属校验、信号后只等待组排空；无额外信号。41c 正式重建后 `final-verified` 为 business 0 / stop 0，不以重试冒充原轮成功。

## 明确未执行与保留资源

小程序发送/真机、远程 BPM、50000 人容量、BPM 独立 340 corpus、OCI/Docker/Linux image、Jenkins 和生产发布 did_not_run；不计为本期业务通过。没有执行自动需求级全覆盖裁决，也不宣称全仓既有测试债务清零。未执行平台或规模不能从当前本地证据推断为通过。

PR：OSS [#2396](https://github.com/AuraBootTeam/auraboot/pull/2396)、BPM [#60](https://github.com/AuraBootTeam/aura-bpm/pull/60)、规则说明 [#1589](https://github.com/AuraBootTeam/auraboot-enterprise/pull/1589)。均保留到审阅；本次不合并，不删 worktree/分支，不销毁 runtime。两个本地栈已暂停；恢复前以最终提交和确切构件执行正式身份核验。文档与源码等价审计不会冒充新一轮业务执行。
