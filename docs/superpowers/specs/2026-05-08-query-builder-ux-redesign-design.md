# Query Builder UX 重设计

- 日期: 2026-05-08
- 范围: `web-admin/app/plugins/core-designer/components/query-builder/**`
- 路由: `/query-builder`
- 关联 E2E: `web-admin/tests/e2e/query-builder/query-builder-basic.spec.ts`
- 视觉基准: `qb-mockup-a.png`(Studio 密集仪表盘) + 吸收 `qb-mockup-b.png` 的 KPI 头与字段类型展示

## 1. 动机

当前 `/query-builder` 页面槽点:

1. 空状态荒凉,右侧 60% 屏幕只显示 "Results" 标题,无引导
2. 左侧 5 段配置(Model/Fields/Filter/Group/Sort)权重相同,无步骤感
3. 关键操作(Run/Limit)位于 header,窄视口下被挤压
4. Model 卡片嵌套边框样式,与外层卡冲突,列表只露 3.5 项
5. 缺查询态反馈(已选字段数 / 筛选条数 / 行数 / 耗时)
6. 缺空/加载/错误三态的统一处理

不动后端 / API / 服务层,**仅前端 UI 重写**。

## 2. 信息架构(Studio 布局)

```
┌─ Header(sticky) ─────────────────────────────────────────────────┐
│ 🔎 Query Builder · Data Exploration                              │
│   summary chip [model · N fields · M filters · K rows · Tms]     │
│   [Reset] [▶ Run query]                                          │
├─ Models rail(280px) ─┬─ Canvas(flex-1) ─┬─ Results dock(底部固定)─┤
│ search input         │ 1 Fields card    │ status bar(KPI 4 卡:    │
│ groups: 系统 / 业务  │ 2 Filters card   │   rows · time · fields ·│
│ model items(active   │ 3 Group&Agg card │   datasource)            │
│   左侧 4px 蓝条)     │   (optional 灰)  │ table preview(zebra +    │
│                      │ 4 Sort & Limit   │   sticky header)         │
└──────────────────────┴──────────────────┴──────────────────────────┘
```

**Header**:

- sticky 顶部 56px;左标题 + 副标题,中间 summary chip,右侧 Reset/Run
- summary chip 实时反映当前查询配置:`e2et_record · 3 字段 · 2 过滤 · 142 行 / 38ms`
- Run 主色 `bg-blue-600`,带 `⌘↵` 提示

**Models rail**:

- 280px 宽,纵向独立滚动
- 顶部 search input(沿用 `qb-model-search` testid)
- 列表项:左 4px accent bar(选中时蓝色) + 中文名 + `text-xs text-slate-500` model code + 右侧行数(若 API 返回)
- 选中态: `bg-blue-50` + 左条 `bg-blue-600`
- 不再嵌套 border-rounded 容器

**Canvas — 4 个步骤卡**:

| # | 标题 | 计数右上角 | 内容 |
|---|------|-----------|------|
| 1 | 字段 / Fields | `已选 3 / 12` | chip 切换:点击 chip 切 选/未选;**hover 显示字段类型**(`bigint` / `varchar` / `timestamp`,吸收 Variant B);超过 12 个时折叠 "show all" |
| 2 | 过滤条件 / Filters | `2 条` | token 行 `[字段 ▼][运算符 ▼][值输入][×]`;末尾 `+ 添加过滤条件`;多条之间显示 `AND` 连接器(暂不支持 OR) |
| 3 | 分组与聚合 · 可选 | `空` / `1 group · 2 agg` | 两列:Group by 多选 chip / Aggregations 行(field, fn, alias);整卡灰底以示 optional |
| 4 | 排序与限制 | — | 单行: `Sort: [field ▼] [ASC/DESC]   Limit: [数字]` |

**Results dock**:

- 底部固定 340px,可拖动(可选;v1 不做)
- 顶栏 KPI 4 小卡(吸收 Variant B):rows / latency / fields shown / data source
- 状态:成功(绿点)/ 失败(红 banner inline)/ 加载(脉冲骨架)
- 表格:zebra 行 `even:bg-slate-50`,header `sticky bg-white`,溢出横向滚动
- 空态:友好图示 + "选模型 → 跑查询" 引导

## 3. 视觉规范(贴齐站内)

| 项 | 值 |
|----|----|
| 背景 | `bg-slate-50` |
| 卡片 | `bg-white rounded-xl border border-slate-200 shadow-sm` |
| 主色 | `blue-600`(Run) / `blue-50`+`blue-700`(选中态) |
| 步骤徽标 | `inline-flex h-6 w-6 items-center justify-center rounded-full bg-blue-50 text-blue-700 ring-1 ring-blue-200 text-xs font-semibold` |
| Chip(未选) | `border border-slate-200 bg-white text-slate-700 hover:border-blue-400` |
| Chip(已选) | `border-blue-500 bg-blue-50 text-blue-700` |
| Section 标题 | `text-sm font-semibold text-slate-700` + 右侧计数 `text-xs text-slate-500` |
| 间距 | 卡片内 `p-5`,卡片间 `gap-4`,canvas 外边 `p-6` |

## 4. 交互三态

| 态 | 行为 |
|----|------|
| 空(未选模型) | Canvas 中央显示 onboarding 卡:图标 + "从左侧选择一个数据模型开始" + 列出 3 个示例模型 quick-pick;Results dock 折叠为 32px status bar `请先选择模型` |
| 加载(`loading=true`) | Run 按钮转圈;Results dock 显示 6 行 skeleton(`animate-pulse`) |
| 错误 | Results dock 顶部红色 inline banner:`查询失败 · {message}` + 重试按钮;不破坏布局 |

## 5. 快捷键

- `⌘/Ctrl + Enter`: 触发 Run(等价于 `qb-run` click)
- `⌘/Ctrl + K`: focus models search
- `Esc`: 关闭任何展开 dropdown

## 6. data-testid 契约

**保留(向后兼容,不破坏现有 QB-01/06):**

- `query-builder` — 根容器
- `qb-model-search` — 模型搜索框
- `qb-model-{code}` — 单个模型项
- `qb-run` — Run 按钮
- `qb-limit` — limit 输入

**新增:**

- `qb-summary` — header 中的 summary chip 区
- `qb-step-fields` / `qb-step-filters` / `qb-step-aggregate` / `qb-step-sort` — 4 个步骤卡根容器
- `qb-field-{code}` — 字段 chip(已存在则保留,否则新建)
- `qb-filter-row-{index}` — 过滤行
- `qb-result-status` — 结果区 KPI bar(包含 `rows={N}` `latency={Tms}` 等可解析数据)
- `qb-result-table` — 结果表格
- `qb-empty-onboarding` — 空状态卡

## 7. E2E 调整

**保留(API 层 QB-02..05):**

在 spec 顶部加 TODO 注释,标记后续按硬规则迁出 `tests/e2e/`(本期不做以避免环境破坏):

```ts
// TODO(2026-05-08): QB-02..05 are API-only and should move to tests/api/
// per testing-e2e-web.md. Kept here to maintain green baseline.
```

**保留 QB-01 / QB-06**(选择器仍在,直接绿)。

**新增 QB-07 — UI 完整链路**:

```ts
test('QB-07: full UI flow — select model, fields, filter, run, verify result', async ({ page }) => {
  await page.goto('/');
  // 从侧边栏菜单导航到 Query Builder(满足 testing-e2e-web 红线 — 禁 page.goto 直达)
  await page.getByRole('link', { name: /query builder|查询构建/i }).click();
  await expect(page.locator('[data-testid="query-builder"]')).toBeVisible();

  // 选 model
  await page.locator('[data-testid="qb-model-e2et_record"]').click();

  // 勾 3 个字段
  await page.locator('[data-testid="qb-field-id"]').click();
  await page.locator('[data-testid="qb-field-scenario"]').click();
  await page.locator('[data-testid="qb-field-status"]').click();

  // 加一条 filter
  await page.locator('[data-testid="qb-step-filters"]').getByRole('button', { name: /添加过滤|add filter/i }).click();
  await page.locator('[data-testid="qb-filter-row-0"] [data-role="field"]').selectOption('status');
  await page.locator('[data-testid="qb-filter-row-0"] [data-role="op"]').selectOption('eq');
  await page.locator('[data-testid="qb-filter-row-0"] [data-role="value"]').fill('failed');

  // 跑查询
  await page.locator('[data-testid="qb-run"]').click();

  // 断言结果状态(rows > 0,具体数据)
  const status = page.locator('[data-testid="qb-result-status"]');
  await expect(status).toBeVisible();
  await expect(status).toContainText(/\d+\s*行|\d+\s*rows/);

  // 表格首行包含 'failed'(我们刚 filter 的)
  const firstRow = page.locator('[data-testid="qb-result-table"] tbody tr').first();
  await expect(firstRow).toContainText('failed');
});
```

满足:UI 交互次数(click/fill ≥ 6) > `page.request`(0);从菜单导航;断言具体数据值。

**新增 QB-08 — 空状态与快捷键**:

```ts
test('QB-08: empty onboarding visible before model selected; ⌘+Enter triggers run after selection', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('link', { name: /query builder|查询构建/i }).click();
  await expect(page.locator('[data-testid="qb-empty-onboarding"]')).toBeVisible();
  await page.locator('[data-testid="qb-model-e2et_record"]').click();
  await expect(page.locator('[data-testid="qb-empty-onboarding"]')).toBeHidden();
  await page.keyboard.press('Meta+Enter');
  await expect(page.locator('[data-testid="qb-result-status"]')).toBeVisible({ timeout: 10000 });
});
```

## 8. 范围控制(YAGNI)

**做**:

- 仅 `QueryBuilder.tsx` + 5 个子组件 UI 重写
- 保留所有现有 API 调用与 service 层
- header summary chip / KPI bar / sticky / 快捷键
- 4 步骤卡 + chip 字段 + token 过滤
- 空/加载/错误三态
- testid 兼容 + 2 个新 UI E2E

**不做**(后续迭代):

- SQL/JSON 视图切换
- 导出 CSV
- 保存查询(需后端表)
- 拖拽 results dock 高度
- OR 条件组、复杂嵌套过滤
- 移动端响应式(<768px 暂不支持)

## 9. 依赖与组件复用

- Tailwind 既有 utilities,无新增 lib
- toast 复用 `~/contexts/ToastContext`
- skeleton 复用 `~/ui/RouteLoadingFallback` 思路或局部 `animate-pulse`
- icon 用 inline SVG(已在用)或 `~/ui/icons`(若已有)

## 10. 风险与回滚

- 风险 1:旧 testid `qb-field-{code}` / `qb-filter-row-{index}` 若现实现未提供,需新增 — 已在子组件实现侧补齐
- 风险 2:从菜单导航需要 `/query-builder` 在 sidebar menu 中可达 — 提前 grep `default-bootstrap.json` 确认;若未注册,先补菜单项再写 E2E
- 回滚:UI 全在 `components/query-builder/**`,git revert 单个 commit 即可恢复

## 11. 验收

- 浏览器手动验证(必须):空态 → 选模型 → 选字段 → 加过滤 → Run → 看结果 KPI;切换错误态(改 limit=-1 或断网模拟);⌘↵ 快捷键
- E2E:`pnpm test --grep "Query Builder"` 全绿,新 QB-07/08 通过
- TypeScript: `npx tsc --noEmit` 零新增错误
- ESLint:零新增 warning
