# MES/WMS 测试质量矩阵（单一权威）

> evidence date：2026-07-26
> allowed claim：**本 handover 授权的 L1–L4 定向闭环通过**。这不是 MES 28 + WMS 32 FR 的全量完成声明，也不是 canonical OSS full E2E 通过声明。

## 范围与当前证据

- 需求真源：AMOS `AMOS_MES_WMS_终局产品需求与UX规格_v1.1.docx`。
- 当前可执行 SOT：AMOS `10-mes-endgame-delivery.md`、`11-mes-fr-crosswalk.md`、`20-wms-endgame-delivery.md`。
- 证据索引：`mes-wms-acceptance-report.html`，SHA-256
  `5b66afb4f42dd07f2e7d52effcf1566308d339da9e929c061a1f78518bcddeb5`。
- 最终 slot 64：fresh 16-jar stage、24 个 focused packages、102 个 mfg commands；
  backend 34/34、FR-10 13/13、baseline 22/22、deep 34/34。
- 最终 HTML：11 个分组行均为绿色，0 黄色、0 红色、0 未测；每行的 exit/screenshot evidence 均已解析。
- runtime artifact manifest SHA-256：
  `cfc1b88b7f34eee5652faa85a92d635e8df13604fcabf35a24ab6952a4adb388`；
  该 manifest 的运行时临时路径会随 task cleanup 删除，hash、源码根、HEAD、jar/config hash 已固化在本节。

## Feature / action coverage matrix

测试身份统一按 **被测面 / 依赖 / 裁决权 / 驱动入口** 四轴描述；下表的 L1–L4 是 handover 工作项名称，不是测试层编号。

| 行动点 | 四轴 | browser evidence | backend / artifact evidence | 状态与边界 |
|---|---|---|---|---|
| 已交付 MES/WMS 命令基础集 | api / real-stack / on-demand / it | N/A | `mes-wms-backend-golden.mjs` 34/34 + DB round-trip | PASS；FR-10 由独立 suite 裁决 |
| FR-10 FEFO/FIFO | api / real-stack / on-demand / it | N/A | `fr10-fefo-golden.mjs` 13/13 | PASS；真实入库 create 路径，无 available-qty update workaround |
| FR-08 错料/过期/生产窗口门禁 | api / real-stack / on-demand / it | N/A | baseline 22/22 + deep 34/34 中的 expiry/window/safe-lot 断言 | PASS；MSL/烘烤寿命和完整替代料审批仍不在本轮 |
| FR-12 SN 唯一与重复扫描幂等 | journey / real-stack / on-demand / it | N/A | deep suite：跨成品拒绝、同组合不新增重复 row | PASS（API-driven） |
| FR-12 relabel | journey / real-stack / on-demand / it | did not run | deep suite：旧标签终态、新标签 active、predecessor/root 不变、旧标签复扫拒绝 | PASS（API-driven）；未声明 UI command 可达 |
| FR-12 reprint | journey / real-stack / on-demand / it | did not run | deep suite：旧打印标签终态、successor 保留 predecessor/root | PASS（API-driven）；未声明 UI command 可达 |
| FR-12 recover_identity | journey / real-stack / on-demand / it | did not run | 证据、requester、server-owned confirmer、confidence 回查；自确认拒绝且不落 successor | PASS（API-driven）；未做非 admin 角色矩阵 |
| FR-12 invalidate | journey / real-stack / on-demand / it | did not run | deep suite：终态、原因、时间戳回查 | PASS（API-driven）；未声明 UI command 可达 |
| FR-14 retest closure | journey / real-stack / on-demand / it | N/A | fail→defect→retest→verified，链回原失败 | PASS |
| FR-14 replace_component | journey / real-stack / on-demand / it | did not run | original retained/replaced；replacement serial/material/lot/predecessor/reason 回查 | PASS（API-driven）；未声明 UI command 可达 |
| MES 存量页面渲染 | ui / real-stack / on-demand / browser | `mes-wms-yellow-fr-golden.mjs` 33/33 + screenshots | live list/data/console signals | PASS；只证明已列页面渲染与数据可见 |
| FR-07 Start 互锁拒绝 | journey / real-stack / on-demand / browser | 真菜单/行行动、拒绝反馈、列表保留 | 后端 400 + 状态未非法转移 | PASS |
| FR-22 交接与签认 | journey / real-stack / on-demand / browser | 真输入/点击、表单、状态与接班人回显 | DB pending_ack→acknowledged | PASS |
| 4 类曾断工作台行动 | journey / real-stack / on-demand / browser | 生成交接单、解决异常、下达 Hold、解除 Hold 的表单与截图 | 每个行动的 DB count/status 变化 | PASS |
| L3 列表搜索与详情 | journey / real-stack / on-demand / browser | 菜单进入；搜索前 N>1、唯一值后 1 行；View 跳转精确实体 | 真 list/detail HTTP response | PASS，3/3 |
| L1 #1501 分辨门 | journey / real-stack / on-demand / browser | mutant 整页“加载失败”；fixed 列表与目标行保留 | source sentinel + 同一真后端行动 400；两侧各 2/2 | PASS，mutation 在已运行多轮的 slot 64 DB 上执行 |

约 46 个 reserve FR 继续由 AMOS crosswalk 标为 `WONT_DO for this handover`。它们未实现、未测试、不进入 HTML 分母，也不能从“11 个绿色分组行”推导为已交付。

## 可信度审计

- 新增的 `list-interaction-golden.mjs` 和 `list-action-error-mutation-golden.mjs`：
  `page.request=0`、`waitForTimeout=0`、`skip/fixme=0`、`retries=0`；列表入口经侧边栏菜单，`page.goto` 只用于登录首页。
- L1 可证伪记录：
  - mutation：仅恢复 `ListPageContent` 行行动错误时的 `setError(err.message)`；
  - observed red：mutant 同一 400 后整页失败、列表行归零；
  - DB state：slot 64 已完成多轮运行，非 fresh-only 假红；
  - restored green：fixed 同一 400 后列表与目标行保留；
  - green class：真绿。
- `report-gen` self-test 通过；manifest claimed-pass 若缺 evidence 会强制变红。
- 新增/修改配置的 `page-golden-audit --contract-only` 为 0 error / 0 warning，fresh 平台 import 为 24/24 packages、102 mfg commands。
- strict 全插件 page audit 在 feature 与 canonical main 上均为同一组 12 个历史错误；本轮没有把这些历史页面债务包装成通过，因此 FR-12 生命周期命令明确标为 API-driven、UI did not run。
- 报告纳入的历史浏览器脚本仍有 37 处 `waitForTimeout`，但无 `skip/fixme` 或 retries。本轮不据此声称 canonical full golden UI；新建的两个分辨门不含固定等待。
- 后端模块未配置有效 JaCoCo/coverage task，`integration_coverage=coverage_not_measured`；245/245 是测试通过数，不换算为 80% 覆盖率。

## Final Evidence Pack

```text
acceptance_report: web-admin/tests/mes-wms/mes-wms-acceptance-report.html (sha256 5b66afb4...)
claim_level: targeted handover completion claim
current_sot: AMOS 10/11/20 + this test-quality matrix
business_scope: owner-authorized MES/WMS handover L1-L4; ~46 reserve FR explicitly excluded/WONT_DO
integration_tests: plugin unit 245/245; OSS platform targeted 19/19; slot64 backend 34/34 + FR10 13/13 + baseline 22/22 + deep 34/34
integration_coverage: coverage_not_measured
e2e_specs: render 33/33; FR07 5/5; FR22 8/8; action points 9/9; UI 5/5; list 3/3; mutation mutant 2/2 + fixed 2/2
feature_action_matrix: table above; no silent rows, API-driven and did-not-run UI paths explicit
browser_evidence: HTML inline screenshots + exact list/detail and mutant/fixed evidence
backend_evidence: fresh jar/PF4J/import/command pipeline/real PostgreSQL round-trip
artifact_evidence: HTML sha256 5b66afb4...; runtime manifest sha256 cfc1b88b...; pcba jar/config hashes recorded
permission_negative: self-confirm recovery rejected; FR07 interlock rejected; non-admin RBAC matrix did_not_run
visual_feedback: screenshots inspected for list retention/blanking, exact detail, FR22 labels/layout, and action forms/results
skip_fixme_threshold_retry_audit: skip=0, fixme=0, retries=0; new discriminators waitForTimeout=0; inherited report scripts waitForTimeout=37
did_not_run: canonical OSS full E2E; non-admin RBAC; UI reachability for the five new FR12/14 lifecycle commands; release-image/docker parity
remaining_blockers: none for the authorized L1-L4 targeted closure; the did-not-run items prevent a broader full-product/golden-UI claim
allowed_claim: handover L1-L4 targeted closure passed; not all 60 FR, not canonical full E2E, not full golden UI
```

## 复跑

```bash
AURA_PLUGINS_PROJECT_ROOT=/path/to/plugins scripts/mes-wms-golden-run.sh --slot 64 --keep --no-ui
BASE=http://127.0.0.1:5164 \
FIXED_BASE=http://127.0.0.1:5164 \
MUTANT_BASE=http://127.0.0.1:5264 \
BACKEND_URL=http://127.0.0.1:6464 \
PG_DB=auraboot_64 \
web-admin/tests/mes-wms/report-run.sh
```
