# PCBA Legacy E2E Specs

This directory preserves historical PCBA ERP/BOM 1.0 specs that depend on legacy pages,
commands, or plugin sets such as `pe_bom`, `pe_bom_line`, `pe_supplier`, `inv_warehouse`,
and old sales/procurement lifecycle flows.

These specs are intentionally excluded from the default, OSS, enterprise, smoke, critical,
and QuoteOps gates. They are not compatibility criteria for the current QuoteOps/BOM workflow.

Run them only when explicitly working on the legacy PCBA suite:

```bash
PW_PROFILE=pcba-legacy pnpm exec playwright test -c playwright.config.ts --project=pcba-legacy
```

