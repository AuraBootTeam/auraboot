# Data Import & Export

Import data from CSV/Excel files, create records in bulk via the CLI, and export data from list pages.

## Goal

By the end of this guide you will be able to:

- Import contacts from a CSV file through the Upload UI
- Bulk-create records using the `aura exec` CLI command
- Export list page data in CSV or Excel format
- Handle validation errors and partial imports

## Prerequisites

- AuraBoot instance running (backend on port 6443, frontend on port 5173)
- A published model with fields defined (e.g., `crm_lead`)
- Aura CLI installed and authenticated (`aura login`)
- Admin or data-import permission for the target model

---

## 1. CSV / Excel Import via Upload UI

### Step 1: Navigate to the list page

Open the list page for the model you want to import data into (e.g., CRM Leads). Click the **Import** button in the toolbar.

### Step 2: Download the import template

Click **Download Template** to get a pre-formatted CSV or Excel file. The template includes:

- All writable fields as column headers
- Required fields marked with `*`
- Enum fields with allowed values listed in a comment row
- Reference fields expecting the referenced record's code or PID

### Step 3: Fill in your data

Open the template in your spreadsheet editor and populate rows. Follow these rules:

| Field Type | Format | Example |
|------------|--------|---------|
| STRING | Plain text | `Acme Corp` |
| INTEGER | Whole number | `42` |
| DECIMAL | Number with decimals | `1299.99` |
| BOOLEAN | `true` or `false` | `true` |
| DATE | `YYYY-MM-DD` | `2026-04-11` |
| DATETIME | `YYYY-MM-DDTHH:mm:ss` | `2026-04-11T14:30:00` |
| ENUM | Enum code (lowercase) | `new` |
| REFERENCE | Target record PID or code | `01ABC123` |

### Step 4: Upload the file

1. Click **Choose File** and select your populated CSV/Excel file
2. The system displays a **Column Mapping** screen showing:
   - Detected columns from your file (left)
   - Available model fields (right)
   - Auto-matched columns highlighted in green
3. Review and adjust any mismatched mappings
4. Click **Start Import**

### Step 5: Review results

After import completes, you see a summary:

```
Import Complete
  Total rows:     100
  Successful:      97
  Failed:           3
  Skipped:          0
```

Failed rows are available for download as a separate CSV with error details in an appended `_error` column.

---

## 2. Bulk Create via CLI

Use `aura exec` to create records from a JSON file without opening the browser.

### Step 1: Prepare a batch JSON file

Create `contacts.json`:

```json
[
  {
    "crm_lead_code": "LD-20260411-001",
    "crm_lead_company": "Acme Corp",
    "crm_lead_contact_name": "Alice Wang",
    "crm_lead_source": "website",
    "crm_lead_status": "new"
  },
  {
    "crm_lead_code": "LD-20260411-002",
    "crm_lead_company": "Beta Inc",
    "crm_lead_contact_name": "Bob Chen",
    "crm_lead_source": "referral",
    "crm_lead_status": "new"
  }
]
```

### Step 2: Execute the batch import

```bash
aura exec crm:create_lead --from contacts.json
```

Expected output:

```
Executing crm:create_lead (1/2)... OK (pid: 01HXYZ001)
Executing crm:create_lead (2/2)... OK (pid: 01HXYZ002)

Summary: 2 succeeded, 0 failed
```

### Step 3: Verify the imported data

```bash
aura query crm_lead -f "crm_lead_code~LD-20260411" -n 10
```

### Alternative: Single record creation

```bash
aura exec crm:create_lead \
  --set crm_lead_code="LD-20260411-003" \
  --set crm_lead_company="Gamma Ltd" \
  --set crm_lead_contact_name="Carol Li" \
  --set crm_lead_source=website \
  --set crm_lead_status=new
```

### Type annotations

Use type suffixes for non-string values:

```bash
aura exec inv:create_product \
  --set inv_product_name="Widget A" \
  --set inv_product_price:float=29.99 \
  --set inv_product_quantity:int=500 \
  --set inv_product_active:bool=true
```

Supported types: `string` (default), `int`, `float`, `bool`, `json`, `null`.

---

## 3. Data Export

### Export from the list page UI

1. Navigate to any list page
2. (Optional) Apply filters, sorting, or tab selection to narrow results
3. Click the **Export** button in the toolbar
4. Choose format:
   - **CSV** -- compatible with any spreadsheet application
   - **Excel (.xlsx)** -- preserves formatting and data types

The export respects the current view: only visible columns and filtered rows are included.

### Export via API

```bash
# Query and pipe to a file
aura query crm_lead -f "crm_lead_status=qualified" --format json > qualified_leads.json
```

For CSV output, pipe through `jq`:

```bash
aura query crm_lead --format json | \
  jq -r '(.[0] | keys_unsorted) as $keys | $keys, (.[] | [.[$keys[]]] ) | @csv' \
  > leads.csv
```

---

## 4. Complete Example: Import 100 Contacts from CSV

### Goal

Import 100 contacts into the `crm_lead` model from a CSV file.

### Step 1: Download the template

Navigate to **CRM > Leads**, click **Import > Download Template**.

### Step 2: Populate data

Fill in 100 rows in the downloaded template. Required columns:

| Column | Required | Notes |
|--------|----------|-------|
| `crm_lead_code` | Yes | Unique identifier, e.g., `LD-001` to `LD-100` |
| `crm_lead_company` | Yes | Company name |
| `crm_lead_contact_name` | Yes | Contact person |
| `crm_lead_source` | No | One of: `website`, `referral`, `trade_show`, `cold_call` |
| `crm_lead_status` | No | Defaults to `new` if omitted |

### Step 3: Upload

1. Click **Import > Upload File**
2. Select your CSV file
3. Verify column mapping (all 5 columns should auto-match)
4. Click **Start Import**

### Step 4: Verify

Check the list page shows 100 new leads, or verify via CLI:

```bash
aura query crm_lead -n 1 -s crm_lead_code:desc
```

---

## 5. Error Handling

### Validation failures

When a row fails validation, the import continues processing remaining rows. Common validation errors:

| Error | Cause | Fix |
|-------|-------|-----|
| `Required field missing` | A required column is empty | Fill in the value |
| `Invalid enum value` | Value not in allowed list | Use one of the valid enum codes |
| `Duplicate code` | Record with same code exists | Change the code or enable upsert mode |
| `Reference not found` | Referenced PID/code doesn't exist | Create the referenced record first |
| `Type mismatch` | e.g., text in a number column | Fix the data format |

### Partial imports

- Successfully imported rows are committed immediately
- Failed rows do not affect successful ones
- Download the error report to fix and re-import only the failed rows

### CLI error handling

When using `aura exec --from batch.json`, failures are reported per-record:

```
Executing crm:create_lead (1/5)... OK
Executing crm:create_lead (2/5)... FAILED: Duplicate code 'LD-001'
Executing crm:create_lead (3/5)... OK
...
Summary: 4 succeeded, 1 failed
```

---

## 6. Performance Tips

| Dataset Size | Recommended Method | Notes |
|--------------|--------------------|-------|
| < 100 rows | Upload UI | Simple, visual feedback |
| 100 - 1,000 rows | Upload UI or CLI | CLI avoids browser memory limits |
| 1,000 - 10,000 rows | `aura exec --from` | Batch JSON, processed server-side |
| > 10,000 rows | Split into multiple files | Process in batches of 5,000 |

**General tips:**

- Disable automations temporarily if import triggers expensive side effects
- Import reference data (e.g., categories, departments) before importing records that reference them
- Use `--dry-run` with the CLI to validate data before committing:

```bash
aura exec crm:create_lead --from contacts.json --dry-run
```

---

## Troubleshooting

| Symptom | Cause | Solution |
|---------|-------|----------|
| Import button not visible | Missing permission | Check `DYNAMIC.{model}.create` permission is assigned |
| All rows fail with "tenant" error | Missing tenant context | Re-login: `aura login` |
| CSV encoding issues (garbled text) | Non-UTF-8 encoding | Save CSV as UTF-8 in your spreadsheet editor |
| Excel date columns misread | Date format mismatch | Use `YYYY-MM-DD` format explicitly |
| CLI returns exit code 5 | Authentication expired | Run `aura login` to refresh token |

---

## Next Steps

- [Formulas and Expressions](formulas-and-expressions.md) -- compute fields automatically
- [CLI Reference](cli-reference.md) -- full command reference for `aura exec` and `aura query`
