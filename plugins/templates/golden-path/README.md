# Golden Path Plugin Template

A complete, production-quality reference implementation demonstrating all agent-ready plugin best practices for the AuraBoot platform.

## What This Template Demonstrates

### Domain: Task Management
A deliberately simple domain (tasks + comments) chosen so that the **plugin structure** is the focus, not business complexity.

- **gp_task** — Main entity with full state machine lifecycle: `NEW -> IN_PROGRESS -> DONE -> ARCHIVED`
- **gp_task_comment** — Child entity demonstrating parent-child REFERENCE relationship

### Agent-Ready Best Practices

| Practice | Where to Find |
|----------|---------------|
| `agent_hint` on every command | `config/commands/gp_task.json` |
| `cmd_risk_level` on every write command | All command files |
| `example_input` on CREATE commands | `gp:create_task` |
| `stateField` + `fromStates` + `toState` on STATE_TRANSITION | `gp:start_task`, `gp:complete_task`, `gp:archive_task` |
| `preconditions` on DELETE | `gp:delete_task` (only NEW tasks can be deleted) |
| `sideEffects` with `side_effect_description` | `gp:complete_task` (auto-creates completion comment) |
| `confirmMessage` on destructive operations | `gp:archive_task`, `gp:delete_task` |
| `semantic_description` on models | `config/models.json` |
| `domain_category` + `data_sensitivity` | `config/models.json` |
| `purpose` + `parameter_schema` on Named Queries | `config/named-queries.json` |
| i18n for all labels (zh-CN + en-US) | `config/i18n.json` |
| Dict definitions with colors | `config/dicts.json` |

### File Structure

```
plugins/templates/golden-path/
  plugin.json                          # Plugin manifest
  config/
    models.json                        # 2 models with semantic metadata
    fields/
      gp_task.json                     # 7 fields (STRING, TEXT, ENUM, DATE)
      gp_task_comment.json             # 3 fields (REFERENCE, TEXT, STRING)
    bindings/
      gp_task.json                     # Field-model binding with display config
      gp_task_comment.json
    commands/
      gp_task.json                     # 8 commands: CREATE, UPDATE, 3x STATE_TRANSITION, DELETE, 2x QUERY
      gp_task_comment.json             # 4 commands: CREATE, UPDATE, DELETE, QUERY
    pages/
      gp_task_list.json                # LIST page with tabs, search, inline edit
      gp_task_form.json                # FORM page with sections
      gp_task_detail.json              # DETAIL page with sub-table
      gp_task_comment_list.json        # LIST page for comments
      gp_task_comment_form.json        # FORM page for comments
    dicts.json                         # 2 dictionaries: task_status, task_priority
    permissions.json                   # 2 permissions: manage, read
    menus.json                         # Menu tree: root + 2 items
    i18n.json                          # Full zh-CN + en-US translations
    named-queries.json                 # 2 NQs: summary aggregate, overdue filter
  README.md                           # This file
```

## Agent-Ready Checklist

Use this checklist when building a new plugin to ensure agent compatibility:

### Commands
- [ ] Every command has `agent_hint` (30+ chars, describes what it does and key constraints)
- [ ] Every write command (CREATE, UPDATE, DELETE, STATE_TRANSITION) has `cmd_risk_level`
- [ ] Risk levels are accurate: L0=read, L1=safe write, L2=write with side effects, L3=bulk, L4=destructive
- [ ] CREATE commands have `autoSetFields` for generated values (codes, default status)
- [ ] CREATE commands have `example_input` showing realistic sample data
- [ ] STATE_TRANSITION commands have `stateField`, `fromStates`, `toState`
- [ ] DELETE commands have `preconditions` restricting which records can be deleted
- [ ] Destructive commands have `confirmMessage` (both zh-CN and en)
- [ ] Commands with side effects have `side_effect_description`

### Models
- [ ] Every model has `semantic_description` (what it represents in business terms)
- [ ] Every model has `domain_category` (PROJECT, SALES, HR, FINANCE, etc.)
- [ ] Every model has `data_sensitivity` (PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED)
- [ ] `extension.titleField` and `extension.subtitleField` are set
- [ ] `extension.icon` uses a valid Lucide icon name

### Fields
- [ ] All fields have `displayName:zh-CN` and `displayName:en`
- [ ] ENUM fields reference a `dictCode`
- [ ] REFERENCE fields have `extension.refModelCode` and `extension.refDisplayField`
- [ ] Constraints are explicit: `required`, `maxLength`, etc.
- [ ] Auto-generated fields have `extension.readOnly: true`

### Named Queries
- [ ] Every NQ has `purpose` (explains business rationale)
- [ ] Every NQ has `parameter_schema` (JSON Schema for input params)
- [ ] SQL uses physical column names (not DSL field codes)
- [ ] `tenant_id = #{params.tenantId}` is included in all WHERE clauses

### i18n
- [ ] Model labels: `model.{modelCode}._meta.label`
- [ ] Field labels: `model.{modelCode}.{fieldCode}.label`
- [ ] Action labels: `{namespace}.action.{commandCode}`
- [ ] Page titles: `{namespace}.page.{pageKey}.title`
- [ ] Both `zh-CN` and `en-US` values present

### Permissions
- [ ] `{MODULE}.{entity}.manage` for write operations
- [ ] `{MODULE}.{entity}.read` for read operations
- [ ] Each permission has `name:zh-CN`, `name:en`, `resourceType`, `module`

### Menus
- [ ] Root menu item (type=0) with no path
- [ ] Child menu items (type=1) with `path` matching DSL page routes
- [ ] `permissionCode` on every leaf menu
- [ ] DSL pages use `/dynamic/{model-code-with-hyphens}` path format

## How to Use This Template

1. **Copy** the `golden-path/` directory to `plugins/{your-plugin-name}/`
2. **Rename** the namespace from `gp` to your plugin namespace
3. **Replace** models, fields, commands with your domain entities
4. **Follow** the checklist above for every new resource
5. **Import** via: `POST /api/plugins/import/import-directory-sync` with `directoryPath` pointing to your plugin

## Risk Level Reference

| Level | Meaning | Examples |
|-------|---------|----------|
| L0 | Read-only, no side effects | QUERY commands, list/detail views |
| L1 | Safe write, easily reversible | CREATE, UPDATE with no cascading effects |
| L2 | Write with side effects or business logic | State transitions that trigger notifications, create child records |
| L3 | Bulk operations | Batch update, batch delete |
| L4 | Destructive, irreversible | DELETE, archive (if terminal state) |
