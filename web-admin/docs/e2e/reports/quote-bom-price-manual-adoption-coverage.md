# Quote BOM Price Manual Adoption E2E Coverage

Date: 2026-06-16

Scope: BOM price review tab on `qo_quote_common` detail page, including manual price entry from the review drawer.

## Test Added

| Spec                                                                        | Scenario                                                                                                                                                                                                                                                                      |
| --------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `web-admin/tests/e2e/pcba-solution/quote-bom-price-manual-adoption.spec.ts` | Seeds one quote line with a DeepSeek suggested price and a Kingdee not-found evidence row, opens the BOM price tab, reviews drawer evidence, records a manual price, and verifies adoption through UI, command payload, evidence row, quote line, and named-query projection. |

## Feature Coverage

| Feature Point                   | Coverage                                                                                                                                                                          |
| ------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| BOM price table column contract | Asserts headers are exactly material, required purchase quantity, source candidate columns, adopted price, adopted source, current status.                                        |
| Removed legacy table columns    | Asserts table headers no longer expose evidence, failure reason, or refresh as separate columns.                                                                                  |
| Current status wording          | Verifies not-found source status before manual adoption and `人工价已采用` after adoption.                                                                                        |
| Drawer evidence details         | Verifies suggested candidate, failed/not-found candidate, failure explanation, retry hint, and next action copy.                                                                  |
| Manual price form               | Opens `录入人工价`, verifies default submit action is `录入并采用`, and checks required unit-price validation.                                                                    |
| Manual command payload          | Captures `/api/meta/commands/execute/qo_quote_line_common:record_manual_price` and checks target line, `UPDATE`, source, price, supplier, source note, reason, and validity date. |
| Evidence persistence            | Reads `qo_price_evidence_common` and verifies manual evidence source, confirmed status, supplier, unit price, currency, override reason, validity date, and snapshot source note. |
| Adopted line persistence        | Reads `qo_quote_line_common` and verifies unit cost, line cost, currency, and risk update after adoption.                                                                         |
| Named-query projection          | Reads `qo_quote_bom_price_waterfall` and verifies adopted source is `人工`, current status is `人工价已采用`, and adopted price matches the manual price.                         |

No remaining delivered-scope gap is intentionally left out of this spec design.

## Execution Note

Static checks and Playwright test collection pass for this spec. Browser pass/fail evidence requires a runtime that includes both the `codex/review-drawer-action-form` web-admin changes and the `codex/quote-manual-price-adoption` aura-quote plugin changes; the current `127.0.0.1:5241` process points at an older overlaid runtime, so it was not used as pass evidence.
