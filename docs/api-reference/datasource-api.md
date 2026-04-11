# Data Source API Reference

The Data Source API provides a unified interface for querying dictionary data and named queries. It powers dropdown options, reference field lookups, chart data, and custom report pages.

## Base Endpoint

```
GET /api/datasource/list
```

**Permission:** `datasource.read`

---

## Dictionary Data Sources

For static or dictionary-backed data sources, pass the dictionary code as `datasourceId`:

```bash
curl -s "http://localhost:6443/api/datasource/list?datasourceId=order_status" \
  -H "Authorization: Bearer <jwt>"
```

**Response:**

```json
{
  "code": "0",
  "message": "success",
  "data": [
    {
      "code": "draft",
      "key": "draft",
      "value": "draft",
      "label": "Draft",
      "name": "Draft",
      "description": "Order is in draft state",
      "disabled": false,
      "icon": null,
      "group": null,
      "order": 0,
      "extra": {}
    },
    {
      "code": "active",
      "key": "active",
      "value": "active",
      "label": "Active",
      "name": "Active",
      "order": 1
    }
  ]
}
```

### Data Source Item Fields

| Field | Type | Description |
|-------|------|-------------|
| `code` | string | Unique item code |
| `key` | string | Item key (usually same as code) |
| `value` | string/object | Item value |
| `label` | string | Display label |
| `name` | string | Item name (usually same as label) |
| `description` | string | Optional description |
| `disabled` | boolean | Whether the item is selectable |
| `icon` | string | Optional icon identifier |
| `group` | string | Optional grouping key |
| `order` | integer | Sort order |
| `extra` | object | Additional metadata |

---

## Named Query Data Sources

Named queries allow querying dynamic data through pre-defined SQL-backed queries. Use the `nq:{queryCode}` format:

### Options Format (Default)

Returns data as a list of `{value, label}` options, suitable for dropdowns and select fields:

```bash
curl -s "http://localhost:6443/api/datasource/list?datasourceId=nq:active_employees&labelField=full_name&valueField=id" \
  -H "Authorization: Bearer <jwt>"
```

**Response:**

```json
{
  "code": "0",
  "message": "success",
  "data": [
    {"code": "1", "key": "1", "value": 1, "label": "John Smith", "name": "John Smith", "order": 0},
    {"code": "2", "key": "2", "value": 2, "label": "Jane Doe", "name": "Jane Doe", "order": 1}
  ]
}
```

### Records Format

Returns raw row data, suitable for tables and charts. Add `format=records`:

```bash
curl -s "http://localhost:6443/api/datasource/list?datasourceId=nq:sales_by_month&format=records&maxItems=100" \
  -H "Authorization: Bearer <jwt>"
```

**Response:**

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "records": [
      {"month": "2026-01", "total_sales": 150000, "deal_count": 42},
      {"month": "2026-02", "total_sales": 180000, "deal_count": 51}
    ],
    "total": 12,
    "pageNum": 1,
    "pageSize": 100,
    "totalPages": 1
  }
}
```

---

## Parameters

### Control Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `datasourceId` | -- (required) | Dictionary code or `nq:{queryCode}` |
| `format` | `options` | `options` for dropdown data, `records` for raw row data |
| `maxItems` | `200` | Maximum number of items to return (max 1000) |
| `valueField` | `id` | Column to use as the value in options format |
| `labelField` | `name` | Column to use as the label in options format |
| `searchField` | -- | Column to search against when `keyword` is provided |
| `keyword` | -- | Search term applied to `searchField` using LIKE |
| `reportingCurrency` | -- | ISO currency code (e.g., `USD`). When set, monetary fields (`*_base`) get additional `*_reporting` columns |

### Business Parameters

Any additional query parameters (beyond the control parameters listed above) are passed through as named query parameters:

```bash
curl -s "http://localhost:6443/api/datasource/list?datasourceId=nq:team_members&department_id=5&role=manager" \
  -H "Authorization: Bearer <jwt>"
```

Here `department_id=5` and `role=manager` are forwarded as parameters to the named query's SQL template.

---

## Reporting Currency Conversion

When `reportingCurrency` is specified and the currency conversion service is configured, monetary fields in the result set are automatically converted:

```bash
curl -s "http://localhost:6443/api/datasource/list?datasourceId=nq:revenue_report&format=records&reportingCurrency=USD" \
  -H "Authorization: Bearer <jwt>"
```

For each column ending in `_base` (e.g., `total_amount_base`), a corresponding `_reporting` column is added (e.g., `total_amount_reporting`) with the converted value.

---

## Cascade Dictionaries

For hierarchical/cascade dictionaries (e.g., country > state > city), the data source returns root-level items. Children are loaded separately through the dictionary cascade API.

---

## Examples

### Populate a Status Dropdown

```bash
curl -s "http://localhost:6443/api/datasource/list?datasourceId=contract_status" \
  -H "Authorization: Bearer <jwt>"
```

### Searchable Employee Picker

```bash
curl -s "http://localhost:6443/api/datasource/list?datasourceId=nq:all_employees&valueField=pid&labelField=display_name&searchField=display_name&keyword=john" \
  -H "Authorization: Bearer <jwt>"
```

### Dashboard Chart Data

```bash
curl -s "http://localhost:6443/api/datasource/list?datasourceId=nq:pipeline_by_stage&format=records&maxItems=500" \
  -H "Authorization: Bearer <jwt>"
```

### Filtered Named Query with Business Parameters

```bash
curl -s "http://localhost:6443/api/datasource/list?datasourceId=nq:overdue_invoices&format=records&customer_id=CUST-001&days_overdue=30" \
  -H "Authorization: Bearer <jwt>"
```
