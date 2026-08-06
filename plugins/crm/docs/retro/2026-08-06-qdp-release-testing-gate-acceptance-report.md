---
type: retro
status: active
date: 2026-08-06
relates_to:
  - /Users/ghj/work/auraboot/amos/docs/product-docs/amos/modular/05-AMOS-APP-01-Customer-Demand.md
  - /Users/ghj/work/auraboot/amos/docs/plans/2026-08-05-amos-dq-fo-current-state-program-ledger.md
---

# AMOS APP-01 QDP Release Center 纵切片验收报告

allowed_claim: targeted-tested owning-repository QDP Release Center slice; not APP-01, assembled DQ, L1/L2, or AMOS readiness.

## 1. 结论与归属

本切片在 AuraBoot Core 公共 `plugins/crm` 的现有 QDP writer 上完成 Requirement Version、File Package、Customer Confirmation 的四记录绑定，补齐 Draft → Ready for Review → Released → Superseded 生命周期，以及版本 diff、Pack Set、下游影响、权限、并发、重放、租户隔离、审计和外部文件失败恢复。它复用 `ReleaseQdpHandler`，保留 legacy `crm:release_qdp`，没有创建第二个 QDP 模型或发布 writer。

产品裁决读取 `/Users/ghj/work/auraboot/amos/docs/product-docs/amos`，并行状态只读取 current-state program ledger。superseded `new_docs` 不参与 ready 推导，本交付未向其写入。AMOS PR #23 / ledger §7.1 是本 slice 的 stacked governance dependency；本 PR 不执行 writer cutover、破坏性迁移、legacy 退役或生产发布。

## 2. 产品功能与设计对齐

| 要求 | 实现与证据 | 裁决 |
| --- | --- | --- |
| AC-APP01-003 | Customer Confirmation 同时绑定 immutable Requirement Version、canonical File Package SHA-256 和确认人/时间/证据；缺失、篡改或文件未完成均 fail closed | pass |
| AC-APP01-004 | Released 不原地修改；后续 revision 发布时以 CAS 将上一版转为 Superseded | pass |
| AC-APP01-006 | UI 与 HTTP caller 的权限、目标 pid、expected version、replay identity 已验证；batch/deep-link/Agent Tool 尚未验证 | partial |
| AC-APP01-008 | normal、read-only/no-permission、conflict/stale、external failure/recovery 已验证；Loading、Empty、Partial Success 尚未验证 | partial |
| GT-D04 | 内容/来源、假设/例外、客户确认、Pack Set、下游责任与资格摘要持久化为 immutable QDP snapshot | pass（本切片） |
| UX-D08 | Release Center 列表/详情展示生命周期、hash、diff、确认、Pack Set、下游影响；错误与权限边界可见 | pass（本切片） |

`Compiling` 与 `Validation Failed` 仅保留为字典状态；异步编译器不在本纵切片，因此完整目标生命周期仍有明确 gap。

## 3. Writer 与兼容裁决

- `crm_qdp_revision_common` 仍是公共 CRM 拥有的 command-only-create 模型。
- `crm:prepare_qdp_draft`、`crm:submit_qdp_review`、`crm:publish_qdp_revision` 与 legacy `crm:release_qdp` 由同一个 `ReleaseQdpHandler` 处理。
- PCBA 只保存 opaque QDP revision pid；只有 legacy release 与 additive publish 两个 exact command writer 可以更新该引用。
- Plugin 私有 `crm/config/fields/crm_qdp_revision_common.json` 的删除是 ownership consolidation 的最终裁决，不是字段合同丢失。兼容替代是本仓 `plugins/crm/config/fields/crm_qdp_revision_common.json`：ce55 的 20 个 legacy QDP 字段全部保留，当前共 45 字段，legacy release 仍可执行。

## 4. Host-first 真栈

- Composition：`amos-qdp-release-center-public-s58`
- Backend / Web / BFF：`6458 / 5158 / 6158`
- PostgreSQL：`auraboot_58`
- Redis DB：`9`
- Core source：`/Users/ghj/work/auraboot/auraboot/.worktrees/amos-qdp-release-center-public-crm/plugins/crm`
- Plugin adapter source：`/Users/ghj/work/auraboot/plugins/.worktrees/amos-qdp-release-center/pcba-crm`
- Core stacked base：`132c619913d2a7de9c18367c614a3747a308d6df`（Core #1597，继续依赖 #1595 `23c887fedad0b79e774a19c853101e14c5b3f5ae`）
- CRM jar SHA-256：`084f7a0c8259a2b59109cdfd687ba18a89bbb79769cdfcb9bde2a88cb3a27937`

运行时由当前 Core worktree 重建 jar，复制到独立 PF4J 目录后以 fresh DB 启动；canonical importer 从上述两个精确源码根导入，cross-plugin reference sweep 通过。HTTP 和浏览器均访问这套真实 backend/Web/PostgreSQL，不使用 mock、静态页面或旧 composition 冒充。

## 5. 自动化与功能测试矩阵

| 测试面 | 结果 | 信任分类 |
| --- | --- | --- |
| CRM QDP config | 7/7 | hermetic contract / unit / blocking-commit |
| Core CRM + Plugin PCBA adapter | 11/11 | cross-repository contract / unit / blocking-commit |
| CRM Node regression | 24/24 | hermetic contract / unit / blocking-commit |
| CRM backend | 156/156；QDP handler 32/32 | hermetic service / unit / blocking-commit |
| CRM JSON parse | 225/225 | hermetic contract / unit / blocking-commit |
| DSL action/reference gates | pass | hermetic contract / unit / blocking-commit |
| Host import/reference sweep | pass | real-stack contract / IT / blocking-commit |
| HTTP true stack | 19/19 | real-stack API/journey / HTTP / blocking-commit |
| Browser golden | 4/4，1 worker，0 retry，2.4m | real-stack UI/journey / browser / blocking-commit |
| Manifest | 14 rows：10 pass、1 partial、1 gap、2 untested；missing evidence 0 | denominator / recorded |

HTTP 19 项覆盖 normal、stale、no-permission、cross-tenant、replay/changed-intent conflict、external file failure/recovery、exact writer、legacy compatibility、supersede 与 audit。浏览器 4 项覆盖列表/详情、stale 可见反馈、外部失败恢复发布、no-permission。

## 6. 原图人工复核

本轮 current-source Playwright 原始 PNG 已用本地原图查看器逐张复核：

- 列表可见 QDP 发布中心以及 Released、Ready for Review、Superseded 等本地化状态。
- 已发布详情首屏可见 GT-D04、64 位内容 Hash 与 `Changed: Requirement Version, File Package Hash, Pack Set`。
- 下滚原图可见客户确认对象/证据/人/时间、客户确认文件包 Hash、`PCBA-MFG@browser`、`1 downstream object(s), 0 blocked` 与 `passed`。
- stale 与外部文件保留失败均显示可操作的错误反馈；no-permission 用户看到明确 Access denied，且无菜单、数据或生命周期动作。
- 未见 raw `crm_qdp_*` 字段名、JSON、Java 堆栈或源码泄漏。

视觉原图只裁决可见性；生命周期、权限与持久化仍由可执行断言和 PostgreSQL 回读裁决。

## 7. 可证伪失败记录

| 失败 | 分类 | 首个根因 | 修复与复验 |
| --- | --- | --- | --- |
| 初次 host import 拒绝 Requirement Version 字段 | implementation defect | `crm_rv_*` 与既有 review 字段全局冲突 | 改为 `crm_reqv_*` 并加入全局唯一性门禁；fresh import 通过 |
| 生命周期 publish 无法写 PCBA 当前 QDP 引用 | compatibility defect | sidecar exact-writer allowlist 只有 legacy command | 同一 writer 增量授权 publish；adapter regression 与 host publish 通过 |
| 初始角色隐式获得 release 权限 | permission defect | CRM admin/manager expansion 绕过 composite duty | 移除隐式授权，只给显式 `pe_qdp_release_manager`；无权限负向通过 |
| 当前源码补拍第一次无法加载 Playwright | environment invocation | Core 根没有 `@playwright/test` | 使用预置 Playwright runtime 的同一配置，不安装依赖、不改锁文件 |
| 补拍第一次详情状态断言失败 | residual fixture / test driver | 复用的 Released pid 已被上一成功 run 合法 supersede | 读取真栈确认当前 Released pid，使用新 replay identity；0 retry 完整 4/4 通过 |

失败 run 未被当作绿灯或从报告中隐藏。

## 8. Evidence Pack

- [机器可读分母](evidence/2026-08-06-qdp-release-center/qdp-release-center-acceptance-manifest.json)
- [HTTP 19/19](evidence/2026-08-06-qdp-release-center/qdp-release-center-true-stack-20260806-191622.json)
- [Browser 4/4](evidence/2026-08-06-qdp-release-center/qdp-release-center-browser-20260806-191622.json)
- [命令与运行记录](evidence/2026-08-06-qdp-release-center/qdp-test-run-evidence.md)
- [列表原图](evidence/2026-08-06-qdp-release-center/qdp-release-center-list.png)
- [详情首屏原图](evidence/2026-08-06-qdp-release-center/qdp-release-center-released-detail.png)
- [确认/Pack Set/下游影响原图](evidence/2026-08-06-qdp-release-center/qdp-release-center-released-impact.png)
- [stale 原图](evidence/2026-08-06-qdp-release-center/qdp-release-center-stale-feedback.png)
- [外部失败原图](evidence/2026-08-06-qdp-release-center/qdp-release-center-external-failure-feedback.png)
- [无权限原图](evidence/2026-08-06-qdp-release-center/qdp-release-center-no-permission.png)

## 9. 残余 gaps

1. `Compiling` / `Validation Failed` 异步编译编排未实现。
2. batch、deep-link command、Agent Tool 的权限/状态/replay parity 未验证。
3. Loading、Empty、Partial Success 的显式页面状态未验证。
4. 旧 standalone plugin CLI 对 host-supported command properties 仍报结构诊断，因此该轴为 partial；canonical import/reference/runtime 是本切片可执行 authority，不把 CLI 伪装成绿。
5. generic command modal 的 semantic dialog role / custom submit label、部分 i18n lookup 与 SSE heartbeat 有 host UX/可访问性债务。
6. 未执行 writer cutover、破坏性迁移、legacy 退役、生产发布或 PR merge。

这些证据仅支持本 owning-repository APP-01 QDP Release Center 纵切片和配套 PCBA adapter，不支持整个 APP-01、DQ Journey、L1/L2 或 AMOS ready。
