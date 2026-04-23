# Visual Page Builder (Page Designer)

Build dynamic list, form, detail, and dashboard pages without writing code. The Page Designer provides a visual canvas where you drag blocks, configure properties, and publish pages -- all driven by your data models.

## Goal

By the end of this guide you will be able to create a complete CRUD page set (list + form + detail) for any data model using the Page Designer.

## Prerequisites

- AuraBoot running locally (backend on port 6443, frontend on port 5173)
- At least one published model (e.g., via a plugin import or the Model Designer)
- Admin account access

## Concepts

AuraBoot pages use a **flat block-based DSL** stored in `ab_page_schema`:

| Concept | Description | Values |
|---------|-------------|--------|
| `kind` | Page shape | `list`, `form`, `detail`, `dashboard` |
| `blockType` | Content block inside a page | `table`, `filters`, `toolbar`, `form-section`, `chart`, `tabs`, `sub-table`, `stat-card`, `custom` |
| `layout` | Arrangement strategy | `{ "type": "stack" }` or `{ "type": "grid", "cols": 12 }` |
| `profile` | Rendering strategy | `admin`, `report` |

> There is no `pageType`, `pageCategory`, or `dslSchema` nesting. The V2 flat format is the only supported format.

## Step-by-Step: Create a List Page

### 1. Open the Page Designer

Navigate to **Settings > Page Designer** in the sidebar, or go directly to `/page-designer`.

You will see a list of all existing page schemas. Click **Create Page** to start a new one.

### 2. Choose a Page Kind

Select the page kind from the dialog:

- **List** -- Table view with filters, toolbar actions, and pagination
- **Form** -- Create/edit form with field sections
- **Detail** -- Read-only detail view with tabs and sub-tables
- **Dashboard** -- Charts, stat cards, and data widgets

For this example, select **List**.

### 3. Understand the Canvas Layout

The Page Designer uses a **three-panel layout**:

```
+------------------+------------------------+-------------------+
|                  |                        |                   |
|  Block Palette   |      Canvas Area       |  Property Panel   |
|                  |                        |                   |
|  - Table         |  [filters]             |  Selected block   |
|  - Filters       |  [toolbar]             |  properties       |
|  - Toolbar       |  [table]               |                   |
|  - Form Section  |                        |  Field config     |
|  - Chart         |                        |  Action config    |
|  - Tabs          |                        |  Style config     |
|  - Sub-table     |                        |                   |
|  - Stat Card     |                        |                   |
|                  |                        |                   |
+------------------+------------------------+-------------------+
```

### 4. Add Blocks to the Canvas

Drag blocks from the **Block Palette** on the left onto the canvas:

1. **Filters block** -- Adds a filter bar at the top of the page
2. **Toolbar block** -- Adds action buttons (Create, Export, Bulk Delete)
3. **Table block** -- The main data table with columns

A typical list page has all three blocks stacked vertically.

### 5. Configure the Table Block

Click the **Table** block on the canvas. The Property Panel on the right shows:

**Columns Configuration:**

Each column maps to a model field. Configure:

| Property | Description |
|----------|-------------|
| `fieldCode` | The model field to display |
| `width` | Column width in pixels (optional) |
| `sortable` | Whether the column supports sorting |
| `hidden` | Hide column by default |
| `renderComponent` | Override the default renderer (e.g., `tag`, `link`, `progress`) |

**Example column configuration (JSON):**

```json
{
  "blockType": "table",
  "config": {
    "columns": [
      { "fieldCode": "sc_code", "width": 120, "sortable": true },
      { "fieldCode": "sc_name", "sortable": true },
      { "fieldCode": "sc_status", "renderComponent": "tag" },
      { "fieldCode": "sc_amount", "sortable": true },
      { "fieldCode": "created_at", "sortable": true }
    ],
    "defaultSort": { "field": "created_at", "order": "desc" },
    "pageSize": 20
  }
}
```

### 6. Configure the Toolbar Block

Click the **Toolbar** block to configure action buttons:

```json
{
  "blockType": "toolbar",
  "config": {
    "actions": [
      {
        "label": { "en": "Create", "zh-CN": "新建" },
        "type": "navigate",
        "target": "create",
        "variant": "primary"
      },
      {
        "label": { "en": "Export", "zh-CN": "导出" },
        "type": "export",
        "variant": "default"
      }
    ]
  }
}
```

> Action `type` must match the intended behavior: `navigate` for page navigation, `command` for executing a DSL command, `export` for data export.

### 7. Configure the Filters Block

```json
{
  "blockType": "filters",
  "config": {
    "fields": [
      { "fieldCode": "sc_status", "operator": "EQ" },
      { "fieldCode": "sc_name", "operator": "LIKE" }
    ],
    "layout": "inline"
  }
}
```

### 8. Page Settings

Click the **gear icon** in the toolbar to access page-level settings:

- **Page Title** -- Localized title (`{ "en": "Showcases", "zh-CN": "展示" }`)
- **Grid Configuration** -- Column count, row gap, column gap
- **Multi-View Support** -- Enable `enableMultiView` for saved view tabs

### 9. Preview and Publish

1. Click **Preview** in the toolbar to see a live rendering with real data
2. If satisfied, click **Save** (auto-saves every few seconds)
3. The page is accessible at `/p/{pageKey}` once the model is published

## Complete Example: List Page DSL

Here is a complete page schema for a list page as stored in the database:

```json
{
  "pageKey": "sc_showcase_list",
  "kind": "list",
  "title": { "en": "Showcases", "zh-CN": "展示列表" },
  "modelCode": "sc_showcase",
  "profile": "admin",
  "layout": { "type": "stack" },
  "blocks": [
    {
      "blockType": "filters",
      "config": {
        "fields": [
          { "fieldCode": "sc_status", "operator": "EQ" },
          { "fieldCode": "sc_name", "operator": "LIKE" }
        ]
      }
    },
    {
      "blockType": "toolbar",
      "config": {
        "actions": [
          { "label": { "en": "Create" }, "type": "navigate", "target": "create", "variant": "primary" }
        ]
      }
    },
    {
      "blockType": "table",
      "config": {
        "columns": [
          { "fieldCode": "sc_code", "width": 120, "sortable": true },
          { "fieldCode": "sc_name", "sortable": true },
          { "fieldCode": "sc_status", "renderComponent": "tag" },
          { "fieldCode": "sc_quantity", "sortable": true },
          { "fieldCode": "created_at", "sortable": true }
        ],
        "rowActions": [
          { "label": { "en": "View" }, "type": "navigate", "target": "detail" },
          { "label": { "en": "Edit" }, "type": "navigate", "target": "edit" }
        ],
        "defaultSort": { "field": "created_at", "order": "desc" },
        "pageSize": 20
      }
    }
  ]
}
```

## Block Types Reference

| Block Type | Use Case | Supported Page Kinds |
|-----------|----------|---------------------|
| `table` | Data table with columns, sorting, pagination | list |
| `filters` | Filter bar with field-based conditions | list |
| `toolbar` | Action buttons (create, export, bulk ops) | list, detail |
| `form-section` | Group of form fields | form |
| `tabs` | Tab navigation for status filtering | list |
| `sub-table` | Related records table (via foreign key or dataSource) | detail |
| `chart` | Visualization (bar, line, pie, area) | dashboard |
| `stat-card` | KPI metric card with trend indicator | dashboard |
| `custom` | Custom React component rendering | any |

## Creating a Form Page

Form pages use `form-section` blocks to group fields:

```json
{
  "pageKey": "sc_showcase_form",
  "kind": "form",
  "modelCode": "sc_showcase",
  "layout": { "type": "stack" },
  "blocks": [
    {
      "blockType": "form-section",
      "config": {
        "title": { "en": "Basic Information" },
        "fields": [
          { "fieldCode": "sc_name", "required": true, "colSpan": 2 },
          { "fieldCode": "sc_code", "required": true },
          { "fieldCode": "sc_status" },
          { "fieldCode": "sc_quantity" },
          { "fieldCode": "sc_description", "colSpan": 2, "renderComponent": "richtext" }
        ],
        "columns": 2
      }
    }
  ]
}
```

## Creating a Detail Page

Detail pages combine read-only field display with sub-tables and tabs:

```json
{
  "pageKey": "sc_showcase_detail",
  "kind": "detail",
  "modelCode": "sc_showcase",
  "layout": { "type": "stack" },
  "blocks": [
    {
      "blockType": "form-section",
      "config": {
        "title": { "en": "Overview" },
        "fields": [
          { "fieldCode": "sc_name" },
          { "fieldCode": "sc_code" },
          { "fieldCode": "sc_status" },
          { "fieldCode": "sc_quantity" }
        ],
        "readonly": true
      }
    },
    {
      "blockType": "sub-table",
      "config": {
        "title": { "en": "Line Items" },
        "modelCode": "sc_showcase_item",
        "foreignKey": "sc_item_showcase_id",
        "columns": [
          { "fieldCode": "sc_item_name" },
          { "fieldCode": "sc_item_quantity" },
          { "fieldCode": "sc_item_price" }
        ]
      }
    }
  ]
}
```

### Detail tab data contracts

Detail tabs should not be treated as generic containers. Each block type has a different data contract, and the page DSL should reflect that explicitly.

| Block type | Purpose | Expected behavior | Recommended test style |
|-----------|---------|-------------------|------------------------|
| `form-section` | Current record snapshot | Shows persisted field values | Assert exact values, allowing locale-aware formatting for dates/numbers |
| `sub-table` | Related rows or query-backed rows | Shows rows when data exists, otherwise explicit empty state | Assert row presence plus key business cells |
| `activity-timeline` | Business activity feed | Can be empty for new records | Assert real events when available, otherwise explicit empty / permission state |
| `field-history` | Audit trail | Can be empty or permission-gated | Assert change entries when available, otherwise explicit empty / permission state |
| `bpm-panel` | Workflow runtime state | Must show `ready`, `empty`, or `error` clearly | Assert block state plus key section content |

For robust page testing, ask:

1. Does the tab have a backing data source?
2. If it does, does the UI render actual rows/events/fields?
3. If empty is valid, does the tab degrade with a clear empty state instead of silent blank content?

### `sub-table` with `namedQuery`

Detail pages support query-backed sub-tables by using `dataSource.kind = "namedQuery"` or `dataSource.type = "namedQuery"`.

Example:

```json
{
  "blockType": "sub-table",
  "config": {
    "title": { "en": "Approval History", "zh-CN": "审批历史" },
    "dataSource": {
      "kind": "namedQuery",
      "queryCode": "wd_leave_request_approval_history",
      "params": {
        "processInstanceId": "${wd_req_process_instance}"
      }
    },
    "columns": [
      { "fieldCode": "taskName" },
      { "fieldCode": "status" },
      { "fieldCode": "comment" }
    ]
  }
}
```

Rules:

- `queryCode` is required when the data source is a named query.
- Runtime should resolve this to `/api/datasource/list` with `datasourceId = nq:${queryCode}`.
- Query-backed table data should use `format = records`.
- If the query returns zero rows, the sub-table must show its empty state rather than a blank tab.

### Placeholder interpolation in detail sub-table params

For detail-page sub-table params, both placeholder styles are supported:

- `${record.fieldName}`
- `${fieldName}`

Examples:

```json
{
  "params": {
    "processInstanceId": "${wd_req_process_instance}",
    "applicantPid": "${record.wd_req_applicant}"
  }
}
```

Rules:

- `${recordId}` resolves to the current detail record id
- `${record.fieldName}` resolves to the parent record field value
- `${fieldName}` also resolves to the parent record field value
- Unknown placeholders should degrade to empty string; never leak raw template text into the outgoing request

### NamedQuery SQL constraints for detail tabs

When a detail tab depends on a named query, the SQL must match the real storage types of the backing tables.

Common pitfalls:

- comparing `varchar tenant_id` columns against numeric `#{params.tenantId}` without a cast
- assuming workflow tables always populate a human-friendly `title`
- asserting approval comments in tabs whose query never returns them

Recommended pattern:

```sql
SELECT
  CAST(t.id AS VARCHAR) AS pid,
  COALESCE(NULLIF(t.title, ''), t.process_definition_activity_id) AS "taskName",
  t.status AS status
FROM se_task_instance t
WHERE t.tenant_id = CAST(#{params.tenantId} AS VARCHAR)
  AND t.process_instance_id = CAST(#{params.processInstanceId} AS BIGINT)
ORDER BY t.gmt_create ASC
```

This avoids:

- PostgreSQL type mismatch errors like `character varying = bigint`
- empty node labels in approval-history tables
- tests asserting labels the query does not actually return

### E2E guidance for detail pages

Prefer block-specific assertions instead of one generic “tab exists” assertion.

- `form-section`: assert field values directly
- `sub-table`: assert visible rows and key cell content
- `activity-timeline`: assert real event content, or explicit empty / permission state
- `field-history`: assert real change entries, or explicit empty / permission state
- `bpm-panel`: assert block state and the expected section content

For workflow detail pages specifically:

- wait for the pending todo task to exist before asserting query-backed approval-history tabs
- do not assume approval comments appear in every tab; they may belong to BPM history or activity timeline instead of the sub-table query
- avoid exact global DOM counts when the detail page pre-renders hidden tabs; prefer visible-container assertions or lower-bound counts

### List row-click behavior

For DSL list pages, the platform default is:

- row click navigates to the detail page
- preview drawer is opt-in, not the default

Use explicit configuration only when you want non-default behavior:

```json
{
  "props": {
    "rowClickAction": "drawer"
  }
}
```

Supported values:

- `detail`: navigate to `/p/{model}/view/{recordId}` or the configured detail page
- `drawer`: open `RecordPreviewDrawer`
- `none`: disable row-click interaction

Notes:

- older pages that relied on an implicit preview drawer should now declare `rowClickAction: "drawer"` explicitly
- designer defaults should stay aligned with runtime defaults: if nothing is configured, treat row click as `detail`

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| Page shows 403 Forbidden | Missing dynamic permission | Ensure the model is published; check that `dynamic.{model_code}.read` permission exists |
| Page shows empty table | No data or wrong modelCode | Verify data exists via `aura query {model_code}` |
| Columns show raw field codes | i18n not configured | Add field translations to the i18n resource files |
| Toolbar Create button does nothing | Wrong action `type` | Use `"type": "navigate"` for page navigation, not `"type": "command"` |
| Custom dataSource page fails | Extension not imported | Re-import the plugin; check `ab_page_schema.extension` is not empty |
| Sub-table shows no data | Wrong foreignKey config | Verify the foreign key field code matches the actual reference field |

## Tips

- **Preview often** -- The designer auto-saves, but preview shows real data
- **Use the Settings panel** (gear icon) to configure page-level properties like grid layout
- **Block order matters** -- Blocks render top-to-bottom in `stack` layout
- **Version history** -- The designer tracks versions; you can roll back to a previous state
- **All labels must use i18n** -- Use `{ "en": "Label", "zh-CN": "标签" }` format, never hardcoded strings

## Next Steps

- [Core Concepts: Data Models](../core-concepts/data-models.md) -- Understand models before designing pages
- [Dashboards Guide](dashboards.md) -- Build chart-based dashboard pages
- [Plugin Development](../tutorials/first-plugin.md) -- Package pages into reusable plugins
