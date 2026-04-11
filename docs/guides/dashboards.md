# Dashboards -- Charts & KPIs

Build data-rich dashboards with charts, stat cards, and tables to monitor business metrics. AuraBoot provides two approaches: **DSL Dashboards** (page schema blocks) and the **Dashboard Designer** (drag-and-drop builder).

## Goal

By the end of this guide you will be able to create a sales performance dashboard with stat cards, bar charts, pie charts, and data tables.

## Prerequisites

- AuraBoot running locally
- At least one model with data (e.g., CRM opportunities, sales orders)
- Named queries or data sources configured for aggregations
- Admin account access

## Two Approaches

| Approach | Best For | How |
|----------|----------|-----|
| **DSL Dashboard** | Plugin-packaged, config-driven dashboards | Define `kind: "dashboard"` page with chart/stat-card blocks |
| **Dashboard Designer** | Ad-hoc, user-created dashboards | Drag-and-drop widgets at `/dashboard-designer` |

Both produce the same visual result. DSL dashboards are version-controlled and portable via plugins. The Dashboard Designer stores layouts in `ab_dashboard`.

## Dashboard Page Schema (DSL)

A dashboard page uses `kind: "dashboard"` with `chart` and `stat-card` blocks:

```json
{
  "pageKey": "sales_dashboard",
  "kind": "dashboard",
  "title": { "en": "Sales Performance", "zh-CN": "销售绩效" },
  "profile": "admin",
  "layout": { "type": "grid", "cols": 12 },
  "blocks": [
    { "blockType": "stat-card", "config": { ... } },
    { "blockType": "stat-card", "config": { ... } },
    { "blockType": "stat-card", "config": { ... } },
    { "blockType": "chart", "config": { ... } },
    { "blockType": "chart", "config": { ... } },
    { "blockType": "table", "config": { ... } }
  ]
}
```

## Stat Card Configuration

Stat cards display a single KPI value with optional trend indicator.

```json
{
  "blockType": "stat-card",
  "gridPosition": { "x": 0, "y": 0, "w": 3, "h": 2 },
  "config": {
    "title": { "en": "Total Revenue", "zh-CN": "总营收" },
    "dataSource": {
      "type": "namedQuery",
      "queryCode": "nq_sales_total_revenue",
      "parameters": {}
    },
    "valueField": "total_revenue",
    "format": "currency",
    "prefix": "$",
    "color": "blue",
    "trend": {
      "type": "percentage",
      "compareField": "prev_period_revenue",
      "positiveDirection": "up"
    },
    "icon": "dollar-sign"
  }
}
```

### Stat Card Properties

| Property | Type | Description |
|----------|------|-------------|
| `title` | LocalizedText | Card title |
| `dataSource` | DataSourceConfig | Where to fetch the value |
| `valueField` | string | Field name in the query result to display |
| `format` | string | `number`, `currency`, `percentage`, `compact` |
| `prefix` / `suffix` | string | Value prefix/suffix (e.g., "$", "%") |
| `color` | string | Theme color: `blue`, `green`, `red`, `orange`, `purple` |
| `trend` | TrendConfig | Comparison with previous period |
| `icon` | string | Icon name from the icon library |

### Trend Configuration

```json
{
  "trend": {
    "type": "percentage",
    "compareField": "prev_month_value",
    "positiveDirection": "up"
  }
}
```

- `type`: `percentage` (shows "+12.5%") or `absolute` (shows "+1,250")
- `compareField`: Field in the query result for comparison value
- `positiveDirection`: `up` (green when increasing) or `down` (green when decreasing, e.g., costs)

## Chart Block Configuration

Charts visualize aggregated data from named queries or data sources.

### Bar Chart

```json
{
  "blockType": "chart",
  "gridPosition": { "x": 0, "y": 2, "w": 6, "h": 4 },
  "config": {
    "title": { "en": "Revenue by Region" },
    "chartType": "bar",
    "dataSource": {
      "type": "namedQuery",
      "queryCode": "nq_revenue_by_region",
      "parameters": {}
    },
    "xField": "region",
    "yField": "total_revenue",
    "color": "#3B82F6",
    "orientation": "vertical"
  }
}
```

### Line Chart (Time Series)

```json
{
  "blockType": "chart",
  "gridPosition": { "x": 6, "y": 2, "w": 6, "h": 4 },
  "config": {
    "title": { "en": "Monthly Sales Trend" },
    "chartType": "line",
    "dataSource": {
      "type": "namedQuery",
      "queryCode": "nq_monthly_sales",
      "parameters": { "year": "2026" }
    },
    "xField": "month",
    "yField": "total_amount",
    "color": "#10B981",
    "smooth": true,
    "showArea": true
  }
}
```

### Pie Chart

```json
{
  "blockType": "chart",
  "gridPosition": { "x": 0, "y": 6, "w": 4, "h": 4 },
  "config": {
    "title": { "en": "Opportunity Stage Distribution" },
    "chartType": "pie",
    "dataSource": {
      "type": "namedQuery",
      "queryCode": "nq_opp_by_stage",
      "parameters": {}
    },
    "labelField": "stage",
    "valueField": "count",
    "showLabels": true,
    "showPercentage": true
  }
}
```

### Area Chart

```json
{
  "blockType": "chart",
  "gridPosition": { "x": 4, "y": 6, "w": 8, "h": 4 },
  "config": {
    "title": { "en": "Cumulative Revenue" },
    "chartType": "area",
    "dataSource": {
      "type": "namedQuery",
      "queryCode": "nq_cumulative_revenue",
      "parameters": {}
    },
    "xField": "date",
    "yField": "cumulative_amount",
    "color": "#8B5CF6",
    "gradient": true
  }
}
```

### Supported Chart Types

| Type | Use Case | Required Fields |
|------|----------|----------------|
| `bar` | Category comparison | `xField`, `yField` |
| `line` | Time series, trends | `xField`, `yField` |
| `pie` | Distribution, proportions | `labelField`, `valueField` |
| `area` | Volume over time | `xField`, `yField` |
| `scatter` | Correlation analysis | `xField`, `yField` |

## Data Sources

### Named Query

Reference a pre-defined query stored in `ab_named_query`:

```json
{
  "dataSource": {
    "type": "namedQuery",
    "queryCode": "nq_sales_by_region",
    "parameters": {
      "startDate": "2026-01-01",
      "endDate": "2026-12-31"
    }
  }
}
```

Named queries are defined in plugin config:

```json
{
  "code": "nq_sales_by_region",
  "name": "Sales by Region",
  "modelCode": "sales_order",
  "queryType": "aggregate",
  "config": {
    "groupBy": ["sl_region"],
    "aggregations": [
      { "fieldCode": "sl_amount", "function": "SUM", "alias": "total_revenue" },
      { "fieldCode": "sl_order_id", "function": "COUNT", "alias": "order_count" }
    ],
    "filters": [
      { "fieldName": "sl_status", "operator": "EQ", "value": "completed" }
    ]
  }
}
```

### Aggregate (Inline)

Define the aggregation directly in the widget config (Dashboard Designer):

```json
{
  "dataSource": {
    "type": "aggregate",
    "modelCode": "sales_order",
    "dimensions": ["sl_region"],
    "metrics": [
      { "fieldCode": "sl_amount", "function": "SUM", "alias": "total_revenue" }
    ],
    "filters": [
      { "fieldName": "sl_status", "operator": "EQ", "value": "completed" }
    ]
  }
}
```

## Grid Layout System

Dashboard pages use a 12-column grid layout:

```json
{
  "layout": {
    "type": "grid",
    "cols": 12,
    "rowHeight": 80,
    "gap": 16
  }
}
```

Each block specifies its grid position:

```json
{
  "gridPosition": {
    "x": 0,
    "y": 0,
    "w": 3,
    "h": 2
  }
}
```

| Property | Description |
|----------|-------------|
| `x` | Column start (0-based, max 11) |
| `y` | Row start (0-based) |
| `w` | Width in columns (1-12) |
| `h` | Height in row units |

### Typical Layout Patterns

**3 stat cards + 2 charts + 1 table:**

```
+------+------+------+------+
| Stat | Stat | Stat |      |    y=0, h=2
+------+------+------+------+
| Bar Chart         | Pie   |    y=2, h=4
|                   | Chart |
+-------------------+-------+
| Data Table                |    y=6, h=4
+---------------------------+
```

Grid positions:
```json
[
  { "x": 0, "y": 0, "w": 4, "h": 2 },
  { "x": 4, "y": 0, "w": 4, "h": 2 },
  { "x": 8, "y": 0, "w": 4, "h": 2 },
  { "x": 0, "y": 2, "w": 8, "h": 4 },
  { "x": 8, "y": 2, "w": 4, "h": 4 },
  { "x": 0, "y": 6, "w": 12, "h": 4 }
]
```

## Dashboard Designer (Drag-and-Drop)

### 1. Open the Designer

Navigate to `/dashboard-designer` to see existing dashboards, or click **Create Dashboard** for a new one.

### 2. Designer Layout

```
+------------------+---------------------------+-------------------+
|  Widget Palette  |       Canvas (Grid)       |  Property Panel   |
|                  |                           |                   |
|  - Bar Chart     |  [drag widgets here]      |  Title            |
|  - Line Chart    |                           |  Data source      |
|  - Pie Chart     |                           |  Style            |
|  - Area Chart    |                           |  Linkage          |
|  - Stat Card     |                           |  Drilldown        |
|  - Table         |                           |  Refresh          |
|  - Scatter       |                           |                   |
+------------------+---------------------------+-------------------+
```

### 3. Add Widgets

Drag a chart type from the palette onto the canvas. Resize and position by dragging edges and handles.

### 4. Configure Data Source

Click a widget to open the Property Panel. Under **Data Source**:

- Select a `namedQuery` and pass parameters
- Or configure an inline `aggregate` query with model, dimensions, and metrics

### 5. Style and Customize

Configure colors, labels, legends, tooltips, and axis formatting in the **Style** tab.

### 6. Set Scope and Publish

| Scope | Visibility |
|-------|-----------|
| `personal` | Only the creator can see it |
| `team` | Team members can see it |
| `global` | All users in the tenant can see it |

Click **Publish** to make the dashboard available. Unpublished dashboards stay in `draft` status.

### 7. Auto-Save

The Dashboard Designer auto-saves every 30 seconds. A version history is maintained for rollback.

### 8. View and Export

Published dashboards are viewable at `/dashboards/view/{code}`.

Export options:
- **PDF** -- Full dashboard rendered as PDF (html2canvas + jsPDF)
- **Excel** -- Each widget exported as a separate sheet (SheetJS)

## Complete Example: Sales Performance Dashboard

### Named Queries (Plugin Config)

```json
[
  {
    "code": "nq_sales_total_metrics",
    "modelCode": "sales_order",
    "queryType": "aggregate",
    "config": {
      "aggregations": [
        { "fieldCode": "sl_amount", "function": "SUM", "alias": "total_revenue" },
        { "fieldCode": "sl_order_id", "function": "COUNT", "alias": "order_count" },
        { "fieldCode": "sl_amount", "function": "AVG", "alias": "avg_order_value" }
      ],
      "filters": [
        { "fieldName": "sl_status", "operator": "IN", "value": ["completed", "shipped"] }
      ]
    }
  },
  {
    "code": "nq_revenue_by_region",
    "modelCode": "sales_order",
    "queryType": "aggregate",
    "config": {
      "groupBy": ["sl_region"],
      "aggregations": [
        { "fieldCode": "sl_amount", "function": "SUM", "alias": "total_revenue" }
      ],
      "sortField": "total_revenue",
      "sortOrder": "DESC"
    }
  },
  {
    "code": "nq_monthly_sales",
    "modelCode": "sales_order",
    "queryType": "aggregate",
    "config": {
      "groupBy": ["month"],
      "aggregations": [
        { "fieldCode": "sl_amount", "function": "SUM", "alias": "monthly_revenue" }
      ],
      "sortField": "month",
      "sortOrder": "ASC"
    }
  },
  {
    "code": "nq_opp_by_stage",
    "modelCode": "crm_opportunity",
    "queryType": "aggregate",
    "config": {
      "groupBy": ["crm_opp_stage"],
      "aggregations": [
        { "fieldCode": "crm_opp_id", "function": "COUNT", "alias": "count" }
      ]
    }
  }
]
```

### Dashboard Page Schema

```json
{
  "pageKey": "sales_performance_dashboard",
  "kind": "dashboard",
  "title": { "en": "Sales Performance", "zh-CN": "销售绩效" },
  "modelCode": null,
  "profile": "admin",
  "layout": { "type": "grid", "cols": 12, "rowHeight": 80, "gap": 16 },
  "blocks": [
    {
      "blockType": "stat-card",
      "gridPosition": { "x": 0, "y": 0, "w": 4, "h": 2 },
      "config": {
        "title": { "en": "Total Revenue" },
        "dataSource": { "type": "namedQuery", "queryCode": "nq_sales_total_metrics" },
        "valueField": "total_revenue",
        "format": "currency",
        "prefix": "$",
        "color": "blue",
        "icon": "dollar-sign"
      }
    },
    {
      "blockType": "stat-card",
      "gridPosition": { "x": 4, "y": 0, "w": 4, "h": 2 },
      "config": {
        "title": { "en": "Total Orders" },
        "dataSource": { "type": "namedQuery", "queryCode": "nq_sales_total_metrics" },
        "valueField": "order_count",
        "format": "number",
        "color": "green",
        "icon": "shopping-cart"
      }
    },
    {
      "blockType": "stat-card",
      "gridPosition": { "x": 8, "y": 0, "w": 4, "h": 2 },
      "config": {
        "title": { "en": "Avg Order Value" },
        "dataSource": { "type": "namedQuery", "queryCode": "nq_sales_total_metrics" },
        "valueField": "avg_order_value",
        "format": "currency",
        "prefix": "$",
        "color": "purple",
        "icon": "trending-up"
      }
    },
    {
      "blockType": "chart",
      "gridPosition": { "x": 0, "y": 2, "w": 8, "h": 4 },
      "config": {
        "title": { "en": "Revenue by Region" },
        "chartType": "bar",
        "dataSource": { "type": "namedQuery", "queryCode": "nq_revenue_by_region" },
        "xField": "sl_region",
        "yField": "total_revenue",
        "color": "#3B82F6"
      }
    },
    {
      "blockType": "chart",
      "gridPosition": { "x": 8, "y": 2, "w": 4, "h": 4 },
      "config": {
        "title": { "en": "Pipeline by Stage" },
        "chartType": "pie",
        "dataSource": { "type": "namedQuery", "queryCode": "nq_opp_by_stage" },
        "labelField": "crm_opp_stage",
        "valueField": "count",
        "showPercentage": true
      }
    },
    {
      "blockType": "chart",
      "gridPosition": { "x": 0, "y": 6, "w": 8, "h": 4 },
      "config": {
        "title": { "en": "Monthly Sales Trend" },
        "chartType": "line",
        "dataSource": { "type": "namedQuery", "queryCode": "nq_monthly_sales" },
        "xField": "month",
        "yField": "monthly_revenue",
        "color": "#10B981",
        "smooth": true,
        "showArea": true
      }
    },
    {
      "blockType": "table",
      "gridPosition": { "x": 8, "y": 6, "w": 4, "h": 4 },
      "config": {
        "title": { "en": "Top Deals" },
        "dataSource": { "type": "namedQuery", "queryCode": "nq_top_deals" },
        "columns": [
          { "fieldCode": "crm_opp_name", "width": 150 },
          { "fieldCode": "crm_opp_amount", "width": 100 }
        ],
        "pageSize": 5
      }
    }
  ]
}
```

## Best Practices

### Query Performance

- **Use Named Queries** for complex aggregations rather than inline queries
- **Add filters** to narrow the dataset (date ranges, status filters)
- **Limit results** -- Pie charts should have <= 8 slices, tables <= 20 rows
- **Cache** -- Named queries with static parameters benefit from caching

### Meaningful KPIs

- **Lead with the most important number** -- Place key metrics (revenue, conversion rate) in the first row
- **Include context** -- Stat cards with trends show if a number is improving or declining
- **Avoid vanity metrics** -- "Total records created" tells you nothing; "Conversion rate" tells you everything
- **Group related charts** -- Revenue bar chart next to revenue trend line

### Layout Tips

- **3-4 stat cards per row** -- Use `w: 3` or `w: 4` for even distribution
- **Charts need height** -- Use `h: 4` minimum for charts to render labels clearly
- **Tables at the bottom** -- Scrollable content works better below visual charts
- **Consistent colors** -- Use the same color for the same metric across cards and charts

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| Dashboard renders as a list page | Wrong `kind` value | Verify `kind: "dashboard"` in page schema (not `list`) |
| Chart shows "No data" | Named query returns empty | Test query with `aura query {model}` or ChatBI |
| Stat card shows 0 | Wrong `valueField` | Check that `valueField` matches the alias in the named query |
| Grid layout broken | Overlapping positions | Verify `gridPosition` values don't overlap |
| Pie chart has too many slices | No limit on group by | Add `LIMIT 8` or use a TOP N filter in the named query |
| Export fails | Canvas rendering issue | Try with a smaller dashboard (fewer widgets) |
| Dashboard not visible to others | Scope is `personal` | Change scope to `team` or `global` |

## Next Steps

- [Page Designer](page-designer.md) -- Build list/form/detail pages alongside your dashboard
- [AI Copilot](ai-copilot.md) -- Use ChatBI to explore data before building permanent charts
- [Automation Rules](automation-rules.md) -- Trigger alerts when KPIs exceed thresholds
