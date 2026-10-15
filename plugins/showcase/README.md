# Showcase — All Field Types

A capability demonstration plugin for AuraBoot that creates a single model (`showcase_all_fields`) exercising every supported field type and Smart component.

## Purpose

- **Technical evaluation**: verify that all 22 field types render correctly in list, form, and detail views
- **Component gallery**: visual reference for field type → component mapping
- **Integration testing**: seed data target for E2E and API tests

## Field Types Covered

| # | Field | DataType | Component |
|---|-------|----------|-----------|
| 1 | sc_name | STRING | text input |
| 2 | sc_code | STRING | auto-generated (SC-{date}-{seq}) |
| 3 | sc_description | TEXT | textarea |
| 4 | sc_quantity | INTEGER | number input (0–99999) |
| 5 | sc_price | DECIMAL | currency input (14,2) |
| 6 | sc_is_active | BOOLEAN | switch |
| 7 | sc_start_date | DATE | date picker |
| 8 | sc_end_date | DATE | date picker |
| 9 | sc_created_at | DATETIME | datetime (readonly) |
| 10 | sc_status | ENUM | select (4 states) |
| 11 | sc_priority | ENUM | select (4 levels) |
| 12 | sc_category | ENUM | select (5 categories) |
| 13 | sc_tags | STRING | multiselect |
| 14 | sc_progress | INTEGER | progress bar (0–100) |
| 15 | sc_rating | INTEGER | star rating (0–5) |
| 16 | sc_color | STRING | color picker |
| 17 | sc_website | STRING | URL input |
| 18 | sc_email | STRING | email input |
| 19 | sc_phone | STRING | phone input |
| 20 | sc_richtext_content | TEXT | rich text editor |
| 21 | sc_attachment | JSON | file attachment |
| 22 | sc_remark | TEXT | textarea |

## State Machine

```
draft → active → review → archived
                    ↘ archived
```

## Installation

```bash
aura plugin publish plugins/showcase --yes
```

## Shared DSL page presentation

The showcase remains a config-only plugin. Its form uses four business sections; optional
sections opt into `extension.collapsible: true` and `extension.defaultCollapsed: true`.
Collapsing keeps controls mounted, so unsaved values are retained. Validation errors expand
the affected section. Other forms remain expanded unless they explicitly opt in.

The shared form renderer provides the section cards and a sticky action bar. AI filling is
not inserted automatically. A form that needs the feature must declare an `ai-fill-banner`
block explicitly; existing command, permission and field-lock contracts still apply.

The list defaults to seven business columns plus actions. Additional columns can be selected
through the existing field/view tools. Search and empty-state guidance come from the table DSL.
The detail header explicitly uses `extension.headerTitleField: "sc_name"` and
`extension.headerSubtitleFields: ["sc_code"]`; generic pages do not assume business field names.
