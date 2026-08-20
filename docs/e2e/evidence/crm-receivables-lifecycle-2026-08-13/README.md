# CRM receivables lifecycle true-stack evidence

- Date: 2026-08-13
- Runtime: `crm-invoice-allocation-refund-20260813-s137`
- Ports: backend `6537`, Vite `5237`, BFF `6237`
- Database: `auraboot_137`
- Sales PF4J JAR SHA-256 prefix: `7ef24fef749c`
- Playwright spec: `web-admin/tests/e2e/sales/crm-receivables-lifecycle.spec.ts`
- Final verdict: `1 passed` in `1.0m`, Chromium, one worker, retry=0, trace enabled, `--no-deps`
- Full Playwright trace: retained outside Git at `/Users/ghj/work/auraboot/.workspace/evidence/crm-receivables-lifecycle-2026-08-13/trace.zip`; the repository keeps the reviewed screenshots and manifest without adding an 88 MB binary to clone history.
- Focused manifest: `22 pass / 4 untested / 0 fail / 0 skipped`; Sales full inventory is `54 pass / 84 untested` across 94 commands and 44 pages.

The user journey creates fresh CRM and Sales records, then drives every new receivables state through real pages:

1. commercial customer invoice creation and issue;
2. confirmed collection allocation and invoice settlement;
3. allocation reversal with an immutable reversal fact;
4. corrected re-allocation;
5. cash-refund credit memo approval and application;
6. refund payment rejected without evidence;
7. evidence capture, refund approval/payment, and net collection rebuild;
8. final invoice, collection, payment-plan, contract, and order balance assertions.

No data migration is part of this development-stage slice.

## Screenshot manifest

- `01-customer-invoice-create.png` — commercial AR invoice form and statutory tax-document seam.
- `02-allocation-create.png` — explicit cash-to-invoice allocation form.
- `03-invoice-settled.png` — initial settled invoice balance.
- `04-allocation-reversal-audit.png` — reversed allocation plus immutable reversal fact.
- `05-credit-memo-create.png` — cash-refund credit memo source chain.
- `06-invoice-refund-pending.png` — credited invoice with refund due.
- `07-refund-evidence.png` — immutable refund source plus payment evidence.
- `08-refund-paid-audit.png` — paid refund traceability.
- `09-invoice-final-audit-chain.png` — final allocation, credit, and refund facts under the invoice.
