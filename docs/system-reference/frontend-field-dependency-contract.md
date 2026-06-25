---
type: system-reference
status: active
---

# Frontend Field Dependency Contract

DSL form fields can declare parent-child selection dependencies with `dependsOn`
or `dependOn`.

Runtime contract:

- A dependent field is disabled while any declared parent value is blank.
- A dependent field must not receive its option `dataSource` while a parent value
  is blank; otherwise option loaders can issue an unfiltered query and expose
  records from every parent.
- Once the parent value is present, the original field `dataSource` is passed
  through unchanged and can use `${form.<field>}` or `${state.<field>}` in params.
- Field-level dependency gating is separate from data-source-level `dependOn`.
  `dataSource.dependOn` controls reload timing, while field `dependsOn` controls
  whether the user can interact with the picker and whether option loading is
  allowed at all.

Example:

```json
{
  "field": "bom_task_project_id",
  "component": "SmartSelect",
  "dependsOn": "bom_task_customer_id",
  "dataSource": {
    "type": "api",
    "endpoint": "/api/dynamic/req_requirement_set_pcba_bom/list",
    "method": "get",
    "params": {
      "bom_project_customer_id": "${form.bom_task_customer_id}"
    },
    "adaptor": "optionList",
    "valueField": "pid",
    "labelField": "bom_project_name",
    "autoFetch": false,
    "dependOn": ["form.bom_task_customer_id"]
  }
}
```

The example must render as: select customer first, then load only projects for
that customer.
