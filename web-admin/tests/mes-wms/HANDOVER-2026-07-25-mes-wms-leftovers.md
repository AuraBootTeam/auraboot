# HANDOVER — MES/WMS 交付遗留 (2026-07-25)

本会话把 MES/WMS 交付的功能大图做透:后端命令层 11 FR + 3 深层 gap 真栈验证、5 个曾断的 UI 行动点全修 + 行动驱动 golden、沿途抓修 9 个真 bug/断点。**所有代码已 merged 到各仓 main**(见下方 PR 清单)。本文件记录**剩余遗留**及关闭方式,供后续会话接手。

## 已完成(已 merged,非遗留,仅索引)

| 项 | PR |
|----|-----|
| 后端命令层 11 FR 真栈 golden + 3 个真 bug(dslPersistence / JSONB instanceof / available_qty) | plugins #240/#243/#244 |
| 5 个断掉的 UI 行动点(签认/生成交接单/解决异常/下达Hold/解除Hold)补 inputFields + 行动驱动 golden | plugins #246 · auraboot #1517/#1520 |
| 深层 spec gap:FR-08 批次效期阻断 / FR-12 组件 SN 唯一 / FR-14 retest→原失败闭环 | plugins #248 · auraboot #1527 |
| 诚实大图报告 `FEATURE-BIGPICTURE-REPORT.md`(三层证据强度)+ 纠正旧「21 全绿」误导 | auraboot #1521/#1528 |

golden 清单(auraboot main `web-admin/tests/mes-wms/`):`mes-wms-backend-golden.mjs`、`fr10-fefo-golden.mjs`、`fr08-12-14-golden.mjs`、`fr08-12-14-deep-golden.mjs`、`ui/mes-wms-yellow-fr-golden.mjs`、`ui/fr07-action-golden.mjs`、`ui/fr22-action-golden.mjs`、`ui/mes-action-points-golden.mjs`、`ui/mes-wms-ui-golden.mjs`。

---

## 剩余遗留(按 ROI 排)

### L1. #1501 缺 mutation-discriminating golden(低 ROI / disproportionate)
- **状态**:修复已交付 + code-reviewed + 修复态在部署前端已验(`fr07-action-golden` 断言拒绝的行动点后列表保留、无整页崩「加载失败」)。
- **缺**:没有"未修复必变红"的变异分辨门。
- **为何难**:需**隔离的未修复前端** + 一个走 `ListPageContent` **页级 onError** 的页/动作;而交付的 MES workbench 页都走 `BlockRenderer`(非 ListPageContent),该触发路径不在交付页内。
- **关闭方式**:① 从 worktree 起第二个 Vite(把 ListPageContent `onError` 改回 `setError`)接同一 backend、spare 端口;② 找/建一个 `/p/<model>` 默认列表页 + 一个会被后端拒的**页级/toolbar** 动作;③ 断言未修复前端整页崩(RED)→ 修复前端列表保留(GREEN)。ROI 低(9 行已 review 的通用修复),按需再做。

### L2. 深层 spec 的"下一层"未做(真功能开发,按业务优先级排期)
本轮做了每个深层 gap 的**核心**,更深的变体仍未做:
- **FR-08**:做了"已过期料阻断";**"近效期但未过期、且会在工单剩余生产窗口内到期"**的更严阻断未做(需读工单 due/window)。MSL/烘烤寿命阻断也未做(见 crosswalk)。
- **FR-12**:做了组件 SN 唯一性;**relabel / reprint / invalidate / 身份恢复**未做。
- **FR-14**:做了 fail→defect→rework→retest 闭环;**As-built 更换字段(哪个元件被换)**未做。
- 关闭方式:各自加字段/handler + 真栈 golden(模式同 `fr08-12-14-deep-golden.mjs`)。

### L3. UI 列表页交互未行动驱动验证(低边际价值)
- 7 个列表页 render + 数据已验、sweep(含 isActionColumn)确认**无 click→400 断按钮**;但搜索/筛选/详情跳转未单独行动驱动验证。
- 这些是**平台通用功能**(非 MES 交付特性),边际价值低。按需加一个列表页交互 golden(打字→行数变、点详情→跳转)。

### L4. 测试卫生(可选清理)
- **FR-10 golden** 仍用 update API 归一化 `inv_bal_available_qty`(#244 修 bug 后已冗余,可删该 workaround)。
- **slot-63 DB** 被反复 golden 跑脏(大量 pending 交接单/hold/异常);测试栈,重置即可。
- 本地各仓 main 检出未 pull(origin/main 是权威);后续会话先 `git pull` 同步。

### L5. 储备基线(非遗留,owner 已定,列此为语境)
- 原始 spec 60 FR 里 **~46 未开发**(储备验收基线,owner 定不按它开工):MES-06 SOP、MES-10 WIP 路线、MES-11/24/25/26、WMS-17/18/23/25/28 等结构性缺口。逐条状态见 `amos/docs/product-docs/mes-wms/11-mes-fr-crosswalk.md`。

---

## 接手须知
- **golden 跑法**:host-first 隔离栈(见 `scripts/mes-wms-golden-run.sh`);action golden 需 live Vite(5163)+ backend(6463)+ 全 pcba-agent 已 import。
- **行动点 config 坑**:workbench 的 command 行动点必须在 action 配置里声明 `inputFields`,否则点了发空 payload → 400;select 选项走 `field.dataSource`(`{type:static,data}` 或 `{type:api,endpoint}`),**不读内联 `options`**(promptInputForm 只读 dataSource)。
- **DATE 列**:读回是 `java.sql.Date`,用 `toLocalDate()`(`toInstant()` 契约抛异常)。
