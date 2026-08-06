# AMOS APP-01 QDP 基础首片验收报告（2026-08-06）

## 1. 结论

本次交付完成并验证了一个可运行的 **QDP 基础纵切片**：CRM 客户需求上的正式发布动作可以消费 PCBA 资格侧车和平台文件服务，基于可信 `row_version` 与客户端请求身份创建不可变 QDP Revision；HTTP 与浏览器真栈均已落到 PostgreSQL，并产生审计记录。

结论必须分层表达：

- 对“QDP 基础首片 + 它依赖的 L1 合同”判定为 **pass**。
- 对北极星中的完整 APP-01 / UX-D08 QDP Release Center 判定为 **partial**。
- 不据此宣称 L1、L2 或整个 AMOS 已 ready；这次只证明了一个 L3 纵切片真实反压并补齐了两项 L1 合同。

## 2. SoT 与文档关系

产品裁决以 `/Users/ghj/work/auraboot/amos/docs/product-docs/amos` 为 SoT，入口是 `/Users/ghj/work/auraboot/amos/docs/README.md`；本切片对应 `modular/05-AMOS-APP-01-Customer-Demand.md` 中的 M10、GT-D04、UX-D08、AC-APP01-003/004/006/008。并行执行状态只读取 `/Users/ghj/work/auraboot/amos/docs/plans/2026-08-05-amos-dq-fo-current-state-program-ledger.md`。

`/Users/ghj/work/auraboot/amos/docs/decisions/DDR-2026-08-06-amos-dq1-qdp-bom-structure-writer-boundary.md` 与 brownfield writer/cutover DDR 是实现边界和 writer 所有权决策的支持文档，不替代产品 SoT。superseded 历史入口不用于推导 ready，本交付也不向其写入。

L1 Shared Platform Base 由 AuraBoot canonical 实现，AMOS 只消费合同；本次实际补齐的是：

1. 动态 `mt_*` 记录的统一 `row_version` 创建、迁移、读取与写入递增合同。
2. Web 详情页正式动作在 `promptUpload` 后仍从“当前被点击记录”解析 `${record.pid}`，并同时传入命令 target 与 payload。

## 3. 已实现产品功能

| 功能 | 状态 | 说明 |
| --- | --- | --- |
| CRM 拥有 QDP Revision | pass | `crm_qdp_revision_common` 为不可变、command-only-create 模型；普通 CRUD 不是 writer |
| 正式发布命令 | pass | `crm:release_qdp` 为唯一当前产品 writer，目标是 Customer Request，发布不覆盖旧版本 |
| PCBA 行业资格侧车 | pass | 服务端校验路由归属、反向关联、DFM `passed/conditional`；PCBA 只保存当前 QDP pid 引用 |
| 文件真实性与保留 | pass（本首片） | 只接受已完成 multipart 的 actor-owned 文件；读取字节计算 SHA-256；retain 失败则发布失败 |
| 乐观锁与幂等 | pass（已测范围） | 缺失/过期版本失败；同一请求身份同意图重放；改意图 409；新身份创建下一 Revision |
| 精确物理 writer 拒绝 | pass（21 字段） | QDP 20 个保护字段和侧车当前-QDP 字段拒绝普通直接写入 |
| QDP 历史 UI | pass | 客户需求详情展示版本号、序号、资格、内容哈希、文件名、发布人和时间 |
| 浏览器上传发布 | pass | 页面上传 `qdp-browser-upload.csv` 后创建并回读 `R0003` |
| 完整 QDP Release Center | gap | 版本差异、客户确认、Pack Set、影响/异常/下游责任与多文件编辑尚未完成 |

## 4. 真栈环境

- Runtime：`amos-qdp-row-version-s54`
- Core：`/Users/ghj/work/auraboot/auraboot/.worktrees/amos-qdp-row-version`
- 原始真栈 Plugin source：`/Users/ghj/work/auraboot/plugins/.worktrees/amos-dq1-qdp-release`（保留为本证据运行的历史 provenance）
- 当前 canonical CRM source：`/Users/ghj/work/auraboot/auraboot/.worktrees/amos-dq1-qdp-release-public-crm/plugins/crm`
- Web / BFF / backend：`5154 / 6154 / 6454`
- PostgreSQL：`auraboot_54`
- Core base：`9efdc7f4bc8cc185db71a24c986e1a8e75a76519`
- Plugin base：`36067e03e22e1cd2112afde29036e0e9abb876f0`

三层健康检查、端口 PID 与源码工作目录均在测试前核验；没有复用旧 Docker 运行态作为证据。

2026-08-06 public CRM ownership consolidation 后，QDP CRM-owned implementation、测试与本证据目录按原字节迁入 `AuraBootTeam/auraboot/plugins/crm`；private Plugins PR 仅保留 PCBA reference-sidecar adapter。迁移不改变 model/command code、writer 语义或原始真栈裁决。

## 5. 功能测试与自动化矩阵

| 测试面 | 结果 | 裁决 |
| --- | --- | --- |
| CRM Java 单元/合同 | 151/151，0 failure，0 error | pass |
| CRM + PCBA QDP DSL/config | 9/9 | pass |
| Core row-version 相关 Java | 176/176；其中真实 PostgreSQL schema IT 3/3 | pass |
| Web 行动点合同 + Detail 回归 | 66/66 | pass |
| Web typecheck / targeted lint / format | typecheck 0；lint 0 error；format pass | pass |
| QDP HTTP 真栈 | 15/15 语义检查 | pass |
| 浏览器入口/详情/上传/发布/回读 | `R0003` 可见且 PostgreSQL/audit 回读一致 | pass |
| 浏览器 stale / no-permission / cross-tenant | 未执行 | untested |
| batch / deep-link / Agent Tool caller parity | 未执行 | untested |

HTTP 真栈覆盖：认证、文件完成状态、未资格化、普通 writer 拒绝、缺失版本、旧版本、缺失文件、目标不一致、第一次发布、21 个保护字段元数据、同意图重放、改意图冲突、第二次发布和审计行。

## 6. 北极星 AC 映射

| 北极星要求 | 当前状态 | 判断 |
| --- | --- | --- |
| AC-APP01-003 客户确认绑定 Requirement Version 与文件包 Hash | 未实现 | gap |
| AC-APP01-004 已发布 QDP 只能通过新 Revision 变更 | 已实现并通过 R0001 → R0002 → R0003 证明 | pass |
| AC-APP01-006 UI/API/批量/深链/Agent Tool 一致校验 | UI + API 已验证；其他 caller 未验证 | partial |
| AC-APP01-008 Loading/Empty/Read-only/No Permission/Conflict/Partial Success/External Failure/Stale | 本次只覆盖正常、部分 conflict/stale API | partial |
| GT-D04 内容、来源、假设、客户确认、Pack Set、下游责任完整 | 仅文件/资格/版本/审计基础 | partial |
| UX-D08 版本差异、客户确认、Pack Set、下游影响和发布 | 仅正式发布与历史 | partial |

## 7. 可证伪记录

### L1 row_version

新合同测试在实现前有 7 个红灯：动态表没有统一物理列、默认读取没有返回版本、若干 mutation 没有递增版本。实现后同一批测试、相关 176 个 Java 测试和真实 PostgreSQL IT 恢复为绿。

### Web action context

第一次真实浏览器上传到达后端，但 payload 中的 `crm_qdp_customer_request_id` 为空，后端按 fail-closed 返回 `Bad parameter`。新增同形测试后，它在修复前明确观察到 `crm_qdp_customer_request_id: undefined`；共享 Web 解析改为使用当前点击记录后，测试转绿。保持 QDP handler 不变重跑浏览器，成功创建 `R0003`。这证明绿灯不是靠放宽后端约束获得。

## 8. 持久化与视觉证据

- 机器可读覆盖清单：[qdp-acceptance-manifest.json](evidence/2026-08-06-qdp-release/qdp-acceptance-manifest.json)
- HTTP 真栈：[qdp-release-true-stack-20260806-141701.json](evidence/2026-08-06-qdp-release/qdp-release-true-stack-20260806-141701.json)
- 命令、红绿与 DB 回读：[qdp-test-run-evidence.md](evidence/2026-08-06-qdp-release/qdp-test-run-evidence.md)
- 客户需求列表：[qdp-customer-request-list.png](evidence/2026-08-06-qdp-release/qdp-customer-request-list.png)
- 正式动作表单：[qdp-release-form.png](evidence/2026-08-06-qdp-release/qdp-release-form.png)
- 浏览器发布后的 `R0003`：[qdp-browser-release-version-3.png](evidence/2026-08-06-qdp-release/qdp-browser-release-version-3.png)

视觉证据经过原图检查；`R0003`、revision `3`、`passed` 和 `qdp-browser-upload.csv` 均清晰可见。可执行语义断言与 PostgreSQL 回读高于截图裁决权，截图只承担视觉/交互证明。

## 9. 剩余产品与架构 gap

1. Requirement Version、客户确认、File Package Hash、假设/例外、Pack Set 和下游责任尚未建成，因此不能宣称完整 GT-D04。
2. QDP `Draft → Compiling → Validation Failed → Ready for Review → Released → Superseded` 生命周期、评审和 supersede 流程尚未实现；当前是 guarded direct release 基础路径。
3. UX-D08 的版本 diff、下游影响/blocker、批准例外、多文件 manifest 编辑、通知/订阅和导出尚未实现。
4. 浏览器 no-permission、cross-tenant、stale-row 409 与恶意/超大文件需要继续补齐；当前不能声称完整 AC-APP01-006/008。
5. revision number 与 client request identity 仍需要数据库唯一约束、长历史和并发双 releaser IT；当前分配仍是 `max + 1`。
6. Customer Request 路由字段和 PCBA 资格/当前-QDP 字段的 exact command-origin、sidecar CAS/aggregate lock、requalification workflow 仍需完成。
7. 文件 controller 的上传/下载/关系/删除能力仍需最小权限拆分；资格证据引用尚未接入独立 evidence authority。
8. Agent、batch、automation 与 deep-link caller 尚未完成稳定 replay identity、target pid 和 expected version 的等价验证。
9. host-first import 的物理模型/表已成功，但 import status ledger 仍错误显示 `previewing/missing`；`ab_plugin_import_log` 未写入，需要单独修复工具状态合同。

## 10. 下一步与并行切点

当前共同合同已经被一个 L3 真栈纵切片验证，但在 Core PR 与 Plugin PR 都提交、CI 绿并冻结接口前，不启动多个窗口去改同一合同。

并行切点条件：

1. Core row-version + Web action-context PR 绿，L1 合同冻结。
2. CRM/PCBA QDP PR 绿，APP-01 QDP 基础纵切片冻结。
3. 共享 writer、模型/命令 code、测试 fixture 与证据目录 ownership 写入并行任务合同。

达到后可拆为独立窗口：QDP Release Center 后续、BOM 标准化遗留产品梳理、报价工具遗留产品梳理、其余 L3 应用/共享测试与集成；各窗口不得各自发明 L1/L2 合同。
