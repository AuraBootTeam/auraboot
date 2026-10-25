---
type: system-reference
status: active
created: 2026-10-25
updated: 2026-10-25
---

# 公告 S2–S8 持续验收记录

## 结论与适用范围

**allowed_claim: targeted-tested；完整功能与黄金验收尚未完成。** 需求、默认取舍及唯一缺口清单以[公告管理设计](./announcement-management.md)§16–§18为准。本记录只裁决已执行证据，不另立需求或缩小S2–S8范围；小程序实际发送端不在本期，适配契约仍在范围内。现有PR保持草稿，未合并，未发布生产。

最新已执行的公告源码为`2e248b1c9943201f82e4d629277f541955b982c2`：106项hermetic单测、32项真实PostgreSQL/HTTP/outbox集成通过；发布恢复1条浏览器旅程与同版其余15条公告旅程分别执行通过，无skip/retry，认证setup单独计数。类型检查通过；212项前端单测来自生产TS相同的325版本，不与其他轮次累加。公开启动器及Workspace身份核验通过后才执行业务浏览器。本轮3张发布恢复原图及第一页历史表/分页器2张补图已逐张复核，[同一manifest](./evidence/announcement-s2-s8-2026-10-25/reminders/manifest.json)保留准确来源和摘要。

恢复旅程使用普通SELF发布者：原成员暂停后真实任务阻断，恢复同一成员后从原生详情恢复原任务，累计第二次尝试发布成功，只有一条站内通知。旧worker、成员重建、期限和终态拒绝另有本轮真实集成断言。此处为定向证据，尚不能将全S2–S8标为完成。后续1fc8cb42b的默认审批绑定/能力查询已实际执行111项hermetic单测及33项真实集成，无失败/跳过；原生模式控件及BPM组合尚待对应复验。当前更晚的操作核对/渠道历史/监控候选不借用这些数量。

下文“本轮实际证据”表保留c1921cd6b历史轮次；707da9c92、5cfd5fcd0及2e248b1c9为后续补充，不将相互重叠用例累加。历史保护恢复曾命中25项Gradle缓存，已经更正为缓存复用；707的26项、5c的28项及2e的32项均有实际执行记录。催办历史第一页的分页器视觉缺口已由本轮单独补图闭合，原历史图与其partial裁决继续保留。

## 本轮实际证据

| 层次 | 已执行裁决 | 边界 |
| --- | --- | --- |
| 后端hermetic unit | 31项目标授权＋19项异步任务，共50项通过，无skip | 授权payload绑定、当前身份复查、队列保留及既有执行边界；不是全产品覆盖率 |
| 真实数据库/HTTP/outbox集成 | c1921cd6b上22项通过，无skip | 10项既有统计/导出、11项催办、1项受控发布人投影；自动日历使用测试主机时钟，数据库和系统时间不变 |
| 前端定向单测 | 先前相关动作、表格、指标、下载272项；后续数据源/路由等104项；最新展示/反馈回归245项通过 | 不同轮次存在重叠，不相加；类型检查分别按准确源码通过 |
| 仓库浏览器回归 | c1921cd6b上14项通过，无retry/skip | 9条生命周期、3条政策、1条催办、1条统计/导出；精确原生入口/请求/持久结果，认证setup另计 |
| 新统计浏览器旅程 | 菜单→详情→固定统计→空筛选/清除→统一命令→刷新任务→真实CSV下载 | 使用原生DSL；core路径通过真实按钮，不用API调用代替按钮 |
| 不确定网络 | 服务端受理后主动丢弃响应，刷新后按钮重试通过 | 两次请求身份相同，返回同一任务且标记幂等重放；读取数据库确认该固定快照仅一条导出 |
| 实际文件 | 仓库用例的实际下载经Python csv独立解析，15列1行；先前3行文件另经csv/datetime/zoneinfo核验 | UTF-8 BOM、固定人员/阅读/知悉状态、冻结时区及持久字节数/摘要一致；1行与3行来自不同受控前置数据，不混记为同一文件 |
| 视觉复核 | c1921cd6b的16张催办/统计/导出/发布信息原图逐张复核通过 | 网络提示已本地化、时间文本单行、空分类说明明确，完成态等待按钮可用后重拍；完整矩阵仍未关闭，不宣称全产品黄金达成 |
| 插件/静态 | 插件校验0错误、4条详情块类型建议警告；页面UX审计8页0错误0警告 | 不是浏览器执行，不能代替安装、HTTP、交互或文件证据 |

测试源码入口：`CommandTargetScopePhaseTest`、`AsyncTaskServiceImplTest`、`AnnouncementStatisticsIntegrationTest`；前端`ActionRegistry`、`DataSourceManager`、`useSchemaLoader`、动态详情身份及块渲染测试。公开浏览器回归为`web-admin/tests/e2e/announcement/`。新增`announcement-statistics.spec.ts`使用自建且仅面向当前用户的发布/阅读前置数据，核心统计及导出走UI；已在c1921cd6b真实执行通过，包含正文已读/知悉未完成的固定统计，以及网络故障后原请求重放。此前冻结驱动与原始结果保存在任务登记的runtime evidence，受控文件和认证状态不提交到公共仓库。

可定位的原始证据轮次为`2026-10-24T23-29-35-891Z-c3432443-2f1b-4f9c-b181-573139bb7c2a`（12条回归），及`2026-10-24T23-46-09-668Z-8efa4ad3-71e4-44a2-a6a2-4f008691fbed`（a1b827a69原生统计v7/v8、实际下载）。efaa3e65c复验轮次为`2026-10-24T23-59-04-117Z-39a89320-7ea2-419c-b48a-3f2f35882c63`（原生统计v9，含9张原图与独立CSV校验）。最新仓库用例与12条回归的轮次为`2026-10-25T00-11-06-682Z-d9225533-cb0a-452a-ad5a-d56af0fac990`；[公开裁决manifest及8张原图](./evidence/announcement-s2-s8-2026-10-25/manifest.json)仅含合成测试数据。原始失败版本、JUnit、身份核验、下载摘要及独立解析结果分别保留，不用成功重跑覆盖原失败。

## 完整范围的覆盖分母

| 范围 | 当前状态 | 未关闭项 |
| --- | --- | --- |
| S2受众与附件 | partial | 分页预览、真实上传/下载、控制文件及完整身份边界 |
| S3发布执行 | partial | 原任务显式恢复已有当前浏览器及真实集成证据；同步与过期、容量/监控及完整异常仍须对账 |
| S4中立审批契约 | targeted-tested | 与最新BPM正式构件配套复验，不能只看领域服务 |
| S5严格BPM | partial | 恢复/重分配页面、完整严格业务浏览器矩阵 |
| S6组合安装/联动 | partial | 最新BPM构件、普通停止、附件及业务审批UI；历史构件组合证据不代表最新源码 |
| S7接收者/铃铛/待办 | partial | 最新候选完整复验、附件及完整错误状态；既有核心菜单旅程有真实证据 |
| S8催办 | targeted-tested | 核心原生旅程、持久批次、去重/额度、并发、撤权及恢复API已有证据；批次历史、完整权限/恢复UI、观测容量仍待完成 |
| S8统计/导出 | targeted-tested | 真正跨页、版本切换、零分母/迁移、各任务/权限状态、上传取消并发、清理失败重试及完整视觉复核 |

任何missing、partial或未执行行都阻止“全部完成、黄金通过、覆盖率100%”结论。历史S1与早期S2–S8证据不重复计数；完整逐行动点的D01–D15仍以设计§16.8为分母。

## 反证与可信度

真实反例曾拒绝未声明页面键、默认下拉适配造成的分页信息丢失、API结果未展开的导航路径，以及统一命令未传入授权payload。修正分别经过生产解析器/数据源/FlowRunner、真实命令或浏览器复验。下载MIME守卫曾在明确变异轮次看到红色，恢复后回归通过；该变异轮次的筛选跳过不算正常回归跳过。不确定网络采用真实上游请求后丢响应，不伪造服务端成功。测试边界与真实残留数据库复跑分别记录。

首次独立CSV检查错误地假设UTC；只读绑定证明公告冻结时区为Asia/Shanghai，随后按冻结元数据验证绝对时间与offset。没有修改产品数据来迎合解析器。

## Final Evidence Pack

```text
acceptance_report: docs/system-reference/2026-10-25-announcement-s2-s8-testing-gate-acceptance-report.md
claim_level: targeted-tested
current_sot: docs/system-reference/announcement-management.md §16–§18
business_scope: S2–S8 including BPM composition; actual mini-program sender excluded
integration_tests: latest 22 real-stack PostgreSQL/HTTP/outbox cases passed; earlier broad 92 announcement units and 19 calendar/eligibility units remain separately scoped
integration_coverage: coverage_not_measured for complete S2–S8 scope
e2e_specs: existing announcement lifecycle/policy specs; repository statistics/lifecycle/policy specs; earlier frozen driver evidence remains separately scoped
e2e_collection: separate from actual execution; static checks do not contribute passes
e2e_execution: 14 repository scenarios passed at c1921cd6b; setup authentication 1 separately; prior driver evidence not counted again
feature_action_matrix: partial; canonical denominator is SOT §16.8, unresolved scope listed above
browser_evidence: exact-source registered runtime evidence and 16 individually reviewed synthetic PNGs; no full golden claim
backend_evidence: exact-source JUnit and real PostgreSQL/HTTP assertions
artifact_evidence: actual browser CSV, independent parser, fixed-capture binding, persisted checksum/size
permission_negative: current-authority HTTP denial tested; full browser/owner/member matrix remains partial
visual_feedback: filter/empty/download and uncertain network observed; identified corrections reverified; full state/permission visual matrix remains partial
skip_fixme_threshold_retry_audit: scoped normal runs report zero skips/retries; mutation filters and no-tests attempts are separate
did_not_run: complete S2–S8 coverage, remaining BPM UI/composition, complete reminder state/security/recovery UI, history and observability validation
remaining_blockers: outstanding scope and state/visual rows above
allowed_claim: targeted-tested; incomplete S2–S8 delivery, no merge or production release
```


最新轮次为`2026-10-25T01-03-32-015Z-5fa48cff-efb4-424a-946d-e63e1322aba8`，[14条执行与16张逐图裁决](./evidence/announcement-s2-s8-2026-10-25/reminders/manifest.json)只包含合成数据；实际CSV仍为15列1行503字节、冻结Asia/Shanghai且摘要一致。原丢响应、错误返回链接、权限夹具跨租户查询及限流文案的失败/修复分别留证，不用重跑覆盖原结果。当前证据不把单页分页控件算作真正多页，也不把“提醒已生成”算作已读或知悉。

## 默认审批与运营入口补充

1fc8cb42b：111项单元、33项真实集成实际执行通过，JUnit独立保留。新增断言验证非空单条流程覆盖被拒绝且无操作残留、历史政策可读但草稿必须明确清除、完整提供方缺默认流程仍不可审批、发布者不读取任意流程目录，以及HTTP选项接口。BPM组合测试已转为政策入口绑定默认值，并加入默认变更不改既有申请、首次派发停用后原申请核对恢复；尚未对最新组合构件执行，不能记为通过。

下一候选增加原操作查询、原生导出核对、渠道分页页和26个固定stage指标。Core152的全量迁移生成、独立drift、原任务库151→152增量及157条组合validate均成功；插件校验0错误/8条块类型建议，12页UX静态审计0错误。功能、UI、指标实际样本及容量验证须由对应运行结果补齐，不以静态或迁移证明代替。


3bf2fdfd1的运营入口定向验证：117项hermetic单测、35项真实PostgreSQL/HTTP/outbox集成及17项前端单测通过，无失败/跳过；43d7d6d1类型检查通过，两版生产TS相同。第一次43d执行曾有116/117单测、34/35集成和16/17前端；分别因缺省mock返回空Map而非null、测试删除缺权限、以及新页使用不支持的secondary按钮值而失败，原日志和JUnit保留，不能改写为首轮通过。修正后全部相关用例实际执行，非Gradle缓存。

3bf原生浏览器2条通过（54.2秒、无skip/retry，认证另1条），包括通知设置的缺BPM禁用模式、预约取消、统计筛选、导出丢响应后核对同一原操作、原键重放、实际CSV摘要/解析、准确版本渠道页和刷新不重发。公开启动9/9及Workspace监听/构件/源码/缓存/DB身份复核无finding/warning。发布方式控件与统计整页原图复核通过；渠道原图发现站内通知列换行，已登记并调整列宽，待单行断言和原图复验。更早2e的16条浏览器旅程不借用为当前全量回归。
