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

## 3. Creating a Computed Field

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

## 4. Materialized Computed Fields

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

## 5. Rollup Fields

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

## 6. Auto-Number Fields

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

## 7. Cross-Model References in Expressions

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

The `_display` suffix is automatically populated by the reference enrichment system (see [Core Concepts: Fields](../core-concepts/fields-and-types.md)).

---

## 8. Complete Example: Order with Computed Total

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

## 9. Recalculation and Performance

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

---

## Troubleshooting

| Symptom | Cause | Solution |
|---------|-------|----------|
| Computed field shows blank | Expression error or missing dependency | Check `computeDependencies` includes all referenced fields |
| Materialized field not updating | Dependency list incomplete | Add the changed field to `computeDependencies` |
| `SpelEvaluationException` | Syntax error in expression | Validate SpEL syntax; check `#` prefix on field names |
| Rollup shows 0 | Wrong `foreignKey` or `aggregateField` | Verify field codes match exactly |
| Auto-number gaps | Records deleted after creation | Expected behavior; sequences don't backfill |
| Division by zero | Denominator field is 0 | Add null/zero check: `#count > 0 ? #total / #count : 0` |

---

## Next Steps

- [Data Import & Export](data-import-export.md) -- bulk-load data into your models
- [Notifications](notifications.md) -- trigger notifications when computed values change
