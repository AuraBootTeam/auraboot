# Report design templates

The report designer exchanges **definitions**, separately from PDF/Excel/JSON result downloads. The template actions appear below the editing toolbar. They require report management access and are disabled while the host is saving or loading. Read-only report views do not expose them.

- Export downloads `report.report-template.json`, including unsaved design edits.
- The envelope is `{ "format": "auraboot.report-template", "version": 1, "dsl": ReportDsl }`.
- Import requires the explicit report schema/version, valid supported block shapes, unique block IDs and resolvable data-source references. Both directions enforce a 2 MiB limit.
- Definitions contain source references and authored static content. They do not contain queried result rows, grants, tenant identity or persisted report identity. Access is checked again by the existing backend when sources are queried or the report is saved.
- Import replaces the current document as one undoable history action; it does not reset the saved baseline, change the target report PID or call the persistence API. Existing save and autosave behavior remains active. Import into a new report to create a separate saved definition; import into an existing report to edit that report.
- Named-query sources may declare fixed scalar `params`, for example `{"projectPid":"…"}`. Template exchange and preview preserve these parameters; the export service uses the same saved source scope. Transport controls such as `datasourceId`, `format` and `maxItems` cannot be overridden. Record authorization is still enforced by the named-query service. These fixed scope parameters are distinct from interactive model-filter report parameters.
- Invalid, unsupported or oversized input leaves the document intact and displays an inline error.
- Portable definitions must not be confused with `/api/reports/export/json`, which exports report **results**.

The shared `ReportDocumentProvider` uses the Unified Designer history kernel. No additional canvas or history store is introduced.

The customer fixture is `web-admin/tests/e2e/showcase/fixtures/customer-analysis.report-template.json`. It references `tpm_customer.tpm_cu_name` and `tpm_cu_contact`; import the project-management plugin before previewing its data. The browser journey enters through Metadata Management → Reports → New report and verifies the downloaded template and saved-definition readback.
