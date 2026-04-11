# AuraBoot Use Cases

AuraBoot powers solutions across industries. Each use case below shows a complete, production-ready business application built entirely through JSON DSL configuration -- no custom code required for core business logic.

## Capability Matrix

| Use Case | CRUD | State Machine | Dashboard | Multi-Model | Sub-Tables | AI | Automation | Named Queries | SLA | Email | Campaigns |
|----------|:----:|:-------------:|:---------:|:-----------:|:----------:|:--:|:----------:|:-------------:|:---:|:-----:|:---------:|
| [CRM](./crm.md) | x | x | x | x | x | x | x | x | x | x | x |
| Project Management | x | x | x | x | x | - | x | x | - | - | - |
| Procurement | x | x | x | x | x | - | x | x | - | - | - |
| Contract & Cost | x | x | x | x | x | - | x | x | - | - | - |
| Quality Management | x | x | x | x | x | - | x | x | - | - | - |
| Asset Management | x | x | x | x | x | - | - | x | - | - | - |
| Production | x | x | x | x | x | - | x | x | - | - | - |
| HR & Leave | x | x | x | x | - | - | x | x | - | - | - |
| Finance | x | x | x | x | x | - | x | x | - | - | - |
| Inventory | x | x | x | x | x | - | x | x | - | - | - |
| Compliance | x | x | x | x | - | - | x | x | - | - | - |
| [Logistics](./logistics.md) | x | x | - | x | x | - | - | - | - | - | - |

## Quick Links

| Use Case | Description | Status |
|----------|-------------|--------|
| [CRM](./crm.md) | Full-cycle customer relationship management: leads, opportunities, accounts, contacts, activities, quotes, campaigns, complaints, and SLA tracking | Flagship |
| Project Management | Task boards, Gantt charts, milestones, and resource allocation with Kanban views | Planned |
| Procurement | Purchase requisitions, supplier management, RFQ/RFP workflows, and approval chains | Planned |
| Contract & Cost | Contract lifecycle management with cost tracking, billing schedules, and budget alerts | Planned |
| [Quality Management](./quality-management.md) | IQC/PQC/FQC inspections, NCR, CAPA, SPC control charts, rework orders, batch traceability, and quality cost tracking | Complete |
| [Logistics](./logistics.md) | Shipment tracking, carrier management, delivery scheduling, tracking events, and delivery notes | Complete |
| [Compliance & Risk](./compliance.md) | Regulatory compliance frameworks (SOC 2, ISO 27001, GDPR), risk assessments, audits, evidence management, and dual prevention | Complete |

## Platform Capabilities Demonstrated

Across all use cases, AuraBoot demonstrates the following platform capabilities:

### Data Modeling
- **18 entity types** in CRM alone, with master, entity, document, reference, and transaction categories
- **Reference fields** linking models (e.g., Opportunity -> Account, Complaint -> Contact)
- **JSONB virtual fields** for polymorphic data (Activity stores type-specific fields in a JSON column)
- **Auto-generated codes** with configurable patterns (e.g., `OPP-{yyyyMMdd}-{seq}`)

### State Machines
- Declarative state transitions defined in command JSON
- Preconditions for destructive operations (delete only in early stages)
- Confirmation dialogs for irreversible transitions
- Multi-source transitions (e.g., "Mark Lost" from any active stage)

### Pages & UI
- **List pages** with status tabs, inline editing, search, and sort
- **Form pages** with grid layout, field grouping, and auto-save
- **Detail pages** with tabbed sub-tables, activity timelines, and action toolbars
- **Dashboard pages** with KPI stat cards, charts (bar, pie, line), and data tables

### Security
- Role-based access control (CRM Admin, Sales Representative, Service Agent)
- Field-level permissions (sensitive fields like phone/email restricted by role)
- Permission-gated toolbar buttons and row actions
- Data scope with owner-field based visibility

### Internationalization
- Full zh-CN / en-US bilingual support
- Three-layer i18n resolution: model -> field -> action
- Localized enum labels with color-coded tags

### AI Integration
- AI-powered lead scoring via toolbar action
- Agent hints on every command for LLM-driven automation

### Named Queries
- Complex SQL queries for dashboards and cross-model data retrieval
- Activity association graph traversal (account timeline spanning contacts and opportunities)
- Pipeline statistics, trend analysis, and win/loss ratios

## How Use Cases Are Built

Every use case follows the same pattern -- a plugin directory containing pure JSON configuration:

```
plugins/<use-case>/config/
  models.json          # Entity definitions
  fields/<model>.json  # Field schemas per model
  commands/<model>.json # CRUD + state transition commands
  pages/<page>.json    # List, form, detail, dashboard pages
  dicts.json           # Enum/dictionary definitions
  menus.json           # Navigation menu structure
  permissions.json     # Permission codes
  roles.json           # Role-permission bindings
  i18n.json            # Internationalization strings
  named-queries.json   # Custom SQL for dashboards
  bindings/            # Field-command bindings
  saved-views.json     # Preset list filters
  default-bootstrap.json # Initial role-permission setup
```

No Java code. No React components. No SQL migrations. Just JSON.
