# Models & Fields

Models and fields are the foundation of every AuraBoot application. A model defines a business entity (like a Contract, Employee, or Task), and fields define the attributes of that entity. Together, they drive the database schema, API behavior, form rendering, and validation -- all from a single JSON definition.

## What is a Model

A **model** represents a business entity. When you define a model and publish it, the platform automatically:

1. Creates a PostgreSQL table named `mt_{model_code}` (the `mt_` prefix stands for "meta table")
2. Adds system columns: `id`, `pid` (public ID), `tenant_id`, `created_by`, `created_at`, `updated_by`, `updated_at`
3. Registers REST API endpoints for CRUD operations
4. Makes the model available for page rendering, command execution, and permission checks

### System columns

Every dynamic table includes these columns automatically. You do not define them as fields:

| Column | Type | Purpose |
|---|---|---|
| `id` | `BIGINT` | Internal primary key (auto-increment) |
| `pid` | `VARCHAR(32)` | Public ID (UUID-based, used in APIs and URLs) |
| `tenant_id` | `BIGINT` | Tenant isolation (auto-injected by the framework) |
| `created_by` | `VARCHAR(64)` | User who created the record |
| `created_at` | `TIMESTAMP` | Creation timestamp |
| `updated_by` | `VARCHAR(64)` | User who last updated the record |
| `updated_at` | `TIMESTAMP` | Last update timestamp |

> **Note:** Dynamic tables (`mt_*`) do not have a `deleted_flag` column. AuraBoot uses hard deletes for dynamic models.

## Model Configuration

Define models in `config/models.json`. Here is the complete schema:

```json
[
  {
    "code": "showcase_all_fields",
    "displayName:zh-CN": "全字段展示",
    "displayName:en": "All Field Types Showcase",
    "description": "Demonstrates all supported field types.",
    "modelType": "entity",
    "modelCategory": "entity",
    "extension": {
      "icon": "Star",
      "category": "showcase",
      "titleField": "sc_name",
      "subtitleField": "sc_code"
    }
  }
]
```

### Model properties

| Property | Type | Required | Description |
|---|---|---|---|
| `code` | `string` | Yes | Unique model identifier. Convention: `{plugin_prefix}_{entity}`. Becomes the table name as `mt_{code}`. |
| `displayName:zh-CN` | `string` | Yes | Chinese display name for the UI. |
| `displayName:en` | `string` | Yes | English display name for the UI. |
| `description` | `string` | No | Human-readable description of the model's purpose. |
| `modelType` | `string` | Yes | `"entity"` for standard business entities. |
| `modelCategory` | `string` | Yes | Controls system behavior. See table below. |
| `extension` | `object` | No | Additional configuration. |

### Model categories

| Category | Description | System Behavior |
|---|---|---|
| `entity` | Standard business entity (Contract, Task) | Basic CRUD, no special behavior |
| `document` | Document-style entity (Invoice, Order) | Auto-injects activity timeline in detail pages |
| `master` | Master data (Product, Customer) | Auto-injects activity timeline; typically long-lived records |
| `config` | Configuration data (Settings, Template) | Used for system configuration |
| `relation` | Join/bridge table (Many-to-many relations) | Lightweight, no standalone pages |

### Extension properties

| Property | Type | Description |
|---|---|---|
| `icon` | `string` | Icon name for sidebar menus and headers (e.g., `"Star"`, `"FileText"`) |
| `category` | `string` | Logical grouping for the model |
| `titleField` | `string` | Field code displayed as the record's title in lists and references |
| `subtitleField` | `string` | Field code displayed as a secondary label |

## Field Types Reference

AuraBoot supports a rich set of field types. Each field type determines the database column type, UI component, validation behavior, and API serialization.

### Quick reference table

| Data Type | DB Column | UI Component | Use Case |
|---|---|---|---|
| `string` | `VARCHAR(n)` | Text input | Names, codes, short text |
| `text` | `TEXT` | Textarea | Descriptions, long content |
| `integer` | `INTEGER` | Number input | Quantities, counts |
| `decimal` | `NUMERIC(p,s)` | Number input | Prices, amounts, percentages |
| `boolean` | `BOOLEAN` | Switch / Checkbox | Toggles, flags |
| `date` | `DATE` | Date picker | Dates without time |
| `datetime` | `TIMESTAMP` | DateTime picker | Timestamps with time |
| `enum` | `VARCHAR` | Select dropdown | Status, priority, category |
| `reference` | `VARCHAR(32)` | Reference picker | Foreign key to another model |
| `json` | `JSONB` | Varies | Attachments, structured data |

### Render components

Beyond the base data types, fields support specialized UI rendering through `extension.renderComponent`:

| Render Component | Base Type | UI Component | Use Case |
|---|---|---|---|
| `richtext` | `text` | Rich text editor (TipTap) | Formatted content with images |
| `fileattachment` | `json` | File upload widget | Documents, images |
| `progress` | `integer` | Progress bar | Task completion percentage |
| `rating` | `integer` | Star rating | Review scores (0-5) |
| `colorpicker` | `string` | Color swatch picker | Color labels, category colors |
| `multiselect` | `string` | Multi-select tags | Tags, labels (comma-separated) |
| `moneyinput` | `decimal` | Currency input | Financial amounts |
| `cascadeselect` | `string` | Cascading dropdown | Hierarchical categories |
| `treeselect` | `string` | Tree selector | Department, org hierarchy |
| `userselect` | `string` | User picker | Assignee, owner |
| `memberpicker` | `string` | Multi-user picker | Team members |
| `organizationselect` | `string` | Org unit picker | Department assignment |
| `coordinatespicker` | `json` | Map coordinate picker | Geographic locations |
| `timepicker` | `string` | Time picker | Time-of-day values |
| `daterange` | `string` | Date range picker | Start-end date pairs |
| `timerangepicker` | `string` | Time range picker | Working hours, shifts |
| `aifield` | `text` | AI-generated content | Summaries, suggestions |

## Field Type Details

### STRING

Stores short text values. Maps to `VARCHAR(n)` in PostgreSQL.

```json
{
  "code": "sc_name",
  "displayName:en": "Name",
  "dataType": "string",
  "constraints": {
    "required": true,
    "maxLength": 200
  },
  "feature": {
    "searchable": true,
    "sortable": true
  }
}
```

**UI:** Renders as a standard text input. When `feature.searchable` is true, the field appears in the global search and filter panels.

**Constraints:** `required`, `maxLength`, `minLength`, `pattern` (regex).

### TEXT

Stores long-form text. Maps to `TEXT` in PostgreSQL (no length limit).

```json
{
  "code": "sc_description",
  "displayName:en": "Description",
  "dataType": "text",
  "feature": {
    "searchable": true
  }
}
```

**UI:** Renders as a multi-line textarea. Use `extension.renderComponent: "richtext"` for a rich text editor with formatting, images, and tables.

### INTEGER

Stores whole numbers. Maps to `INTEGER` in PostgreSQL.

```json
{
  "code": "sc_quantity",
  "displayName:en": "Quantity",
  "dataType": "integer",
  "constraints": {
    "min": 0,
    "max": 99999
  }
}
```

**UI:** Renders as a number input with stepper controls. Can be enhanced with render components like `progress` (0-100 progress bar) or `rating` (star rating).

**Constraints:** `min`, `max`, `required`.

### DECIMAL

Stores precise decimal numbers. Maps to `NUMERIC(precision, scale)` in PostgreSQL.

```json
{
  "code": "sc_price",
  "displayName:en": "Price",
  "dataType": "decimal",
  "constraints": {
    "min": 0
  },
  "extension": {
    "precision": 14,
    "scale": 2
  }
}
```

**UI:** Renders as a number input. Use `extension.renderComponent: "moneyinput"` for a currency-formatted input with symbol prefix.

**Extension:** `precision` (total digits, default 14), `scale` (decimal places, default 2).

**Money input example:**

```json
{
  "code": "sc_budget",
  "displayName:en": "Budget",
  "dataType": "decimal",
  "constraints": { "min": 0, "max": 99999999.99 },
  "extension": {
    "renderComponent": "moneyinput",
    "precision": 2,
    "currencySymbol": "$"
  }
}
```

### BOOLEAN

Stores true/false values. Maps to `BOOLEAN` in PostgreSQL.

```json
{
  "code": "sc_is_active",
  "displayName:en": "Active",
  "dataType": "boolean",
  "defaultValue": "true"
}
```

**UI:** Renders as a toggle switch in forms, and as a colored badge (Yes/No) in list views.

### DATE

Stores date values without time. Maps to `DATE` in PostgreSQL.

```json
{
  "code": "sc_start_date",
  "displayName:en": "Start Date",
  "dataType": "date",
  "feature": {
    "sortable": true
  }
}
```

**UI:** Renders as a date picker. The date range variant uses `extension.renderComponent: "daterange"`.

**Date range example:**

```json
{
  "code": "sc_date_range",
  "displayName:en": "Date Range",
  "dataType": "string",
  "constraints": { "maxLength": 100 },
  "extension": {
    "renderComponent": "daterange",
    "clearable": true
  }
}
```

### DATETIME

Stores timestamps with time. Maps to `TIMESTAMP` in PostgreSQL.

```json
{
  "code": "sc_created_at",
  "displayName:en": "Created At",
  "dataType": "datetime",
  "feature": { "sortable": true },
  "extension": { "readOnly": true }
}
```

**UI:** Renders as a date-time picker. Setting `extension.readOnly: true` makes the field display-only (commonly used for system timestamps).

### ENUM

Stores a value from a predefined dictionary. Maps to `VARCHAR` in PostgreSQL; the actual value is a dictionary item code (e.g., `"draft"`, `"active"`).

```json
{
  "code": "sc_status",
  "displayName:en": "Status",
  "dataType": "enum",
  "dictCode": "sc_status_dict",
  "defaultValue": "draft",
  "feature": {
    "searchable": true,
    "sortable": true
  }
}
```

**UI:** Renders as a dropdown select. The options come from the referenced dictionary, including labels, colors, and sort order. In list views, enum values display as colored badges.

**Dictionary definition** (in `dicts.json`):

```json
{
  "code": "sc_status_dict",
  "name": "Showcase Status",
  "dictType": "static",
  "items": [
    {
      "value": "draft",
      "label": "Draft",
      "label:zh-CN": "草稿",
      "sortNo": 10,
      "status": "enabled",
      "extension": { "color": "gray" }
    },
    {
      "value": "active",
      "label": "Active",
      "label:zh-CN": "启用",
      "sortNo": 20,
      "status": "enabled",
      "extension": { "color": "green" }
    }
  ]
}
```

**Tree dictionaries** support hierarchical options using `parentValue`:

```json
{
  "code": "sc_cascade_category_dict",
  "dictType": "tree",
  "items": [
    { "value": "electronics", "label": "Electronics", "sortNo": 10 },
    { "value": "electronics_phone", "label": "Phone", "parentValue": "electronics", "sortNo": 1 },
    { "value": "electronics_phone_smart", "label": "Smartphone", "parentValue": "electronics_phone", "sortNo": 1 }
  ]
}
```

### REFERENCE (Foreign Key)

Creates a relationship to another model. Stores the target record's `pid` as a `VARCHAR(32)` column.

```json
{
  "code": "org_emp_dept_id",
  "displayName:en": "Department",
  "dataType": "reference",
  "constraints": { "required": true },
  "refTarget": {
    "targetModel": "org_department",
    "targetField": "org_dept_name"
  },
  "extension": {
    "placeholder:en": "Select a department"
  }
}
```

**Properties:**

| Property | Description |
|---|---|
| `refTarget.targetModel` | The model code of the referenced entity |
| `refTarget.targetField` | The field to display when rendering the reference (e.g., the name field) |
| `referenceModelCode` | Alternative shorthand (just the target model code, without display field config) |

**UI:** Renders as a reference picker -- a searchable dropdown that queries the target model. The displayed value comes from `targetField`.

**Alternative format:**

```json
{
  "code": "grw_en_course_id",
  "displayName:en": "Course",
  "dataType": "reference",
  "referenceModelCode": "grw_course",
  "constraints": { "required": true }
}
```

### JSON

Stores structured data as PostgreSQL `JSONB`. Use this for attachments, coordinates, or any complex nested data.

```json
{
  "code": "sc_attachment",
  "displayName:en": "Attachment",
  "dataType": "json",
  "extension": {
    "renderComponent": "fileattachment"
  }
}
```

**UI:** The default rendering shows raw JSON. Use render components to provide specialized UIs:
- `fileattachment` -- file upload/download widget
- `coordinatespicker` -- map with lat/lng selection

### TAGS (via multiselect)

Tags are implemented as a `string` field with `renderComponent: "multiselect"`. Values are stored as comma-separated strings.

```json
{
  "code": "sc_tags",
  "displayName:en": "Tags",
  "dataType": "string",
  "constraints": { "maxLength": 500 },
  "extension": {
    "renderComponent": "multiselect"
  }
}
```

**UI:** Renders as a tag input where users can type and select multiple values.

### PROGRESS

A progress indicator field. Implemented as an `integer` field with `renderComponent: "progress"`.

```json
{
  "code": "sc_progress",
  "displayName:en": "Progress",
  "dataType": "integer",
  "constraints": { "min": 0, "max": 100 },
  "extension": {
    "renderComponent": "progress"
  }
}
```

**UI:** Renders as a progress bar (0-100%) in both forms and list views.

### RATING

A star rating field. Implemented as an `integer` field with `renderComponent: "rating"`.

```json
{
  "code": "sc_rating",
  "displayName:en": "Rating",
  "dataType": "integer",
  "constraints": { "min": 0, "max": 5 },
  "extension": {
    "renderComponent": "rating"
  }
}
```

**UI:** Renders as clickable stars (1-5) in forms, and as filled stars in list views.

### COLOR

A color value field. Implemented as a `string` field with `renderComponent: "colorpicker"`.

```json
{
  "code": "sc_color",
  "displayName:en": "Color",
  "dataType": "string",
  "constraints": { "maxLength": 20 },
  "extension": {
    "renderComponent": "colorpicker"
  }
}
```

**UI:** Renders as a color swatch picker. Stores hex color values (e.g., `"#52c41a"`).

### RICHTEXT

Rich text content with formatting. Implemented as a `text` field with `renderComponent: "richtext"`.

```json
{
  "code": "sc_richtext_content",
  "displayName:en": "Rich Text Content",
  "dataType": "text",
  "extension": {
    "renderComponent": "richtext"
  }
}
```

**UI:** Renders a TipTap-based rich text editor supporting bold, italic, headings, lists, images, tables, and code blocks. Content is stored as HTML.

### AI Field

An AI-powered auto-generated field. Implemented as a `text` field with `renderComponent: "aifield"`.

```json
{
  "code": "sc_ai_summary",
  "displayName:en": "AI Summary",
  "dataType": "text",
  "extension": {
    "renderComponent": "aifield",
    "operation": "summarize",
    "sourceFields": ["sc_name", "sc_description", "sc_category"],
    "maxTokens": 300
  }
}
```

**UI:** Renders as a text area with a "Generate" button. When clicked, the platform sends the `sourceFields` values to the configured LLM and populates the field with the result.

**Extension properties:**

| Property | Description |
|---|---|
| `operation` | AI operation type: `"summarize"`, `"translate"`, `"extract"` |
| `sourceFields` | Array of field codes whose values are sent as context |
| `maxTokens` | Maximum token limit for the AI response |

### Specialized Selectors

AuraBoot includes several domain-specific selector components:

**User Select** -- pick a system user:

```json
{
  "code": "sc_assignee",
  "displayName:en": "Assignee",
  "dataType": "string",
  "extension": {
    "renderComponent": "userselect",
    "allowClear": true
  }
}
```

**Member Picker** -- pick multiple team members:

```json
{
  "code": "sc_team_members",
  "displayName:en": "Team Members",
  "dataType": "string",
  "constraints": { "maxLength": 2000 },
  "extension": {
    "renderComponent": "memberpicker",
    "multiple": true
  }
}
```

**Organization Select** -- pick a department/org unit:

```json
{
  "code": "sc_department",
  "displayName:en": "Department",
  "dataType": "string",
  "extension": {
    "renderComponent": "organizationselect",
    "showHierarchy": true,
    "allowClear": true
  }
}
```

**Cascade Select** -- hierarchical multi-level dropdown:

```json
{
  "code": "sc_cascade_category",
  "displayName:en": "Cascade Category",
  "dataType": "string",
  "constraints": { "maxLength": 500 },
  "extension": {
    "renderComponent": "cascadeselect",
    "levels": 3,
    "levelLabels": ["Level 1", "Level 2", "Level 3"],
    "dictCode": "sc_cascade_category_dict"
  }
}
```

**Tree Select** -- tree structure dropdown:

```json
{
  "code": "sc_tree_node",
  "displayName:en": "Tree Select",
  "dataType": "string",
  "constraints": { "maxLength": 200 },
  "extension": {
    "renderComponent": "treeselect",
    "searchable": true,
    "clearable": true,
    "dictCode": "sc_tree_dept_dict"
  }
}
```

### Time Fields

**Time Picker** -- time-of-day selection:

```json
{
  "code": "sc_time_slot",
  "displayName:en": "Time Slot",
  "dataType": "string",
  "constraints": { "maxLength": 10 },
  "extension": {
    "renderComponent": "timepicker",
    "format": "HH:mm",
    "clearable": true
  }
}
```

**Time Range Picker** -- start and end time:

```json
{
  "code": "sc_working_hours",
  "displayName:en": "Working Hours",
  "dataType": "string",
  "constraints": { "maxLength": 50 },
  "extension": {
    "renderComponent": "timerangepicker",
    "format": "24h",
    "minuteStep": 15
  }
}
```

## Field Extensions

The `extension` object on a field provides additional configuration beyond the base data type:

| Extension Property | Type | Description |
|---|---|---|
| `renderComponent` | `string` | Override the default UI component (see render components table above) |
| `readOnly` | `boolean` | Field is display-only, cannot be edited |
| `precision` | `integer` | For `decimal` fields: total digits (default 14) |
| `scale` | `integer` | For `decimal` fields: decimal places (default 2) |
| `currencySymbol` | `string` | For `moneyinput`: currency symbol prefix |
| `placeholder` | `string` | Input placeholder text |
| `placeholder:zh-CN` | `string` | Localized placeholder |
| `multiple` | `boolean` | Allow selecting multiple values |
| `searchable` | `boolean` | Enable search within the selector |
| `clearable` | `boolean` | Allow clearing the selection |
| `allowClear` | `boolean` | Alias for `clearable` |
| `showHierarchy` | `boolean` | Show hierarchical path in org selectors |
| `format` | `string` | Display format (e.g., `"HH:mm"` for time pickers) |
| `minuteStep` | `integer` | Minute increment for time pickers |
| `levels` | `integer` | Number of cascade levels |
| `levelLabels` | `string[]` | Labels for each cascade level |
| `dictCode` | `string` | Dictionary code for cascade/tree selectors |
| `operation` | `string` | AI field operation type |
| `sourceFields` | `string[]` | AI field source fields for context |
| `maxTokens` | `integer` | AI field max response tokens |
| `defaultZoom` | `integer` | Default zoom level for map pickers |

## Field Constraints

Constraints enforce data integrity at both the API and UI levels:

```json
{
  "constraints": {
    "required": true,
    "maxLength": 200,
    "minLength": 1,
    "min": 0,
    "max": 99999,
    "pattern": "^[A-Z]{2}-\\d{6}$"
  }
}
```

| Constraint | Applies To | Description |
|---|---|---|
| `required` | All types | Field must have a non-null, non-empty value |
| `maxLength` | `string`, `text` | Maximum character count |
| `minLength` | `string`, `text` | Minimum character count |
| `min` | `integer`, `decimal` | Minimum numeric value |
| `max` | `integer`, `decimal` | Maximum numeric value |
| `pattern` | `string` | Regular expression the value must match |

Constraints are validated in two places:
1. **Frontend:** Form fields show validation errors inline before submission
2. **Backend:** The command pipeline's `SCHEMA_VALIDATE` stage rejects invalid data with error messages

## Field Features

The `feature` object controls search and sort behavior:

```json
{
  "feature": {
    "searchable": true,
    "sortable": true
  }
}
```

| Feature | Description |
|---|---|
| `searchable` | Field appears in filter panels and is included in keyword search |
| `sortable` | Column header is clickable for sort-by in list views |

## Field Bindings

Bindings connect fields to models and control their presentation order, visibility, and editability. Define them in `config/bindings/{model_code}.json`:

```json
[
  {
    "modelCode": "showcase_all_fields",
    "fieldCode": "sc_name",
    "sequence": 1,
    "required": true,
    "visible": true,
    "editable": true
  },
  {
    "modelCode": "showcase_all_fields",
    "fieldCode": "sc_code",
    "sequence": 2,
    "required": true,
    "visible": true,
    "editable": false
  }
]
```

| Property | Type | Description |
|---|---|---|
| `modelCode` | `string` | The model this binding belongs to |
| `fieldCode` | `string` | The field being bound |
| `sequence` | `integer` | Display order (lower = first) |
| `required` | `boolean` | Override the field's required constraint for this model |
| `visible` | `boolean` | Whether the field appears in default views |
| `editable` | `boolean` | Whether the field can be edited (set `false` for auto-generated fields like `sc_code`) |

## Relations

### One-to-Many (REFERENCE)

Use a `reference` field on the child model pointing to the parent:

```json
// In the child model's fields (e.g., order_line)
{
  "code": "ol_order_id",
  "dataType": "reference",
  "referenceModelCode": "sales_order",
  "constraints": { "required": true }
}
```

The parent model's detail page can display child records using a `sub-table` block:

```json
{
  "blockType": "sub-table",
  "subTable": {
    "modelCode": "order_line",
    "foreignKey": "ol_order_id"
  }
}
```

### Self-Referential

A model can reference itself for hierarchical structures (e.g., categories with sub-categories, tasks with sub-tasks):

```json
{
  "code": "cat_parent_id",
  "dataType": "reference",
  "referenceModelCode": "category",
  "constraints": { "required": false }
}
```

### Many-to-Many

Many-to-many relationships use a bridge model (a model with `modelCategory: "relation"`) containing two reference fields:

```json
// Bridge model: project_member
{
  "code": "project_member",
  "modelCategory": "relation"
}

// Fields on the bridge model:
{ "code": "pm_project_id", "dataType": "reference", "referenceModelCode": "project" }
{ "code": "pm_user_id", "dataType": "reference", "referenceModelCode": "ab_user" }
```

Display the related records on a detail page using a `sub-table` block with `resolveVia`:

```json
{
  "blockType": "sub-table",
  "subTable": {
    "modelCode": "ab_user",
    "resolveVia": {
      "bridgeModel": "project_member",
      "bridgeParentField": "pm_project_id",
      "bridgeChildField": "pm_user_id"
    }
  }
}
```

## Computed Fields

Commands can define computed fields that are calculated during execution. These are defined in the command's `executionConfig`, not on the field definition:

```json
{
  "code": "ord:update_order",
  "type": "update",
  "executionConfig": {
    "computedFields": {
      "total_amount": "qty * unit_price",
      "avg_price": "sales_qty > 0 ? sales_amount / sales_qty : 0"
    }
  }
}
```

Computed fields use a simple expression syntax with arithmetic operators (`+`, `-`, `*`, `/`), ternary expressions, and field references by code.

## Auto-Set Fields

Commands can automatically set field values during execution:

```json
{
  "autoSetFields": {
    "sc_code": {
      "strategy": "auto_generate",
      "pattern": "SC-{yyyyMMdd}-{seq}"
    },
    "sc_created_at": {
      "strategy": "current_datetime"
    },
    "sc_is_active": {
      "strategy": "fixed_value",
      "value": true
    },
    "sc_status": {
      "strategy": "fixed_value",
      "value": "draft"
    }
  }
}
```

| Strategy | Description | Config |
|---|---|---|
| `auto_generate` | Generate a sequential code | `pattern`: template with `{yyyyMMdd}` (date), `{seq}` (auto-increment) |
| `current_datetime` | Set to the current timestamp | None |
| `current_user` | Set to the current user's ID | None |
| `fixed_value` | Set to a constant value | `value`: the constant |

## Best Practices

### Naming conventions

- **Model codes:** Use `{plugin_prefix}_{entity}` format. Keep it short but descriptive: `crm_lead`, `pm_task`, `hr_employee`.
- **Field codes:** Use `{ns}_{field}` format. The namespace prefix prevents collisions: `crm_lead_name`, not just `name`.
- **Dict codes:** Suffix with `_dict`: `crm_stage_dict`, `pm_priority_dict`.

### Choosing the right field type

| Scenario | Recommended Type |
|---|---|
| Short text (< 500 chars) | `string` |
| Long text, descriptions | `text` |
| Formatted content (HTML) | `text` + `renderComponent: "richtext"` |
| Whole numbers | `integer` |
| Currency, prices | `decimal` + `renderComponent: "moneyinput"` |
| Percentages, progress | `integer` + `renderComponent: "progress"` |
| Yes/No toggles | `boolean` |
| Dates | `date` |
| Timestamps | `datetime` |
| Fixed set of options | `enum` + dictionary |
| Foreign key to another entity | `reference` |
| File uploads | `json` + `renderComponent: "fileattachment"` |
| Tags / multi-select labels | `string` + `renderComponent: "multiselect"` |
| Star ratings (1-5) | `integer` + `renderComponent: "rating"` |
| Color codes | `string` + `renderComponent: "colorpicker"` |
| People assignment | `string` + `renderComponent: "userselect"` |
| Department/org | `string` + `renderComponent: "organizationselect"` |
| Hierarchical categories | `string` + `renderComponent: "cascadeselect"` |
| Geographic coordinates | `json` + `renderComponent: "coordinatespicker"` |

### Indexing tips

- Set `feature.searchable: true` on fields users frequently filter by (status, name, date).
- Set `feature.sortable: true` on fields users frequently sort by (date, amount, priority).
- Reference fields are automatically indexed.
- For high-cardinality text searches, use `searchable` rather than custom indexes -- the platform handles the query optimization.

### When to use extension vs constraints

- **Constraints** enforce data integrity (required, min/max, pattern). They generate validation errors.
- **Extensions** control presentation (render component, placeholder, format). They change how data is displayed and input.

Keep constraints minimal and meaningful. Over-constraining fields makes the system rigid and frustrating for users.

## Next Steps

- [DSL Engine](./dsl-engine.md) -- How DSL resources are resolved at runtime
- [Commands](./commands.md) -- Configure CRUD operations and state machines
- [Pages & Layouts](./pages-and-layouts.md) -- Build list, form, detail, and dashboard pages
