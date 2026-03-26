# HR Essentials Plugin Template

A complete HR management template covering employee records, attendance tracking, and leave request workflows for the AuraBoot platform.

## Domain: Human Resources

- **thr_employee** — Employee entity with lifecycle: `ACTIVE → ON_LEAVE (↔ ACTIVE) → RESIGNED`
- **thr_attendance** — Daily attendance records with check-in/check-out tracking and type classification
- **thr_leave_request** — Leave request with approval workflow: `PENDING → APPROVED / REJECTED / CANCELLED`

## Models

| Model | Fields | Commands | Lifecycle |
|-------|--------|----------|-----------|
| thr_employee | 9 fields (code, name, department, position, email, phone, hire_date, status, notes) | CREATE, UPDATE, on_leave, return_from_leave, resign, DELETE, detail, list | ACTIVE ↔ ON_LEAVE → RESIGNED |
| thr_attendance | 7 fields (code, employee_id, date, check_in, check_out, type, notes) | CREATE, UPDATE, DELETE, detail, list | No lifecycle (classification only) |
| thr_leave_request | 8 fields (code, employee_id, leave_type, start_date, end_date, days, status, reason) | CREATE, UPDATE, approve, reject, cancel, DELETE, detail, list | PENDING → APPROVED / REJECTED / CANCELLED |

## File Structure

```
plugins/templates/hr-essentials/
  plugin.json                              # Plugin manifest
  config/
    models.json                            # 3 models with semantic metadata
    fields/
      thr_employee.json                    # 9 fields (STRING, ENUM, DATE, TEXT)
      thr_attendance.json                  # 7 fields (STRING, REFERENCE, DATE, DATETIME, ENUM, TEXT)
      thr_leave_request.json               # 8 fields (STRING, REFERENCE, ENUM, DATE, DECIMAL, TEXT)
    bindings/
      thr_employee.json                    # Field-model binding with display config
      thr_attendance.json
      thr_leave_request.json
    commands/
      thr_employee.json                    # 8 commands: CREATE, UPDATE, 3x STATE_TRANSITION, DELETE, 2x QUERY
      thr_attendance.json                  # 5 commands: CREATE, UPDATE, DELETE, 2x QUERY
      thr_leave_request.json               # 8 commands: CREATE, UPDATE, 3x STATE_TRANSITION, DELETE, 2x QUERY
    pages/
      thr_employee_list.json               # LIST page with status tabs
      thr_employee_form.json               # FORM page with sections
      thr_employee_detail.json             # DETAIL page with leave request sub-table
      thr_attendance_list.json             # LIST page for attendance
      thr_attendance_form.json             # FORM page for attendance
      thr_attendance_detail.json           # DETAIL page for attendance
      thr_leave_request_list.json          # LIST page with status tabs
      thr_leave_request_form.json          # FORM page for leave requests
      thr_leave_request_detail.json        # DETAIL page with approve/reject/cancel actions
    dicts.json                             # 5 dictionaries
    permissions.json                       # 6 permissions (3 entities x manage/read)
    menus.json                             # Menu tree: root + 3 items
    i18n.json                              # Full zh-CN + en-US translations
    named-queries.json                     # 1 NQ: leave summary by employee
  README.md                               # This file
```

## Key Features Demonstrated

| Feature | Where |
|---------|-------|
| Bidirectional state transition (ACTIVE ↔ ON_LEAVE) | `thr:on_leave`, `thr:return_from_leave` |
| Multi-source state transition (ACTIVE/ON_LEAVE → RESIGNED) | `thr:resign` |
| Approval workflow (PENDING → APPROVED/REJECTED/CANCELLED) | Leave request commands |
| REFERENCE fields (parent-child) | `thr_at_employee_id`, `thr_lv_employee_id` |
| Sub-table in detail page | Employee detail → leave requests |
| List tabs by status | Employee list, leave request list |
| Named Query with JOIN | `thr_leave_summary_by_employee` |
| DECIMAL field type | `thr_lv_days` (supports half-day increments) |
| Preconditioned DELETE | Employee (ACTIVE only), leave request (PENDING only) |
| Confirm messages on destructive ops | `thr:resign`, `thr:delete_employee`, `thr:delete_leave_request` |
| Agent hints on all commands | All command files |

## Dictionaries

| Code | Values |
|------|--------|
| thr_department | ENGINEERING, SALES, MARKETING, FINANCE, HR, OPERATIONS, OTHER |
| thr_emp_status | ACTIVE, ON_LEAVE, RESIGNED |
| thr_att_type | NORMAL, LATE, EARLY_LEAVE, ABSENT |
| thr_leave_type | ANNUAL, SICK, PERSONAL, MATERNITY, OTHER |
| thr_leave_status | PENDING, APPROVED, REJECTED, CANCELLED |

## How to Use

1. **Import** via: `POST /api/plugins/import/import-directory-sync` with `directoryPath` pointing to this plugin directory
2. **Verify** menu appears under "HR" in the sidebar
3. **Test** the full lifecycle: create employee → record attendance → submit leave → approve/reject
