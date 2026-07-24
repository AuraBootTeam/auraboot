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

### 实际进度（2026-07-24 本会话）

| FR | 后端真栈 IT | 备注 |
|----|------------|------|
| **FR-04** HandlingUnit | 🟡 **6/6 绿**（真命令管道 + 真 DB round-trip）| create HU→pack→DB 断言:parent qty 5+10=15 / child 链 parent / 2 event 行 / **note 用 code 非 pid（#230 live 验证）**。断言全 falsifiable（DB 值/守恒/事件数）|
| FR-09/FR-20 | ⬜ spec 已写，未跑 | 卡 pcba-manufacturing import（见下）|
| FR-05/16/22 | ⬜ | 卡 pcba-manufacturing import |
| FR-10/FR-13 | ⬜ | inventory 已导,但前置需效期 lot/balance（FR-10 FEFO）、work order（FR-13 齐套）多模型 seed,未做 |

### 🔴 已知 infra 阻塞（诚实记录 — 剩余为多会话工作）

**pcba-manufacturing 的 5 个 FR（FR-05/09/16/20/22）被依赖网阻塞**:
`pcba-manufacturing → pcba-industry(缺 dict eng_route_step_type)→ pcba-sales(hybrid handler pe:convert_opp_to_rfq)→ pcba-solution/pcba-crm/quote-core/bom-standardization → ...`
= 需**完整 pcba-agent hybrid 插件集**(30+ 插件,跨 `plugins/` + `aura-quote/` 两仓,quote-core 还有独立 S-EXT-HANDLER)。这是 pcba-agent profile 存在的原因,是一项大 infra 工作。

**已验证可行的 infra 基建**(本会话真跑通):fresh hybrid jar(inventory/pcba-manufacturing/product-catalog/quality/finance/crm/procurement/pcba-sales)→ `AURA_PLUGINS_DIR` 加载 → 跨仓两阶段 import(base 集 + finance/procurement/sales 链 OK)。runner `scripts/mes-wms-golden-run.sh` 已 codify;要跑全 8 FR 需把 `HYBRID_JARS`/`IMPORT_CHAIN` 扩到完整 pcba-agent 集。

**结论**:本会话交付了 **可复用 infra 基建 + FR-04 真栈 golden(live 验证 #230)+ harness + runner + 矩阵**;完成全 8 FR × (IT+UI) + 报告是**多会话工作**(pcba 依赖网 + FR-10/13 复杂 seed + UI golden 各自可观)。

## 证据口径
- **真栈 IT**：Playwright API spec `mes-wms-commands.spec.ts`，`executeCommandViaApi` → `POST /api/meta/commands/execute/{code}` → `GET /api/dynamic/{model}/list` 反查 DB。凭据 = spec pass + 断言查的是**结果**（DB 行/字段值）非请求次数。
- **UI golden**：Playwright E2E spec `mes-wms/*.spec.ts`，真点每个行动点 + 断言状态变化 + 截图；截图存 `test-results/`。
- **变异验证**：每个新断言当场做（改 seed / 去掉修复 → 必须变红 → 还原）。
- **报告**：runner 跑完出 `mes-wms-golden-report.html`（内联截图 + 每 FR pass/fail + DB 断言证据）。

## 基础设施（Phase 0 gate — 已通过）
- 6 hybrid jar（inventory/pcba-manufacturing fresh 构建 + product-catalog/quality/crm/procurement）staged 到 AURA_PLUGINS_DIR。
- host-first 隔离栈（dev.sh runtime slot 63，零 docker）；backend `java -jar bootJar` + AURA_PLUGINS_DIR；跨仓 import（`--enterprise-plugin-root=/Users/ghj/work/auraboot/plugins`）。
- 验证：backend UP + `HandlingUnitHandler commandType=inv:pack` 等 FR handler 真加载。
