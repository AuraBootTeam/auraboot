# ACP Showcase Dashboard Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite `acs_dashboard.json` from a single welcome rich-text into a 15-widget storytelling page that explains ACP's 7-layer semantic pipeline, tipping points, and safety valves, driven entirely by existing named queries.

**Architecture:** Pure declarative — no frontend tsx changes. The dashboard JSON references existing widgets (`smart-rich-text` / `smart-number-card` / `smart-bar-chart` / `smart-pie-chart` / `smart-line-chart` / `smart-combo-chart` / `smart-table-chart` / `smart-shortcuts`) and consumes 5 existing + 1 new named query. The 7-layer pipeline diagram is inline SVG inside `smart-rich-text`.

**Tech Stack:** AuraBoot plugin DSL JSON, Playwright E2E, no new runtime code.

**Spec:** `auraboot/docs/plans/2026-05/2026-05-08-acs-showcase-dashboard-redesign-design.md`

**Locked decisions** (from spec Q&A + pre-flight grep):
- 7-layer pipeline = horizontal layout (wide-screen friendly)
- Recent logs table = 10 rows
- CTA strip uses `smart-shortcuts` (link-only, no command exec) — CTA #1 routes to `/p/acs_demo_request/new` (Page Designer create form), CTA #2 to `/p/acs_demo_request`, CTA #3 to `/p/acs_safety_rule`
- i18n locale codes = `zh-CN` / `en-US` (matches existing `i18n.json`)

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `auraboot/plugins/acp-showcase/config/dashboards/acs_dashboard.json` | **Rewrite** | Layout + 15 widgets |
| `auraboot/plugins/acp-showcase/config/named-queries.json` | **Append** 1 entry | New `acs_showcase_recent_logs` query |
| `auraboot/plugins/acp-showcase/config/i18n.json` | **Append** ~30 entries | Dashboard-specific zh-CN/en-US strings |
| `auraboot/web-admin/tests/e2e/acs-showcase-dashboard.spec.ts` | **Create** | E2E coverage (nav / render / data / CTA / i18n) |

No tsx changes. No platform changes. No schema migration.

---

## Task 1: Pre-flight Diagnostics

**Goal:** Confirm two spec risks before writing JSON. Lock decisions inline.

**Files:** read-only inspection.

- [ ] **Step 1: Confirm `smart-rich-text` does NOT sanitize inline SVG**

Run:
```bash
grep -nE "DOMPurify|sanitize|stripTags" /Users/ghj/work/auraboot/auraboot/web-admin/app/plugins/core-dashboard/widgets/workbench/*.tsx /Users/ghj/work/auraboot/auraboot/web-admin/app/framework/widgets/**/*.tsx 2>/dev/null | grep -i rich
```
Expected: empty (no sanitize on rich-text). If non-empty, read the matching file and confirm SVG passes through. If SVG is stripped, fall back to `smart-image` widget pointing to a local SVG asset bundled with the plugin (out of current plan scope; flag for re-design).

- [ ] **Step 2: Confirm `smart-shortcuts` only supports `path`-based items**

Run:
```bash
grep -nE "type:|commandCode|onAction" /Users/ghj/work/auraboot/auraboot/web-admin/app/plugins/core-dashboard/widgets/workbench/ShortcutsWidget.tsx
```
Expected: `ShortcutItem` interface has `path: string` only, no `command` discriminator. If a `command` branch exists, optionally upgrade CTA #1 to fire `acs:create_demo_request` directly (still link is fine; do not block on this).

- [ ] **Step 3: Verify named-query data path is alive**

Run:
```bash
psql -h localhost -p 5432 -U auraboot -d auraboot_oss -c "SELECT COUNT(*) FROM mt_acs_demo_request;" 2>&1 | tail -3
psql -h localhost -p 5432 -U auraboot -d auraboot_oss -c "SELECT COUNT(*) FROM mt_acs_execution_log;" 2>&1 | tail -3
```
Expected: numeric count (may be 0 — that's fine; empty state is part of the design). If table missing, re-import plugin via Aura CLI (see Task 6 Step 1) before continuing.

- [ ] **Step 4: Commit pre-flight log**

No code change in this task. Record findings inline in subsequent task commits.

---

## Task 2: Add `acs_showcase_recent_logs` Named Query

**Files:**
- Modify: `auraboot/plugins/acp-showcase/config/named-queries.json`

- [ ] **Step 1: Append new entry**

Open the file. The current array ends at line 62 with `]`. Insert before the closing `]`:

```json
  ,
  {
    "code": "acs_showcase_recent_logs",
    "name:zh-CN": "最近执行日志",
    "name:en": "Recent Execution Logs",
    "description": "Most recent 10 execution log entries for ACP showcase dashboard",
    "fromSql": "SELECT acs_log_timestamp AS log_time, acs_log_layer AS layer, acs_log_action_type AS action_type, acs_log_status AS status, acs_log_risk_level AS risk_level, acs_log_safety_triggered AS safety_triggered, acs_log_duration_ms AS duration_ms FROM mt_acs_execution_log WHERE tenant_id = #{params.tenantId} ORDER BY acs_log_timestamp DESC LIMIT 10",
    "outputFields": [
      {"code": "log_time", "columnExpr": "log_time", "dataType": "datetime"},
      {"code": "layer", "columnExpr": "layer", "dataType": "string"},
      {"code": "action_type", "columnExpr": "action_type", "dataType": "string"},
      {"code": "status", "columnExpr": "status", "dataType": "string"},
      {"code": "risk_level", "columnExpr": "risk_level", "dataType": "string"},
      {"code": "safety_triggered", "columnExpr": "safety_triggered", "dataType": "boolean"},
      {"code": "duration_ms", "columnExpr": "duration_ms", "dataType": "number"}
    ]
  }
```

- [ ] **Step 2: Validate JSON parses**

Run:
```bash
python3 -c "import json; json.load(open('/Users/ghj/work/auraboot/auraboot/plugins/acp-showcase/config/named-queries.json')); print('OK')"
```
Expected: `OK`.

- [ ] **Step 3: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot
git add plugins/acp-showcase/config/named-queries.json
git commit -m "feat(acp-showcase): add acs_showcase_recent_logs named query"
```

---

## Task 3: Add Dashboard i18n Keys

**Files:**
- Modify: `auraboot/plugins/acp-showcase/config/i18n.json`

The existing format is a JSON array of `{key, zh-CN, en-US, refType}` objects.

- [ ] **Step 1: Append dashboard keys before closing `]`**

Insert these 38 entries (each on its own line) into the array. `refType: "ui"` for free-form UI strings.

```json
  ,{"key": "acs.dashboard.hero.title", "zh-CN": "Agent Control Plane — 引爆点与安全阀门", "en-US": "Agent Control Plane — Tipping Points & Safety Valves", "refType": "ui"},
  {"key": "acs.dashboard.hero.subtitle", "zh-CN": "7 层语义管道 · 全审计 · 安全可控的 AI 业务执行框架", "en-US": "7-layer semantic pipeline · full audit trail · safe & governable AI execution", "refType": "ui"},
  {"key": "acs.dashboard.hero.badge_running", "zh-CN": "运行中", "en-US": "Running", "refType": "ui"},
  {"key": "acs.dashboard.kpi.total_requests", "zh-CN": "累计请求", "en-US": "Total Requests", "refType": "ui"},
  {"key": "acs.dashboard.kpi.success_rate", "zh-CN": "成功率", "en-US": "Success Rate", "refType": "ui"},
  {"key": "acs.dashboard.kpi.avg_duration", "zh-CN": "平均耗时", "en-US": "Avg Duration", "refType": "ui"},
  {"key": "acs.dashboard.kpi.safety_triggers", "zh-CN": "安全阀门触发", "en-US": "Safety Valve Triggers", "refType": "ui"},
  {"key": "acs.dashboard.kpi.pending_approvals", "zh-CN": "待审批阻塞", "en-US": "Pending Approvals", "refType": "ui"},
  {"key": "acs.dashboard.kpi.total_cost", "zh-CN": "累计成本 (USD)", "en-US": "Total Cost (USD)", "refType": "ui"},
  {"key": "acs.dashboard.pipeline.title", "zh-CN": "7 层语义管道", "en-US": "7-Layer Semantic Pipeline", "refType": "ui"},
  {"key": "acs.dashboard.pipeline.svg_title", "zh-CN": "ACP 7 层语义管道流程图", "en-US": "ACP 7-Layer Semantic Pipeline Diagram", "refType": "ui"},
  {"key": "acs.dashboard.pipeline.layers.l5", "zh-CN": "L5 自然语言", "en-US": "L5 Natural Language", "refType": "ui"},
  {"key": "acs.dashboard.pipeline.layers.l4", "zh-CN": "L4 意图解析", "en-US": "L4 Intent Parsing", "refType": "ui"},
  {"key": "acs.dashboard.pipeline.layers.l3", "zh-CN": "L3 能力规划", "en-US": "L3 Capability Planning", "refType": "ui"},
  {"key": "acs.dashboard.pipeline.layers.l2", "zh-CN": "L2 动作执行", "en-US": "L2 Action Execution", "refType": "ui"},
  {"key": "acs.dashboard.pipeline.layers.l1", "zh-CN": "L1 工具调用", "en-US": "L1 Tool Invocation", "refType": "ui"},
  {"key": "acs.dashboard.pipeline.layers.l0", "zh-CN": "L0 数据写入", "en-US": "L0 Data Persistence", "refType": "ui"},
  {"key": "acs.dashboard.pipeline.legend_trigger", "zh-CN": "⚡ 引爆点", "en-US": "⚡ Tipping Point", "refType": "ui"},
  {"key": "acs.dashboard.pipeline.legend_valve", "zh-CN": "🛡 安全阀门", "en-US": "🛡 Safety Valve", "refType": "ui"},
  {"key": "acs.dashboard.cta.run_demo", "zh-CN": "运行一次 Demo 请求", "en-US": "Run a Demo Request", "refType": "ui"},
  {"key": "acs.dashboard.cta.view_requests", "zh-CN": "查看请求列表", "en-US": "View Request List", "refType": "ui"},
  {"key": "acs.dashboard.cta.view_rules", "zh-CN": "查看安全阀门规则", "en-US": "View Safety Rules", "refType": "ui"},
  {"key": "acs.dashboard.chart.status_title", "zh-CN": "请求状态分布", "en-US": "Request Status Distribution", "refType": "ui"},
  {"key": "acs.dashboard.chart.risk_title", "zh-CN": "风险等级分布", "en-US": "Risk Level Distribution", "refType": "ui"},
  {"key": "acs.dashboard.chart.category_title", "zh-CN": "分类成败统计", "en-US": "Category Success/Failure", "refType": "ui"},
  {"key": "acs.dashboard.chart.safety_trend_title", "zh-CN": "30 天安全阀门触发趋势", "en-US": "30-Day Safety Valve Trigger Trend", "refType": "ui"},
  {"key": "acs.dashboard.recent_logs.title", "zh-CN": "最近 10 条执行审计", "en-US": "Recent 10 Execution Audits", "refType": "ui"},
  {"key": "acs.dashboard.recent_logs.col_time", "zh-CN": "时间", "en-US": "Time", "refType": "ui"},
  {"key": "acs.dashboard.recent_logs.col_layer", "zh-CN": "层级", "en-US": "Layer", "refType": "ui"},
  {"key": "acs.dashboard.recent_logs.col_action", "zh-CN": "动作", "en-US": "Action", "refType": "ui"},
  {"key": "acs.dashboard.recent_logs.col_status", "zh-CN": "状态", "en-US": "Status", "refType": "ui"},
  {"key": "acs.dashboard.recent_logs.col_risk", "zh-CN": "风险", "en-US": "Risk", "refType": "ui"},
  {"key": "acs.dashboard.recent_logs.col_valve", "zh-CN": "阀门", "en-US": "Valve", "refType": "ui"},
  {"key": "acs.dashboard.recent_logs.col_duration", "zh-CN": "耗时 (ms)", "en-US": "Duration (ms)", "refType": "ui"},
  {"key": "acs.dashboard.footer.title", "zh-CN": "如何使用本插件", "en-US": "How to Use This Plugin", "refType": "ui"},
  {"key": "acs.dashboard.footer.step1", "zh-CN": "1. 点击"运行一次 Demo 请求",填写自然语言输入,体验 7 层管道全流程", "en-US": "1. Click \"Run a Demo Request\" to feel the full 7-layer pipeline", "refType": "ui"},
  {"key": "acs.dashboard.footer.step2", "zh-CN": "2. 在"查看安全阀门规则"中调整阈值,观察阻塞 / 审批 / 熔断行为", "en-US": "2. Tweak thresholds in \"View Safety Rules\" to observe block / approve / circuit-break behavior", "refType": "ui"},
  {"key": "acs.dashboard.footer.step3", "zh-CN": "3. 回到本页查看 KPI / 趋势 / 审计日志,理解 ACP 在你业务里如何兜底", "en-US": "3. Return here to inspect KPIs / trends / audit logs and see how ACP safeguards your business", "refType": "ui"},
  {"key": "acs.dashboard.empty.no_data_hint", "zh-CN": "暂无数据 — 点击下方"运行一次 Demo 请求"开启第一条管道执行", "en-US": "No data yet — click \"Run a Demo Request\" below to start your first pipeline", "refType": "ui"}
```

- [ ] **Step 2: Validate JSON parses**

Run:
```bash
python3 -c "import json; d=json.load(open('/Users/ghj/work/auraboot/auraboot/plugins/acp-showcase/config/i18n.json')); print(f'{len(d)} entries')"
```
Expected: `~117 entries` (was 79 + ~38 new).

- [ ] **Step 3: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot
git add plugins/acp-showcase/config/i18n.json
git commit -m "feat(acp-showcase): add dashboard i18n keys (zh-CN + en-US)"
```

---

## Task 4: Write Failing E2E Spec

**Files:**
- Create: `auraboot/web-admin/tests/e2e/acs-showcase-dashboard.spec.ts`

Use the THR golden template's pattern: nav-from-sidebar, no `page.goto`, no `waitForTimeout`, real-data assertions.

- [ ] **Step 1: Create spec file**

```typescript
import { test, expect } from '@playwright/test';
import { loginAs, openDashboardByCode } from './helpers/dashboard-helpers';

// Lifecycle E2E for ACP Showcase Dashboard.
// Asserts: nav from sidebar / 14 widgets render / KPI numeric / pipeline SVG layers / chart data / table rows / CTA round-trip / i18n no leak.

test.describe('ACP Showcase Dashboard', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page, 'tenant_admin');
  });

  test('renders all 15 widgets with real data and CTA flow', async ({ page }) => {
    // 1. Navigate from sidebar (NOT page.goto direct)
    await page.getByRole('link', { name: /Dashboards|仪表盘/ }).click();
    await page.getByRole('tab', { name: /ACP Showcase/i }).click();
    await expect(page.locator('[data-dashboard-code="acs_dashboard"]')).toBeVisible();

    // 2. All widgets present
    const widgetIds = [
      'hero',
      'kpi_total_requests', 'kpi_success_rate', 'kpi_avg_duration', 'kpi_safety_triggers',
      'pipeline_diagram',
      'cta_strip',
      'chart_status', 'chart_risk', 'chart_category',
      'chart_safety_trend',
      'kpi_pending_approvals', 'kpi_total_cost',
      'recent_logs',
      'footer_guide',
    ];
    for (const id of widgetIds) {
      await expect(page.locator(`[data-widget-id="${id}"]`)).toBeVisible();
    }

    // 3. KPI cards show numeric values (not '--' / empty)
    for (const kpi of ['kpi_total_requests', 'kpi_success_rate', 'kpi_avg_duration', 'kpi_safety_triggers', 'kpi_pending_approvals', 'kpi_total_cost']) {
      const text = await page.locator(`[data-widget-id="${kpi}"]`).textContent();
      expect(text).toMatch(/\d/);
    }

    // 4. Pipeline SVG: 6 layer nodes
    for (const layer of ['L5', 'L4', 'L3', 'L2', 'L1', 'L0']) {
      await expect(page.locator(`[data-widget-id="pipeline_diagram"] svg [data-layer="${layer}"]`)).toBeVisible();
    }

    // 5. Charts: each has at least 1 data shape
    await expect(page.locator('[data-widget-id="chart_status"] .recharts-bar-rectangle').first()).toBeVisible();
    await expect(page.locator('[data-widget-id="chart_risk"] .recharts-pie-sector').first()).toBeVisible();
    await expect(page.locator('[data-widget-id="chart_category"] .recharts-bar-rectangle').first()).toBeVisible();
    await expect(page.locator('[data-widget-id="chart_safety_trend"] .recharts-line-curve').first()).toBeVisible();

    // 6. Recent logs table: 1..10 rows
    const rows = page.locator('[data-widget-id="recent_logs"] tbody tr');
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThanOrEqual(1);
    expect(rowCount).toBeLessThanOrEqual(10);

    // 7. i18n key not leaked anywhere on the page
    const body = await page.locator('body').textContent();
    expect(body).not.toMatch(/\$i18n:/);

    // 8. CTA round-trip — "Run a Demo Request" routes to create form
    await page.locator('[data-widget-id="cta_strip"]').getByText(/Run a Demo Request|运行一次 Demo 请求/).click();
    await expect(page).toHaveURL(/\/p\/acs_demo_request\/new/);
    await expect(page.getByLabel(/Request Title|请求标题/)).toBeVisible();
  });
});
```

- [ ] **Step 2: Run spec — expect FAIL**

Run:
```bash
LOG=/tmp/pw-acs-$(date +%Y%m%d-%H%M%S).log
echo "Log: $LOG"
cd /Users/ghj/work/auraboot/auraboot && npx playwright test tests/e2e/acs-showcase-dashboard.spec.ts --workers=1 --reporter=line 2>&1 | tee "$LOG"
```
Expected: spec fails because most widgets don't exist yet (only `hero` does).

- [ ] **Step 3: If `dashboard-helpers.ts` lacks `openDashboardByCode`**

Verify:
```bash
grep -nE "loginAs|openDashboardByCode" /Users/ghj/work/auraboot/auraboot/web-admin/tests/e2e/helpers/dashboard-helpers.ts 2>/dev/null
```
If `loginAs` exists but `openDashboardByCode` does not, remove the unused import — the spec navigates via sidebar already and doesn't call it.

- [ ] **Step 4: Commit failing spec**

```bash
cd /Users/ghj/work/auraboot/auraboot
git add web-admin/tests/e2e/acs-showcase-dashboard.spec.ts
git commit -m "test(acp-showcase): add failing e2e for dashboard redesign"
```

---

## Task 5: Rewrite Dashboard JSON — Top Half (Hero / KPI / Pipeline / CTA)

**Files:**
- Modify: `auraboot/plugins/acp-showcase/config/dashboards/acs_dashboard.json`

Replace the entire file content. This task lays down rows 1–4 (hero, kpi row, pipeline, cta strip) plus the layout shell. Bottom half added in Task 6.

- [ ] **Step 1: Replace the file content**

```json
{
  "code": "acs_dashboard",
  "title": "$i18n:acs.dashboard.hero.title",
  "description": "$i18n:acs.dashboard.hero.subtitle",
  "scope": "global",
  "status": "published",
  "layoutConfig": {
    "columns": 12,
    "rowHeight": 100,
    "gap": 16
  },
  "widgets": [
    {
      "id": "hero",
      "type": "smart-rich-text",
      "x": 0, "y": 0, "w": 12, "h": 2,
      "config": {
        "content": "<div data-widget-id=\"hero\" class=\"relative overflow-hidden rounded-xl bg-gradient-to-r from-indigo-600 via-blue-600 to-cyan-500 px-8 py-6 text-white shadow-lg\"><div class=\"flex items-center justify-between\"><div><h1 class=\"text-2xl font-semibold\">$i18n:acs.dashboard.hero.title</h1><p class=\"mt-2 text-sm opacity-90\">$i18n:acs.dashboard.hero.subtitle</p></div><span class=\"inline-flex items-center gap-1 rounded-full bg-white/20 px-3 py-1 text-xs\"><span class=\"h-2 w-2 rounded-full bg-emerald-300\"></span>$i18n:acs.dashboard.hero.badge_running</span></div></div>"
      }
    },
    {
      "id": "kpi_total_requests",
      "type": "smart-number-card",
      "x": 0, "y": 2, "w": 3, "h": 2,
      "config": {
        "title": "$i18n:acs.dashboard.kpi.total_requests",
        "icon": "Rocket",
        "dataSource": { "type": "namedQuery", "queryCode": "acs_showcase_kpi", "metricField": "total_requests" }
      }
    },
    {
      "id": "kpi_success_rate",
      "type": "smart-number-card",
      "x": 3, "y": 2, "w": 3, "h": 2,
      "config": {
        "title": "$i18n:acs.dashboard.kpi.success_rate",
        "icon": "TrendingUp",
        "dataSource": { "type": "namedQuery", "queryCode": "acs_showcase_kpi", "metricField": "success_rate" },
        "visualization": { "suffix": "%" }
      }
    },
    {
      "id": "kpi_avg_duration",
      "type": "smart-number-card",
      "x": 6, "y": 2, "w": 3, "h": 2,
      "config": {
        "title": "$i18n:acs.dashboard.kpi.avg_duration",
        "icon": "Clock",
        "dataSource": { "type": "namedQuery", "queryCode": "acs_showcase_kpi", "metricField": "avg_duration_ms" },
        "visualization": { "suffix": " ms" }
      }
    },
    {
      "id": "kpi_safety_triggers",
      "type": "smart-number-card",
      "x": 9, "y": 2, "w": 3, "h": 2,
      "config": {
        "title": "$i18n:acs.dashboard.kpi.safety_triggers",
        "icon": "ShieldCheck",
        "dataSource": { "type": "namedQuery", "queryCode": "acs_showcase_kpi", "metricField": "safety_triggers" }
      }
    },
    {
      "id": "pipeline_diagram",
      "type": "smart-rich-text",
      "x": 0, "y": 4, "w": 12, "h": 4,
      "config": {
        "title": "$i18n:acs.dashboard.pipeline.title",
        "content": "<div data-widget-id=\"pipeline_diagram\" class=\"rounded-xl bg-white p-6 shadow-sm dark:bg-slate-900\"><div class=\"mb-3 flex items-center justify-between\"><h3 class=\"text-base font-semibold\">$i18n:acs.dashboard.pipeline.title</h3><div class=\"flex gap-3 text-xs text-slate-500\"><span>$i18n:acs.dashboard.pipeline.legend_trigger</span><span>$i18n:acs.dashboard.pipeline.legend_valve</span></div></div><svg role=\"img\" viewBox=\"0 0 1200 240\" preserveAspectRatio=\"xMidYMid meet\" class=\"w-full h-auto\"><title>$i18n:acs.dashboard.pipeline.svg_title</title><defs><marker id=\"arr\" viewBox=\"0 0 10 10\" refX=\"10\" refY=\"5\" markerWidth=\"6\" markerHeight=\"6\" orient=\"auto\"><path d=\"M0,0 L10,5 L0,10 z\" fill=\"#6366f1\"/></marker></defs><g font-family=\"system-ui\" font-size=\"14\"><rect data-layer=\"L5\" x=\"20\" y=\"40\" width=\"180\" height=\"60\" rx=\"10\" fill=\"#eef2ff\" stroke=\"#6366f1\" stroke-width=\"2\"/><text x=\"110\" y=\"75\" text-anchor=\"middle\" fill=\"#312e81\">$i18n:acs.dashboard.pipeline.layers.l5</text><line x1=\"200\" y1=\"70\" x2=\"320\" y2=\"70\" stroke=\"#6366f1\" stroke-width=\"2\" marker-end=\"url(#arr)\"/><text x=\"260\" y=\"60\" text-anchor=\"middle\" font-size=\"18\">⚡</text><rect data-layer=\"L4\" x=\"320\" y=\"40\" width=\"180\" height=\"60\" rx=\"10\" fill=\"#eef2ff\" stroke=\"#6366f1\" stroke-width=\"2\"/><text x=\"410\" y=\"75\" text-anchor=\"middle\" fill=\"#312e81\">$i18n:acs.dashboard.pipeline.layers.l4</text><line x1=\"500\" y1=\"70\" x2=\"620\" y2=\"70\" stroke=\"#6366f1\" stroke-width=\"2\" marker-end=\"url(#arr)\"/><text x=\"560\" y=\"60\" text-anchor=\"middle\" font-size=\"18\">🛡</text><rect data-layer=\"L3\" x=\"620\" y=\"40\" width=\"180\" height=\"60\" rx=\"10\" fill=\"#eef2ff\" stroke=\"#6366f1\" stroke-width=\"2\"/><text x=\"710\" y=\"75\" text-anchor=\"middle\" fill=\"#312e81\">$i18n:acs.dashboard.pipeline.layers.l3</text><path d=\"M800,70 Q900,70 900,140 Q900,170 800,170\" stroke=\"#6366f1\" stroke-width=\"2\" fill=\"none\" marker-end=\"url(#arr)\"/><rect data-layer=\"L2\" x=\"620\" y=\"140\" width=\"180\" height=\"60\" rx=\"10\" fill=\"#ecfeff\" stroke=\"#06b6d4\" stroke-width=\"2\"/><text x=\"710\" y=\"175\" text-anchor=\"middle\" fill=\"#155e75\">$i18n:acs.dashboard.pipeline.layers.l2</text><line x1=\"620\" y1=\"170\" x2=\"500\" y2=\"170\" stroke=\"#06b6d4\" stroke-width=\"2\" marker-end=\"url(#arr)\"/><text x=\"560\" y=\"160\" text-anchor=\"middle\" font-size=\"18\">🛡</text><rect data-layer=\"L1\" x=\"320\" y=\"140\" width=\"180\" height=\"60\" rx=\"10\" fill=\"#ecfeff\" stroke=\"#06b6d4\" stroke-width=\"2\"/><text x=\"410\" y=\"175\" text-anchor=\"middle\" fill=\"#155e75\">$i18n:acs.dashboard.pipeline.layers.l1</text><line x1=\"320\" y1=\"170\" x2=\"200\" y2=\"170\" stroke=\"#06b6d4\" stroke-width=\"2\" marker-end=\"url(#arr)\"/><text x=\"260\" y=\"160\" text-anchor=\"middle\" font-size=\"18\">🛡</text><rect data-layer=\"L0\" x=\"20\" y=\"140\" width=\"180\" height=\"60\" rx=\"10\" fill=\"#dcfce7\" stroke=\"#16a34a\" stroke-width=\"2\"/><text x=\"110\" y=\"175\" text-anchor=\"middle\" fill=\"#166534\">$i18n:acs.dashboard.pipeline.layers.l0</text></g></svg></div>"
      }
    },
    {
      "id": "cta_strip",
      "type": "smart-shortcuts",
      "x": 0, "y": 8, "w": 12, "h": 2,
      "config": {
        "title": "",
        "shortcuts": [
          { "label": "$i18n:acs.dashboard.cta.run_demo", "icon": "🚀", "path": "/p/acs_demo_request/new", "color": "bg-indigo-50" },
          { "label": "$i18n:acs.dashboard.cta.view_requests", "icon": "📋", "path": "/p/acs_demo_request", "color": "bg-blue-50" },
          { "label": "$i18n:acs.dashboard.cta.view_rules", "icon": "🛡", "path": "/p/acs_safety_rule", "color": "bg-emerald-50" }
        ]
      }
    }
  ]
}
```

- [ ] **Step 2: Validate JSON parses**

Run:
```bash
python3 -c "import json; json.load(open('/Users/ghj/work/auraboot/auraboot/plugins/acp-showcase/config/dashboards/acs_dashboard.json')); print('OK')"
```
Expected: `OK`.

- [ ] **Step 3: Commit (top half intentionally incomplete — bottom widgets in Task 6)**

```bash
cd /Users/ghj/work/auraboot/auraboot
git add plugins/acp-showcase/config/dashboards/acs_dashboard.json
git commit -m "feat(acp-showcase): dashboard top half (hero + kpi + pipeline + cta)"
```

---

## Task 6: Append Bottom Half (Charts / Trend / Table / Footer)

**Files:**
- Modify: `auraboot/plugins/acp-showcase/config/dashboards/acs_dashboard.json`

- [ ] **Step 1: Append 8 more widgets into the `widgets` array**

In the `widgets` array, after the `cta_strip` entry and before the closing `]`, insert (don't forget the leading comma):

```json
    ,{
      "id": "chart_status",
      "type": "smart-bar-chart",
      "x": 0, "y": 10, "w": 4, "h": 4,
      "config": {
        "title": "$i18n:acs.dashboard.chart.status_title",
        "dataSource": { "type": "namedQuery", "queryCode": "acs_showcase_status_distribution" },
        "visualization": { "xField": "label", "yField": "count" }
      }
    },
    {
      "id": "chart_risk",
      "type": "smart-pie-chart",
      "x": 4, "y": 10, "w": 4, "h": 4,
      "config": {
        "title": "$i18n:acs.dashboard.chart.risk_title",
        "dataSource": { "type": "namedQuery", "queryCode": "acs_showcase_risk_distribution" },
        "visualization": { "labelField": "label", "valueField": "value" }
      }
    },
    {
      "id": "chart_category",
      "type": "smart-combo-chart",
      "x": 8, "y": 10, "w": 4, "h": 4,
      "config": {
        "title": "$i18n:acs.dashboard.chart.category_title",
        "dataSource": { "type": "namedQuery", "queryCode": "acs_showcase_category_stats" },
        "visualization": { "xField": "category", "series": [ {"field": "success", "type": "bar"}, {"field": "failed", "type": "bar"} ] }
      }
    },
    {
      "id": "chart_safety_trend",
      "type": "smart-line-chart",
      "x": 0, "y": 14, "w": 8, "h": 4,
      "config": {
        "title": "$i18n:acs.dashboard.chart.safety_trend_title",
        "dataSource": { "type": "namedQuery", "queryCode": "acs_showcase_safety_trend" },
        "visualization": { "xField": "date", "yField": "trigger_count" }
      }
    },
    {
      "id": "kpi_pending_approvals",
      "type": "smart-number-card",
      "x": 8, "y": 14, "w": 4, "h": 2,
      "config": {
        "title": "$i18n:acs.dashboard.kpi.pending_approvals",
        "icon": "Inbox",
        "dataSource": { "type": "namedQuery", "queryCode": "acs_showcase_kpi", "metricField": "pending_approvals" }
      }
    },
    {
      "id": "kpi_total_cost",
      "type": "smart-number-card",
      "x": 8, "y": 16, "w": 4, "h": 2,
      "config": {
        "title": "$i18n:acs.dashboard.kpi.total_cost",
        "icon": "DollarSign",
        "dataSource": { "type": "namedQuery", "queryCode": "acs_showcase_kpi", "metricField": "total_cost" },
        "visualization": { "prefix": "$" }
      }
    },
    {
      "id": "recent_logs",
      "type": "smart-table-chart",
      "x": 0, "y": 18, "w": 12, "h": 5,
      "config": {
        "title": "$i18n:acs.dashboard.recent_logs.title",
        "dataSource": { "type": "namedQuery", "queryCode": "acs_showcase_recent_logs" },
        "columns": [
          { "field": "log_time", "label": "$i18n:acs.dashboard.recent_logs.col_time" },
          { "field": "layer", "label": "$i18n:acs.dashboard.recent_logs.col_layer" },
          { "field": "action_type", "label": "$i18n:acs.dashboard.recent_logs.col_action" },
          { "field": "status", "label": "$i18n:acs.dashboard.recent_logs.col_status" },
          { "field": "risk_level", "label": "$i18n:acs.dashboard.recent_logs.col_risk" },
          { "field": "safety_triggered", "label": "$i18n:acs.dashboard.recent_logs.col_valve" },
          { "field": "duration_ms", "label": "$i18n:acs.dashboard.recent_logs.col_duration" }
        ]
      }
    },
    {
      "id": "footer_guide",
      "type": "smart-rich-text",
      "x": 0, "y": 23, "w": 12, "h": 2,
      "config": {
        "content": "<div data-widget-id=\"footer_guide\" class=\"rounded-xl bg-slate-50 p-6 dark:bg-slate-800\"><h3 class=\"mb-3 text-base font-semibold\">$i18n:acs.dashboard.footer.title</h3><ol class=\"space-y-1 text-sm text-slate-600 dark:text-slate-300\"><li>$i18n:acs.dashboard.footer.step1</li><li>$i18n:acs.dashboard.footer.step2</li><li>$i18n:acs.dashboard.footer.step3</li></ol></div>"
      }
    }
```

- [ ] **Step 2: Validate JSON parses**

Run:
```bash
python3 -c "import json; d=json.load(open('/Users/ghj/work/auraboot/auraboot/plugins/acp-showcase/config/dashboards/acs_dashboard.json')); print(f'{len(d[\"widgets\"])} widgets')"
```
Expected: `15 widgets`.

- [ ] **Step 3: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot
git add plugins/acp-showcase/config/dashboards/acs_dashboard.json
git commit -m "feat(acp-showcase): dashboard bottom half (charts + trend + table + footer)"
```

---

## Task 7: Re-import Plugin and Manual Browser Verification

**Goal:** Catch widget rendering / data plumbing failures before E2E run.

- [ ] **Step 1: Re-import plugin via Aura CLI**

Run (adjust path to `aura` binary if different):
```bash
cd /Users/ghj/work/auraboot/auraboot
aura plugins import plugins/acp-showcase 2>&1 | tail -20
```
Expected: `imported: dashboards=1, namedQueries=6, i18n=...` with no errors.

If `aura` binary is not on PATH, use:
```bash
./gradlew :platform:bootRun -Dauraboot.plugins.import=plugins/acp-showcase 2>&1 | tail -10
```

- [ ] **Step 2: Open dashboard in browser**

Visit: `http://localhost:5173/dashboards?code=acs_dashboard` (or click via sidebar Dashboards → ACP Showcase tab).

Visually verify:
- Hero band gradient renders
- 4 KPI cards on row 2 show numbers (not `--`)
- 7-layer SVG renders 6 boxes in two rows with arrows + ⚡/🛡 marks
- 3 CTA shortcuts visible
- 3 charts on row 5 each show data
- Line chart + 2 right-stacked KPIs on row 6
- Audit table shows 1–10 rows with 7 columns
- Footer guide shows 3 steps
- Switch locale en-US: all text turns English, no `$i18n:` leak
- Toggle dark mode: contrast still readable

- [ ] **Step 3: Iterate on any visual / data issues**

For each issue, edit the JSON and re-import (Step 1). Common likely fixes:
- Pipeline SVG: text overflow → reduce font-size or shorten i18n string
- KPI card shows `NaN`: `metricField` name mismatch — re-check named query `outputFields`
- Chart blank: `xField`/`yField` doesn't match named query output → re-check codes
- Recent logs missing: `mt_acs_execution_log` empty → run a demo via CTA #1 to populate

- [ ] **Step 4: Take confirmation screenshot**

Run:
```bash
mkdir -p /tmp/acs-screenshots
# Use whatever screenshot tool you prefer; the goal is to keep a visual artifact for the commit description.
```

- [ ] **Step 5: Commit any iteration fixes**

```bash
cd /Users/ghj/work/auraboot/auraboot
git add plugins/acp-showcase/
git commit -m "fix(acp-showcase): dashboard visual + data adjustments after manual verify"
# (skip commit if no changes were needed)
```

---

## Task 8: Run E2E Spec to Green

**Files:**
- Modify (if needed): `auraboot/web-admin/tests/e2e/acs-showcase-dashboard.spec.ts`

- [ ] **Step 1: Run spec with full log**

```bash
LOG=/tmp/pw-acs-$(date +%Y%m%d-%H%M%S).log
echo "Log: $LOG"
cd /Users/ghj/work/auraboot/auraboot && npx playwright test tests/e2e/acs-showcase-dashboard.spec.ts --workers=1 --reporter=line 2>&1 | tee "$LOG"
```
Expected after fixes: `1 passed`.

- [ ] **Step 2: For each failing assertion, diagnose + fix the test OR the JSON**

Acceptable fixes:
- Selector mismatch (e.g., chart library outputs different DOM) → adjust selector to whatever the rendered DOM actually uses (verified in browser DevTools), do **NOT** weaken the assertion to "exists" if the original intent was data-shape
- Widget ID not appearing in DOM: ensure `data-widget-id` attribute is rendered by the wrapper (some widgets only render it when `id` is present in widget JSON — already set)
- i18n leak: missing key → add to `i18n.json` (Task 3 list might need extending) and re-import

Forbidden fixes (red-line #2):
- Replacing `page.click` with `page.request.post` to hit API
- Adding `retries: N` to mask flake without root-cause fix
- Adding `test.skip` to a real product gap (file backlog issue instead)

- [ ] **Step 3: Run e2e-truth self-audit before claiming pass**

Mentally walk the spec body:
- Body has more `page.click/fill` than `page.request.*` ✓
- No `waitForTimeout` ✓
- No `page.goto` for the dashboard URL ✓
- No `retries` or `test.skip` ✓
- Assertions check real values, not just visibility ✓ (KPI regex, row count range, layer presence)

- [ ] **Step 4: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot
git add web-admin/tests/e2e/acs-showcase-dashboard.spec.ts plugins/acp-showcase/
git commit -m "test(acp-showcase): dashboard e2e green"
```

---

## Task 9: Final Verification + Push

- [ ] **Step 1: Spec coverage check**

Walk the spec's "Verification Criteria" section. Confirm each item:
1. ✓ 15 widgets render (Task 7 manual + Task 8 E2E)
2. ✓ SVG fits 1280 / 1920 (Task 7 manual — resize browser)
3. ✓ en-US locale clean (Task 7 manual + Task 8 E2E i18n leak assertion)
4. ✓ E2E green, no fake-pass patterns (Task 8 self-audit)
5. ✓ Dark theme OK (Task 7 manual)
6. (Optional) Lighthouse a11y ≥ 90 — run `npx lighthouse http://localhost:5173/dashboards?code=acs_dashboard --only-categories=accessibility --quiet` if time permits
7. ✓ CTA round-trip works (Task 8 E2E)

- [ ] **Step 2: Push to main**

```bash
cd /Users/ghj/work/auraboot/auraboot
git log --oneline origin/main..HEAD
git push origin main
```
Expected: ~7 commits pushed (named-query, i18n, e2e-skeleton, dashboard top, dashboard bottom, optional iteration fix, e2e green).

- [ ] **Step 3: Update active-work memory**

Append entry to `/Users/ghj/.claude/projects/-Users-ghj-work-auraboot/memory/active-work.md` noting `acs_dashboard` redesign shipped + commit hash range.
