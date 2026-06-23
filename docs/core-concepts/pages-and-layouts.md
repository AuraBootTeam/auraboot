---
type: system-reference
status: active
---

# Pages & Layouts

Pages define how data is presented to users. Every page in AuraBoot is a JSON configuration that specifies a **kind** (what the page does), a list of **blocks** (what content it shows), and a **layout** (how blocks are arranged). The platform renders pages dynamically from these configurations -- no custom React components needed for standard business CRUD.

> **Related docs:** [Models & Fields](./models-and-fields.md) for the data layer, [Commands](./commands.md) for business logic, [State Machines](./state-machines.md) for status flows, [Permissions](./permissions.md) for access control.

## Page Kinds

Every page has a `kind` that determines its fundamental behavior:

| Kind     | Purpose                                                                          | Typical URL                                    |
| -------- | -------------------------------------------------------------------------------- | ---------------------------------------------- |
| `list`   | Browse, search, and filter records. Table with toolbar, tabs, and pagination.    | `/p/{pageKey}`                                 |
| `form`   | Create or edit a single record. Grouped field sections with validation.          | `/p/{pageKey}/new` or `/p/{pageKey}/edit/{id}` |
| `detail` | View a single record in read-only mode. Tabbed sections with related sub-tables. | `/p/{pageKey}/view/{id}`                       |

All values are **lowercase**. There are no aliases -- `pageType`, `pageCategory`, `RuntimePageType`, and `SchemaKind` have been removed.

> **Importable plugin page kinds are `list` / `form` / `detail` only.** Dashboards are built with the separate **Dashboard Designer** (stored in `ab_dashboard`, served under `/dashboards/...`), **not** as a `pages.json` page — the plugin importer hard-rejects `kind: "dashboard"` (and `composite`), which have no plugin-page renderer. `chart` / `stat-card` blocks may still be embedded inside `detail` pages.

## Block Types

Blocks are the building units of a page. Each block has a `blockType` and type-specific configuration:

| Block Type     | Used In      | Purpose                                                                                                                                                                                    |
| -------------- | ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `table`        | list         | Data table with columns, sorting, search, row actions, and pagination                                                                                                                      |
| `filters`      | list         | Filter panel above the table                                                                                                                                                               |
| `toolbar`      | list, detail | Action buttons (create, edit, delete, state transitions)                                                                                                                                   |
| `tabs`         | list, detail | Tab navigation. In list pages, tabs filter by status. In detail pages, tabs organize content sections.                                                                                     |
| `form-section` | form, detail | A group of fields with a section title. In forms, fields are editable. In details, fields are read-only.                                                                                   |
| `form-buttons` | form         | Submit and cancel buttons for the form                                                                                                                                                     |
| `sub-table`    | detail       | Related child records displayed as an inline table. Supports three data modes.                                                                                                             |
| `chart`        | detail       | Visualization: bar, line, pie, funnel, radar, area, gauge, scatter, heatmap, treemap, combo, wordcloud, nps (embeddable in detail pages; standalone dashboards use the Dashboard Designer) |
| `stat-card`    | detail       | KPI metric cards with icon, value, and title                                                                                                                                               |
| `custom`       | any          | Escape hatch for custom React components (platform pages only, not business CRUD)                                                                                                          |

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

| Profile  | Behavior                                                                 |
| -------- | ------------------------------------------------------------------------ |
| `admin`  | Standard admin UI with full toolbar, filters, and CRUD actions (default) |
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
  "schemaVersion": 4,
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

| Property        | Type   | Required    | Description                                                                                                                                      |
| --------------- | ------ | ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `pageKey`       | string | Yes         | Unique page identifier. Used in URLs as `/p/{pageKey}`. Convention: `{model_code}_{kind}`.                                                       |
| `name:zh-CN`    | string | Yes         | Chinese display name                                                                                                                             |
| `name:en`       | string | Yes         | English display name                                                                                                                             |
| `modelCode`     | string | Model pages | Required for model-backed `list`, `form`, and `detail` pages. Optional for dashboards and custom non-record pages.                               |
| `kind`          | string | Yes         | `list`, `form`, or `detail` (importable plugin page kinds). `dashboard`/`composite` are **not** importable as pages — see note under Page Kinds. |
| `schemaVersion` | number | Yes         | Always `4` (DSL V4 flat format). The plugin importer **hard-fails** any page that does not explicitly declare `schemaVersion: 4`.                |
| `layout`        | object | Yes         | `{ "type": "stack" }` or `{ "type": "grid", "cols": 12 }`                                                                                        |
| `blocks`        | array  | Yes         | Flat array of block objects                                                                                                                      |
| `profile`       | string | No          | `admin` (default) or `report`                                                                                                                    |
| `title`         | object | No          | Localized title override: `{ "zh-CN": "...", "en": "..." }`                                                                                      |
| `description`   | string | No          | Human-readable page description                                                                                                                  |

## Page DSL Validation Contract

Plugin import rejects obvious page DSL gaps before the page can be published. Generated DSL must satisfy this contract:

| Rule Code                             | Scope                     | Contract                                                                                                                                                                                                                          |
| ------------------------------------- | ------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `S-PAGE-KIND` / `S-PAGE-KIND-UNKNOWN` | page                      | `kind` is required and must be one of the registered page kinds.                                                                                                                                                                  |
| `S-PAGE-LAYOUT`                       | page                      | `layout` is required and cannot be empty.                                                                                                                                                                                         |
| `S-PAGE-BLOCKS`                       | page                      | `blocks` is required and cannot be empty.                                                                                                                                                                                         |
| `S-PAGE-LEGACY-FORMAT`                | page                      | Legacy top-level `dslSchema` and `pageType` are rejected. Use the v4 flat format.                                                                                                                                                 |
| `S-PAGE-BLOCK-ID`                     | block                     | Every block requires a stable `id` for runtime diagnostics and golden verification.                                                                                                                                               |
| `S-PAGE-BLOCK-TYPE`                   | block                     | `blockType` must be registered in `DslRegistry.BlockType`.                                                                                                                                                                        |
| `S-PAGE-TABLE-COLUMNS`                | `table`, `sub-table`      | Table-like blocks require non-empty `columns`. For `sub-table`, columns may live under `subTable.columns`.                                                                                                                        |
| `S-PAGE-FORM-FIELDS`                  | `form-section`            | Form sections require non-empty `fields`.                                                                                                                                                                                         |
| `S-PAGE-BUTTONS`                      | `toolbar`, `form-buttons` | Command blocks require non-empty `buttons` or `actions`.                                                                                                                                                                          |
| `S-PAGE-FIELD-REF`                    | table/form fields         | Field references are required and must be bound to the page model when binding metadata is present.                                                                                                                               |
| `S-PAGE-LABEL`                        | list headers and commands | User-visible list/table headers and command labels must resolve to business labels. Table columns may use field display names as fallback; command labels must not be raw codes such as `sc_name`, `BOM_PROJECT_NO`, or `create`. |
| `S-PAGE-FORM-REQUIRED`                | editable forms            | If a field is required in the model binding or field constraints, the editable page field must set `required: true`.                                                                                                              |
| `S-PAGE-I18N`                         | user-facing text          | Non-ASCII hardcoded text is rejected. Use LocalizedText maps or `$i18n:key`.                                                                                                                                                      |

LocalizedText is the preferred inline label format:

```json
{
  "label": { "zh-CN": "Project No", "en": "Project No" }
}
```

This does not require adding a separate i18n resource key. The label is stored directly in the DSL as a localized object and resolved by the renderer. Use `$i18n:key` when the text must be shared or centrally managed.

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
  "schemaVersion": 4,
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
          "filter": {
            "field": "sc_status",
            "value": "active",
            "operator": "EQ"
          }
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
        {
          "field": "sc_code",
          "label": { "en": "Code" },
          "width": 150,
          "sortable": true
        },
        {
          "field": "sc_name",
          "label": { "en": "Name" },
          "width": 200,
          "sortable": true
        },
        {
          "field": "sc_status",
          "label": { "en": "Status" },
          "width": 110,
          "renderType": "tag",
          "dictCode": "sc_status_dict"
        },
        {
          "field": "sc_priority",
          "label": { "en": "Priority" },
          "width": 100,
          "renderType": "tag",
          "dictCode": "sc_priority_dict"
        },
        { "field": "sc_quantity", "label": { "en": "Quantity" }, "width": 80 },
        {
          "field": "sc_progress",
          "label": { "en": "Progress" },
          "width": 120,
          "renderType": "progress"
        },
        {
          "field": "sc_rating",
          "label": { "en": "Rating" },
          "width": 120,
          "renderType": "rating"
        },
        {
          "field": "actions",
          "label": { "en": "Actions" },
          "isActionColumn": true,
          "buttons": [
            {
              "code": "view",
              "label": { "en": "Detail" },
              "action": {
                "type": "navigate",
                "to": "showcase_all_fields_detail"
              }
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

| Property         | Type          | Description                                                                                                                                                                     |
| ---------------- | ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `field`          | string        | Field code from the model, or `"actions"` for action column                                                                                                                     |
| `label`          | object/string | Business label for the column header. Prefer LocalizedText or `$i18n:key`; if omitted, the field definition must provide a business display name. Raw field codes are rejected. |
| `width`          | number        | Column width in pixels                                                                                                                                                          |
| `sortable`       | boolean       | Enable column sorting                                                                                                                                                           |
| `renderType`     | string        | Special rendering: `tag` (colored badge), `progress` (progress bar), `rating` (stars)                                                                                           |
| `dictCode`       | string        | Dictionary code for tag rendering (maps values to colors/labels)                                                                                                                |
| `valueType`      | string        | Display format: `color`, `url`, `email`                                                                                                                                         |
| `isActionColumn` | boolean       | Marks this as the row actions column                                                                                                                                            |
| `buttons`        | array         | Row-level action buttons (only for action columns)                                                                                                                              |
| `align`          | string        | Text alignment: `left`, `center`, `right`                                                                                                                                       |

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

| Type               | Behavior                                                                                           |
| ------------------ | -------------------------------------------------------------------------------------------------- |
| `navigate`         | Navigate to another page. `to` is the target pageKey.                                              |
| `command`          | Execute a command via API. `command` is the command code (e.g., `sc:delete_showcase`).             |
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
  "schemaVersion": 4,
  "layout": { "type": "grid", "cols": 12, "gap": 16 },
  "blocks": [
    {
      "id": "section_basic",
      "blockType": "form-section",
      "title": { "zh-CN": "Basic Information", "en-US": "Basic Information" },
      "fields": [
        { "field": "sc_name", "colSpan": 6, "required": true },
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

| Property           | Type     | Description                                                                                    |
| ------------------ | -------- | ---------------------------------------------------------------------------------------------- |
| `field`            | string   | Field code from the model                                                                      |
| `colSpan`          | number   | Width of the field in grid columns (out of 12)                                                 |
| `required`         | boolean  | Must be `true` for editable fields that are required by the model binding or field constraints |
| `readOnly`         | boolean  | Field is displayed but not editable                                                            |
| `span`             | number   | Alternative to colSpan for detail sections with fixed column count                             |
| `allowCreate`      | boolean  | For `reference` fields only: show the inline-create affordance in the picker                   |
| `createCommand`    | string   | Command code used by the inline-create modal. Defaults to `{targetModel}:create`               |
| `createPageKey`    | string   | Form page key rendered inside the quick-create modal. Defaults to `${targetModel}_new`         |
| `createPermission` | string   | Permission code required to show inline create. Defaults to `createCommand`                    |
| `createFields`     | string[] | Reserved for a future field-subset quick-create form. Currently accepted but not honored       |

The field's input component is automatically determined by its `dataType` in the model definition. For example, `DATE` fields render a date picker, `ENUM` fields render a select dropdown, `BOOLEAN` fields render a switch.

### Reference Inline Create

For standard model-backed `reference` fields, a form page can let users create the
referenced record without leaving the current form. Add `allowCreate: true` to the
page field entry and provide the target model's create command/page when the defaults
do not match your plugin.

```json
{
  "field": "so_customer_id",
  "colSpan": 6,
  "allowCreate": true,
  "createCommand": "cust:create_customer",
  "createPageKey": "customer_form",
  "createPermission": "cust.customer.manage"
}
```

At runtime the reference picker shows a localized `+ New` action when the current user
has `createPermission`. The action opens a modal backed by the configured DSL form
page, submits through `createCommand`, then writes the created record's `pid` back to
the current field and refreshes target-model reference options. Failed creates keep the
modal open and do not change the current form value.

Phase 1 renders the full configured target-model form. `createFields` is a reserved
schema/type field for a later trimmed-form phase and does not filter the modal today.

Inline create is intentionally limited to reference fields that use the normal
model-backed picker. It is not rendered for enum fields, static options, named-query or
external API data sources, or any field entry with an explicit `dataSource`.

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
  "schemaVersion": 4,
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
                  {
                    "field": "crm_ol_product_name",
                    "label": { "en-US": "Product" },
                    "width": 200
                  },
                  {
                    "field": "crm_ol_quantity",
                    "label": { "en-US": "Quantity" },
                    "width": 100,
                    "align": "right"
                  },
                  {
                    "field": "crm_ol_unit_price",
                    "label": { "en-US": "Unit Price" },
                    "width": 120,
                    "align": "right"
                  },
                  {
                    "field": "crm_ol_amount",
                    "label": { "en-US": "Amount" },
                    "width": 120,
                    "align": "right"
                  }
                ],
                "actions": [
                  {
                    "code": "add",
                    "label": { "en-US": "Add" },
                    "action": {
                      "type": "command",
                      "command": "crm:create_opp_line"
                    }
                  },
                  {
                    "code": "edit",
                    "label": { "en-US": "Edit" },
                    "action": {
                      "type": "command",
                      "command": "crm:update_opp_line"
                    }
                  },
                  {
                    "code": "delete",
                    "danger": true,
                    "label": { "en-US": "Delete" },
                    "action": {
                      "type": "command",
                      "command": "crm:delete_opp_line"
                    }
                  }
                ],
                "summary": {
                  "fields": [
                    {
                      "field": "crm_ol_amount",
                      "aggregation": "sum",
                      "label": { "en-US": "Total" }
                    }
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
                  {
                    "field": "crm_act_type",
                    "label": { "en-US": "Type" },
                    "width": 100,
                    "renderType": "tag"
                  },
                  {
                    "field": "crm_act_subject",
                    "label": { "en-US": "Subject" },
                    "width": 200
                  },
                  {
                    "field": "crm_act_date",
                    "label": { "en-US": "Date" },
                    "width": 160
                  }
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
          "label": { "en-US": "Edit" },
          "action": { "type": "navigate", "to": "crm_opportunity_form" }
        },
        {
          "code": "qualify",
          "label": { "en-US": "Qualify" },
          "action": {
            "type": "state_transition",
            "command": "crm:qualify_opportunity"
          }
        },
        {
          "code": "win",
          "primary": true,
          "label": { "en-US": "Mark Won" },
          "action": {
            "type": "state_transition",
            "command": "crm:win_opportunity"
          }
        },
        {
          "code": "lose",
          "danger": true,
          "label": { "en-US": "Mark Lost" },
          "action": {
            "type": "state_transition",
            "command": "crm:lose_opportunity"
          }
        }
      ]
    }
  ]
}
```

### Sub-Table Data Modes

Sub-tables support three ways to load data:

| Mode                      | Configuration                                | Use Case                                                                                                            |
| ------------------------- | -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| **foreignKey** (direct)   | `childModel` + `parentField`                 | Child table has a direct foreign key to the parent. E.g., opportunity line items reference `crm_ol_opportunity_id`. |
| **resolveVia** (indirect) | `childModel` + `resolveVia` (junction table) | Many-to-many relationship through a junction table. E.g., opportunity contacts through `crm_opp_contact`.           |
| **dataSource** (API)      | `dataSource.type: "api"` + URL + params      | Data loaded from a named query or custom API. E.g., activities filtered by object type and ID.                      |

### Sub-Table Properties

| Property      | Type    | Description                                                 |
| ------------- | ------- | ----------------------------------------------------------- |
| `childModel`  | string  | Model code of the child records                             |
| `parentField` | string  | Foreign key field in the child model pointing to the parent |
| `readOnly`    | boolean | If `true`, no add/edit/delete actions                       |
| `columns`     | array   | Column definitions (same schema as table columns)           |
| `actions`     | array   | CRUD action buttons for the sub-table                       |
| `summary`     | object  | Aggregation row (e.g., sum of amounts)                      |
| `dataSource`  | object  | API-based data loading (alternative to foreignKey mode)     |

## Dashboard Page

Dashboard pages display KPIs and visualizations using stat-card and chart blocks.

### Complete Example

```json
{
  "pageKey": "sc_arsenal_dashboard",
  "name:en": "Widget Dashboard",
  "modelCode": "showcase_all_fields",
  "kind": "dashboard",
  "schemaVersion": 4,
  "layout": { "type": "grid", "cols": 12, "gap": 16 },
  "blocks": [
    {
      "id": "stat_customers",
      "blockType": "stat-card",
      "layout": { "colSpan": 3 },
      "cards": [
        {
          "title": { "en": "Total Customers" },
          "value": 60,
          "icon": "UserGroupIcon"
        }
      ]
    },
    {
      "id": "stat_pipeline",
      "blockType": "stat-card",
      "layout": { "colSpan": 3 },
      "cards": [
        {
          "title": { "en": "Pipeline Amount" },
          "value": 580,
          "icon": "CurrencyDollarIcon"
        }
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

| Chart Type  | Description                                            |
| ----------- | ------------------------------------------------------ |
| `bar`       | Vertical bar chart. Supports grouped and stacked bars. |
| `line`      | Line chart with multiple series.                       |
| `area`      | Filled area chart.                                     |
| `pie`       | Pie/donut chart.                                       |
| `funnel`    | Funnel chart for conversion stages.                    |
| `radar`     | Radar/spider chart for multi-dimensional comparison.   |
| `gauge`     | Gauge meter showing progress toward a target.          |
| `scatter`   | Scatter plot for correlation analysis.                 |
| `heatmap`   | Heat map for activity or density visualization.        |
| `treemap`   | Treemap for hierarchical data proportions.             |
| `combo`     | Combined bar + line chart with dual Y axes.            |
| `wordcloud` | Word cloud from keyword frequency data.                |
| `nps`       | Net Promoter Score visualization.                      |

### Chart Data Sources

Charts can load data from two sources:

| Source      | Configuration                                 | Use Case                    |
| ----------- | --------------------------------------------- | --------------------------- |
| Static      | `"type": "static", "staticData": [...]`       | Demo dashboards, fixed KPIs |
| Named Query | `"type": "namedQuery", "queryCode": "nq:..."` | Live data from the database |

## Page Designer

AuraBoot includes a visual **Page Designer** for building pages through drag-and-drop. It generates the same JSON configuration described above. The designer supports:

- Dragging blocks onto a canvas
- Configuring block properties through a schema-driven property panel
- Live preview of the page
- Auto-saving drafts via `latestDslRef`

The Page Designer produces pages in v4 flat format (`blocks` array, no nesting). Pages created through the designer are functionally identical to hand-written JSON.

## Database Schema

Pages are stored in the `ab_page_schema` table:

| Column    | Type    | Description                           |
| --------- | ------- | ------------------------------------- |
| `kind`    | VARCHAR | `list`, `form`, `detail`, `dashboard` |
| `blocks`  | JSONB   | Flat array of block objects           |
| `layout`  | JSONB   | Layout configuration                  |
| `title`   | JSONB   | Localized title                       |
| `profile` | VARCHAR | `admin` or `report`                   |

There are no `page_type`, `page_category`, or `dsl_schema` columns.

## Best Practices

1. **One model, four pages.** Most business entities need a list page, a form page, a detail page, and optionally a dashboard. Name them `{model}_list`, `{model}_form`, `{model}_detail`, `{model}_dashboard`.

2. **Use tabs for status filtering.** On list pages, add a `tabs` block with one tab per status value plus an "All" tab. This gives users instant status filtering without complex filter UI.

3. **Group form fields logically.** Create multiple `form-section` blocks grouped by domain (Basic Info, Financial, Dates, People). Use `colSpan` to create responsive layouts.

4. **Sub-tables for related data.** Use `foreignKey` mode when the child has a direct FK. Use `dataSource` mode when the relationship is complex or involves a junction table.

5. **Keep dashboards focused.** Use 3-4 stat cards at the top for key metrics, followed by 2-3 charts. Too many charts dilute the message.

6. **Grid layout for dashboards, stack for details.** Dashboards benefit from multi-column grid layouts. Detail pages work well with stack layout and tabbed sections.

7. **Always specify `schemaVersion: 4`.** This is the v4 flat format. V1 formats with `dslSchema` nesting are no longer supported.

---

## Saved Views

A **SavedView** (`ab_saved_view`) 保存某个页面上的用户视图状态，包括列、排序、筛选、密度和视图类型配置。

截至 2026-06-23 Personal-only release baseline，OSS 列表页 UI 只验收个人视图：

- SavedView 主入口固定在页面标题旁。
- 隐式默认视图是系统基线，不是用户手工创建的个人视图。UI 应显示为"默认视图"，不应显示为"我的 Default View *"，也不应出现在普通个人视图管理列表中。
- "我的记录"、"今日新建"、"本周修改" 等日常快捷筛选保留在列表工具栏，并且可以另存为个人视图。
- 个人视图支持新建、切换、保存当前变更、另存为新视图、重命名、复制、删除、设为默认。
- 用户必须能从 selector 返回"默认视图"。返回默认视图时，列表应清除 `view` 以及 `sort`、`keyword`、`preset`、`pageNum`、`filters`、`filter_*` 等临时 URL 状态，并恢复默认视图配置。
- 个人视图出现本地变更时，"放弃变更"必须重新应用当前个人视图已保存的配置，清除临时 URL 排序/筛选/搜索状态，且不能写回服务端配置。
- 当前可见配额是个人 `10/10`；达到上限后禁用新建个人视图，并提示用户清理已有视图。
- 高级视图类型必须经过 capability gate。`blocked` 视图不能保存；`degraded` 视图保存前必须解释限制。

后端模型和历史 API 契约仍可能保留 `team`、`global` scope，用于后续共享视图路线；但它们不属于当前 OSS Personal-only UI 验收面。重新引入团队/全员视图时，必须单独开 scope 文档、mockup 和 E2E 矩阵。

### 隐藏 SavedView 入口

当页面不是日常记录工作台时，例如平台元数据页面、或者不加载列表数据的页面，可以隐藏 SavedView UI。

当前支持两个开关：

```ts
const listExtensions = {
  hideSavedViews: true,
  hideQuickFilters: true,
};
```

```json
{
  "extension": {
    "hideSavedViews": true,
    "hideQuickFilters": true
  }
}
```

`hideSavedViews: true` 会隐藏标题旁 SavedView selector，并停止当前列表页的 SavedView 自动加载。由于 selector 不渲染，普通用户也不会看到新建、管理、配置入口。

`hideQuickFilters: true` 是独立开关。"我的记录"、"今日新建"、"本周修改" 等快捷筛选位于列表工具栏，不会因为 `hideSavedViews` 自动隐藏。

这是整页 SavedView 入口开关，不是只读模式。当前平台还没有独立支持"保留视图切换，但隐藏新建/管理/配置"；如果页面需要这种行为，应新增专门能力开关，不要复用 `hideSavedViews`。

### pageKey contract

Every `SavedView` row has a `page_key` column that **must exactly match a `page_key` in `ab_page_schema`**.

- **Canonical format:** `{modelCode}_{kind}` — e.g. `crm_lead_list`, `crm_lead_form`, `crm_lead_detail`.
- **JSON filename:** `config/pages/{pageKey}.json` inside your plugin zip/directory.

**The backend enforces this at write time (since 2026-06-20):**

- `SavedViewService.create()` calls `pageSchemaMapper.selectAnyByPageKey(pageKey)` and throws  
  `[S-SAVED-VIEW] pageKey '<key>' does not exist in ab_page_schema …` if the page is not found.
- `PluginImportServiceImpl.importSavedViews()` skips the entry and adds a `[S-SAVED-VIEW]` warning  
  to the import result when the page is missing.

**Why strict?** The frontend `useSavedViews` hook performs a strict-equals match on `pageKey`.  
A `SavedView` with a dangling `pageKey` is silently invisible to every user on every page load —  
there is no graceful fallback. Rejecting at write time surfaces the misconfiguration immediately.

### Common trap: normalization

The backend does **not** normalize `pageKey` (no lowercase conversion, no slash→underscore).  
Whatever string you write is what `useSavedViews` matches. Make sure the `pageKey` in  
`savedViews/*.json` inside your plugin exactly matches the `pageKey` field in the corresponding  
`config/pages/{pageKey}.json` file.
