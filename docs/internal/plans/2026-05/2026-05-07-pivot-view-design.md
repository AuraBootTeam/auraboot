# Pivot 视图加固与 SavedView 集成设计(2026-05-07)

## 1. 背景与现状

> **2026-05-07 review 修正**:原 spec 假设 Pivot 是 greenfield,实查发现 OSS 已有完整 BI 模块(`platform/.../bi/`),但实现存在硬伤。本 spec 改为**"加固 + 升级"**,而非新建。

### 1.1 现有 OSS BI 模块清单

| 组件 | 路径 | 状态 |
|---|---|---|
| `PivotQueryService` | `platform/.../bi/service/PivotQueryService.java` | ✅ 接口已定义 |
| `PivotQueryServiceImpl` | `platform/.../bi/service/impl/PivotQueryServiceImpl.java`(248 行) | ⚠️ **SQL 注入风险** |
| `PivotQueryController` | `platform/.../bi/controller/PivotQueryController.java`(`/api/reports/pivot`) | ⚠️ 无 SavedView 集成 |
| `PivotQueryRequest/Response` DTO | `bi/dto/` | ⚠️ 单 valueField,无 bucket |
| 前端 `PivotTable.tsx` | `web-admin/app/ui/reports/PivotTable.tsx`(207 行) | ✅ 手写矩阵,可复用 |
| 前端 `ReportCrossTabBlock` | `core-designer/.../report-designer/blocks/`(231 行) | ✅ 已可用 |
| 周边设施 | `DashboardDataService` / `ReportSchedule` / `ReportDelivery` | ✅ 已有 |

### 1.2 现有实现的真问题

1. **🔴 SQL 注入风险**(`PivotQueryServiceImpl:51-67`):
   ```java
   StringBuilder whereClause = new StringBuilder("WHERE tenant_id = " + tenantId);
   whereClause.append(" AND ").append(fn).append(" ").append(op).append(" '")
           .append(sanitizeValue(String.valueOf(val))).append("'");
   ```
   仅靠 `sanitizeIdentifier`/`sanitizeValue` 字符串过滤,违反 CLAUDE.md "API 调用规范" / "数据库与数据" 红线。
2. **🟡 没有真小计/总计**:用 `GROUP BY` 而非 `GROUPING SETS`,行小计/列小计/总计**没在 SQL 出**(代码里实际没实现)。
3. **🟡 单 valueField**:不能同时算 sum + count,业务侧报表受限。
4. **🟡 无 time bucket**:无法"按月聚合 created_at",日常报表场景缺位。
5. **🟡 无 SavedView 集成**:独立 `/api/reports/pivot`,不能保存为视图、不能共享、不参与列表页 SavedView 切换器。
6. **🟡 行数保护错配**:`maxColumns=50` 是列保护,没有结果基数估算/原始行数保护。

## 2. 设计目标

1. **B0 紧急修注入**(独立 hotfix PR,不引入新功能)
2. **GROUPING SETS 真小计**:替换应用层重组为 SQL 层 grouping
3. **SavedView 集成**:`viewType=PIVOT` 加入 SavedView,与 LIST 同级
4. **time bucket + 多 value 字段**:补齐报表常用能力
5. **行基数估算保护**:替代当前 `maxColumns=50`,防大表打挂

## 3. 范围

### 3.1 In scope

| 能力 | 状态 | Phase |
|---|---|---|
| SQL 参数化(MyBatis `@SelectProvider`) | 🆕 加固 | B0 |
| `GROUPING SETS` 真行/列/总计 | 🆕 重写 | B1 |
| `viewType: PIVOT` SavedView 集成 | 🆕 新建 | B2 |
| 时间桶 `bucket: day/week/month/quarter/year` | 🆕 新建 | B3 |
| 多 valueField + 6 种 aggregator | ⚠️ 升级(原单 value) | B3 |
| 行基数估算保护 | 🆕 替换 | B4 |
| E2E + CSV 导出 | 🆕 新建 | B4 |
| 前端 PivotTable 增强(虚拟滚动 / sticky / 钻取) | ⚠️ 升级 | B5 |

### 3.2 Out of scope

- **OLAP cube / 物化预聚合**:在线聚合 + 行数保护够用
- **Pivot 嵌入 Dashboard**:已通过 `ReportCrossTabBlock` 实现,本 spec 不动
- **Pivot 用 Formula 字段做 value**:依赖独立 Formula spec,Q4 协同
- **AG Grid 等商业组件引入**:已有手写矩阵足够

## 4. 架构总览

```
┌─────────────────────────────────────────────┐
│ SavedView (viewType=PIVOT)  ← B2 新增类型    │
│   config = { rows, columns, values, ... }   │
└─────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────┐
│ POST /api/reports/pivot  ← 现有路径不变      │
│ 增量:支持 savedViewId 参数自动加载 config    │
└─────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────┐
│ PivotQueryServiceImpl (重写)                 │
│  1. B0: MyBatis @SelectProvider 参数化       │
│  2. B1: GROUPING SETS SQL 生成               │
│  3. B3: date_trunc / 多 value                │
│  4. B4: 基数估算保护                         │
└─────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────┐
│ PivotTable.tsx (增强)                        │
│  B5: 虚拟滚动 / sticky header / 钻取         │
└─────────────────────────────────────────────┘
```

## 5. DSL / API 详解

### 5.1 SavedView config(B2 后形态)

```jsonc
{
  "viewType": "PIVOT",
  "name": "按部门 × 月度的销售额",
  "modelCode": "order",
  "config": {
    "rows": [{ "field": "department_id", "label": "部门" }],
    "columns": [{ "field": "created_at", "bucket": "month", "label": "月份" }],
    "values": [
      { "field": "amount", "aggregator": "sum", "format": "currency" },
      { "field": "id", "aggregator": "count", "label": "订单数" }
    ],
    "filters": [{ "field": "status", "operator": "eq", "value": "completed" }],
    "options": { "rowSubtotals": true, "columnSubtotals": true, "grandTotal": true }
  }
}
```

### 5.2 API

```
POST /api/reports/pivot   (现有路径,B2 增量加 savedViewId 支持)
Body: { modelCode, savedViewId? } | { modelCode, config }
```

`savedViewId` 与 `config` 二选一,提供 savedViewId 则后端加载并执行其 config。

### 5.3 Zod / 后端校验红线

- `rows.length ≤ 3`,`columns.length ≤ 2`
- `bucket` 仅对 DATE/DATETIME 字段允许,enum 5 个
- `aggregator` enum 6 个;`sum/avg/min/max` 仅 INTEGER/DECIMAL 允许
- `values.length ≥ 1`
- 任何非白名单 `field/bucket/aggregator` → `IllegalArgumentException`,**不做 fallback**

## 6. 后端实现

### 6.1 B0 紧急 hotfix(独立 PR)

**目标**:消除 SQL 注入风险,**不引入新功能**。

| 改动 | 文件 |
|---|---|
| 新增 `PivotSqlBuilder` 用 MyBatis `SQL` builder + `@Param` 化 | `bi/service/impl/PivotSqlBuilder.java`(新增) |
| `PivotQueryServiceImpl` 改用 `PivotSqlBuilder`,所有用户输入走 `#{}` | 现有文件 |
| 新增 `PivotQueryMapper` 用 `@SelectProvider` | `bi/dao/mapper/PivotQueryMapper.java`(新增) |
| identifier(表名/列名)走严格白名单 — 必须存在于 model 元数据 | `PivotSqlBuilder` 内 |
| 集成测试:SQL 注入 payload 全部抛 `IllegalArgumentException` | `PivotQueryServiceSecurityIntegrationTest`(新增) |

**关键**:identifier 不能参数化,但**必须**对照 model 元数据(`ModelDefinitionService`)校验后才能拼;**不再**靠正则 `sanitizeIdentifier`。

### 6.2 B1 GROUPING SETS 重写

```sql
-- 示例:1 row × 1 col × 2 value
SELECT
  department_id,
  date_trunc(#{colBucket}, created_at) AS col_dim,
  SUM(amount) AS val_0,
  COUNT(*) AS val_1,
  GROUPING(department_id, date_trunc(#{colBucket}, created_at)) AS grp_lvl
FROM mt_order
WHERE tenant_id = #{tenantId}
  AND status = #{f0}
  AND (deleted_flag = FALSE OR deleted_flag IS NULL)
GROUP BY GROUPING SETS (
  (department_id, date_trunc(#{colBucket}, created_at)),
  (department_id),
  (date_trunc(#{colBucket}, created_at)),
  ()
)
ORDER BY grp_lvl, department_id, col_dim
```

`grp_lvl` 二进制位 → 前端区分主格(`00`)、行小计(`01`)、列小计(`10`)、总计(`11`)。

### 6.3 B2 SavedView 集成

| 改动 | 文件 |
|---|---|
| `SavedViewType` enum 加 `PIVOT` | `platform/.../meta/entity/SavedView.java` |
| `SavedViewService.execute(viewId)` 路由到 `PivotQueryService` | 现有文件 |
| `PivotQueryController` 加 `savedViewId` 参数支持 | 现有文件 |
| 前端列表页 SavedView 切换器加 PIVOT 选项 | `web-admin/app/smart/components/savedView/` |

### 6.4 B3 time bucket + 多 value

DTO 升级:`PivotQueryRequest.colDimensions: List<ColDim>`(原 `List<String>`,带 bucket),`valueField → values: List<ValueDef>`。

**向后兼容**:dev-stage 红线 = breaking changes preferred,**不做** alias / forwarding stub,直接改 DTO,前端同步改。

### 6.5 B4 行基数估算保护

替换当前 `maxColumns=50` 检查:

```java
// 预 sample 估算
long rowCardinality = estimateDistinct(rowDimField, modelCode, filters);
long colCardinality = estimateDistinct(colDimField, modelCode, filters);
if (rowCardinality * colCardinality > 10_000) {
    throw new PivotResultOverflowException(...);
}
```

`estimateDistinct` 用 `pg_stats.n_distinct` 或 `COUNT(DISTINCT ...) WHERE` LIMIT sample,2 选 1 看精度需求(实测决定)。

### 6.6 SQL 注入防御白名单

| 输入 | 防御 |
|---|---|
| `modelCode → tableName` | 必须存在于 `ModelDefinitionService.getModel()`,否则抛 |
| `field` (row/col/value) | 必须存在于该 model 的 fields 列表 |
| `bucket` | enum 严格匹配 `day/week/month/quarter/year` |
| `aggregator` | enum 严格匹配 6 个 |
| `filter.value` | MyBatis `#{}` 参数化 |
| `tenantId` | `MetaContext.getCurrentTenantId()`,**禁止**接受请求体传入 |

## 7. 前端实现

| Phase | 改动 | 文件 |
|---|---|---|
| B2 | SavedView 切换器加 PIVOT 选项 | `web-admin/app/smart/components/savedView/SavedViewSelector.tsx` |
| B3 | Pivot 配置面板(行/列/值拖拽,Schema-driven) | `web-admin/app/plugins/core-designer/.../pivot/PivotConfigPanel.tsx`(新增) |
| B5 | `PivotTable.tsx` 加虚拟滚动(>500 行)+ sticky header + 单元格钻取 | 现有 |
| B5 | CSV 导出 | `PivotTable.tsx` 内 `onExport` 实现 |

**Schema-driven 强制**:配置面板必须 `PropertySchema[] + SchemaBlockConfigPanel`(CLAUDE.md 红线)。

## 8. 关键决策(已自主拍板)

1. **沿用 `/api/reports/pivot` 路径** —— 不改 URL,降低前端联调成本
2. **不做 OLAP cube** —— 在线 + 基数保护够用
3. **time bucket 5 个固定 enum** —— 不开自定义格式
4. **aggregator 6 个固定 enum** —— 不开 SpEL,防注入 + 性能可控
5. **dev-stage breaking changes**:直接改 `PivotQueryRequest` DTO,无 alias / forwarding
6. **identifier 走 model 元数据白名单** —— 不再依赖正则 `sanitizeIdentifier`
7. **结果基数 ≤ 10000** —— 超过抛 `PivotResultOverflowException`,无 sampling fallback

## 9. 测试策略

### 9.1 后端

`PivotQueryServiceIntegrationTest`(BaseIntegrationTest,真 PG):
- B0:SQL 注入 payload(`'; DROP TABLE`、`' OR 1=1` 等 8 类)全部抛异常
- B1:6 种聚合各 1 case + 行/列/总小计断言
- B1:租户隔离(跨租户数据不出现)
- B1:`deleted_flag` 软删数据不出现(CLAUDE.md 红线)
- B3:bucket 5 种各 1 case,DATE/DATETIME 类型校验
- B3:多 value 同时 `sum + count + avg`
- B4:超过 10000 基数抛 `PivotResultOverflowException`
- B4:非白名单 field/bucket/aggregator 抛 `IllegalArgumentException`

### 9.2 E2E

`web-admin/tests/e2e/specs/pivot-saved-view-lifecycle.spec.ts`:
- 从侧边栏菜单进入 → 创建 PIVOT SavedView
- 拖拽行 / 列 / 值字段
- 切换 bucket(month → quarter)
- 渲染断言:具体单元格数值(用 `test-fixtures` 种子数据)
- 行小计 / 列小计 / 总计可见 + 数值断言
- 导出 CSV → 文件内容断言

### 9.3 性能基线

| 数据量 | 期望 |
|---|---|
| 10k 行 | < 800ms |
| 100k 行 | < 3s |
| 基数 > 10000 | 拒绝 |

## 10. Phase 拆分(每 Phase = 1 PR)

| Phase | 内容 | 周 | 依赖 | 风险 |
|---|---|---|---|---|
| **B0** ✅ | SQL 注入 hotfix(MetaModelService 白名单 + `#{params.*}` 参数化) | DONE 2026-05-07 | — | 已 ship |
| **B1** | GROUPING SETS 重写 + 真小计/总计 | W1-2 | B0 ✅ | DTO 兼容(dev-stage breaking) |
| **B2** | SavedView `viewType=PIVOT` 集成 | W3 | B1 | 现有 SavedView 类型扩展 |
| **B3** | time bucket + 多 valueField | W4 | B2 | DTO 第二轮 breaking |
| **B4** | 行基数估算保护 + E2E + CSV 导出 | W5 | B3 | pg_stats 精度需实测 |
| **B5** | 前端 PivotTable 增强(虚拟滚动 / sticky / 钻取) | W6 | B4 | 独立可异步 |

### B0 ship 记录(2026-05-07)

- **Commit**: `215c30d3 fix(bi): close SQL injection in PivotQueryServiceImpl (Pivot B0 hotfix)` 已合 main
- **改动**: `PivotQueryServiceImpl` 重写 SQL 构造,改走 `DynamicDataMapper.selectByQuery` 标准参数化路径;identifier 经 `MetaModelService` 白名单解析(modelCode → tableName,field code → columnName);values 经 `#{params.*}` JDBC PreparedStatement 占位
- **顺带修复 pre-existing bug**: 之前直接把 `modelCode` 当物理表名使用(忽略 `ModelDefinition.tableName`)
- **顺带修复**: soft-delete 模型现在自动加 `(deleted_flag = FALSE OR deleted_flag IS NULL)` predicate,select + count 双覆盖
- **测试**: 11/11 unit tests PASS,含 `executePivot_parameterisesUserSuppliedValues` 合约测试 —— 注入 `'; DROP TABLE ns_content; --` 作为 filter value,断言 SQL 中无该 payload + params map 真承载之
- **未做(B1+ 范围)**: 真 GROUPING SETS / 多 valueField / time bucket / SavedView 集成 / 行基数估算

**总计 6 周**(含 B0 hotfix 1-2 天)。

### Phase 1 验收 Go/No-Go

B0 + B1 ship 后强制 review,任一不达标则 reset:
- B0:8 类 SQL 注入 payload 全拒,无现有测试退化
- B1:实测 10k 行查询 < 800ms,真小计/总计与 spec 一致

### 业务验收场景

**场景:销售看板 OKR**
1. 创建 PIVOT SavedView "Q2 部门月度业绩"
2. rows=`department_id`,columns=`{field:"created_at", bucket:"month"}`,values=`[{sum amount}, {count *}]`
3. filters=`status=completed`,过滤 Q2 时间窗
4. 渲染矩阵 + 行/列/总小计 → 数值与 SQL 直查一致
5. 导出 CSV 给老板

B0+B1 ship 即可落地此场景。

## 11. 风险与缓解

| 风险 | 缓解 |
|---|---|
| B0 hotfix 改动太大破回归 | 紧抠 surface:identifier 白名单 + value 参数化,不动其他逻辑;集成测试覆盖现有 case |
| B1 GROUPING SETS 在动态表上跑出问题 | 实测多 model;若 `dynamicQueryMapper` 有限制,降级用 MyBatis `@SelectProvider` 直接写 |
| B2 SavedView 类型扩展破现有 LIST 视图 | enum 加值非破坏;Mapper 加 case 走旁路 |
| B3 DTO 第二轮 breaking | 一次性改完,不分两次;CHANGELOG 记录 |
| B4 pg_stats 精度差 | 二选一:精确 `COUNT(DISTINCT) WHERE LIMIT 10000` 兜底 |
| 前端 PivotTable 增强引发现有 ReportCrossTab 行为变化 | B5 加 `mode: 'compact' \| 'enhanced'` prop,`ReportCrossTab` 用 compact |

## 12. 与现有规范对齐

- ✅ MyBatis `#{}` 参数化(CLAUDE.md "数据与 API" 红线)
- ✅ `deleted_flag` 显式处理(CLAUDE.md 红线)
- ✅ TenantContext 不接受请求体传入
- ✅ Schema-driven 配置面板
- ✅ Dev stage:无 fallback / 无 sampling 退化 / 直接改 DTO
- ✅ E2E 必须从菜单导航(不 page.goto)+ 14 维度断言

## 13. 与 AI 一等公民设计的协同

- **AI 自然语言 → Pivot SavedView**:AI Block A6 增值,demo 价值高
- **Pivot 结果作为 AI Block 上下文**:Phase A3 后增值
- **Pivot value 用 Formula 字段**:依赖独立 Formula spec,本 spec 不阻塞

## 14. 后续(Out of scope but tracked)

- **B6**:Pivot → Chart 一键转换(选中行/列直接生成 SmartChart)
- **B7**:OLAP cube / 物化预聚合(>100k 行场景超 5 个时启动)
- **B8**:Pivot 用 Formula 字段做 value(依赖 Formula spec)
- **B9**:Pivot 跨字段过滤器(field-A 和 field-B 联合过滤)
