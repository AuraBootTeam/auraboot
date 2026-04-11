# Plugin Development Overview

AuraBoot's plugin system lets you extend the platform with new business modules, custom logic, and UI components -- without modifying core source code. This document covers the architecture, plugin types, lifecycle, and how to choose the right approach for your use case.

---

## Architecture

AuraBoot uses a layered plugin architecture:

```
+---------------------------------------------------------------+
|                    AuraBoot Plugin System                      |
|                                                               |
|  +-------------------+                                        |
|  | Extension Points  |  Code-level interfaces                 |
|  | (Handlers,        |  (CommandHandler, Validator, etc.)     |
|  |  Validators, etc) |                                        |
|  +---------+---------+                                        |
|            |                                                  |
|            v                                                  |
|  +-------------------+                                        |
|  |   Plugin SDK      |  Lifecycle management                  |
|  |                   |  (install, enable, disable, uninstall) |
|  +---------+---------+                                        |
|            |                                                  |
|            v                                                  |
|  +-------------------+                                        |
|  |   Hot Loading     |  Runtime code loading                  |
|  |  PF4J (backend)   |  Module Federation (frontend)          |
|  +---------+---------+                                        |
|            |                                                  |
|            v                                                  |
|  +-------------------+                                        |
|  |   Unified Package |  Distributable plugin package          |
|  |  (ZIP / directory)|  config + backend JAR + frontend       |
|  +-------------------+                                        |
+---------------------------------------------------------------+
```

**Technology stack:**

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Backend hot loading | PF4J 3.13.0 + pf4j-spring | Java plugin hot loading with class isolation |
| Frontend hot loading | Module Federation (Vite) | Remote React component loading |
| Config import | Plugin Import API | DSL resource import (models, fields, commands, pages) |

---

## Plugin Types

### Config-Only Plugin

The simplest and most common plugin type. Consists entirely of JSON configuration files -- no Java or React code required. About **80% of business features** can be built this way.

**What you can do:**
- Define data models with fields, validations, and constraints
- Create CRUD commands (create, update, delete, query)
- Build state machines (draft -> submitted -> approved -> archived)
- Design list, form, and detail pages
- Define menus, permissions, and roles
- Add i18n translations and dictionaries
- Set up auto-generated fields (auto-numbering, timestamps, current user)
- Configure side effects (cascade operations on state changes)

**Example:** A Task Tracker, CRM module, or Inventory system where all logic follows standard CRUD + state machine patterns.

See: [Config-Only Plugin Tutorial](./config-only-plugin.md)

### Backend Plugin

A Java plugin packaged as a JAR, loaded at runtime by PF4J. Use this when you need custom business logic that cannot be expressed through DSL configuration.

**When to use:**
- Custom `CommandHandler` implementations (complex calculations, external API calls)
- Custom validators with dynamic rules
- Event listeners for async processing (webhooks, notifications)
- Custom data providers for dropdowns or lookups
- External system integrations (email, SMS, payment gateways)

**Example:** A "Send Email" command handler that calls an SMTP service when an approval is granted.

See: [Backend Plugin Guide](./backend-plugin.md)

### Frontend Plugin

A React module built with Vite and loaded via Module Federation. Use this when you need custom UI components that go beyond what the DSL page system provides.

**When to use:**
- Custom block renderers for the Page Designer (e.g., map view, Gantt chart)
- Custom dashboard widgets (e.g., specialized chart types)
- Custom field renderers for forms (e.g., rich text editor, color picker)
- Entirely custom pages with complex interactions

**Example:** A "Map View" block that renders records as pins on an interactive map.

See: [Frontend Plugin Guide](./frontend-plugin.md)

### Full-Stack Plugin

Combines config, backend, and frontend into a single distributable package. All three layers are installed atomically.

**When to use:**
- Complete business modules that need custom logic AND custom UI
- Features that require tight integration between server-side processing and client-side rendering

**Example:** A "Document Approval" system with a custom approval workflow engine (backend) and a document viewer component (frontend).

See: [Full-Stack Plugin Guide](./full-stack-plugin.md)

---

## Plugin Lifecycle

Every plugin follows a defined lifecycle:

```
develop --> validate --> publish --> activate
   |           |            |           |
   |           |            |           +-- Plugin is live, menus visible,
   |           |            |               commands executable
   |           |            |
   |           |            +-- Import into platform, models/commands/pages
   |           |                created in database
   |           |
   |           +-- Run `aura plugin validate` to check
   |               structural and semantic correctness
   |
   +-- Write JSON config files (and optionally
       Java/React code)
```

**State machine:**

```
  [not installed] --install--> [installed] --enable--> [enabled]
                                    ^                     |
                                    |                     |
                                    +------disable--------+
                                    |
                              [uninstall]
```

For config-only plugins, the lifecycle is simplified: `validate -> publish -> active`. The `aura plugin publish` CLI command handles import and activation in one step.

---

## Directory Structure

### Config-Only Plugin

```
plugins/my-plugin/
+-- plugin.json              # Plugin manifest (required)
+-- README.md                # Plugin documentation (optional)
+-- config/
    +-- models.json          # Model definitions
    +-- fields/              # Field definitions (one file per model)
    |   +-- my_model.json
    +-- bindings/            # Model-field bindings (one file per model)
    |   +-- my_model.json
    +-- commands/            # Command definitions (one file per model)
    |   +-- my_model.json
    +-- pages/               # Page schemas
    |   +-- my_model_list.json
    |   +-- my_model_form.json
    |   +-- my_model_detail.json
    +-- dicts.json           # Dictionary definitions
    +-- permissions.json     # Permission definitions
    +-- roles.json           # Role definitions
    +-- menus.json           # Menu definitions
    +-- i18n.json            # Internationalization entries
    +-- default-bootstrap.json  # Initial role-permission bindings
```

### Full-Stack Plugin (ZIP Package)

```
my-plugin.zip
+-- plugin.json              # Plugin manifest (required)
+-- config/                  # DSL config resources
|   +-- (same as config-only)
+-- backend/                 # PF4J JAR file
|   +-- my-plugin-1.0.0.jar
+-- frontend/                # Module Federation build output
    +-- remoteEntry.js       # Remote entry point
    +-- assets/
        +-- index-xxx.js
        +-- index-xxx.css
```

---

## Plugin Manifest (plugin.json)

The manifest is the entry point for every plugin. It declares metadata, dependencies, and resource locations.

**Minimal example:**

```json
{
  "pluginId": "com.example.task-tracker",
  "namespace": "tt",
  "version": "1.0.0",
  "pluginType": "config",
  "displayName": "Task Tracker",
  "displayName:zh-CN": "Task Tracker",
  "displayName:en": "Task Tracker",
  "description": "Simple task tracking with status workflow",
  "author": "Your Name",
  "minPlatformVersion": "1.0.0",
  "dependencies": [],
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
    "createResourcePermissions": true
  }
}
```

For a complete field reference, see: [Plugin Manifest Reference](./plugin-manifest-reference.md)

---

## Decision Tree: Which Plugin Type?

```
Do you need custom Java logic (external APIs, complex calculations)?
  |
  +-- No --> Do you need custom React components?
  |            |
  |            +-- No --> Config-Only Plugin
  |            |          (JSON only, ~80% of use cases)
  |            |
  |            +-- Yes --> Frontend Plugin
  |                        (React + Module Federation)
  |
  +-- Yes --> Do you also need custom React components?
               |
               +-- No --> Backend Plugin
               |          (Java JAR via PF4J)
               |
               +-- Yes --> Full-Stack Plugin
                           (Config + JAR + Frontend)
```

**Quick reference:**

| Scenario | Plugin Type | Difficulty |
|----------|------------|------------|
| Standard CRUD business module | Config-Only | Low |
| CRUD with status machine workflow | Config-Only | Low |
| Custom email/SMS notifications | Backend | Medium |
| Custom chart or map component | Frontend | Medium |
| External payment gateway integration | Backend | Medium |
| Complete ERP module with custom UI | Full-Stack | High |

---

## What's Next

| Guide | Description |
|-------|-------------|
| [Config-Only Plugin Tutorial](./config-only-plugin.md) | Step-by-step tutorial to build a complete plugin with JSON only |
| [Backend Plugin Guide](./backend-plugin.md) | Custom Java command handlers, validators, and data providers |
| [Frontend Plugin Guide](./frontend-plugin.md) | Custom React components via Module Federation |
| [Full-Stack Plugin Guide](./full-stack-plugin.md) | Combining config + backend + frontend |
| [Plugin Manifest Reference](./plugin-manifest-reference.md) | Complete plugin.json field reference |
