# CRM Demo Plugin

> Customer-facing CRM demonstration package for AuraBoot.

A polished 6-model CRM showcase intended for **customer demos and sales conversations** —
not a teaching template. It is a strict subset of the enterprise `crm` plugin; data captured
in this package upgrades seamlessly to the enterprise version with no migration.

## Positioning

| Audience | Goal |
|---|---|
| **Sales / Pre-sales** | Show a working CRM (kanban, dashboard, state machines) on the OSS edition |
| **Prospects evaluating AuraBoot** | Try a real CRM workflow before purchasing the enterprise edition |
| **Existing OSS customers** | Run a usable CRM until they upgrade |

For a minimal teaching template (3-model CRM walkthrough), see
[`crm-quick-start`](../crm-quick-start) instead.

## Features

### 6 business models
| Model | Code | Lifecycle |
|---|---|---|
| Account | `crm_account` | master, no status lifecycle |
| Contact | `crm_contact` | master, no status lifecycle |
| Lead | `crm_lead` | NEW → CONTACTED → QUALIFIED → CONVERTED / LOST |
| Opportunity | `crm_opportunity` | DISCOVERY → QUALIFICATION → PROPOSAL → NEGOTIATION → CLOSED_WON / CLOSED_LOST |
| Activity | `crm_activity` | activity log (call / visit / email / meeting / wechat / other) |
| Campaign | `crm_campaign` | PLANNED → ACTIVE → COMPLETED / CANCELLED |

### Pipeline & insight
- **Pipeline Board** — kanban grouped by `crm_opp_stage`, sum/count aggregations,
  drag-to-advance with terminal markers (won/lost)
- **Lead Board** — kanban grouped by `crm_lead_status`, drag-to-progress
- **Overview Dashboard** — recent opportunities + recent leads tables

### State machines
Opportunity advance commands (`qualify` / `advance_opp_to_proposal` /
`advance_opp_to_negotiation` / `win_opportunity` / `lose_opportunity`) and
campaign transitions (`activate_campaign` / `complete_campaign` / `cancel_campaign`).

## Difference from `crm-quick-start`

| Aspect | crm-quick-start | crm-starter (this) |
|---|---|---|
| Purpose | Teaching template | Customer demo |
| Models | 3 (account / contact / lead) | 6 (full CRM) |
| Kanban / dashboard | None | Pipeline Board + Lead Board + dashboard |
| State machines | Lead 5-state | Lead + Opportunity + Campaign |
| Upgrade target | None — pure tutorial | Strict subset of enterprise `crm` (data-compatible) |

## Upgrade to Enterprise CRM

This plugin shares the `crm` namespace and a strict subset of the enterprise
`crm` plugin schema (models / fields / dicts / commands). When the enterprise
plugin is installed:

1. Enterprise plugin imports with `conflictStrategy: overwrite` and takes over
   models, fields, dicts, commands, pages, menus.
2. **All existing data is retained** — table rows are not touched. New optional
   columns appear with NULL / default values where the enterprise plugin
   extended the schema (currency, exchange rate, lost reason, virtual JSONB
   activity status, etc).
3. Menu codes already align (`crm_*`); permission codes already lowercase
   (`crm.<resource>.<action>`).
4. Saved views and dashboards from this demo remain valid — enterprise package
   adds extra ones alongside.

No SQL migration is required for the upgrade path.

## Screenshots

> _TODO: Pipeline Board kanban screenshot._

## Resource layout

```
plugins/crm-starter/
├── plugin.json
├── README.md
├── config/
│   ├── models.json
│   ├── fields/<model>.json (6)
│   ├── bindings/<model>.json (6)
│   ├── commands/<model>.json (6)
│   ├── pages/crm_<model>_{list,form,detail}.json
│   ├── dashboards/crm_overview.json
│   ├── saved-views.json
│   ├── dicts.json
│   ├── menus.json
│   ├── permissions.json
│   ├── roles.json
│   └── i18n.json
```

## Install

```bash
aura plugin import --path plugins/crm-starter
```

## Compatibility

- Platform: AuraBoot ≥ 1.0.0
- Conflicts: namespaced as `crm`; will be cleanly overwritten by the enterprise
  `crm` plugin when installed.
