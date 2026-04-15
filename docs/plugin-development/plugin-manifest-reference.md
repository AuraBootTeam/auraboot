# Plugin Manifest Reference (plugin.json)

This is the complete reference for the `plugin.json` manifest file. Every AuraBoot plugin must have this file at its root directory.

---

## Top-Level Fields

### Required Fields

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `pluginId` | `string` | Globally unique plugin identifier in reverse domain name format. | `"com.auraboot.task-tracker"` |
| `namespace` | `string` | Short prefix for all plugin resources. Must be lowercase letters, 2-10 characters. Used to prefix model codes (`tt_task`), command codes (`tt:create_task`), and permission codes (`tt.task.manage`). | `"tt"` |
| `version` | `string` | Semantic version number (semver). | `"1.0.0"` |

### Identity Fields (Optional)

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `displayName` | `string` | Default display name. | `"Task Tracker"` |
| `displayName:zh-CN` | `string` | Chinese display name. | `"Task Tracker"` |
| `displayName:en` | `string` | English display name. | `"Task Tracker"` |
| `description` | `string` | Brief description of what the plugin does. | `"Simple task tracking with workflow"` |
| `author` | `string` | Author name or organization. | `"AuraBoot Community"` |
| `homepage` | `string` | URL to plugin documentation or homepage. | `"https://auraboot.com/plugins/tt"` |

### Plugin Type Fields (Optional)

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `dslVersion` | `integer` | `1` | DSL schema version number. Controls which schema features are available. |
| `pluginType` | `string` | `"config"` | Plugin type. See [Plugin Types](#plugin-types) below. |

### Compatibility Fields (Optional)

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `minPlatformVersion` | `string` | Minimum AuraBoot platform version required. | `"1.0.0"` |

---

## Plugin Types

The `pluginType` field declares what layers the plugin contains:

| Value | Description | Contains |
|-------|-------------|----------|
| `config` | Config-only plugin. No Java or React code. | `config/` directory only |
| `hybrid` | Backend and/or frontend plugin with optional config. | `config/` + `backend/` and/or `frontend/` |
| `solution` | Meta-package that orchestrates multiple plugins. | Plugin dependency declarations + shared config |
| `platform` | Platform plugin with custom tsx routes. | Custom React pages + routes |

---

## Dependencies

### `dependencies`

Declares plugins that must be installed before this plugin. Supports two formats:

**Simple format (array of strings):**

```json
{
  "dependencies": [
    "com.auraboot.org-management",
    "com.auraboot.crm"
  ]
}
```

**Extended format (array of objects with version constraints):**

```json
{
  "dependencies": [
    { "pluginId": "com.auraboot.crm", "version": ">=1.0.0" },
    { "pluginId": "com.auraboot.sales", "version": "^1.2.0" }
  ]
}
```

**Mixed format (both in same array):**

```json
{
  "dependencies": [
    "com.auraboot.org-management",
    { "pluginId": "com.auraboot.crm", "version": ">=1.0.0" }
  ]
}
```

**Version constraint syntax:**

| Syntax | Meaning | Example |
|--------|---------|---------|
| `>=1.0.0` | At least version 1.0.0 | `>=1.0.0` matches 1.0.0, 1.5.0, 2.0.0 |
| `^1.2.0` | Compatible with 1.2.0 (same major) | `^1.2.0` matches 1.2.0, 1.9.9, not 2.0.0 |
| `~1.0.0` | Approximately 1.0.0 (same major.minor) | `~1.0.0` matches 1.0.0, 1.0.9, not 1.1.0 |

---

## Provides and Requires

### `provides`

Declares the resources this plugin provides to the platform. Used for dependency resolution and marketplace display.

```json
{
  "provides": [
    { "type": "model", "code": "tt_task" },
    { "type": "command", "code": "tt:create_task" },
    { "type": "command", "code": "tt:update_task" },
    { "type": "command", "code": "tt:delete_task" }
  ]
}
```

**Resource types:** `model`, `command`, `field`, `page`, `dict`, `permission`, `menu`, `role`

### `requires`

Declares resources from other plugins that this plugin depends on (e.g., referencing a model from another plugin).

```json
{
  "requires": [
    { "type": "model", "code": "org_employee", "pluginId": "com.auraboot.org-management" }
  ]
}
```

---

## Resource Directories

### `resourceDirs`

Maps resource types to file paths relative to the plugin root. The import system reads files in a specific order to resolve dependencies correctly.

```json
{
  "resourceDirs": {
    "dicts": "config/dicts.json",
    "fields": "config/fields",
    "models": "config/models.json",
    "bindings": "config/bindings",
    "commands": "config/commands",
    "processes": "config/processes.json",
    "permissions": "config/permissions.json",
    "roles": "config/roles.json",
    "menus": "config/menus.json",
    "pages": "config/pages",
    "i18n": "config/i18n.json",
    "data": "config/data.json"
  }
}
```

**Import order** (resources are imported in this sequence):

| Order | Key | Description | File Format |
|-------|-----|-------------|-------------|
| 1 | `dicts` | Dictionary definitions | JSON array or single file |
| 2 | `fields` | Field definitions | JSON array or directory of files |
| 3 | `models` | Model definitions | JSON array |
| 4 | `bindings` | Model-field binding relationships | JSON array or directory of files |
| 5 | `commands` | Command definitions | JSON array or directory of files |
| 6 | `processes` | BPMN process definitions (see [Process designerJson contract](#process-designerjson-contract)) | JSON array |
| 7 | `permissions` | Permission definitions | JSON array |
| 8 | `roles` | Role definitions | JSON array |
| 9 | `menus` | Menu tree definitions | JSON array |
| 10 | `pages` | Page schema definitions | JSON object or directory of files |
| 11 | `i18n` | Internationalization entries | JSON array |
| 12 | `data` | Seed data for initial records | JSON array |

**File vs. directory paths:**

- If the path ends with `.json`, the system reads it as a single file
- If the path is a directory (no extension), the system reads all `.json` files in that directory and merges them
- Directory paths are useful for organizing resources by model (e.g., `config/fields/tt_task.json`, `config/fields/tt_project.json`)

---

## Import Options

### `importOptions`

Controls how the plugin import system handles conflicts and post-import actions.

```json
{
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

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `conflictStrategy` | `string` | `"error"` | How to handle existing resources. `"overwrite"`: replace existing. `"error"`: fail on conflict. `"skip"`: keep existing. |
| `validateReferences` | `boolean` | `true` | Check that all cross-references are valid (e.g., commands reference valid models, fields reference valid dictionaries). |
| `autoPublishModels` | `boolean` | `false` | Automatically publish models after import (creates database tables). |
| `autoPublishFields` | `boolean` | `false` | Automatically publish fields after import (adds columns to tables). |
| `autoPublishCommands` | `boolean` | `false` | Automatically publish commands after import. |
| `autoPublishPages` | `boolean` | `false` | Automatically publish page schemas after import. |
| `autoDeployProcesses` | `boolean` | `false` | Automatically deploy BPMN processes after import. |
| `createResourcePermissions` | `boolean` | `false` | Automatically create DYNAMIC permissions for published models (CRUD permissions). |

---

## Process designerJson contract

`processes.json` entries may define the workflow either inline as BPMN XML
(`bpmnContent`), as a file reference (`bpmnFile`), or — most common — as a
React Flow `designerJson` object that is converted to BPMN at deploy time.

When using `designerJson`, **every outgoing sequence flow of an exclusive
gateway must carry a non-empty condition** under `data.condition.content`.
SmartEngine rejects BPMN `default=` fallback at runtime; marking an edge with
`isDefault: true` emits the attribute for spec compliance but does NOT
exempt the edge from needing an evaluable condition (use `"true"` or an
explicit inverse as the catch-all).

### Correct shape

```json
{
  "key": "sc_workflow_main",
  "autoDeploy": true,
  "designerJson": {
    "nodes": [
      { "id": "start_1", "type": "startEvent" },
      { "id": "gw", "type": "exclusiveGateway" },
      { "id": "approve", "type": "userTask", "data": { "label:zh-CN": "审批" } },
      { "id": "auto",    "type": "userTask", "data": { "label:zh-CN": "自动通过" } },
      { "id": "end_1", "type": "endEvent" }
    ],
    "edges": [
      { "id": "e1", "source": "start_1", "target": "gw" },
      {
        "id": "e_approve",
        "source": "gw",
        "target": "approve",
        "data": {
          "label:zh-CN": "> 5 万",
          "condition": { "type": "expression", "content": "amount > 50000" }
        }
      },
      {
        "id": "e_auto",
        "source": "gw",
        "target": "auto",
        "data": {
          "label:zh-CN": "其他",
          "isDefault": true,
          "condition": { "type": "expression", "content": "true" }
        }
      },
      { "id": "e_approve_end", "source": "approve", "target": "end_1" },
      { "id": "e_auto_end",    "source": "auto",    "target": "end_1" }
    ]
  }
}
```

### Common mistakes (rejected at deploy time)

| Anti-pattern | Why it fails |
|-------------|--------------|
| `data.conditionExpression: "..."` (wrong field name) | Converter only reads `data.condition.content`; the edge is treated as naked and F5 validation rejects deploy |
| `{ "conditionExpression": "..." }` at edge root | Same — wrong field location |
| `"${amount > 1000}"` | SmartEngine evaluates plain MVEL; the `${...}` wrapper is Java-EL and not supported as a gateway condition |
| Using only `isDefault: true` without `condition` | SmartEngine does not honor BPMN `default=` fallback; every outgoing edge needs an evaluable expression |
| Two edges both marked `isDefault: true` on one gateway | Rejected as "multiple default flows" |

The full shape is enforced by the `plugin-manifest.schema.json` JSON Schema
(see `plugins/schemas/plugin-manifest.schema.json` under `$defs.processDefinition.properties.designerJson`).

---

## Backend Configuration

### `backend`

Configuration for the PF4J backend JAR (only for `hybrid` or `solution` plugin types).

```json
{
  "backend": {
    "jar": "backend/my-plugin-1.0.0.jar",
    "pluginClass": "com.example.MyPlugin"
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `jar` | `string` | Yes | Path to the PF4J JAR file, relative to plugin root. |
| `pluginClass` | `string` | Yes | Fully qualified class name of the main plugin class (must extend `AuraPlugin`). |

---

## Frontend Configuration

### `frontend`

Configuration for the Module Federation frontend module (only for `hybrid` plugin types).

```json
{
  "frontend": {
    "remoteEntry": "frontend/remoteEntry.js",
    "modules": {
      "blocks": {
        "map-view": {
          "module": "./MapViewBlock",
          "name": "Map View",
          "description": "Renders records as pins on a map",
          "configSchema": [
            { "key": "latField", "label": "Latitude Field", "type": "field-select", "required": true },
            { "key": "lngField", "label": "Longitude Field", "type": "field-select", "required": true }
          ]
        }
      },
      "widgets": {
        "heatmap": {
          "module": "./HeatmapWidget",
          "name": "Heatmap",
          "description": "A heatmap visualization widget"
        }
      },
      "fields": {
        "code-editor": {
          "module": "./CodeEditorField",
          "name": "Code Editor",
          "description": "A syntax-highlighted code editor"
        }
      }
    }
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `remoteEntry` | `string` | Yes | Path to the Module Federation remote entry file. |
| `modules` | `object` | Yes | Map of module types to module definitions. |
| `modules.blocks` | `object` | No | Custom block renderers for the Page Designer. |
| `modules.widgets` | `object` | No | Custom widgets for the Dashboard Designer. |
| `modules.fields` | `object` | No | Custom field renderers for forms. |

**Module definition fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `module` | `string` | Yes | Module path as defined in Vite's `exposes` config (e.g., `"./MapViewBlock"`). |
| `name` | `string` | Yes | Human-readable component name. |
| `description` | `string` | No | Brief description shown in designers. |
| `configSchema` | `array` | No | Configuration fields shown in the block/widget property panel. |

---

## Plugin Settings

### `settings`

Defines per-tenant configurable settings for the plugin. Settings are stored in the database and accessible via `CommandContext.settings()` in backend handlers.

```json
{
  "settings": {
    "smtpEndpoint": {
      "type": "string",
      "default": "https://api.sendgrid.com/v3/mail/send",
      "description": "SMTP API endpoint for sending emails"
    },
    "apiKey": {
      "type": "string",
      "default": "",
      "description": "API key for the email service",
      "sensitive": true
    },
    "maxRetries": {
      "type": "number",
      "default": 3,
      "description": "Maximum retry attempts for failed email sends"
    },
    "enabled": {
      "type": "boolean",
      "default": true,
      "description": "Enable or disable email notifications"
    }
  }
}
```

**Setting definition fields:**

| Field | Type | Description |
|-------|------|-------------|
| `type` | `string` | Value type: `"string"`, `"number"`, `"boolean"` |
| `default` | `any` | Default value |
| `description` | `string` | Human-readable description |
| `sensitive` | `boolean` | If true, value is masked in the UI (for API keys, passwords) |

---

## Metadata

### `metadata`

Arbitrary key-value metadata for the plugin. Not used by the platform, but useful for documentation and tooling.

```json
{
  "metadata": {
    "license": "MIT",
    "repository": "https://github.com/example/task-tracker",
    "keywords": ["task", "project", "workflow"],
    "category": "productivity"
  }
}
```

---

## Complete Example

Here is a complete `plugin.json` for a full-stack plugin:

```json
{
  "pluginId": "com.example.document-approval",
  "namespace": "da",
  "version": "1.2.0",
  "dslVersion": 1,
  "pluginType": "hybrid",

  "displayName": "Document Approval",
  "displayName:zh-CN": "Document Approval",
  "displayName:en": "Document Approval",
  "description": "Document submission and approval workflow with email notifications.",
  "author": "Example Corp",
  "homepage": "https://example.com/plugins/document-approval",
  "minPlatformVersion": "1.0.0",

  "dependencies": [
    "com.auraboot.org-management",
    { "pluginId": "com.auraboot.crm", "version": ">=1.0.0" }
  ],

  "provides": [
    { "type": "model", "code": "da_document" },
    { "type": "command", "code": "da:create_document" },
    { "type": "command", "code": "da:approve_document" }
  ],

  "requires": [
    { "type": "model", "code": "org_employee", "pluginId": "com.auraboot.org-management" }
  ],

  "resourceDirs": {
    "dicts": "config/dicts.json",
    "fields": "config/fields",
    "models": "config/models.json",
    "bindings": "config/bindings",
    "commands": "config/commands",
    "permissions": "config/permissions.json",
    "roles": "config/roles.json",
    "menus": "config/menus.json",
    "pages": "config/pages",
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
  },

  "backend": {
    "jar": "backend/document-approval-1.2.0.jar",
    "pluginClass": "com.example.docapproval.DocumentApprovalPlugin"
  },

  "frontend": {
    "remoteEntry": "frontend/remoteEntry.js",
    "modules": {
      "blocks": {
        "document-viewer": {
          "module": "./DocumentViewer",
          "name": "Document Viewer"
        }
      }
    }
  },

  "settings": {
    "smtpEndpoint": {
      "type": "string",
      "default": "",
      "description": "Email service API endpoint"
    },
    "notificationsEnabled": {
      "type": "boolean",
      "default": true,
      "description": "Enable email notifications on approval/rejection"
    }
  },

  "metadata": {
    "license": "MIT",
    "category": "workflow",
    "keywords": ["document", "approval", "workflow"]
  }
}
```

---

## Validation

The platform validates the manifest at import time with three layers:

| Layer | Checks | Behavior |
|-------|--------|----------|
| **Structural** | JSON syntax, required fields present, field types correct | Error blocks import |
| **Semantic** | Cross-references valid, namespace consistency, command config correct | Error blocks import |
| **Governance** | i18n coverage, circular dependencies, permission completeness | Warning only |

Validate before publishing:

```bash
aura plugin validate plugins/my-plugin
```

---

## Related Documentation

- [Plugin Development Overview](./overview.md)
- [Config-Only Plugin Tutorial](./config-only-plugin.md)
- [Backend Plugin Guide](./backend-plugin.md)
- [Frontend Plugin Guide](./frontend-plugin.md)
- [Full-Stack Plugin Guide](./full-stack-plugin.md)
