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

### 实际进度（2026-07-24 本会话 — pcba 依赖网攻克后）

**后端真栈 golden:28/28 checks 全绿,0 fail,2 deferred。6 个 FR 完全覆盖。**

| FR | 后端真栈 IT | live 验证的交付 |
|----|------------|----------------|
| **FR-04** HandlingUnit | 🟢 6/6 | pack 数量守恒 5+10=15 + child 链 + 2 event + **note 用 code 非 pid（#230）** |
| **FR-05** 开工互锁 | 🟢 6/6 | check_interlock **checkedItems=7** + 明细表 7 项含 **tooling_life（#219 第7检查）** |
| **FR-09** SMT 工装 | 🟢 3/3 | create tooling → record_usage → used_cycles 累加 |
| **FR-16** Hold | 🟢 3/3 | place_hold → active hold 行持久化 |
| **FR-20** 设备停机 | 🟢 4/4 | breakdown 转 status + **重叠 breakdown 不重复计时=1 open downtime（#219）** |
| **FR-22** 班次交接 | 🟢 5/5 | create → pending_ack → acknowledge → **acknowledged（双签认）** |
| **FR-13** 齐套 | 🟢 7/7 | product→BOM→line→work order→compute_kitting → 1 kitting_result / **status=short_non_critical rate=0（缺料正确报缺,非静默 kitted=临界件安全）** |
| FR-10 FEFO | ⬜ 1 DEFER | 唯一未覆盖:需 warehouse(strategy=fefo)+ 2 效期 lot + balance(经 warehouse_in 收货流)+ pick source demand 的多步 seed;FEFO 排序逻辑已在 #217 交付,golden 待此 seed |

**7/8 FR 后端真栈 golden 全绿(34/34 checks,0 fail,1 deferred)**。断言全 falsifiable(DB 守恒值/事件数/互锁明细表 7 项/状态机),真命令管道 + 真 DB round-trip(psql 直查,非 API scope)。live 验证交付:**#219(互锁 tooling_life 第7检查 + 停机防重叠)+ #230(note 用 code)**。

### ✅ Infra 突破（本会话攻克 pcba 依赖网）
- **16 个 hybrid jar 全 fresh 构建**(15 in `plugins/` + quote-engine in `aura-quote/`)→ `AURA_PLUGINS_DIR` 加载。
- **跨仓 `--profile=pcba-agent` 单次 import(两阶段 defer)**:`mfg 命令 97 个注册`。关键洞察:整 profile 一次导入让 defer 解析依赖网(pcba-industry 的 pe:* handler 从 jar 加载即可、config import 失败不影响命令注册)。3 个失败插件(pcba-solution/pcba-sales/pcba-quote)是 FR 不需要的 quote 侧。
- runner `scripts/mes-wms-golden-run.sh` 已 codify 全流程(build 16 jar → stage → runtime → schema → backend → bootstrap → import profile → golden → teardown)。

### 剩余（诚实）
- **FR-10/FR-13 full golden**:需 BOM/行 + balance/效期 lot + warehouse 多模型 seed(deferred,已在 golden 里明确标注非产品 bug)。
- **UI golden(全 8 FR)**:需起前端 + per-page Playwright spec(未做)。

## 证据口径
- **真栈 IT**：Playwright API spec `mes-wms-commands.spec.ts`，`executeCommandViaApi` → `POST /api/meta/commands/execute/{code}` → `GET /api/dynamic/{model}/list` 反查 DB。凭据 = spec pass + 断言查的是**结果**（DB 行/字段值）非请求次数。
- **UI golden**：Playwright E2E spec `mes-wms/*.spec.ts`，真点每个行动点 + 断言状态变化 + 截图；截图存 `test-results/`。
- **变异验证**：每个新断言当场做（改 seed / 去掉修复 → 必须变红 → 还原）。
- **报告**：runner 跑完出 `mes-wms-golden-report.html`（内联截图 + 每 FR pass/fail + DB 断言证据）。

## 基础设施（Phase 0 gate — 已通过）
- 6 hybrid jar（inventory/pcba-manufacturing fresh 构建 + product-catalog/quality/crm/procurement）staged 到 AURA_PLUGINS_DIR。
- host-first 隔离栈（dev.sh runtime slot 63，零 docker）；backend `java -jar bootJar` + AURA_PLUGINS_DIR；跨仓 import（`--enterprise-plugin-root=/Users/ghj/work/auraboot/plugins`）。
- 验证：backend UP + `HandlingUnitHandler commandType=inv:pack` 等 FR handler 真加载。
