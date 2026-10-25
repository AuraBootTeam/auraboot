---
type: system-reference
status: active
created: 2026-10-25
updated: 2026-10-25
---

# 公告 S2–S8 持续验收记录

## 结论与适用范围

**allowed_claim: targeted-tested；完整功能与黄金验收尚未完成。** 需求、默认取舍及唯一缺口清单以[公告管理设计](./announcement-management.md)§16–§18为准。本记录只裁决已执行证据，不另立需求或缩小S2–S8范围；小程序实际发送端不在本期，适配契约仍在范围内。现有PR保持草稿，未合并，未发布生产。

最新仓库浏览器验收的源码为`cd5ce8296a6d614f031e6cb9ec04f72887463b19`，使用同一隔离数据库、独占Redis及公开启动器的Web/BFF/后端。启动器产品构件核验及完成启动后的Workspace核验通过。源码、端口、监听进程与连接身份同源；health不独立充当身份凭证。网络提示与时间列修正已在该源码复验，应用代码与efaa3e65c相同；后端Java代码与50项单测、2项真实集成所验证的a1b827a69相同。后续差异及未执行矩阵仍分开记录。

新增持久催办在f5efd77be执行21项真实PostgreSQL/HTTP/outbox集成，无skip，包括11项催办与10项既有统计/导出场景；规则层55f63c026执行19项单测。Core151空库迁移、独立drift及150→151升级通过。恢复及历史状态接口已有真实证据；完整权限与原生催办页面仍待验证，不能套用cd5的浏览器证据；实现边界见设计§18.16–18.17。

## 本轮实际证据

| 层次 | 已执行裁决 | 边界 |
| --- | --- | --- |
| 后端hermetic unit | 31项目标授权＋19项异步任务，共50项通过，无skip | 授权payload绑定、当前身份复查、队列保留及既有执行边界；不是全产品覆盖率 |
| 真实数据库/HTTP集成 | 2项通过，无skip | 实际队列生成、受控HTTP下载和撤权；提交事务中先取消，删除被拒后状态仍可查，无生成尝试 |
| 前端定向单测 | 先前相关动作、表格、指标、下载272项；后续数据源/路由等104项；最新展示/反馈回归245项通过 | 不同轮次存在重叠，不相加；类型检查分别按准确源码通过 |
| 仓库浏览器回归 | cd5ce8296上13项通过，无retry/skip | 9条公告生命周期、3条租户政策及1条自建前置数据的原生统计/导出旅程；认证setup另计，不计为业务场景 |
| 新统计浏览器旅程 | 菜单→详情→固定统计→空筛选/清除→统一命令→刷新任务→真实CSV下载 | 使用原生DSL；core路径通过真实按钮，不用API调用代替按钮 |
| 不确定网络 | 服务端受理后主动丢弃响应，刷新后按钮重试通过 | 两次请求身份相同，返回同一任务且标记幂等重放；读取数据库确认该固定快照仅一条导出 |
| 实际文件 | 仓库用例的实际下载经Python csv独立解析，15列1行；先前3行文件另经csv/datetime/zoneinfo核验 | UTF-8 BOM、固定人员/阅读/知悉状态、冻结时区及持久字节数/摘要一致；1行与3行来自不同受控前置数据，不混记为同一文件 |
| 视觉复核 | cd5ce8296仓库用例的8张原图、先前efaa3e65c的9张原图均逐张复核通过 | 网络提示已本地化、时间文本单行、空分类说明明确，完成态等待按钮可用后重拍；完整矩阵仍未关闭，不宣称全产品黄金达成 |
| 插件/静态 | 插件校验0错误、4条详情块类型建议警告；页面UX审计8页0错误0警告 | 不是浏览器执行，不能代替安装、HTTP、交互或文件证据 |

测试源码入口：`CommandTargetScopePhaseTest`、`AsyncTaskServiceImplTest`、`AnnouncementStatisticsIntegrationTest`；前端`ActionRegistry`、`DataSourceManager`、`useSchemaLoader`、动态详情身份及块渲染测试。公开浏览器回归为`web-admin/tests/e2e/announcement/`。新增`announcement-statistics.spec.ts`使用自建且仅面向当前用户的发布/阅读前置数据，核心统计及导出走UI；已在cd5ce8296真实执行通过，包含正文已读/知悉未完成的固定统计，以及网络故障后原请求重放。此前冻结驱动与原始结果保存在任务登记的runtime evidence，受控文件和认证状态不提交到公共仓库。

可定位的原始证据轮次为`2026-10-24T23-29-35-891Z-c3432443-2f1b-4f9c-b181-573139bb7c2a`（12条回归），及`2026-10-24T23-46-09-668Z-8efa4ad3-71e4-44a2-a6a2-4f008691fbed`（a1b827a69原生统计v7/v8、实际下载）。efaa3e65c复验轮次为`2026-10-24T23-59-04-117Z-39a89320-7ea2-419c-b48a-3f2f35882c63`（原生统计v9，含9张原图与独立CSV校验）。最新仓库用例与12条回归的轮次为`2026-10-25T00-11-06-682Z-d9225533-cb0a-452a-ad5a-d56af0fac990`；[公开裁决manifest及8张原图](./evidence/announcement-s2-s8-2026-10-25/manifest.json)仅含合成测试数据。原始失败版本、JUnit、身份核验、下载摘要及独立解析结果分别保留，不用成功重跑覆盖原失败。

## 完整范围的覆盖分母

| 范围 | 当前状态 | 未关闭项 |
| --- | --- | --- |
| S2受众与附件 | partial | 分页预览、真实上传/下载、控制文件及完整身份边界 |
| S3发布执行 | partial | 恢复/重试、同步与过期、容量/监控及完整异常；既有立即/定时/取消有真实证据 |
| S4中立审批契约 | targeted-tested | 与最新BPM正式构件配套复验，不能只看领域服务 |
| S5严格BPM | partial | 恢复/重分配页面、完整严格业务浏览器矩阵 |
| S6组合安装/联动 | partial | 最新BPM构件、普通停止、附件及业务审批UI；历史构件组合证据不代表最新源码 |
| S7接收者/铃铛/待办 | partial | 最新候选完整复验、附件及完整错误状态；既有核心菜单旅程有真实证据 |
| S8催办 | backend targeted-tested；UI candidate | 持久批次、共享去重/额度、并发、消费前撤权及恢复已有真实证据；原生页面完整旅程、完整权限/状态、观测与容量仍待验证 |
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
integration_tests: latest 50 hermetic units + 2 real-stack HTTP/database cases passed
integration_coverage: coverage_not_measured for complete S2–S8 scope
e2e_specs: existing announcement lifecycle/policy specs; repository statistics/lifecycle/policy specs; earlier frozen driver evidence remains separately scoped
e2e_collection: separate from actual execution; static checks do not contribute passes
e2e_execution: 13 repository scenarios passed at cd5ce8296; prior driver evidence is not counted again
feature_action_matrix: partial; canonical denominator is SOT §16.8, unresolved scope listed above
browser_evidence: registered runtime evidence rounds and original PNGs; no full golden claim
backend_evidence: exact-source JUnit and real PostgreSQL/HTTP assertions
artifact_evidence: actual browser CSV, independent parser, fixed-capture binding, persisted checksum/size
permission_negative: current-authority HTTP denial tested; full browser/owner/member matrix remains partial
visual_feedback: filter/empty/download and uncertain network observed; identified corrections reverified; full state/permission visual matrix remains partial
skip_fixme_threshold_retry_audit: scoped normal runs report zero skips/retries; mutation filters and no-tests attempts are separate
did_not_run: complete S2–S8 coverage, remaining BPM UI/composition, reminder browser/state/security and observability validation
remaining_blockers: outstanding scope and state/visual rows above
allowed_claim: targeted-tested; incomplete S2–S8 delivery, no merge or production release
```
