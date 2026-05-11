# Config-Only Plugin Tutorial

This is a step-by-step tutorial for building a small AuraBoot plugin using JSON configuration files. By the end, you will have a working **Task Tracker** with:

- A data model with 10+ fields
- CRUD commands (create, update, delete)
- A state machine workflow (draft -> in_progress -> completed -> archived)
- List, form, and detail pages
- Menus, permissions, and roles
- i18n translations (English and Chinese)
- Dictionaries for enum fields

No Java or React code required.

---

## Prerequisites

- AuraBoot platform running locally (backend on port 6443, frontend on port 5173)
- Aura CLI installed (`aura status` returns healthy)
- Logged in (`aura login`)

---

## Step 1: Create Directory Structure

Create the plugin directory under `plugins/`:

```bash
mkdir -p plugins/task-tracker/config/{fields,bindings,commands,pages}
```

Your directory should look like:

```
plugins/task-tracker/
+-- plugin.json              # Step 2
+-- config/
    +-- models.json          # Step 3
    +-- fields/
    |   +-- tt_task.json     # Step 4
    +-- bindings/
    |   +-- tt_task.json     # Step 5
    +-- commands/
    |   +-- tt_task.json     # Step 5
    +-- pages/
    |   +-- tt_task_list.json    # Step 6
    |   +-- tt_task_form.json    # Step 6
    |   +-- tt_task_detail.json  # Step 6
    +-- dicts.json           # Step 9
    +-- permissions.json     # Step 7
    +-- roles.json           # Step 7
    +-- menus.json           # Step 7
    +-- i18n.json            # Step 8
    +-- default-bootstrap.json   # Step 10
```

---

## Step 2: Write plugin.json Manifest

The manifest declares your plugin's identity, dependencies, and where to find each resource file.

**`plugins/task-tracker/plugin.json`**:

```json
{
  "pluginId": "com.example.task-tracker",
  "namespace": "tt",
  "version": "1.0.0",
  "dslVersion": 1,
  "pluginType": "config",
  "displayName": "Task Tracker",
  "displayName:zh-CN": "Task Tracker",
  "displayName:en": "Task Tracker",
  "description": "A simple task tracking plugin with status workflow. Manage tasks from creation through completion and archival.",
  "author": "AuraBoot Community",
  "homepage": "https://auraboot.com/plugins/task-tracker",
  "minPlatformVersion": "1.0.0",
  "dependencies": [],
  "provides": [
    {"type": "model", "code": "tt_task"},
    {"type": "command", "code": "tt:create_task"},
    {"type": "command", "code": "tt:update_task"},
    {"type": "command", "code": "tt:delete_task"},
    {"type": "command", "code": "tt:start_task"},
    {"type": "command", "code": "tt:complete_task"},
    {"type": "command", "code": "tt:archive_task"}
  ],
  "requires": [],
  "resourceDirs": {
    "models": "config/models.json",
    "fields": "config/fields",
    "bindings": "config/bindings",
    "commands": "config/commands",
    "pages": "config/pages",
    "dicts": "config/dicts.json",
    "permissions": "config/permissions.json",
    "roles": "config/roles.json",
    "menus": "config/menus.json",
    "i18n": "config/i18n.json"
  },
  "importOptions": {
    "conflictStrategy": "overwrite",
    "validateReferences": true,
    "autoPublishModels": true,
    "autoPublishFields": true,
    "autoPublishCommands": true,
    "autoPublishPages": true,
    "autoDeployProcesses": false,
    "createResourcePermissions": true
  }
}
```

**Key fields explained:**

| Field | Value | Why |
|-------|-------|-----|
| `pluginId` | `com.example.task-tracker` | Reverse domain name, globally unique |
| `namespace` | `tt` | Short prefix for all resources. Models become `tt_task`, commands become `tt:create_task` |
| `pluginType` | `config` | No backend JAR or frontend code |
| `resourceDirs` | (object) | Maps resource types to file paths, relative to plugin root |
| `importOptions.conflictStrategy` | `overwrite` | Re-importing overwrites existing resources |
| `importOptions.autoPublishModels` | `true` | Models are published (not left in draft) after import |

---

## Step 3: Define Models (models.json)

Models describe your data entities. Each model becomes a database table.

**`plugins/task-tracker/config/models.json`**:

```json
[
  {
    "code": "tt_task",
    "displayName:zh-CN": "Task",
    "displayName:en": "Task",
    "description": "A trackable work item with status workflow",
    "modelType": "entity",
    "modelCategory": "entity",
    "extension": {
      "icon": "CheckSquare",
      "category": "task-tracker",
      "titleField": "tt_title",
      "subtitleField": "tt_code"
    }
  }
]
```

**Field reference:**

| Field | Required | Description |
|-------|----------|-------------|
| `code` | Yes | Unique model code. Must start with namespace prefix (`tt_`) |
| `displayName:en` | Yes | English display name |
| `displayName:zh-CN` | No | Chinese display name |
| `modelType` | Yes | Always `"entity"` for business models |
| `extension.icon` | No | Icon name from the icon library |
| `extension.titleField` | No | Field used as the record title in lists/cards |
| `extension.subtitleField` | No | Field used as subtitle |

---

## Step 4: Define Fields (fields/*.json)

Fields define the columns of your model's database table. Create one JSON file per model.

**`plugins/task-tracker/config/fields/tt_task.json`**:

```json
[
  {
    "code": "tt_title",
    "displayName:zh-CN": "Title",
    "displayName:en": "Title",
    "dataType": "string",
    "constraints": { "required": true, "maxLength": 200 },
    "feature": { "searchable": true, "sortable": true }
  },
  {
    "code": "tt_code",
    "displayName:zh-CN": "Code",
    "displayName:en": "Code",
    "dataType": "string",
    "constraints": { "required": true, "maxLength": 50 },
    "feature": { "searchable": true, "sortable": true }
  },
  {
    "code": "tt_description",
    "displayName:zh-CN": "Description",
    "displayName:en": "Description",
    "dataType": "text",
    "feature": { "searchable": true }
  },
  {
    "code": "tt_status",
    "displayName:zh-CN": "Status",
    "displayName:en": "Status",
    "dataType": "enum",
    "dictCode": "tt_status_dict",
    "defaultValue": "draft",
    "feature": { "searchable": true, "sortable": true }
  },
  {
    "code": "tt_priority",
    "displayName:zh-CN": "Priority",
    "displayName:en": "Priority",
    "dataType": "enum",
    "dictCode": "tt_priority_dict",
    "feature": { "searchable": true }
  },
  {
    "code": "tt_assignee",
    "displayName:zh-CN": "Assignee",
    "displayName:en": "Assignee",
    "dataType": "string",
    "constraints": { "maxLength": 100 },
    "feature": { "searchable": true }
  },
  {
    "code": "tt_due_date",
    "displayName:zh-CN": "Due Date",
    "displayName:en": "Due Date",
    "dataType": "date",
    "feature": { "sortable": true }
  },
  {
    "code": "tt_estimated_hours",
    "displayName:zh-CN": "Estimated Hours",
    "displayName:en": "Estimated Hours",
    "dataType": "decimal",
    "constraints": { "min": 0 },
    "extension": { "precision": 10, "scale": 1 }
  },
  {
    "code": "tt_is_urgent",
    "displayName:zh-CN": "Urgent",
    "displayName:en": "Urgent",
    "dataType": "boolean",
    "defaultValue": "false"
  },
  {
    "code": "tt_created_at",
    "displayName:zh-CN": "Created At",
    "displayName:en": "Created At",
    "dataType": "datetime",
    "feature": { "sortable": true },
    "extension": { "readOnly": true }
  },
  {
    "code": "tt_completed_at",
    "displayName:zh-CN": "Completed At",
    "displayName:en": "Completed At",
    "dataType": "datetime",
    "feature": { "sortable": true },
    "extension": { "readOnly": true }
  },
  {
    "code": "tt_tags",
    "displayName:zh-CN": "Tags",
    "displayName:en": "Tags",
    "dataType": "string",
    "constraints": { "maxLength": 500 },
    "extension": { "renderComponent": "multiselect" }
  }
]
```

**Supported data types:**

| Data Type | Description | DB Column Type |
|-----------|-------------|---------------|
| `string` | Short text (up to maxLength) | VARCHAR |
| `text` | Long text, multiline | TEXT |
| `integer` | Whole number | BIGINT |
| `decimal` | Decimal number (precision, scale) | DECIMAL |
| `boolean` | True/false | BOOLEAN |
| `date` | Date without time | DATE |
| `datetime` | Date with time | TIMESTAMP |
| `enum` | Enumerated value (backed by dictionary) | VARCHAR |
| `reference` | Foreign key to another model | VARCHAR |
| `json` | Arbitrary JSON data | JSONB |
| `computed` | SpEL expression computed field | VARCHAR |

**Field options:**

| Option | Description |
|--------|-------------|
| `constraints.required` | Field is required |
| `constraints.maxLength` | Max string length |
| `constraints.min` / `constraints.max` | Numeric range |
| `feature.searchable` | Included in keyword search |
| `feature.sortable` | Column can be sorted |
| `dictCode` | Links enum field to a dictionary |
| `defaultValue` | Default value for new records |
| `extension.readOnly` | Field cannot be edited by user |
| `extension.precision` / `extension.scale` | Decimal precision |
| `extension.renderComponent` | Custom render component (`multiselect`, `richtext`, etc.) |

---

## Step 5: Define Commands and Bindings

### Commands (commands/*.json)

Commands define the operations users can perform on records. AuraBoot supports several command types:

| Type | Purpose |
|------|---------|
| `create` | Create a new record |
| `update` | Edit an existing record |
| `delete` | Delete a record |
| `state_transition` | Change the status field |
| `query` | Read-only query |

**`plugins/task-tracker/config/commands/tt_task.json`**:

```json
[
  {
    "code": "tt:create_task",
    "displayName:zh-CN": "Create Task",
    "displayName:en": "Create Task",
    "type": "create",
    "modelCode": "tt_task",
    "inputFields": [
      "tt_title",
      "tt_description",
      "tt_priority",
      "tt_assignee",
      "tt_due_date",
      "tt_estimated_hours",
      "tt_is_urgent",
      "tt_tags"
    ],
    "autoSetFields": {
      "tt_code": {
        "strategy": "auto_generate",
        "pattern": "TT-{yyyyMMdd}-{seq}"
      },
      "tt_created_at": {
        "strategy": "current_datetime"
      },
      "tt_status": {
        "strategy": "fixed_value",
        "value": "draft"
      }
    },
    "validation": {
      "rules": [
        {
          "type": "unique_composite",
          "fields": ["tt_title"],
          "message:en": "A task with this title already exists",
          "message:zh-CN": "A task with this title already exists"
        }
      ]
    },
    "permissions": ["tt.task.manage"],
    "agent_hint": "Create a new task. Auto-generates code and sets initial status to draft.",
    "cmd_risk_level": "L1"
  },
  {
    "code": "tt:update_task",
    "displayName:zh-CN": "Update Task",
    "displayName:en": "Update Task",
    "type": "update",
    "modelCode": "tt_task",
    "inputFields": [
      "tt_title",
      "tt_description",
      "tt_priority",
      "tt_assignee",
      "tt_due_date",
      "tt_estimated_hours",
      "tt_is_urgent",
      "tt_tags"
    ],
    "validation": {
      "rules": [
        {
          "type": "unique_composite",
          "fields": ["tt_title"],
          "message:en": "A task with this title already exists",
          "message:zh-CN": "A task with this title already exists"
        }
      ]
    },
    "permissions": ["tt.task.manage"],
    "agent_hint": "Update an existing task. All user-editable fields can be modified.",
    "cmd_risk_level": "L1"
  },
  {
    "code": "tt:delete_task",
    "displayName:zh-CN": "Delete Task",
    "displayName:en": "Delete Task",
    "type": "delete",
    "modelCode": "tt_task",
    "preconditions": [
      {
        "field": "tt_status",
        "operator": "IN",
        "value": ["draft", "archived"]
      }
    ],
    "extension": {
      "confirmMessage:en": "Are you sure you want to delete this task?",
      "confirmMessage:zh-CN": "Are you sure you want to delete this task?"
    },
    "permissions": ["tt.task.manage"],
    "agent_hint": "Delete a task. Only allowed when status is draft or archived.",
    "cmd_risk_level": "L4"
  },
  {
    "code": "tt:start_task",
    "displayName:zh-CN": "Start",
    "displayName:en": "Start",
    "type": "state_transition",
    "modelCode": "tt_task",
    "stateField": "tt_status",
    "fromStates": ["draft"],
    "toState": "in_progress",
    "permissions": ["tt.task.manage"],
    "agent_hint": "Move task from draft to in_progress.",
    "cmd_risk_level": "L1"
  },
  {
    "code": "tt:complete_task",
    "displayName:zh-CN": "Complete",
    "displayName:en": "Complete",
    "type": "state_transition",
    "modelCode": "tt_task",
    "stateField": "tt_status",
    "fromStates": ["in_progress"],
    "toState": "completed",
    "autoSetFields": {
      "tt_completed_at": {
        "strategy": "current_datetime"
      }
    },
    "permissions": ["tt.task.manage"],
    "agent_hint": "Mark task as completed. Auto-sets completed_at timestamp.",
    "cmd_risk_level": "L1"
  },
  {
    "code": "tt:archive_task",
    "displayName:zh-CN": "Archive",
    "displayName:en": "Archive",
    "type": "state_transition",
    "modelCode": "tt_task",
    "stateField": "tt_status",
    "fromStates": ["completed"],
    "toState": "archived",
    "extension": {
      "confirmMessage:en": "Archive this task? It will become read-only.",
      "confirmMessage:zh-CN": "Archive this task? It will become read-only."
    },
    "permissions": ["tt.task.manage"],
    "agent_hint": "Archive a completed task. Makes it read-only.",
    "cmd_risk_level": "L2"
  },
  {
    "code": "tt:detail_task",
    "displayName:zh-CN": "View Task",
    "displayName:en": "View Task",
    "type": "query",
    "modelCode": "tt_task",
    "permissions": ["tt.task.read"],
    "agent_hint": "View task details.",
    "cmd_risk_level": "L0"
  },
  {
    "code": "tt:list_task",
    "displayName:zh-CN": "Task List",
    "displayName:en": "Task List",
    "type": "query",
    "modelCode": "tt_task",
    "permissions": ["tt.task.read"],
    "agent_hint": "Query task list.",
    "cmd_risk_level": "L0"
  }
]
```

**State machine visualization:**

```
  [draft] --start--> [in_progress] --complete--> [completed] --archive--> [archived]
```

**Command fields explained:**

| Field | Description |
|-------|-------------|
| `code` | Must follow `{namespace}:{action}` format |
| `type` | One of: `create`, `update`, `delete`, `state_transition`, `query` |
| `modelCode` | The model this command operates on |
| `inputFields` | Fields the user can fill in (for create/update) |
| `autoSetFields` | Fields automatically set by the system |
| `stateField` | The enum field used as the state machine field (for state_transition) |
| `fromStates` | Valid current states to execute this transition |
| `toState` | Target state after transition |
| `preconditions` | Conditions that must be true before the command can execute |
| `validation.rules` | Additional validation rules |
| `permissions` | Permission codes required to execute |
| `cmd_risk_level` | L0 (read-only) to L4 (destructive) |

**Auto-set strategies:**

| Strategy | Description | Example |
|----------|-------------|---------|
| `auto_generate` | Generate value from pattern | `TT-{yyyyMMdd}-{seq}` |
| `current_datetime` | Current timestamp | -- |
| `current_date` | Current date | -- |
| `current_user` | Current user ID | -- |
| `fixed_value` | Constant value | `"draft"` |

### Bindings (bindings/*.json)

Bindings connect fields to a model and define their order and editability.

**`plugins/task-tracker/config/bindings/tt_task.json`**:

```json
[
  { "modelCode": "tt_task", "fieldCode": "tt_title", "sequence": 1, "required": true, "visible": true, "editable": true },
  { "modelCode": "tt_task", "fieldCode": "tt_code", "sequence": 2, "required": true, "visible": true, "editable": false },
  { "modelCode": "tt_task", "fieldCode": "tt_description", "sequence": 3, "required": false, "visible": true, "editable": true },
  { "modelCode": "tt_task", "fieldCode": "tt_status", "sequence": 4, "required": false, "visible": true, "editable": false },
  { "modelCode": "tt_task", "fieldCode": "tt_priority", "sequence": 5, "required": false, "visible": true, "editable": true },
  { "modelCode": "tt_task", "fieldCode": "tt_assignee", "sequence": 6, "required": false, "visible": true, "editable": true },
  { "modelCode": "tt_task", "fieldCode": "tt_due_date", "sequence": 7, "required": false, "visible": true, "editable": true },
  { "modelCode": "tt_task", "fieldCode": "tt_estimated_hours", "sequence": 8, "required": false, "visible": true, "editable": true },
  { "modelCode": "tt_task", "fieldCode": "tt_is_urgent", "sequence": 9, "required": false, "visible": true, "editable": true },
  { "modelCode": "tt_task", "fieldCode": "tt_created_at", "sequence": 10, "required": false, "visible": true, "editable": false },
  { "modelCode": "tt_task", "fieldCode": "tt_completed_at", "sequence": 11, "required": false, "visible": true, "editable": false },
  { "modelCode": "tt_task", "fieldCode": "tt_tags", "sequence": 12, "required": false, "visible": true, "editable": true }
]
```

---

## Step 6: Define Pages (pages/*.json)

Pages define the UI layout for list, form, and detail views. AuraBoot uses a block-based page schema (V2 flat format).

### List Page

**`plugins/task-tracker/config/pages/tt_task_list.json`**:

```json
{
  "pageKey": "tt_task_list",
  "name:zh-CN": "Task List",
  "name:en": "Task List",
  "modelCode": "tt_task",
  "kind": "list",
  "schemaVersion": 2,
  "layout": {
    "type": "grid",
    "cols": 12
  },
  "blocks": [
    {
      "id": "tt_tabs",
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
          "filter": { "field": "tt_status", "value": "draft", "operator": "EQ" }
        },
        {
          "key": "in_progress",
          "label": { "en": "In Progress", "zh-CN": "In Progress" },
          "filter": { "field": "tt_status", "value": "in_progress", "operator": "EQ" }
        },
        {
          "key": "completed",
          "label": { "en": "Completed", "zh-CN": "Completed" },
          "filter": { "field": "tt_status", "value": "completed", "operator": "EQ" }
        },
        {
          "key": "archived",
          "label": { "en": "Archived", "zh-CN": "Archived" },
          "filter": { "field": "tt_status", "value": "archived", "operator": "EQ" }
        }
      ]
    },
    {
      "id": "tt_toolbar",
      "blockType": "toolbar",
      "buttons": [
        {
          "code": "create",
          "primary": true,
          "permissionCode": "tt.task.manage",
          "label": { "en": "New Task", "zh-CN": "New Task" },
          "action": {
            "type": "navigate",
            "to": "tt_task_form",
            "command": "tt:create_task"
          }
        }
      ]
    },
    {
      "id": "tt_table",
      "blockType": "table",
      "onRowClick": "navigate",
      "columns": [
        { "field": "tt_code", "width": 150, "sortable": true },
        { "field": "tt_title", "width": 250, "sortable": true },
        { "field": "tt_status", "width": 120, "renderType": "tag", "dictCode": "tt_status_dict" },
        { "field": "tt_priority", "width": 100, "renderType": "tag", "dictCode": "tt_priority_dict" },
        { "field": "tt_assignee", "width": 120 },
        { "field": "tt_due_date", "width": 120, "sortable": true },
        { "field": "tt_estimated_hours", "width": 100 },
        { "field": "tt_is_urgent", "width": 80 },
        { "field": "tt_created_at", "width": 160, "sortable": true },
        {
          "field": "actions",
          "isActionColumn": true,
          "buttons": [
            {
              "code": "view",
              "label": { "en": "Detail", "zh-CN": "Detail" },
              "action": { "type": "navigate", "to": "tt_task_detail" }
            },
            {
              "code": "edit",
              "permissionCode": "tt.task.manage",
              "label": { "en": "Edit", "zh-CN": "Edit" },
              "action": { "type": "navigate", "to": "tt_task_form" }
            },
            {
              "code": "delete",
              "danger": true,
              "permissionCode": "tt.task.manage",
              "label": { "en": "Delete", "zh-CN": "Delete" },
              "confirm": "delete.confirm",
              "action": { "type": "command", "command": "tt:delete_task" }
            }
          ]
        }
      ],
      "searchFields": ["tt_title", "tt_code", "tt_description"],
      "defaultSort": { "field": "created_at", "order": "desc" }
    }
  ]
}
```

**Page schema concepts:**

| Concept | Description | Values |
|---------|-------------|--------|
| `kind` | Page type | `list`, `form`, `detail`, `dashboard` |
| `blockType` | Content block type | `table`, `tabs`, `toolbar`, `form-section`, `form-buttons` |
| `layout.type` | Layout engine | `grid` (12-column) or `stack` (vertical) |

### Form Page

**`plugins/task-tracker/config/pages/tt_task_form.json`**:

```json
{
  "pageKey": "tt_task_form",
  "name:zh-CN": "Task Form",
  "name:en": "Task Form",
  "modelCode": "tt_task",
  "kind": "form",
  "schemaVersion": 2,
  "layout": {
    "type": "grid",
    "cols": 12,
    "gap": 16
  },
  "blocks": [
    {
      "id": "section_basic",
      "blockType": "form-section",
      "title": { "en-US": "Basic Information", "zh-CN": "Basic Information" },
      "fields": [
        { "field": "tt_title", "colSpan": 8 },
        { "field": "tt_code", "colSpan": 4, "readOnly": true },
        { "field": "tt_description", "colSpan": 12 }
      ]
    },
    {
      "id": "section_details",
      "blockType": "form-section",
      "title": { "en-US": "Task Details", "zh-CN": "Task Details" },
      "fields": [
        { "field": "tt_priority", "colSpan": 4 },
        { "field": "tt_assignee", "colSpan": 4 },
        { "field": "tt_due_date", "colSpan": 4 },
        { "field": "tt_estimated_hours", "colSpan": 4 },
        { "field": "tt_is_urgent", "colSpan": 4 },
        { "field": "tt_tags", "colSpan": 4 }
      ]
    },
    {
      "id": "buttons",
      "blockType": "form-buttons",
      "buttons": [
        {
          "code": "submit",
          "primary": true,
          "label": { "en": "Save", "zh-CN": "Save" },
          "action": { "type": "command", "command": "tt:update_task" }
        },
        {
          "code": "cancel",
          "label": { "en": "Cancel", "zh-CN": "Cancel" }
        }
      ]
    }
  ]
}
```

### Detail Page

**`plugins/task-tracker/config/pages/tt_task_detail.json`**:

```json
{
  "pageKey": "tt_task_detail",
  "name:zh-CN": "Task Detail",
  "name:en": "Task Detail",
  "modelCode": "tt_task",
  "kind": "detail",
  "schemaVersion": 2,
  "layout": {
    "type": "stack"
  },
  "blocks": [
    {
      "id": "tt_detail_tabs",
      "blockType": "tabs",
      "tabs": [
        {
          "key": "overview",
          "label": { "en-US": "Overview", "zh-CN": "Overview" },
          "blocks": [
            {
              "id": "section_basic",
              "blockType": "form-section",
              "title": { "en-US": "Basic Information", "zh-CN": "Basic Information" },
              "columns": 2,
              "fields": [
                { "field": "tt_code", "readOnly": true },
                { "field": "tt_title", "readOnly": true },
                { "field": "tt_status", "readOnly": true },
                { "field": "tt_priority", "readOnly": true },
                { "field": "tt_description", "span": 2, "readOnly": true }
              ]
            },
            {
              "id": "section_details",
              "blockType": "form-section",
              "title": { "en-US": "Details", "zh-CN": "Details" },
              "columns": 2,
              "fields": [
                { "field": "tt_assignee", "readOnly": true },
                { "field": "tt_due_date", "readOnly": true },
                { "field": "tt_estimated_hours", "readOnly": true },
                { "field": "tt_is_urgent", "readOnly": true },
                { "field": "tt_tags", "readOnly": true },
                { "field": "tt_created_at", "readOnly": true },
                { "field": "tt_completed_at", "readOnly": true }
              ]
            }
          ]
        }
      ]
    }
  ]
}
```

---

## Step 7: Define Menus, Permissions, and Roles

### Permissions

**`plugins/task-tracker/config/permissions.json`**:

```json
[
  {
    "code": "tt.task.manage",
    "name:zh-CN": "Manage Tasks",
    "name:en": "Manage Tasks",
    "resourceType": "operation",
    "module": "tt"
  },
  {
    "code": "tt.task.read",
    "name:zh-CN": "View Tasks",
    "name:en": "View Tasks",
    "resourceType": "data",
    "module": "tt"
  }
]
```

### Roles

**`plugins/task-tracker/config/roles.json`**:

```json
[
  {
    "code": "tt_admin",
    "name:zh-CN": "Task Tracker Admin",
    "name:en": "Task Tracker Admin",
    "description": "Full access to Task Tracker",
    "permissions": [
      "tt.task.manage",
      "tt.task.read"
    ]
  },
  {
    "code": "tt_viewer",
    "name:zh-CN": "Task Tracker Viewer",
    "name:en": "Task Tracker Viewer",
    "description": "Read-only access to Task Tracker",
    "permissions": [
      "tt.task.read"
    ]
  }
]
```

### Menus

**`plugins/task-tracker/config/menus.json`**:

```json
[
  {
    "code": "tt_root",
    "parentCode": null,
    "name:zh-CN": "Task Tracker",
    "name:en": "Task Tracker",
    "path": null,
    "component": null,
    "icon": "IconCheckbox",
    "type": 0,
    "permissionCode": null,
    "orderNo": 10,
    "visible": true
  },
  {
    "code": "tt_task_list_menu",
    "parentCode": "tt_root",
    "name:zh-CN": "Tasks",
    "name:en": "Tasks",
    "path": "/p/tt_task",
    "component": null,
    "icon": "IconList",
    "type": 1,
    "permissionCode": "tt.task.read",
    "orderNo": 1,
    "visible": true,
    "pageKey": "tt_task_list"
  }
]
```

**Menu fields:**

| Field | Description |
|-------|-------------|
| `code` | Unique menu code |
| `parentCode` | Parent menu code (`null` for top-level) |
| `type` | `0` = directory (folder), `1` = page link |
| `path` | URL path. Use `/p/{pageKey}` for DSL pages |
| `icon` | Icon name from Tabler Icons (prefixed with `Icon`) |
| `permissionCode` | Permission required to see this menu |
| `pageKey` | Links to a page schema |
| `orderNo` | Sort order within parent |

---

## Step 8: Add i18n Translations

i18n entries provide multilingual labels for models, fields, dictionaries, and commands.

**`plugins/task-tracker/config/i18n.json`**:

```json
[
  { "key": "model.tt_task._meta.label", "zh-CN": "Task", "en-US": "Task", "source": "import", "refType": "model" },

  { "key": "model.tt_task.tt_title.label", "zh-CN": "Title", "en-US": "Title", "source": "import", "refType": "field" },
  { "key": "model.tt_task.tt_code.label", "zh-CN": "Code", "en-US": "Code", "source": "import", "refType": "field" },
  { "key": "model.tt_task.tt_description.label", "zh-CN": "Description", "en-US": "Description", "source": "import", "refType": "field" },
  { "key": "model.tt_task.tt_status.label", "zh-CN": "Status", "en-US": "Status", "source": "import", "refType": "field" },
  { "key": "model.tt_task.tt_priority.label", "zh-CN": "Priority", "en-US": "Priority", "source": "import", "refType": "field" },
  { "key": "model.tt_task.tt_assignee.label", "zh-CN": "Assignee", "en-US": "Assignee", "source": "import", "refType": "field" },
  { "key": "model.tt_task.tt_due_date.label", "zh-CN": "Due Date", "en-US": "Due Date", "source": "import", "refType": "field" },
  { "key": "model.tt_task.tt_estimated_hours.label", "zh-CN": "Estimated Hours", "en-US": "Estimated Hours", "source": "import", "refType": "field" },
  { "key": "model.tt_task.tt_is_urgent.label", "zh-CN": "Urgent", "en-US": "Urgent", "source": "import", "refType": "field" },
  { "key": "model.tt_task.tt_created_at.label", "zh-CN": "Created At", "en-US": "Created At", "source": "import", "refType": "field" },
  { "key": "model.tt_task.tt_completed_at.label", "zh-CN": "Completed At", "en-US": "Completed At", "source": "import", "refType": "field" },
  { "key": "model.tt_task.tt_tags.label", "zh-CN": "Tags", "en-US": "Tags", "source": "import", "refType": "field" },

  { "key": "dict.tt_status_dict.label", "zh-CN": "Task Status", "en-US": "Task Status", "source": "import", "refType": "dict" },
  { "key": "dict.tt_status_dict.DRAFT", "zh-CN": "Draft", "en-US": "Draft", "source": "import", "refType": "dict_item" },
  { "key": "dict.tt_status_dict.IN_PROGRESS", "zh-CN": "In Progress", "en-US": "In Progress", "source": "import", "refType": "dict_item" },
  { "key": "dict.tt_status_dict.COMPLETED", "zh-CN": "Completed", "en-US": "Completed", "source": "import", "refType": "dict_item" },
  { "key": "dict.tt_status_dict.ARCHIVED", "zh-CN": "Archived", "en-US": "Archived", "source": "import", "refType": "dict_item" },

  { "key": "dict.tt_priority_dict.label", "zh-CN": "Priority", "en-US": "Priority", "source": "import", "refType": "dict" },
  { "key": "dict.tt_priority_dict.LOW", "zh-CN": "Low", "en-US": "Low", "source": "import", "refType": "dict_item" },
  { "key": "dict.tt_priority_dict.MEDIUM", "zh-CN": "Medium", "en-US": "Medium", "source": "import", "refType": "dict_item" },
  { "key": "dict.tt_priority_dict.HIGH", "zh-CN": "High", "en-US": "High", "source": "import", "refType": "dict_item" },
  { "key": "dict.tt_priority_dict.CRITICAL", "zh-CN": "Critical", "en-US": "Critical", "source": "import", "refType": "dict_item" }
]
```

**i18n key format:**

| Pattern | Example | Description |
|---------|---------|-------------|
| `model.{modelCode}._meta.label` | `model.tt_task._meta.label` | Model display name |
| `model.{modelCode}.{fieldCode}.label` | `model.tt_task.tt_title.label` | Field label |
| `dict.{dictCode}.label` | `dict.tt_status_dict.label` | Dictionary name |
| `dict.{dictCode}.{VALUE}` | `dict.tt_status_dict.DRAFT` | Dictionary item label |

---

## Step 9: Add Dictionaries

Dictionaries define the allowed values for enum fields.

**`plugins/task-tracker/config/dicts.json`**:

```json
[
  {
    "code": "tt_status_dict",
    "name": "Task Status",
    "name:zh-CN": "Task Status",
    "dictType": "static",
    "items": [
      {
        "value": "draft",
        "label": "Draft",
        "label:zh-CN": "Draft",
        "sortNo": 10,
        "color": "#d9d9d9",
        "status": "enabled",
        "extension": { "color": "gray" }
      },
      {
        "value": "in_progress",
        "label": "In Progress",
        "label:zh-CN": "In Progress",
        "sortNo": 20,
        "color": "#1890ff",
        "status": "enabled",
        "extension": { "color": "blue" }
      },
      {
        "value": "completed",
        "label": "Completed",
        "label:zh-CN": "Completed",
        "sortNo": 30,
        "color": "#52c41a",
        "status": "enabled",
        "extension": { "color": "green" }
      },
      {
        "value": "archived",
        "label": "Archived",
        "label:zh-CN": "Archived",
        "sortNo": 40,
        "color": "#fa8c16",
        "status": "enabled",
        "extension": { "color": "gray" }
      }
    ]
  },
  {
    "code": "tt_priority_dict",
    "name": "Priority",
    "name:zh-CN": "Priority",
    "dictType": "static",
    "items": [
      {
        "value": "low",
        "label": "Low",
        "label:zh-CN": "Low",
        "sortNo": 10,
        "color": "#d9d9d9",
        "status": "enabled",
        "extension": { "color": "gray" }
      },
      {
        "value": "medium",
        "label": "Medium",
        "label:zh-CN": "Medium",
        "sortNo": 20,
        "color": "#faad14",
        "status": "enabled",
        "extension": { "color": "gold" }
      },
      {
        "value": "high",
        "label": "High",
        "label:zh-CN": "High",
        "sortNo": 30,
        "color": "#fa541c",
        "status": "enabled",
        "extension": { "color": "orange" }
      },
      {
        "value": "critical",
        "label": "Critical",
        "label:zh-CN": "Critical",
        "sortNo": 40,
        "color": "#f5222d",
        "status": "enabled",
        "extension": { "color": "red" }
      }
    ]
  }
]
```

**Dictionary fields:**

| Field | Description |
|-------|-------------|
| `code` | Unique dictionary code (referenced by field's `dictCode`) |
| `dictType` | `"static"` for predefined values |
| `items[].value` | Stored value (lowercase) |
| `items[].label` | Display label |
| `items[].sortNo` | Sort order |
| `items[].color` | Hex color for UI rendering |
| `items[].extension.color` | Named color for tag rendering (`gray`, `blue`, `green`, `red`, etc.) |

---

## Step 10: Write default-bootstrap.json

The bootstrap file configures initial role-permission bindings when the plugin is first installed. The `"*"` wildcard assigns all plugin permissions to the tenant admin role.

**`plugins/task-tracker/config/default-bootstrap.json`**:

```json
{
  "rolePermissionBindings": [
    {
      "roleCode": "tenant_admin",
      "permissionCodes": ["*"]
    }
  ]
}
```

This ensures the admin user can access all Task Tracker features immediately after installation.

---

## Step 11: Validate

Before publishing, validate your plugin structure and references:

```bash
aura plugin validate plugins/task-tracker
```

The validator checks:
- **Structural**: JSON syntax, manifest schema, resource file existence
- **Semantic**: Cross-references (commands reference valid models, fields reference valid dictionaries, menus reference valid permissions)
- **Namespace consistency**: Model codes start with `tt_`, command codes start with `tt:`
- **i18n coverage**: All models and fields have translation entries

Fix any reported errors before proceeding.

---

## Step 12: Publish

Import and activate the plugin:

```bash
aura plugin publish plugins/task-tracker --yes
```

This single command:
1. Parses all JSON files
2. Creates dictionaries, fields, models, bindings, commands, pages in the database
3. Publishes all resources (models, commands, pages)
4. Creates permissions and role bindings
5. Registers menus

---

## Step 13: Verify

After publishing, verify everything works:

1. **Check platform status:**
   ```bash
   aura status
   ```

2. **Verify the model exists:**
   ```bash
   aura dsl show tt_task
   ```

3. **Create a test record via CLI:**
   ```bash
   aura exec tt:create_task --set tt_title="My First Task" --set tt_priority="high"
   ```

4. **Query records:**
   ```bash
   aura query tt_task
   ```

5. **Open the browser** and navigate to the Task Tracker menu in the sidebar. You should see:
   - The "Task Tracker" menu group in the sidebar
   - A "Tasks" submenu that opens the list page
   - A "New Task" button in the toolbar
   - Status tabs (All, Draft, In Progress, Completed, Archived)
   - Your test record in the table

---

## Summary

You built a complete plugin with:

| Resource | Count | File |
|----------|-------|------|
| Models | 1 | `config/models.json` |
| Fields | 12 | `config/fields/tt_task.json` |
| Bindings | 12 | `config/bindings/tt_task.json` |
| Commands | 8 | `config/commands/tt_task.json` |
| Pages | 3 | `config/pages/*.json` |
| Dictionaries | 2 | `config/dicts.json` |
| Permissions | 2 | `config/permissions.json` |
| Roles | 2 | `config/roles.json` |
| Menus | 2 | `config/menus.json` |
| i18n entries | 23 | `config/i18n.json` |

**Next steps:**
- Add more models (e.g., `tt_project` to group tasks)
- Add REFERENCE fields to link models together
- Add sub-table blocks to detail pages
- Add dashboards with chart blocks
- If you need custom logic, see [Backend Plugin Guide](./backend-plugin.md)
