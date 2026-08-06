---
type: retro
status: active
date: 2026-08-06
relates_to:
  - /Users/ghj/work/auraboot/amos/docs/product-docs/amos/modular/05-AMOS-APP-01-Customer-Demand.md
  - /Users/ghj/work/auraboot/amos/docs/plans/2026-08-05-amos-dq-fo-current-state-program-ledger.md
---

# AMOS APP-01 QDP 异步状态与页面状态纵切片验收报告

allowed_claim: targeted-tested owning-repository QDP async compilation and visible page-state slice; not APP-01, assembled DQ, L1/L2, or AMOS readiness.

## 1. 结论

本切片在 AuraBoot Core 公共 CRM 的现有 `ReleaseQdpHandler` 上增量加入 `crm:compile_qdp_revision`，用平台既有 async command/task 路径实现 Draft/Validation Failed → Compiling → Ready for Review，并持久化编制阶段、进度、结果与校验恢复信息。带 approved exception 的有效编制以 `Partial Success` 完成；客户确认绑定错误则持久化 `Validation Failed`，修正后可对同一 QDP 重试。

Release Center 同时补齐 Loading、Empty、Partial Success、Validation Failed/recovery 可见状态，并继续覆盖 normal、stale、no-permission、cross-tenant、replay 和 external failure。legacy prepare/review/publish/release commands 保留；Released/Superseded 语义、Requirement Version + File Package Hash 客户确认绑定、diff/Pack Set/downstream impact 继续由同一个 writer 管理。

产品真源只读取 `/Users/ghj/work/auraboot/amos/docs/product-docs/amos`，并行状态只读取 current-state program ledger。未向 superseded `new_docs` 写入，也未从其历史状态推导 ready。

## 2. 产品、设计与所有权对齐

| 要求 | 本切片结果 | 裁决 |
| --- | --- | --- |
| APP-01 M10 / GT-D04 | 编制校验继续绑定 immutable Requirement Version、canonical File Package SHA-256、Customer Confirmation、diff、Pack Set 与 downstream impact | pass（本切片） |
| UX-D08 | Release Center 显示 Compiling/Validation Failed/Partial Success、进度、错误恢复、Released identity 与 impact | pass（本切片） |
| AC-APP01-003 | invalid/stale confirmation fail closed；修正绑定后可重试同一 QDP | pass |
| AC-APP01-004 | Ready for Review、Released、Superseded 与 legacy command 回归不变 | pass |
| AC-APP01-006 | UI/API permission、stale、tenant、replay 已验证；batch/deep-link/Agent caller parity 未验证 | partial |
| AC-APP01-008 | Loading、Empty、Partial Success、Validation Failed/recovery 加上既有 normal/read-only/conflict/external failure 均有真栈浏览器证据 | pass（本切片） |

公共合同没有被另起模型或 writer 改写。新 compile command 是同一 handler 的 additive command；其异步调度、权限、target/version lock、idempotency 与审计仍走平台 command contract。batch/deep-link/Agent parity 没有在本 slice 内擅自冻结新公共合同，因此保留为明确未测项。

## 3. Writer 与 legacy 字段合同裁决

- Core `plugins/crm` 是唯一 public `crm_qdp_revision_common` writer。
- Plugin `pcba-crm` 只保存 opaque QDP pid 并维持 exact-writer adapter；本切片没有在 Plugin 新增业务 writer。
- `crm:prepare_qdp_draft`、`crm:compile_qdp_revision`、`crm:submit_qdp_review`、`crm:publish_qdp_revision` 与 legacy `crm:release_qdp` 都复用 `ReleaseQdpHandler`。
- BOM、Quote、MDP owner 未修改；#270 只读，不在本交付范围。
- Plugin 侧历史删除 `crm/config/fields/crm_qdp_revision_common.json` 的最终裁决仍是 ownership consolidation，而不是合同删除。可验证兼容替代位于 Core `plugins/crm/config/fields/crm_qdp_revision_common.json`；ce55 的 20 个 legacy writer 字段与 legacy release 均通过 regression、config/reference gate 和 fresh host 执行验证。

## 4. Host-first 独立真栈

| Identity | Value |
| --- | --- |
| Composition | `amos-qdp-async-states-s59` |
| Backend / Web / BFF | `6459 / 5159 / 6159` |
| PostgreSQL / Redis | `auraboot_59 / DB 10` |
| Core source | `/Users/ghj/work/auraboot/auraboot/.worktrees/amos-qdp-release-center-public-crm/plugins/crm` |
| Plugin adapter source | `/Users/ghj/work/auraboot/plugins/.worktrees/amos-qdp-release-center/pcba-crm` |
| Core starting head | `454034b12c017443b55def09f27fc088aaa5bc54` |
| Plugin head | `598454bbee9a2177ce892d29756993a08740667c` |
| CRM jar SHA-256 | `6e3f49ec2abc9fd1c7e6c709a3b23e3b3524a8f6be5e7d6081d9c153ee1a71d2`，built/runtime 相同 |
| Import/reference | Core CRM `OK`、Plugin PCBA `OK`、0 dangling references |

HTTP、浏览器和 PostgreSQL 回读都访问上述独立真栈；没有用 mock、静态页面、生产系统或旧 composition 冒充。

## 5. 功能 / action / test matrix

| 功能 / action | 正常 | 负向/恢复 | 自动化 authority |
| --- | --- | --- | --- |
| Async compile dispatch | running → completed → Ready for Review | stale version、no permission、replay conflict | handler UT + 21-check HTTP IT + browser |
| Confirmation validation | exact Requirement Version + File Package Hash | Validation Failed 持久化；修正后同记录重试 | handler UT + HTTP IT + browser |
| Partial Success | approved exception 完成且可 review | outcome 不伪装为 full success | handler UT + HTTP IT + browser/modal/detail |
| Loading | task modal/progress 在真实 async task 运行时可见 | 缺少 marker 则 manifest incomplete | browser |
| Empty | 真实列表查询完成后明确 No data | 不把加载中/失败误报为空 | browser response + DOM |
| Released identity/impact | title/status/revision/hash/diff/confirmation/Pack Set/impact | raw code/JSON 泄漏断言 | browser + original PNG |
| Security/concurrency | exact release duty、tenant、CAS、idempotency、audit | 403、cross-tenant、stale、changed-intent、lock conflict | 21-check HTTP IT |
| Legacy compatibility | old release + 20 writer fields | direct field writer denied | config/handler/host regression |

## 6. 自动化测试结果

| 测试面 | 结果 | 分层 |
| --- | --- | --- |
| QDP config | 7/7 | contract / hermetic / blocking-commit / unit |
| Core + Plugin cross-repo contract | 11/11 | contract / hermetic / blocking-commit / unit |
| CRM Node regression | 24/24 | contract / hermetic / blocking-commit / unit |
| CRM backend | 159/159；QDP handler 35/35 | service / hermetic / blocking-commit / unit |
| CRM JSON parse | 217/217 | contract / hermetic / blocking-commit / unit |
| Plugin adapter | 13/13 | contract / hermetic / blocking-commit / unit |
| Host import/reference sweep | pass | contract / real-stack / blocking-commit / IT |
| HTTP true stack | 21/21 | API/journey / real-stack / blocking-commit / HTTP |
| Browser full golden | 6/6；1 worker；0 retry；3.6m | UI/journey / real-stack / blocking-commit / browser |
| Screenshot alignment | 1/1；0 retry | UI / real-stack / recorded / browser |

受控 mutation 将 compile retry 允许状态从 `{draft, validation_failed}` 改为 `{draft}` 后，校验失败恢复测试 1/1 变红；恢复产品条件后同一命令 1/1 变绿。完整失败分类与修复链见 trust report。

## 7. 分母变化

旧 manifest：14 rows = 10 pass + 1 partial + 1 gap + 2 untested。

新 manifest：17 rows = 15 pass + 1 partial + 1 untested + 0 gap。

变化原因：异步 gap 拆成 async compilation 与 validation-failed recovery 两个可执行 action；页面状态总行拆成 Loading、Empty、Partial Success 三个可执行 action。因此分母 `14 → 17`，pass `10 → 15`，gap `1 → 0`，untested `2 → 1`。这不是 100%：caller parity 仍 untested，旧 standalone CLI 仍 partial。

## 8. 原始浏览器 PNG 复核

本轮 current-source Playwright PNG 均以本地 `view_image` 原图模式检查：

- released identity 原图在同一 1280×720 画面显示 QDP code、Released、GT-D04、revision 4、64 位内容 Hash、`Changed: Requirement Version, File Package Hash, Pack Set` 与需求版本绑定；
- released impact 原图显示 Customer Confirmation、Pack Set `PCBA-MFG@browser`、`1 downstream object(s), 0 blocked`；
- loading、empty、validation-failed modal/detail、partial-success modal/detail、stale、external failure 和 no-permission 原图均呈现对应可操作反馈；
- 未见 raw `crm_qdp_*` 字段名、raw stage/outcome code、JSON、Java stack 或源码泄漏。

视觉只裁决可见性；状态、权限、绑定与持久化由可执行 DOM/API/DB 断言裁决。

## 9. Evidence Pack

- [机器可读分母](evidence/2026-08-06-qdp-async-states/qdp-async-states-acceptance-manifest.json)
- [机器可读 runtime identity](evidence/2026-08-06-qdp-async-states/qdp-async-states-runtime-evidence.json)
- [Trust report](evidence/2026-08-06-qdp-async-states/qdp-async-states-trust-report.md)
- [命令与测试记录](evidence/2026-08-06-qdp-async-states/qdp-async-states-test-evidence.md)
- [HTTP 21/21](evidence/2026-08-06-qdp-async-states/qdp-release-center-true-stack-20260806-231048.json)
- [Browser 6/6](evidence/2026-08-06-qdp-async-states/qdp-release-center-browser-20260806-231048.json)
- [Released identity 原图](evidence/2026-08-06-qdp-async-states/qdp-release-center-released-identity.png)
- [Released impact 原图](evidence/2026-08-06-qdp-async-states/qdp-release-center-released-impact.png)
- [Loading 原图](evidence/2026-08-06-qdp-async-states/qdp-release-center-compiling-loading.png)
- [Empty 原图](evidence/2026-08-06-qdp-async-states/qdp-release-center-empty-state.png)
- [Validation Failed 原图](evidence/2026-08-06-qdp-async-states/qdp-release-center-validation-failed-detail.png)
- [Partial Success 原图](evidence/2026-08-06-qdp-async-states/qdp-release-center-partial-success-detail.png)
- [No Permission 原图](evidence/2026-08-06-qdp-async-states/qdp-release-center-no-permission.png)

## 10. 残余 gaps / 边界

1. batch、direct deep-link command、Agent Tool caller parity 未验证。
2. 旧 standalone plugin CLI 对部分 host-supported async command presentation property 仍为 partial。
3. 未执行 writer cutover、破坏性迁移、legacy 退役、生产发布、PR merge 或 #270 业务修改。
4. 本证据只支持这一 owning-repository async/page-state 纵切片，不支持整个 APP-01、assembled DQ、L1/L2 或 AMOS ready。
