# Pages & Layouts

Pages define how data is presented to users. Every page in AuraBoot is a JSON configuration that specifies a **kind** (what the page does), a list of **blocks** (what content it shows), and a **layout** (how blocks are arranged). The platform renders pages dynamically from these configurations -- no custom React components needed for standard business CRUD.

> **Related docs:** [Models & Fields](./models-and-fields.md) for the data layer, [Commands](./commands.md) for business logic, [State Machines](./state-machines.md) for status flows, [Permissions](./permissions.md) for access control.

## Page Kinds

Every page has a `kind` that determines its fundamental behavior:

| Kind | Purpose | Typical URL |
|---|---|---|
| `list` | Browse, search, and filter records. Table with toolbar, tabs, and pagination. | `/p/{pageKey}` |
| `form` | Create or edit a single record. Grouped field sections with validation. | `/p/{pageKey}/new` or `/p/{pageKey}/edit/{id}` |
| `detail` | View a single record in read-only mode. Tabbed sections with related sub-tables. | `/p/{pageKey}/view/{id}` |
| `dashboard` | KPI overview with charts, stat cards, and metrics. No record-level data binding. | `/p/{pageKey}` |

All values are **lowercase**. There are no aliases -- `pageType`, `pageCategory`, `RuntimePageType`, and `SchemaKind` have been removed.

## Block Types

Blocks are the building units of a page. Each block has a `blockType` and type-specific configuration:

| Block Type | Used In | Purpose |
|---|---|---|
| `table` | list | Data table with columns, sorting, search, row actions, and pagination |
| `filters` | list | Filter panel above the table |
| `toolbar` | list, detail | Action buttons (create, edit, delete, state transitions) |
| `tabs` | list, detail | Tab navigation. In list pages, tabs filter by status. In detail pages, tabs organize content sections. |
| `form-section` | form, detail | A group of fields with a section title. In forms, fields are editable. In details, fields are read-only. |
| `form-buttons` | form | Submit and cancel buttons for the form |
| `sub-table` | detail | Related child records displayed as an inline table. Supports three data modes. |
| `chart` | dashboard | Visualization: bar, line, pie, funnel, radar, area, gauge, scatter, heatmap, treemap, combo, wordcloud, nps |
| `stat-card` | dashboard | KPI metric cards with icon, value, and title |
| `custom` | any | Escape hatch for custom React components (platform pages only, not business CRUD) |

## Layout System

The `layout` object controls how blocks are arranged on the page.

### Stack Layout (Vertical)

Blocks stack vertically, each taking full width. This is the default for detail pages:

```json
{
  "layout": {
    "type": "stack"
  }
}
```

### Grid Layout (12-Column)

Blocks are placed on a 12-column grid. Each block can specify its column span via `layout.colSpan`:

```json
{
  "layout": {
    "type": "grid",
    "cols": 12,
    "gap": 16
  }
}
```

Inside a grid layout, individual blocks control their width:

```json
{
  "id": "stat_customers",
  "blockType": "stat-card",
  "layout": { "colSpan": 3 }
}
```

A `colSpan` of 3 on a 12-column grid means 25% width. Four stat cards with `colSpan: 3` each fill one row.

### Field-Level Column Span

Within `form-section` blocks, individual fields can specify their column span:

```json
{
  "field": "sc_name",
  "colSpan": 6
}
```

A `colSpan` of 6 means the field takes half the section width. A `colSpan` of 12 means full width.

## Profile System

The `profile` field controls rendering strategy:

| Profile | Behavior |
|---|---|
| `admin` | Standard admin UI with full toolbar, filters, and CRUD actions (default) |
| `report` | Report-oriented layout with emphasis on data display, charts, and export |

If omitted, `admin` is used.

## Page Configuration Schema

Every page JSON file follows this top-level structure:

```json
{
  "pageKey": "showcase_all_fields_list",
  "name:zh-CN": "Full Field Types List",
  "name:en": "All Field Types",
  "modelCode": "showcase_all_fields",
  "kind": "list",
  "schemaVersion": 2,
  "layout": {
    "type": "grid",
    "cols": 12
  },
  "blocks": [
    { "id": "...", "blockType": "tabs", ... },
    { "id": "...", "blockType": "toolbar", ... },
    { "id": "...", "blockType": "table", ... }
  ]
}
```

| Property | Type | Required | Description |
|---|---|---|---|
| `pageKey` | string | Yes | Unique page identifier. Used in URLs as `/p/{pageKey}`. Convention: `{model_code}_{kind}`. |
| `name:zh-CN` | string | Yes | Chinese display name |
| `name:en` | string | Yes | English display name |
| `modelCode` | string | Yes | The model this page is bound to |
| `kind` | string | Yes | `list`, `form`, `detail`, or `dashboard` |
| `schemaVersion` | number | Yes | Always `2` (V2 flat format) |
| `layout` | object | Yes | `{ "type": "stack" }` or `{ "type": "grid", "cols": 12 }` |
| `blocks` | array | Yes | Flat array of block objects |
| `profile` | string | No | `admin` (default) or `report` |
| `title` | object | No | Localized title override: `{ "zh-CN": "...", "en": "..." }` |
| `description` | string | No | Human-readable page description |

## List Page

A list page typically contains three blocks: tabs, toolbar, and table.

### Complete Example

This is a real configuration from the Showcase plugin:

```json
{
  "pageKey": "showcase_all_fields_list",
  "name:zh-CN": "Full Field Types List",
  "name:en": "All Field Types",
  "modelCode": "showcase_all_fields",
  "kind": "list",
  "schemaVersion": 2,
  "layout": { "type": "grid", "cols": 12 },
  "blocks": [
    {
      "id": "sc_list_tabs",
      "blockType": "tabs",
      "tabs": [
        {
          "key": "all",
          "label": { "en": "All", "zh-CN": "All" },
          "filter": null
        },
        {
          "key": "draft",
          "label": { "en": "Draft", "zh-CN": "Draft" },
          "filter": { "field": "sc_status", "value": "draft", "operator": "EQ" }
        },
        {
          "key": "active",
          "label": { "en": "Active", "zh-CN": "Active" },
          "filter": { "field": "sc_status", "value": "active", "operator": "EQ" }
        }
      ]
    },
    {
      "id": "sc_toolbar",
      "blockType": "toolbar",
      "buttons": [
        {
          "code": "create",
          "primary": true,
          "permissionCode": "sc.showcase.manage",
          "label": { "zh-CN": "Create", "en": "Create" },
          "action": {
            "type": "navigate",
            "to": "showcase_all_fields_form",
            "command": "sc:create_showcase"
          }
        }
      ]
    },
    {
      "id": "sc_table",
      "blockType": "table",
      "onRowClick": "navigate",
      "columns": [
        { "field": "sc_code", "width": 150, "sortable": true },
        { "field": "sc_name", "width": 200, "sortable": true },
        { "field": "sc_status", "width": 110, "renderType": "tag", "dictCode": "sc_status_dict" },
        { "field": "sc_priority", "width": 100, "renderType": "tag", "dictCode": "sc_priority_dict" },
        { "field": "sc_quantity", "width": 80 },
        { "field": "sc_progress", "width": 120, "renderType": "progress" },
        { "field": "sc_rating", "width": 120, "renderType": "rating" },
        {
          "field": "actions",
          "isActionColumn": true,
          "buttons": [
            {
              "code": "view",
              "label": { "en": "Detail" },
              "action": { "type": "navigate", "to": "showcase_all_fields_detail" }
            },
            {
              "code": "edit",
              "permissionCode": "sc.showcase.manage",
              "label": { "en": "Edit" },
              "action": { "type": "navigate", "to": "showcase_all_fields_form" }
            },
            {
              "code": "delete",
              "danger": true,
              "permissionCode": "sc.showcase.manage",
              "label": { "en": "Delete" },
              "confirm": "delete.confirm",
              "action": { "type": "command", "command": "sc:delete_showcase" }
            }
          ]
        }
      ],
      "searchFields": ["sc_name", "sc_code", "sc_description"],
      "defaultSort": { "field": "created_at", "order": "desc" }
    }
  ]
}
```

### Table Column Properties

| Property | Type | Description |
|---|---|---|
| `field` | string | Field code from the model, or `"actions"` for action column |
| `width` | number | Column width in pixels |
| `sortable` | boolean | Enable column sorting |
| `renderType` | string | Special rendering: `tag` (colored badge), `progress` (progress bar), `rating` (stars) |
| `dictCode` | string | Dictionary code for tag rendering (maps values to colors/labels) |
| `valueType` | string | Display format: `color`, `url`, `email` |
| `isActionColumn` | boolean | Marks this as the row actions column |
| `buttons` | array | Row-level action buttons (only for action columns) |
| `align` | string | Text alignment: `left`, `center`, `right` |

### Tab Filter Object

Each tab can optionally filter the table:

```json
{
  "key": "draft",
  "label": { "en": "Draft", "zh-CN": "Draft" },
  "filter": {
    "field": "sc_status",
    "value": "draft",
    "operator": "EQ"
  }
}
```

Setting `"filter": null` shows all records (the "All" tab).

### Button Action Types

| Type | Behavior |
|---|---|
| `navigate` | Navigate to another page. `to` is the target pageKey. |
| `command` | Execute a command via API. `command` is the command code (e.g., `sc:delete_showcase`). |
| `state_transition` | Execute a state transition command. Shows only when the current record state matches `fromStates`. |

## Form Page

A form page contains `form-section` blocks for field grouping and a `form-buttons` block for submit/cancel.

### Complete Example

```json
{
  "pageKey": "showcase_all_fields_form",
  "name:en": "Showcase Form",
  "modelCode": "showcase_all_fields",
  "kind": "form",
  "schemaVersion": 2,
  "layout": { "type": "grid", "cols": 12, "gap": 16 },
  "blocks": [
    {
      "id": "section_basic",
      "blockType": "form-section",
      "title": { "zh-CN": "Basic Information", "en-US": "Basic Information" },
      "fields": [
        { "field": "sc_name", "colSpan": 6 },
        { "field": "sc_code", "colSpan": 6, "readOnly": true },
        { "field": "sc_description", "colSpan": 12 }
      ]
    },
    {
      "id": "section_numbers",
      "blockType": "form-section",
      "title": { "en-US": "Numeric Fields" },
      "fields": [
        { "field": "sc_quantity", "colSpan": 4 },
        { "field": "sc_price", "colSpan": 4 },
        { "field": "sc_budget", "colSpan": 4 },
        { "field": "sc_progress", "colSpan": 4 },
        { "field": "sc_rating", "colSpan": 4 }
      ]
    },
    {
      "id": "section_enums",
      "blockType": "form-section",
      "title": { "en-US": "Enums & Selection" },
      "fields": [
        { "field": "sc_status", "colSpan": 4 },
        { "field": "sc_priority", "colSpan": 4 },
        { "field": "sc_category", "colSpan": 4 },
        { "field": "sc_is_active", "colSpan": 6 }
      ]
    },
    {
      "id": "buttons",
      "blockType": "form-buttons",
      "buttons": [
        {
          "code": "submit",
          "primary": true,
          "label": { "en": "Save" },
          "action": { "type": "command", "command": "sc:update_showcase" }
        },
        {
          "code": "cancel",
          "label": { "en": "Cancel" }
        }
      ]
    }
  ]
}
```

### Form Section Field Properties

| Property | Type | Description |
|---|---|---|
| `field` | string | Field code from the model |
| `colSpan` | number | Width of the field in grid columns (out of 12) |
| `readOnly` | boolean | Field is displayed but not editable |
| `span` | number | Alternative to colSpan for detail sections with fixed column count |

The field's input component is automatically determined by its `dataType` in the model definition. For example, `DATE` fields render a date picker, `ENUM` fields render a select dropdown, `BOOLEAN` fields render a switch.

## Detail Page

A detail page shows a single record in read-only mode. It typically uses a `tabs` block to organize sections, plus a `toolbar` block for actions.

### Complete Example

From the CRM Opportunity plugin, showing sub-tables in three different data modes:

```json
{
  "pageKey": "crm_opportunity_detail",
  "name:en": "Opportunity Detail",
  "modelCode": "crm_opportunity",
  "kind": "detail",
  "schemaVersion": 2,
  "layout": { "type": "stack" },
  "blocks": [
    {
      "id": "crm_opportunity_tabs",
      "blockType": "tabs",
      "tabs": [
        {
          "key": "overview",
          "label": { "en-US": "Overview" },
          "blocks": [
            {
              "id": "section_basic",
              "blockType": "form-section",
              "title": { "en-US": "Opportunity Information" },
              "columns": 2,
              "fields": [
                { "field": "crm_opp_code", "readOnly": true },
                { "field": "crm_opp_name", "readOnly": true },
                { "field": "crm_opp_stage", "readOnly": true },
                { "field": "crm_opp_expected_amount", "readOnly": true },
                { "field": "crm_opp_notes", "span": 2, "readOnly": true }
              ]
            }
          ]
        },
        {
          "key": "line_items",
          "label": { "en-US": "Line Items" },
          "blocks": [
            {
              "id": "block_line_items",
              "blockType": "sub-table",
              "title": { "en-US": "Line Items" },
              "subTable": {
                "childModel": "crm_opportunity_line",
                "parentField": "crm_ol_opportunity_id",
                "readOnly": false,
                "columns": [
                  { "field": "crm_ol_product_name", "width": 200 },
                  { "field": "crm_ol_quantity", "width": 100, "align": "right" },
                  { "field": "crm_ol_unit_price", "width": 120, "align": "right" },
                  { "field": "crm_ol_amount", "width": 120, "align": "right" }
                ],
                "actions": [
                  { "code": "add", "label": "create", "action": { "type": "command", "command": "crm:create_opp_line" } },
                  { "code": "edit", "label": "edit", "action": { "type": "command", "command": "crm:update_opp_line" } },
                  { "code": "delete", "danger": true, "label": "delete", "action": { "type": "command", "command": "crm:delete_opp_line" } }
                ],
                "summary": {
                  "fields": [
                    { "field": "crm_ol_amount", "aggregation": "sum", "label": { "en-US": "Total" } }
                  ]
                }
              }
            }
          ]
        },
        {
          "key": "activities",
          "label": { "en-US": "Activities" },
          "blocks": [
            {
              "id": "block_activities",
              "blockType": "sub-table",
              "subTable": {
                "dataSource": {
                  "type": "api",
                  "url": "/api/datasource/list",
                  "params": {
                    "datasourceId": "nq:crm_activities_by_object",
                    "objectType": "opportunity",
                    "objectId": "${recordId}"
                  }
                },
                "readOnly": true,
                "columns": [
                  { "field": "crm_act_type", "width": 100, "renderType": "tag" },
                  { "field": "crm_act_subject", "width": 200 },
                  { "field": "crm_act_date", "width": 160 }
                ]
              }
            }
          ]
        }
      ]
    },
    {
      "id": "crm_opp_detail_toolbar",
      "blockType": "toolbar",
      "buttons": [
        {
          "code": "edit",
          "icon": "Edit",
          "permissionCode": "CRM.opportunity.manage",
          "label": "edit",
          "action": { "type": "navigate", "to": "crm_opportunity_form" }
        },
        {
          "code": "qualify",
          "label": "execute",
          "action": { "type": "state_transition", "command": "crm:qualify_opportunity" }
        },
        {
          "code": "win",
          "primary": true,
          "label": "execute",
          "action": { "type": "state_transition", "command": "crm:win_opportunity" }
        },
        {
          "code": "lose",
          "danger": true,
          "label": "execute",
          "action": { "type": "state_transition", "command": "crm:lose_opportunity" }
        }
      ]
    }
  ]
}
```

### Sub-Table Data Modes

Sub-tables support three ways to load data:

| Mode | Configuration | Use Case |
|---|---|---|
| **foreignKey** (direct) | `childModel` + `parentField` | Child table has a direct foreign key to the parent. E.g., opportunity line items reference `crm_ol_opportunity_id`. |
| **resolveVia** (indirect) | `childModel` + `resolveVia` (junction table) | Many-to-many relationship through a junction table. E.g., opportunity contacts through `crm_opp_contact`. |
| **dataSource** (API) | `dataSource.type: "api"` + URL + params | Data loaded from a named query or custom API. E.g., activities filtered by object type and ID. |

### Sub-Table Properties

| Property | Type | Description |
|---|---|---|
| `childModel` | string | Model code of the child records |
| `parentField` | string | Foreign key field in the child model pointing to the parent |
| `readOnly` | boolean | If `true`, no add/edit/delete actions |
| `columns` | array | Column definitions (same schema as table columns) |
| `actions` | array | CRUD action buttons for the sub-table |
| `summary` | object | Aggregation row (e.g., sum of amounts) |
| `dataSource` | object | API-based data loading (alternative to foreignKey mode) |

## Dashboard Page

Dashboard pages display KPIs and visualizations using stat-card and chart blocks.

### Complete Example

```json
{
  "pageKey": "sc_arsenal_dashboard",
  "name:en": "Widget Dashboard",
  "modelCode": "showcase_all_fields",
  "kind": "dashboard",
  "schemaVersion": 2,
  "layout": { "type": "grid", "cols": 12, "gap": 16 },
  "blocks": [
    {
      "id": "stat_customers",
      "blockType": "stat-card",
      "layout": { "colSpan": 3 },
      "cards": [
        { "title": { "en": "Total Customers" }, "value": 60, "icon": "UserGroupIcon" }
      ]
    },
    {
      "id": "stat_pipeline",
      "blockType": "stat-card",
      "layout": { "colSpan": 3 },
      "cards": [
        { "title": { "en": "Pipeline Amount" }, "value": 580, "icon": "CurrencyDollarIcon" }
      ]
    },
    {
      "id": "chart_monthly_sales",
      "blockType": "chart",
      "chartType": "bar",
      "layout": { "colSpan": 6 },
      "title": { "en": "Monthly Sales Comparison" },
      "chartConfig": {
        "dataSource": {
          "type": "static",
          "staticData": [
            { "month": "2025/07", "target": 80, "actual": 45 },
            { "month": "2025/08", "target": 80, "actual": 68 }
          ]
        },
        "xField": "month",
        "series": [
          { "name": { "en": "Target" }, "field": "target" },
          { "name": { "en": "Actual" }, "field": "actual" }
        ]
      }
    },
    {
      "id": "chart_opp_stage",
      "blockType": "chart",
      "chartType": "pie",
      "layout": { "colSpan": 4 },
      "title": { "en": "Opportunity Stage Distribution" },
      "chartConfig": {
        "dataSource": {
          "type": "static",
          "staticData": [
            { "name": { "en": "Initial Contact" }, "value": 15 },
            { "name": { "en": "Proposal" }, "value": 18 },
            { "name": { "en": "Won" }, "value": 22 }
          ]
        }
      }
    },
    {
      "id": "chart_gauge",
      "blockType": "chart",
      "chartType": "gauge",
      "layout": { "colSpan": 4 },
      "title": { "en": "Q1 Target Completion" },
      "chartConfig": {
        "dataSource": {
          "type": "static",
          "staticData": [{ "value": 78, "max": 100 }]
        },
        "value": 78,
        "max": 100
      }
    }
  ]
}
```

### Chart Types

| Chart Type | Description |
|---|---|
| `bar` | Vertical bar chart. Supports grouped and stacked bars. |
| `line` | Line chart with multiple series. |
| `area` | Filled area chart. |
| `pie` | Pie/donut chart. |
| `funnel` | Funnel chart for conversion stages. |
| `radar` | Radar/spider chart for multi-dimensional comparison. |
| `gauge` | Gauge meter showing progress toward a target. |
| `scatter` | Scatter plot for correlation analysis. |
| `heatmap` | Heat map for activity or density visualization. |
| `treemap` | Treemap for hierarchical data proportions. |
| `combo` | Combined bar + line chart with dual Y axes. |
| `wordcloud` | Word cloud from keyword frequency data. |
| `nps` | Net Promoter Score visualization. |

### Chart Data Sources

Charts can load data from two sources:

| Source | Configuration | Use Case |
|---|---|---|
| Static | `"type": "static", "staticData": [...]` | Demo dashboards, fixed KPIs |
| Named Query | `"type": "namedQuery", "queryCode": "nq:..."` | Live data from the database |

## Page Designer

AuraBoot includes a visual **Page Designer** for building pages through drag-and-drop. It generates the same JSON configuration described above. The designer supports:

- Dragging blocks onto a canvas
- Configuring block properties through a schema-driven property panel
- Live preview of the page
- Auto-saving drafts via `latestDslRef`

The Page Designer produces pages in V2 flat format (`blocks` array, no nesting). Pages created through the designer are functionally identical to hand-written JSON.

## Database Schema

Pages are stored in the `ab_page_schema` table:

| Column | Type | Description |
|---|---|---|
| `kind` | VARCHAR | `list`, `form`, `detail`, `dashboard` |
| `blocks` | JSONB | Flat array of block objects |
| `layout` | JSONB | Layout configuration |
| `title` | JSONB | Localized title |
| `profile` | VARCHAR | `admin` or `report` |

There are no `page_type`, `page_category`, or `dsl_schema` columns.

## Best Practices

1. **One model, four pages.** Most business entities need a list page, a form page, a detail page, and optionally a dashboard. Name them `{model}_list`, `{model}_form`, `{model}_detail`, `{model}_dashboard`.

2. **Use tabs for status filtering.** On list pages, add a `tabs` block with one tab per status value plus an "All" tab. This gives users instant status filtering without complex filter UI.

3. **Group form fields logically.** Create multiple `form-section` blocks grouped by domain (Basic Info, Financial, Dates, People). Use `colSpan` to create responsive layouts.

4. **Sub-tables for related data.** Use `foreignKey` mode when the child has a direct FK. Use `dataSource` mode when the relationship is complex or involves a junction table.

5. **Keep dashboards focused.** Use 3-4 stat cards at the top for key metrics, followed by 2-3 charts. Too many charts dilute the message.

6. **Grid layout for dashboards, stack for details.** Dashboards benefit from multi-column grid layouts. Detail pages work well with stack layout and tabbed sections.

7. **Always specify `schemaVersion: 2`.** This is the V2 flat format. V1 formats with `dslSchema` nesting are no longer supported.
