---
type: system-reference
status: active
---

# Plugin Manifest

In AuraBoot, the plugin is the unit of delivery. Whether you ship a small lookup table, an end-to-end CRM, or a complete industry solution, the package travels with the same shape: a directory containing a `plugin.json` manifest and a set of resource files that the platform imports.

The manifest is more than a name and a version. It is a typed declaration of every resource the plugin contributes — models, fields, commands, permissions, menus, pages, named queries, and so on — plus the capabilities the plugin provides to others and depends on from others. That declaration is what makes plugins upgrade-safe, composable, and analyzable by tools and agents.

## Why the manifest exists

Without a manifest, a plugin is just a folder of code and JSON. Two problems follow immediately:

1. **Upgrades become unsafe.** When a new version of a plugin ships, the platform has no way to know which resources are owned by that plugin and which were added by a customer or another plugin. Diffs become guesswork.
2. **Composition becomes ad hoc.** A plugin that wants to extend a CRM has no machine-readable way to say "I require the `crm_account` model" or "I provide the `mfg:scrap` command." Cross-plugin contracts live only in human prose.

The plugin manifest closes both gaps. It serves as a typed registry of contributions and capabilities. The platform reads it at install time and uses it during upgrades, dependency resolution, validation, packaging, and entitlement gating.

The same manifest is also the source of truth that downstream tools rely on:

- The plugin loader uses `pluginType`, `dependencies`, and `resourceDirs` to decide load order and which files to import.
- The DSL designer uses `provides` and `requires` to suggest references and to warn when a target model is missing.
- The permission system reads `requiredPermissions` to seed roles.
- The license layer reads `licenseMode`, `plans`, `features`, and `planFeatures` to gate behavior.
- AI agents reason about the manifest to suggest configurations or generate new plugins.

A well-formed manifest is therefore not a packaging detail. It is the contract that lets every other layer treat the plugin as a first-class object.

## The three plugin types

AuraBoot recognizes three plugin types declared in the `pluginType` field. Choosing the right type up front simplifies packaging, deployment, and review.

**`config`** plugins contain only JSON resource files. They define models, fields, commands, pages, permissions, menus, dictionaries, and named queries through the declarative DSL. No Java code, no compiled binary. The vast majority of AuraBoot plugins — including most business modules and templates — are config plugins. Choose `config` whenever your behavior can be expressed declaratively through commands, validation rules, side effects, and BPM processes.

**`hybrid`** plugins combine declarative JSON with Java code loaded by the PF4J runtime. They are the right choice when you need a custom command handler that calls an external system, a data provider that integrates a non-relational source, or a specialized action that cannot be expressed in the standard execution modes. The hybrid plugin still carries a `config/` directory; the Java code is added as a JAR plus a `backend.entryClass` declared in the manifest. Choose `hybrid` only when JSON alone cannot express the operation.

**`solution`** plugins are industry-vertical packages. A solution does not usually introduce its own models; instead, it declares dependencies on several config or hybrid plugins, adds cross-plugin glue resources, and ships pre-tuned configurations for a specific industry (manufacturing, asset management, project delivery, and so on). Choose `solution` when you are bundling a curated set of plugins into a deployable industry stack rather than building a single feature.

If a plugin starts as `config` and later needs custom Java behavior, you can promote it to `hybrid` by adding a `backend` block with `jarPath` and `entryClass`. Going from `config` to `solution` is unusual; solutions are typically designed as solutions from the start.

## Top-level manifest fields

The manifest schema is defined in `plugin-manifest.schema.json` and is the authoritative source. The most important top-level fields are:

| Field | Type | Required | Purpose |
|-------|------|----------|---------|
| `pluginId` | string | yes | Globally unique identifier in reverse domain form (e.g. `com.auraboot.asset-management`). |
| `namespace` | string | yes | Short resource-isolation prefix used in model codes, command codes, and permissions. Lowercase, underscores allowed. |
| `version` | string | yes | Semantic version following `MAJOR.MINOR.PATCH` with optional pre-release/build metadata. |
| `pluginType` | enum | no | `config`, `hybrid`, or `solution`. Defaults to `config`. |
| `displayName` | string | no | Default display name; pair with `displayName:zh-CN` and `displayName:en` for localization. |
| `description` | string | no | Human-readable summary. |
| `author` | string | no | Author or organization. |
| `homepage` | string | no | URL to documentation or product page. |
| `minPlatformVersion` | string | no | Minimum AuraBoot platform version required. |
| `dslVersion` | integer | no | DSL schema version this plugin's page definitions target. Defaults to `1`. |
| `dependencies` | array | no | List of plugins that must be installed first. See below. |
| `provides` | array | no | Capabilities this plugin offers to others. |
| `requires` | array | no | Capabilities this plugin needs from others. |
| `resourceDirs` | object | no | File paths to the resource directories for models, fields, commands, etc. |
| `backend` | object | no | JAR file location and entry point for hybrid plugins. |
| `client` | object | no | Frontend plugin configuration including exposed components. |
| `importOptions` | object | no | Behavior flags for import (conflict strategy, validation, auto-publish). |
| `requiredPermissions` | array | no | Permission codes the plugin needs to be granted at install. |
| `providedModels` | array | no | Model codes the plugin will register. |
| `providedCommands` | array | no | Command codes the plugin will register. |
| `licenseMode` | enum | no | `free`, `platform`, or `vendor`. Drives entitlement behavior. |
| `plans` | array | no | Subscription plans available for this plugin. |
| `features` | array | no | Entitlement feature flags that can be gated per plan. |
| `planFeatures` | object | no | Map from plan code to enabled feature keys. |

The manifest also supports inline resource arrays at the top level (`models`, `fields`, `commands`, `permissions`, `roles`, `menus`, `pages`, `processes`, `namedQueries`, `dicts`, `modelFieldBindings`, `bindingRules`). In most plugins the resources live in dedicated files and `resourceDirs` points to them; inline arrays are convenient for very small plugins or test fixtures.

## Resource directories

The `resourceDirs` object maps a logical resource type to a file or directory path inside the plugin package. The loader walks each declared entry and imports the resources it finds. The schema recognizes the following categories:

- **`models`** — Model definitions describing business entities, their categories, abstract status, and parent inheritance.
- **`fields`** — Reusable meta field definitions: data type, constraints, UI hints, query and validation schemas.
- **`modelFieldBindings`** — Bindings that attach fields to models with per-context overrides (required, visible, editable, sequence).
- **`commands`** — Command definitions covering CRUD, state transitions, batch operations, and custom actions, along with their handlers, side effects, validation rules, and post-actions.
- **`processes`** — BPM process definitions delivered as BPMN files or as designer JSON consumed by the workflow engine.
- **`permissions`** — Permission codes with category, resource type, action, module, and data scope.
- **`roles`** — Predefined RBAC roles that bundle permission codes.
- **`menus`** — Navigation tree entries with path, icon, parent code, permission gating, and page binding.
- **`pages`** — DSL-driven page definitions: list, form, detail, dashboard, or custom pages with their schema.
- **`reports`** — Report definitions for the reporting subsystem.
- **`namedQueries`** — Stored, parameterized queries with declared fields, default ordering, and operator whitelists.
- **`dicts`** — Dictionary (enumerated value set) definitions used by `dict`-typed fields.
- **`data`** — Seed data to import alongside metadata.

Each directory entry can be a single JSON file (`config/commands.json`) or a directory of JSON files (`config/commands/`). Both are walked recursively where applicable.

## Capabilities: `dependencies`, `provides`, `requires`

AuraBoot distinguishes two ways of expressing inter-plugin relationships:

**`dependencies`** is a plugin-to-plugin link. It says "plugin B must be installed before I am loaded." It can be a list of plugin IDs (matching any version) or a list of objects with semver version ranges:

```json
{
  "dependencies": [
    "com.auraboot.org-management",
    { "pluginId": "com.auraboot.crm", "version": ">=1.2.0" }
  ]
}
```

**`provides`** and **`requires`** are capability-level declarations. They say "I offer this model / command / query / automation / api" and "I need this from someone else." A capability has a type (`model`, `command`, `query`, `automation`, `api`) and a code:

```json
{
  "provides": [
    { "type": "model",   "code": "asset_unit" },
    { "type": "command", "code": "asset:retire" }
  ],
  "requires": [
    { "type": "model", "code": "org_employee", "optional": true }
  ]
}
```

The resolver uses this information at install and upgrade time. It topologically sorts plugins by `dependencies`, validates that every required capability is provided by some installed plugin, and refuses to load when a non-optional requirement is missing. Optional requirements simply disable the dependent features without failing the install.

Capability declarations also help tooling: the DSL designer can suggest references only across installed providers, and an AI agent can reason about which plugin gap blocks an end-to-end workflow.

## Plugin lifecycle

A plugin moves through the same stages whether it is installed for the first time or upgraded:

```text
upload package
  -> validate manifest
  -> resolve dependencies and capabilities
  -> load Java entrypoint (hybrid only)
  -> import dictionaries
  -> import fields and models
  -> import model-field bindings
  -> import commands and binding rules
  -> import permissions and roles
  -> import named queries
  -> import pages and menus
  -> import processes (deploy if autoDeploy)
  -> register frontend components (if client.enabled)
  -> activate
```

During an upgrade, the platform diffs the new manifest against the installed state. Resources owned by the plugin are reconciled according to the `importOptions.conflictStrategy`:

- `error` — abort if an existing resource has been changed locally.
- `skip` — keep the local version and log the conflict.
- `overwrite` — replace the local version with the new manifest's version.

Resources removed from a newer manifest are not deleted unconditionally; the platform marks them as orphaned so an administrator can decide whether to retire them. This protects against data loss when references still exist.

## Walkthrough: a minimal config plugin

Consider a tiny Notes plugin with one model, one command, one page, and one menu entry.

**Directory layout:**

```text
com.acme.notes/
  plugin.json
  config/
    models.json
    commands.json
    permissions.json
    pages/
      notes-list.json
    menus.json
```

**`plugin.json`:**

```json
{
  "pluginId": "com.acme.notes",
  "namespace": "note",
  "version": "1.0.0",
  "pluginType": "config",
  "displayName": "Notes",
  "displayName:en": "Notes",
  "displayName:zh-CN": "便签",
  "description": "Lightweight personal notes",
  "author": "Acme",
  "minPlatformVersion": "1.0.0",
  "provides": [
    { "type": "model",   "code": "note_item" },
    { "type": "command", "code": "note:create" }
  ],
  "resourceDirs": {
    "models":      "config/models.json",
    "commands":    "config/commands.json",
    "permissions": "config/permissions.json",
    "menus":       "config/menus.json",
    "pages":       "config/pages"
  },
  "importOptions": {
    "conflictStrategy": "overwrite",
    "validateReferences": true,
    "autoPublishPages": true
  }
}
```

**`config/models.json`** declares one model:

```json
[
  {
    "code": "note_item",
    "displayName:en": "Note",
    "displayName:zh-CN": "便签",
    "modelType": "entity",
    "modelCategory": "document"
  }
]
```

**`config/commands.json`** registers the create command:

```json
[
  {
    "code": "note:create",
    "modelCode": "note_item",
    "type": "create",
    "displayName:en": "Create Note",
    "displayName:zh-CN": "新建便签",
    "permissions": ["note.note_item.create"]
  }
]
```

**`config/permissions.json`** declares the permission:

```json
[
  {
    "code": "note.note_item.create",
    "name:en": "Create Note",
    "name:zh-CN": "新建便签",
    "resourceType": "MODEL",
    "resourceCode": "note_item",
    "action": "CREATE",
    "module": "note"
  }
]
```

**`config/pages/notes-list.json`** carries the DSL schema for a list page (omitted here for brevity), and **`config/menus.json`** wires the menu entry to that page:

```json
[
  {
    "code": "note.menu.list",
    "name:en": "Notes",
    "name:zh-CN": "便签",
    "path": "/notes",
    "type": 1,
    "pageKey": "note.notes-list",
    "permissionCode": "note.note_item.create",
    "orderNo": 100
  }
]
```

After importing this package the platform has a working list page reachable from the sidebar, backed by a typed model and a single create command. There is no Java code and no React code. Every other CRUD command (update, delete, view) can be added by appending more entries to `commands.json` and binding them to the page.

## Hybrid plugin extensions

Sometimes JSON is not enough. A command that calls an external pricing service, a custom data provider that streams from a sensor gateway, or an action that emits a binary file cannot be expressed declaratively. Hybrid plugins solve this by adding a Java JAR alongside the JSON.

The manifest of a hybrid plugin adds a `backend` block:

```json
{
  "pluginType": "hybrid",
  "backend": {
    "jarPath": "backend/notes-extras-1.0.0.jar",
    "entryClass": "com.acme.notes.NotesExtrasPlugin"
  }
}
```

The platform loads the JAR through PF4J. The class named by `backend.entryClass` declares the plugin lifecycle hooks (`start`, `stop`). Inside the JAR you can register PF4J **extensions** that implement platform-defined extension points — most commonly `CommandHandler`, `DataProvider`, or custom action interfaces.

The connection between a JSON command and its Java handler is the binding rule. A command in `commands.json` carries an `executionConfig.handler` that names the handler bean or class, and the `bindingRules.json` file (registered in `resourceDirs.commands` alongside commands) declares additional binding metadata such as field mappings, event handlers, and triggers.

A critical packaging rule: binding rules must live in their own file. Inline `bindingRules` inside `commands.json` are not imported. The standalone `bindingRules.json` file is the only supported location, and it must be registered through `resourceDirs`.

When a hybrid plugin starts, the platform:

1. Loads the JAR into an isolated classloader.
2. Calls the entry point's `start()` hook.
3. Discovers `@Extension`-annotated classes and registers them with the host.
4. Wires JSON command handlers to their Java implementations through the binding rules.
5. Exposes the plugin's services to host code through SPI seams (background accessors, credential resolvers, tenant resolvers, and so on).

Stop and uninstall reverse the process. The classloader is torn down, extensions are unregistered, and any host caches keyed on the plugin's resources are invalidated.

## Solution packages

A solution plugin bundles other plugins into a deployable industry stack. The manifest sets `pluginType: solution` and uses `dependencies` to enumerate the constituent plugins:

```json
{
  "pluginId": "com.auraboot.solution.pcba",
  "namespace": "pcba",
  "version": "1.0.0",
  "pluginType": "solution",
  "displayName": "PCBA Manufacturing Solution",
  "dependencies": [
    { "pluginId": "com.auraboot.bom-standardization",     "version": "^1.0.0" },
    { "pluginId": "com.auraboot.manufacturing-execution", "version": "^1.0.0" },
    { "pluginId": "com.auraboot.quality-management",       "version": "^1.0.0" }
  ],
  "resourceDirs": {
    "menus":         "config/menus.json",
    "permissions":   "config/permissions.json",
    "pages":         "config/pages",
    "namedQueries":  "config/named-queries.json"
  }
}
```

The solution still ships its own `resourceDirs` — but the resources are typically cross-plugin glue: a curated landing dashboard, a top-level menu that arranges the constituent modules, named queries that span multiple models, and pre-tuned permissions and roles for the industry's typical job profiles.

Solutions differ from regular plugins in three ways:

- **Deployment.** Installing a solution triggers a transitive install of every dependency. Uninstall is symmetric.
- **Upgrade tooling.** Solution upgrades are coordinated across all dependencies; the platform refuses partial states.
- **Licensing posture.** Solutions are often the unit at which entitlements are sold. The `plans`, `features`, and `planFeatures` fields are typically declared at the solution level rather than on each constituent plugin.

If you are building a single feature, ship a config or hybrid plugin. Reach for `solution` only when you are packaging a curated industry stack.

## Validation and gates

Every plugin import must pass two gates.

**Static manifest validation** is a pre-check. The platform parses `plugin.json` against the JSON Schema, walks `resourceDirs` to confirm every referenced file exists, checks that inline resources match their schemas, and surfaces common authoring mistakes:

- `S-PAGE-LABEL` — a button or column has a non-standard or missing label that would leak a raw code to the UI.
- `S-PAGE-FORM-REQUIRED` — a required field is not declared in both the binding and the form schema.
- `S-PAGE-TABLE-DICT` — a list column references a dictionary code that is not declared.
- `S-PAGE-BUTTONS` empty — a toolbar block has no buttons.
- `S-EXT-HANDLER` — a binding rule references a Java handler that the plugin does not register.

A clean static check is necessary but not sufficient.

**Server-side import** is the authoritative gate. The platform's plugin import API parses the package, resolves dependencies and capabilities, imports each resource in topological order inside a single transaction, and runs cross-resource validation (references, permission integrity, page-model consistency, command-permission coverage). Only when the import API returns `success: true` is the plugin actually installed.

For day-to-day development, run the static checks early and often, then rely on the import API as the final gate. Static checks catch the bulk of authoring mistakes; the import API catches the structural ones that only emerge once all resources are loaded together.

## Enterprise extensions

The AuraBoot Enterprise distribution extends the manifest with additional capabilities for organizations that ship plugins as products: a private marketplace with signed bundles and versioned channels, an Entitlement service that gates `plans` and `features` per tenant, advanced solution-package upgrade tooling with staged rollout and one-click rollback, and policy controls for who can publish, install, and update plugins across multi-tenant deployments. The plugin manifest itself stays the same; the additional behavior is layered on top.

## Next steps

- [Command Pipeline](/docs/core-concepts/command-pipeline)
- [Permissions](/docs/core-concepts/permissions)
- [Models and Fields](/docs/core-concepts/models-and-fields)
- [Pages and Layouts](/docs/core-concepts/pages-and-layouts)
