# MES/WMS 交付 FR — 测试质量矩阵(单一权威）

> 覆盖金轮交付的 8 个 FR 的**真栈 IT**(命令管道 API + DB round-trip）+ **注册 UI golden**（真浏览器驱动行动点）。
> 由自包含 runner `scripts/mes-wms-golden-run.sh` 一键跑（起栈→import→seed→backend IT→UI golden→报告→拆栈）。
> 层次定义见 `auraboot-enterprise/docs/standards/core/testing-layering.md`：**IT = 真栈 + 真 DB**；golden = 固定集 + 见过红 + 注册可复跑。

## 覆盖矩阵

| FR | 能力 | 主命令 | 后端真栈 IT（API+DB） | UI golden | 状态 |
|----|------|--------|----------------------|-----------|------|
| **FR-04** | HandlingUnit pack/split/merge | `inv:pack` `inv:split_hu` `inv:merge_hu` | 执行 pack→查 `inv_handling_unit` 数量守恒 + `inv_handling_unit_event` 事件行；note 用 code 非 pid | HU 列表/详情页真点 pack + 事件历史断言 | ⬜ |
| **FR-10** | FEFO/FIFO 拣货分配 | `inv:create_pick_order` | 建效期不同的库存→create_pick_order→断言分配按 FEFO（近效期先）+ 生产窗口排除 | 拣货页真点 + 分配结果断言 | ⬜ |
| **FR-13** | 齐套分析 | `inv:compute_kitting` | 建工单+缺料→compute_kitting→断言 full/critical 齐套结果落 `inv_kitting_result` | 齐套页真点 + 结果断言 | ⬜ |
| **FR-05** | 开工互锁（7 检查） | `mfg_work_order_operation_pcba_execution:check_interlock` | 建 WO 操作（违反 msl/env/tooling）→check→断言 blocked + 具体 reason | 互锁卡真渲染 7 检查 + 截图 | ⬜ |
| **FR-09** | SMT 工装寿命 | `mfg_tooling_pcba_asset:record_usage` | 建工装→record_usage×N→断言使用计数累加 + 到寿告警 | 工装页真点 record_usage | ⬜ |
| **FR-16** | Hold 与解除 | `mfg_hold:place_hold` `mfg_hold:release_hold` | 建目标→place_hold→断言拦开工 + 传播 + 部分解除 | Hold 工作台真点 place/release | ⬜ |
| **FR-20** | 设备停机（防重叠重复计时） | `mfg_equipment_pcba_asset:breakdown` `:end_downtime` | breakdown→再 breakdown（重叠）→断言不重复计时（open 检查）；end_downtime→断言时长 | 停机工作台真点 | ⬜ |
| **FR-22** | 班次交接 + 双签认 | `mfg_shift_handover:create_handover` `:acknowledge_handover` | create→断言快照 + carry-forward；acknowledge→断言签认；标题/引用解析 | 交接工作台真点 create+ack + 抽屉断言 | ⬜ |

状态图例：⬜ 未做 · 🟡 IT 绿/UI 待 · 🟢 IT+UI 双绿（变异验证过）· 🔴 发现产品 bug

## 证据口径
- **真栈 IT**：Playwright API spec `mes-wms-commands.spec.ts`，`executeCommandViaApi` → `POST /api/meta/commands/execute/{code}` → `GET /api/dynamic/{model}/list` 反查 DB。凭据 = spec pass + 断言查的是**结果**（DB 行/字段值）非请求次数。
- **UI golden**：Playwright E2E spec `mes-wms/*.spec.ts`，真点每个行动点 + 断言状态变化 + 截图；截图存 `test-results/`。
- **变异验证**：每个新断言当场做（改 seed / 去掉修复 → 必须变红 → 还原）。
- **报告**：runner 跑完出 `mes-wms-golden-report.html`（内联截图 + 每 FR pass/fail + DB 断言证据）。

## 基础设施（Phase 0 gate — 已通过）
- 6 hybrid jar（inventory/pcba-manufacturing fresh 构建 + product-catalog/quality/crm/procurement）staged 到 AURA_PLUGINS_DIR。
- host-first 隔离栈（dev.sh runtime slot 63，零 docker）；backend `java -jar bootJar` + AURA_PLUGINS_DIR；跨仓 import（`--enterprise-plugin-root=/Users/ghj/work/auraboot/plugins`）。
- 验证：backend UP + `HandlingUnitHandler commandType=inv:pack` 等 FR handler 真加载。
