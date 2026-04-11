# AuraBoot Use Cases

AuraBoot powers solutions across industries. Each use case below shows a complete, production-ready business application built entirely through JSON DSL configuration -- no custom code required for core business logic.

## Capability Matrix

| Use Case | CRUD | State Machine | Dashboard | Multi-Model | Sub-Tables | BPM | Automation | Named Queries |
|----------|:----:|:-------------:|:---------:|:-----------:|:----------:|:---:|:----------:|:-------------:|
| [CRM](./crm.md) | ✓ | ✓ | ✓ | ✓ | ✓ | - | ✓ | ✓ |
| [Sales](./sales.md) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| [Project Management](./project-management.md) | ✓ | ✓ | ✓ | ✓ | ✓ | - | ✓ | ✓ |
| [Procurement](./procurement.md) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| [Manufacturing](./manufacturing.md) | ✓ | ✓ | ✓ | ✓ | ✓ | - | ✓ | ✓ |
| [Warehouse](./warehouse.md) | ✓ | ✓ | ✓ | ✓ | ✓ | - | ✓ | ✓ |
| [Logistics](./logistics.md) | ✓ | ✓ | - | ✓ | ✓ | - | - | - |
| [Finance](./finance.md) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| [Quality Management](./quality-management.md) | ✓ | ✓ | ✓ | ✓ | ✓ | - | ✓ | ✓ |
| [Compliance](./compliance.md) | ✓ | ✓ | ✓ | ✓ | - | - | ✓ | ✓ |
| [Asset Management](./asset-management.md) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | - | ✓ |
| [HR Leave Management](./hr-leave-management.md) | ✓ | ✓ | ✓ | ✓ | - | ✓ | ✓ | ✓ |
| [Knowledge Base](./knowledge-base.md) | ✓ | ✓ | - | ✓ | - | - | - | - |
| [PCBA Industry Solution](./pcba-industry-solution.md) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| [AI Agent Platform](./ai-agent-platform.md) | ✓ | ✓ | ✓ | ✓ | ✓ | - | ✓ | ✓ |

## Quick Links

| Use Case | Description | Models |
|----------|-------------|--------|
| [CRM](./crm.md) | Leads, opportunities, accounts, contacts, activities, complaints, and SLA tracking | 18 |
| [Sales](./sales.md) | Sales orders, quotations, shipments, returns, RMA, price lists, and discount rules | 17 |
| [Project Management](./project-management.md) | Projects, tasks, milestones, resources, timesheets, Kanban and Gantt views | 13 |
| [Procurement](./procurement.md) | Purchase orders, receipts, returns, supplier management, three-way match, contracts | 21 |
| [Manufacturing](./manufacturing.md) | Production orders, BOM management, work centers, routing, material planning | 28 |
| [Warehouse](./warehouse.md) | Inventory tracking, inbound/outbound, stock counts, transfers, pick orders, locations | 16 |
| [Logistics](./logistics.md) | Shipment tracking, carrier management, delivery scheduling, tracking events | 5 |
| [Finance](./finance.md) | Journal entries, invoicing, AP/AR, payments, bank reconciliation, cost accounting | 31 |
| [Quality Management](./quality-management.md) | IQC/PQC/FQC inspections, NCR, CAPA, SPC charts, batch traceability, rework orders | 16 |
| [Compliance](./compliance.md) | Regulatory frameworks, risk assessments, audits, evidence management, dual prevention | 13 |
| [Asset Management](./asset-management.md) | Equipment tracking, maintenance orders, locations, depreciation, lifecycle management | 4 |
| [HR Leave Management](./hr-leave-management.md) | Employee records, leave requests, multi-level approval, balance tracking | 2 |
| [Knowledge Base](./knowledge-base.md) | Documents, articles, categories, version tracking, RAG-powered AI search | 5 |
| [PCBA Industry Solution](./pcba-industry-solution.md) | End-to-end PCBA: CRM → Sales → Procurement → Manufacturing → QA → Shipping (10 plugins) | 60+ |
| [AI Agent Platform](./ai-agent-platform.md) | Multi-agent orchestration, autonomous AI employees, tool registry, governance | 16 |

## Platform Capabilities Demonstrated

Across all use cases, AuraBoot demonstrates:

### Data Modeling
- **200+ entity types** across all use cases, with master, entity, document, reference, and transaction categories
- **Reference fields** linking models across modules (e.g., Sales Order → CRM Account → Finance Invoice)
- **JSONB virtual fields** for polymorphic data storage
- **Auto-generated codes** with configurable patterns (e.g., `PO-{yyyyMMdd}-{seq}`)

### State Machines
- Declarative state transitions defined in command JSON
- Guard conditions (role-based, field-based, SpEL expressions)
- Side effects on transition (field updates, record creation, notifications)
- Multi-source transitions (e.g., "Cancel" from any active stage)

### Pages & UI
- **List pages** with status tabs, inline editing, search, sort, and saved views
- **Form pages** with grid layout, field grouping, conditional visibility
- **Detail pages** with tabbed sub-tables, activity timelines, and action toolbars
- **Dashboard pages** with KPI stat cards, charts (bar, pie, line, area), and data tables

### Cross-Module Integration
- PCBA Industry Solution demonstrates 10 plugins working together
- Procurement → Manufacturing → Quality → Warehouse data flow
- CRM → Sales → Finance revenue tracking chain

### Security
- Role-based access control with fine-grained permissions per module
- Field-level permissions (sensitive data restricted by role)
- Data scope filtering (owner-based, department-based visibility)

### Internationalization
- Full zh-CN / en-US bilingual support across all use cases
- Three-layer i18n resolution: model → field → action

## How Use Cases Are Built

Every use case follows the same pattern -- a plugin directory containing pure JSON configuration:

```
plugins/<use-case>/config/
  plugin.json            # Plugin manifest and metadata
  models.json            # Entity definitions
  fields/<model>.json    # Field schemas per model
  commands/<model>.json  # CRUD + state transition commands
  bindings/<model>.json  # Field-command bindings
  pages/<page>.json      # List, form, detail, dashboard pages
  dicts.json             # Enum/dictionary definitions
  menus.json             # Navigation menu structure
  permissions.json       # Permission codes
  roles.json             # Role-permission bindings
  i18n.json              # Internationalization strings
  named-queries.json     # Custom SQL for dashboards
  saved-views.json       # Preset list filters and views
  default-bootstrap.json # Initial role-permission setup
```

No Java code. No React components. No SQL migrations. Just JSON.

## Getting Started with Any Use Case

```bash
# 1. Install the plugin
aura plugin publish plugins/<use-case> --yes

# 2. Verify models were created
aura dsl show <model_code>

# 3. Navigate to the menu in your browser
# Open http://localhost:5173 → Sidebar → <Use Case Menu>

# 4. Seed sample data
aura exec <ns>:create_<model> --set field1="value1" --set field2="value2"
```

See each use case document for specific installation and verification steps.
