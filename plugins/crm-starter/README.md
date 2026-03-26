# CRM Starter Plugin

A minimal 3-model CRM plugin for teaching AuraBoot plugin development. Extracted and simplified from the full CRM plugin.

## Models

| Model | Code | Description |
|-------|------|-------------|
| Account | `crm_account` | Customer company — the graph root. Has code, name, industry, website, phone, address, rating, owner, status. |
| Contact | `crm_contact` | Person linked to an Account (1:N via REFERENCE field `crm_ct_account_id`). Has name, title, email, phone, mobile, primary flag. |
| Lead | `crm_lead` | Unqualified sales prospect. Has a 5-state machine: NEW → CONTACTED → QUALIFIED → CONVERTED / LOST. |

## Relationships

```
Account (1) ──── (N) Contact
    crm_account          crm_ct_account_id → crm_account
```

Lead is standalone — it represents a prospect before it becomes an Account.

## Plugin Namespace

`crms` (CRM Starter) — distinct from the full CRM plugin namespace `crm` to allow both plugins to coexist.

## Included Resources

| Resource Type | Count | Details |
|---------------|-------|---------|
| Models | 3 | crm_account, crm_contact, crm_lead |
| Dicts | 5 | account_status, account_rating, lead_status, lead_source, contact_role |
| Commands | 14 | CRUD + state transitions for all 3 models |
| Pages | 9 | list / form / detail for each model |
| Menus | 4 | CRMS_ROOT + Accounts / Contacts / Leads |
| Permissions | 6 | manage + read for each model |
| Roles | 2 | CRMS_ADMIN, CRMS_SALES |

## Lead State Machine

```
NEW ──[contact]──> CONTACTED ──[qualify]──> QUALIFIED ──[convert]──> CONVERTED
 │                     │                       │
 └──────────────────[lose]────────────────────>┘
                                            LOST
```

State transition commands: `crms:contact_lead`, `crms:qualify_lead`, `crms:convert_lead`, `crms:lose_lead`

## Installation

Import via the plugin import API or AuraBoot CLI:

```bash
# CLI
auraboot plugin import ./plugins/crm-starter

# API
POST /api/plugins/import/import-directory-sync
Body: { "pluginDir": "plugins/crm-starter" }
```

After import, the plugin auto-publishes all models, fields, commands, and pages. Permissions are granted to `TENANT_ADMIN` via `default-bootstrap.json`.

## Key Concepts Demonstrated

1. **Models** (`config/models.json`) — Define entities with metadata like icon, titleField, subtitleField.
2. **Fields** (`config/fields/`) — Field definitions with data types, constraints, and searchability.
3. **Bindings** (`config/bindings/`) — Connect fields to models with visibility and edit rules.
4. **Commands** (`config/commands/`) — CRUD operations and state transitions with `autoSetFields`, `preconditions`, and `permissions`.
5. **Pages** (`config/pages/`) — DSL-driven list, form, and detail pages with toolbars, tabs, sub-tables.
6. **Dicts** (`config/dicts.json`) — Static enum definitions with colors for tag rendering.
7. **Permissions** (`config/permissions.json`) — Fine-grained read/manage permissions per model.
8. **Menus** (`config/menus.json`) — Sidebar navigation entries with permission guards.
9. **i18n** (`config/i18n.json`) — Full zh-CN + en-US translations for all labels.
10. **Bootstrap** (`config/default-bootstrap.json`) — Grant permissions to platform roles on install.

## Learning Next Steps

- Add a 4th model (e.g., `crm_opportunity`) referencing `crm_account` and see how REFERENCE fields work.
- Add a sub-table to account detail showing associated leads.
- Create a NamedQuery for cross-model aggregation.
- Extend with an L2 industry plugin that adds PCBA-specific fields to the account model.
