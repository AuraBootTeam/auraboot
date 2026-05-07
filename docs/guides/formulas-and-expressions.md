# Formulas and Expressions

Define computed fields, rollup aggregations, and auto-number sequences using AuraBoot's expression engine.

## Goal

By the end of this guide you will be able to:

- Create formula fields that compute values from other fields
- Write SpEL expressions for math, string, date, and conditional logic
- Configure rollup fields (SUM, COUNT, AVG, MIN, MAX)
- Set up auto-number fields for automatic sequence generation
- Reference fields across related models

## Prerequisites

- AuraBoot instance running
- A model with existing fields to compute from
- Admin access or model-edit permission

---

## 1. Formula Fields Overview

Formula fields are **virtual fields** that derive their value from other fields. AuraBoot supports three types:

| Virtual Type | Stored in DB | Computed When | Use Case |
|-------------|-------------|---------------|----------|
| `COMPUTED_READONLY` | No | Every query | Display-only calculations (totals, labels) |
| `MATERIALIZED` | Yes | On write (insert/update) | Searchable computed values, indexes |
| `TRANSIENT` | No | In-page only | Form interaction logic, temporary variables |

### Choose the right computation layer

AuraBoot supports **two different computation layers** and they solve different problems:

| Layer | Where it runs | Expression style | Best for | Should persist? |
|------|---------------|------------------|----------|-----------------|
| Form runtime computed field | `web-admin` form page | JavaScript-like expression in `extension.formula` | Instant UI feedback, derived read-only form values, interactive summaries | Optional |
| Command computed field | backend command pipeline | SpEL in `computedFields` | Write-time normalization, stored values, post-write derived fields | Yes |

Use the **form runtime** when the user needs to see a value update immediately while editing.
Use the **command pipeline** when the backend must own the final stored value regardless of UI.

For business fields that drive workflow routing, reporting, filtering, or notifications, the recommended pattern is:

1. Keep the user-facing inputs editable.
2. Keep the business result field read-only.
3. Compute it in the form for immediate feedback.
4. Materialize or submit the computed result so backend workflow logic can rely on it.

This pattern avoids making users enter redundant values while still preserving a searchable and routable field.

---

## 2. Expression Syntax (SpEL)

AuraBoot uses **Spring Expression Language (SpEL)** for all formula expressions. Field values are accessed with the `#` prefix.

### Basic syntax

```
#fieldCode                    -- reference a field value
#fieldA + #fieldB             -- arithmetic
#field ?: 'default'           -- null-safe default (Elvis operator)
```

### Arithmetic operations

| Operation | Syntax | Example |
|-----------|--------|---------|
| Add | `+` | `#price + #tax` |
| Subtract | `-` | `#revenue - #cost` |
| Multiply | `*` | `#quantity * #unitPrice` |
| Divide | `/` | `#total / #count` |
| Modulo | `%` | `#number % 2` |
| Parentheses | `()` | `#price * (1 - #discount / 100)` |

### String functions

| Function | Example | Result |
|----------|---------|--------|
| Concatenation | `CONCAT(#firstName, ' ', #lastName)` | `"Alice Wang"` |
| Uppercase | `#code.toUpperCase()` | `"ABC123"` |
| Lowercase | `#name.toLowerCase()` | `"acme corp"` |
| Substring | `#code.substring(0, 3)` | `"ABC"` |
| Length | `#name.length()` | `8` |
| Contains | `#email.contains('@')` | `true` |
| Replace | `#phone.replace('-', '')` | `"13800138000"` |

### Date functions

| Function | Example | Result |
|----------|---------|--------|
| Current date | `T(java.time.LocalDate).now()` | `2026-04-11` |
| Days ago | `T(java.time.LocalDate).now().minusDays(#days)` | Depends on `#days` |
| Add months | `#startDate.plusMonths(#duration)` | Date + N months |
| Days between | `T(java.time.temporal.ChronoUnit).DAYS.between(#startDate, #endDate)` | Number of days |

### Conditional expressions

```
// Ternary operator
#status == 'active' ? 'Active' : 'Inactive'

// Nested conditions
#amount > 10000 ? 'VIP' : (#amount > 1000 ? 'Premium' : 'Standard')

// Null checks
#discount != null ? #price * (1 - #discount / 100) : #price
```

---

## 3. Form Runtime Computed Fields

For DSL-driven forms, you can declare a computed field directly on the field definition using `extension.computed`, `extension.formula`, and `extension.computeDependencies`.

This is the preferred approach when:

- the field is derived from other form inputs
- the user should see the result immediately
- the field should be read-only in the form

### Example: leave duration computed from dates and half-day slots

```json
{
  "code": "wd_req_days",
  "displayName:en": "Days",
  "displayName:zh-CN": "Days",
  "dataType": "decimal",
  "constraints": { "required": true, "precision": 6, "scale": 1 },
  "extension": {
    "computed": true,
    "readOnly": true,
    "placeholder:en": "Auto calculated",
    "computeDependencies": [
      "wd_req_start_date",
      "wd_req_start_slot",
      "wd_req_end_date",
      "wd_req_end_slot"
    ],
    "formula": "${!wd_req_start_date || !wd_req_end_date || !wd_req_start_slot || !wd_req_end_slot ? '' : (() => { const start = new Date(wd_req_start_date + 'T00:00:00'); const end = new Date(wd_req_end_date + 'T00:00:00'); const diffDays = Math.round((end.getTime() - start.getTime()) / 86400000); if (Number.isNaN(diffDays) || diffDays < 0) return ''; if (diffDays === 0 && wd_req_start_slot === 'PM' && wd_req_end_slot === 'AM') return ''; const startDeduction = wd_req_start_slot === 'PM' ? 0.5 : 0; const endDeduction = wd_req_end_slot === 'AM' ? 0.5 : 0; const value = diffDays + 1 - startDeduction - endDeduction; return value > 0 ? Number(value.toFixed(1)) : ''; })()}"
  }
}
```

### Runtime properties

| Property | Description |
|----------|-------------|
| `extension.computed` | Enables runtime computed behavior on form pages |
| `extension.formula` | JavaScript-like expression evaluated in the browser |
| `extension.computeDependencies` | Fields that trigger recomputation |
| `extension.readOnly` | Makes the target field non-editable |
| `extension.computeFallbackValue` | Optional fallback value when evaluation fails |

### Runtime expression scope

The form runtime evaluates `extension.formula` against current form values. Common globals available in expressions include:

- `Math`
- `Number`
- `String`
- `Boolean`
- `parseInt`
- `parseFloat`
- `isNaN`
- `Date`

Unlike backend SpEL formulas, form runtime formulas are evaluated in the browser and are intended for **interactive UI behavior**, not backend trust boundaries.

### Recommended UX pattern

When a value is computed from other inputs, do **not** ask the user to type it manually.

Use this structure instead:

- editable input fields: start date, end date, start session, end session
- read-only computed field: days
- optional helper text or placeholder: "Auto calculated"

This keeps the form intuitive and removes redundant input.

### Validation UX convention for computed forms

For forms that combine editable source fields with computed business fields, use a **two-layer validation UX**:

1. **Field-level errors** for simple, actionable input problems
2. **Page-level summary errors** for cross-field or server-side business rules

Recommended split:

| Error type | Where to show it | Example |
|-----------|------------------|---------|
| Required / format / length / single-field range | Directly under the field | `请选择申请人` |
| Cross-field rule | Top summary, and optionally highlight the target field | `结束日期不能早于开始日期` |
| Backend business validation | Top summary | `剩余年假不足，无法提交该申请` |

Use this convention:

- Do **not** show only the first required-field error in a top banner
- Do **show** required-field messages inline near each field
- Do **keep** complex business rules in a page-level summary area
- Do **not** model computed persisted fields as low-level schema-required when the value is derived from other inputs
- Instead, make the computed field read-only and enforce its presence through a business rule once the prerequisite inputs are valid

For leave-request style forms, the preferred behavior is:

- `申请人` empty → inline field error: `请选择申请人`
- `请假类型` empty → inline field error: `请选择请假类型`
- `结束日期 < 开始日期` → page summary error: `结束日期不能早于开始日期`
- legal date/session inputs but no computed `天数` → page summary or target-field error: `请完善开始/结束日期与时段，系统才能计算请假天数`

This produces a clearer correction path:

- users fix missing inputs where they entered them
- page-level banners stay reserved for multi-field or domain-level problems
- computed fields remain business-owned, not user-entered

---

## 4. Creating a Computed Field

### Via plugin configuration (fields.json)

Add a computed field to your plugin's `fields.json`:

```json
{
  "code": "order_total_amount",
  "displayName:en": "Total Amount",
  "displayName:zh-CN": "Total Amount",
  "dataType": "DECIMAL",
  "feature": {
    "virtualType": "COMPUTED_READONLY",
    "computeExpression": "#order_quantity * #order_unit_price",
    "computeDependencies": ["order_quantity", "order_unit_price"],
    "precision": 18,
    "scale": 2
  }
}
```

### Key properties

| Property | Description |
|----------|-------------|
| `virtualType` | One of `COMPUTED_READONLY`, `MATERIALIZED`, `TRANSIENT` |
| `computeExpression` | SpEL expression string |
| `computeDependencies` | Array of field codes this formula depends on |
| `precision` / `scale` | For DECIMAL results |
| `indexed` | (MATERIALIZED only) Whether to create a DB index |

### Via Field Designer UI

1. Open the model in the Field Designer
2. Click **Add Field**
3. Set data type to `DECIMAL` (or appropriate type)
4. In the **Advanced** section, set **Virtual Type** to `Computed (Read-only)`
5. Enter the expression in the **Compute Expression** field
6. List dependent fields in **Dependencies**
7. Save

---

## 5. Materialized Computed Fields

Materialized fields are computed on write and stored in the database. Use them when:

- The field needs to be searchable or sortable
- The computation is expensive
- You need to index the result

```json
{
  "code": "search_text",
  "dataType": "STRING",
  "feature": {
    "virtualType": "MATERIALIZED",
    "computeExpression": "CONCAT(#name, ' ', #code, ' ', #description)",
    "computeDependencies": ["name", "code", "description"],
    "indexed": true
  }
}
```

**Trigger behavior:**

| Event | Action |
|-------|--------|
| INSERT | Compute and store |
| UPDATE (dependency changed) | Recompute and store |
| UPDATE (no dependency changed) | Skip recomputation |

---

## 6. Rollup Fields

Rollup fields aggregate values from related (child) records. Configure them in the parent model.

### Supported aggregations

| Function | Description | Example |
|----------|-------------|---------|
| `SUM` | Sum of values | Total of all line item amounts |
| `COUNT` | Number of records | Number of tasks in a project |
| `AVG` | Average value | Average score across reviews |
| `MIN` | Minimum value | Earliest task start date |
| `MAX` | Maximum value | Latest task due date |

### Configuration

In the parent model's `fields.json`:

```json
{
  "code": "order_line_total",
  "displayName:en": "Order Total",
  "dataType": "DECIMAL",
  "feature": {
    "virtualType": "MATERIALIZED",
    "rollup": {
      "childModel": "order_line_item",
      "foreignKey": "order_line_order_id",
      "aggregateField": "order_line_amount",
      "function": "SUM"
    },
    "precision": 18,
    "scale": 2
  }
}
```

### Rollup properties

| Property | Description |
|----------|-------------|
| `childModel` | Model code of the child/related entity |
| `foreignKey` | Field in the child model that references the parent |
| `aggregateField` | Field in the child model to aggregate |
| `function` | `SUM`, `COUNT`, `AVG`, `MIN`, or `MAX` |

### Recalculation

Rollup fields are recalculated when:

- A child record is created, updated, or deleted
- The aggregated field value changes
- A manual recalculation is triggered

---

## 7. Auto-Number Fields

Auto-number fields generate sequential identifiers automatically when a record is created.

### Configuration

```json
{
  "code": "order_number",
  "displayName:en": "Order Number",
  "dataType": "STRING",
  "feature": {
    "autoNumber": {
      "prefix": "ORD-",
      "pattern": "{YYYY}{MM}{DD}-{SEQ:4}",
      "startFrom": 1,
      "incrementBy": 1
    }
  }
}
```

### Pattern tokens

| Token | Description | Example |
|-------|-------------|---------|
| `{YYYY}` | 4-digit year | `2026` |
| `{YY}` | 2-digit year | `26` |
| `{MM}` | 2-digit month | `04` |
| `{DD}` | 2-digit day | `11` |
| `{SEQ:N}` | Zero-padded sequence, N digits | `{SEQ:4}` -> `0001` |

### Example patterns

| Pattern | Output |
|---------|--------|
| `ORD-{YYYY}{MM}{DD}-{SEQ:4}` | `ORD-20260411-0001` |
| `INV-{SEQ:6}` | `INV-000001` |
| `{YY}{MM}-{SEQ:3}` | `2604-001` |
| `PO-{YYYY}-{SEQ:5}` | `PO-2026-00001` |

### Sequence reset

- Sequences can be configured to reset daily, monthly, yearly, or never
- Sequence generation is atomic and thread-safe (uses database sequences)
- Auto-number fields are read-only after creation

---

## 8. Cross-Model References in Expressions

You can reference fields from related models in expressions using dot notation through reference fields.

### Example: Display customer name on an order

If `order` has a REFERENCE field `order_customer_id` pointing to `customer`:

```json
{
  "code": "order_customer_display",
  "dataType": "STRING",
  "feature": {
    "virtualType": "COMPUTED_READONLY",
    "computeExpression": "#order_customer_id_display"
  }
}
```

The `_display` suffix is automatically populated by the reference enrichment system (see [Core Concepts: Models and Fields](../core-concepts/models-and-fields.md)).

---

## 9. Pattern: Editable Inputs + Persisted Business Field

Many business workflows need a field that is:

- derived from other fields
- visible in the form
- used later for routing, filtering, reporting, or notifications

In these cases, do **not** delete the business field from the model. Instead, change its role:

- from user-entered field
- to system-computed business field

### Recommended structure

| Concern | Recommended design |
|---------|--------------------|
| User input | Separate source fields |
| UI feedback | Runtime computed read-only field |
| Workflow / reporting / list sort | Persisted business field |
| Final authority | Backend command or stored payload |

### Leave-request example

For leave duration, prefer:

- `wd_req_start_date`
- `wd_req_start_slot`
- `wd_req_end_date`
- `wd_req_end_slot`
- `wd_req_days` as computed read-only business field

This is better than letting users type `wd_req_days` manually because:

- it removes duplicate data entry
- it supports half-day leave cleanly
- the workflow can still route on `wd_req_days`
- list pages and reports can still sort and aggregate by `wd_req_days`

### When to use a pure virtual field

Use a pure virtual field when the value is only for display, for example:

- summary text
- badge label
- temporary UI hint
- helper field that should never be queried or routed on

Use a persisted field when the value is part of business state.

---

## 10. Complete Example: Order with Computed Total

### Scenario

An order model with line items. The order should:
1. Auto-generate an order number
2. Compute the total as SUM of line item amounts
3. Show a status label based on the total

### Order model fields

```json
[
  {
    "code": "so_order_number",
    "dataType": "STRING",
    "feature": {
      "autoNumber": {
        "prefix": "SO-",
        "pattern": "{YYYY}{MM}-{SEQ:4}"
      }
    }
  },
  {
    "code": "so_order_total",
    "dataType": "DECIMAL",
    "feature": {
      "virtualType": "MATERIALIZED",
      "rollup": {
        "childModel": "so_order_line",
        "foreignKey": "so_line_order_id",
        "aggregateField": "so_line_amount",
        "function": "SUM"
      },
      "precision": 18,
      "scale": 2
    }
  },
  {
    "code": "so_order_tier",
    "dataType": "STRING",
    "feature": {
      "virtualType": "COMPUTED_READONLY",
      "computeExpression": "#so_order_total > 100000 ? 'enterprise' : (#so_order_total > 10000 ? 'business' : 'standard')",
      "computeDependencies": ["so_order_total"]
    }
  }
]
```

### Line item model fields

```json
[
  {
    "code": "so_line_order_id",
    "dataType": "REFERENCE",
    "refTarget": { "targetModel": "so_order" }
  },
  {
    "code": "so_line_quantity",
    "dataType": "INTEGER"
  },
  {
    "code": "so_line_unit_price",
    "dataType": "DECIMAL",
    "feature": { "precision": 18, "scale": 2 }
  },
  {
    "code": "so_line_amount",
    "dataType": "DECIMAL",
    "feature": {
      "virtualType": "MATERIALIZED",
      "computeExpression": "#so_line_quantity * #so_line_unit_price",
      "computeDependencies": ["so_line_quantity", "so_line_unit_price"],
      "precision": 18,
      "scale": 2
    }
  }
]
```

### Data flow

```
Line Item created:
  so_line_quantity=10, so_line_unit_price=99.99
  -> so_line_amount = 10 * 99.99 = 999.90 (MATERIALIZED)
  -> Parent order so_order_total recalculated: SUM(all line amounts)
  -> so_order_tier recomputed: "standard" (< 10,000)
```

---

## 11. Recalculation and Performance

### When are formulas recalculated?

| Type | Trigger | Performance Impact |
|------|---------|-------------------|
| `COMPUTED_READONLY` | Every query/list load | Low for simple expressions; avoid expensive operations |
| `MATERIALIZED` | On insert/update of dependencies | One-time cost at write; fast reads |
| Rollup | On child record create/update/delete | Proportional to child count |

### Best practices

- Use `MATERIALIZED` for fields that are frequently filtered or sorted
- Use `COMPUTED_READONLY` for display-only values that change often
- Keep `computeDependencies` accurate -- missing dependencies cause stale values
- Avoid circular dependencies (A depends on B, B depends on A)
- For complex multi-step calculations, chain materialized fields rather than writing one giant expression
- For form-derived business values, prefer `editable source fields + read-only computed target field`
- Do not rely on frontend formulas alone for high-value business invariants; the backend should still receive or recompute the authoritative result

### Date and time calculations

Date-range formulas often look simple but become fragile when they include:

- half-day sessions
- time zones
- working calendars
- lunch breaks
- business holidays

Recommendation:

- if the requirement is only full-day plus half-day, model it explicitly with date + session fields
- if the requirement is hourly leave or shift-aware leave, move to datetime inputs and backend-owned calendar logic

Avoid pushing complex scheduling semantics into one giant generic formula too early.

---

## Troubleshooting

| Symptom | Cause | Solution |
|---------|-------|----------|
| Computed field shows blank | Expression error or missing dependency | Check `computeDependencies` includes all referenced fields |
| Materialized field not updating | Dependency list incomplete | Add the changed field to `computeDependencies` |
| `SpelEvaluationException` | Syntax error in expression | Validate SpEL syntax; check `#` prefix on field names |
| Runtime computed field works in form but backend rejects data | Frontend formula and backend contract are out of sync | Ensure the computed value is submitted or recomputed in a command |
| Rollup shows 0 | Wrong `foreignKey` or `aggregateField` | Verify field codes match exactly |
| Auto-number gaps | Records deleted after creation | Expected behavior; sequences don't backfill |
| Division by zero | Denominator field is 0 | Add null/zero check: `#count > 0 ? #total / #count : 0` |

---

## Next Steps

- [Data Import & Export](data-import-export.md) -- bulk-load data into your models
- [Notifications](notifications.md) -- trigger notifications when computed values change
