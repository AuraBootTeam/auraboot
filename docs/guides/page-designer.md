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
