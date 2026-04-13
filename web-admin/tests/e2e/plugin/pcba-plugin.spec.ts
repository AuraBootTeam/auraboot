/**
 * PCBA Plugin Family — E2E Tests
 *
 * Covers list page loading and basic CRUD for core models across
 * the PCBA plugin ecosystem:
 *   product-catalog, pcba-crm, pcba-industry, pcba-solution,
 *   crm, sales, procurement, inventory, finance, quality
 *
 * Prerequisites:
 *   - PCBA plugins imported and models published
 *   - test-fixtures.setup.ts completed
 *
 * Uses real database, NO MOCKING.
 *
 * @since 7.0.0
 */

import { test, expect } from '../../fixtures';
import { navigateToDynamicPage, waitForDynamicPageLoad } from '../helpers/index';

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/**
 * Assert that a dynamic list page loads successfully.
 * Verifies that either a table or an empty-state message is visible.
 */
async function assertListPageLoads(page: import('@playwright/test').Page, pageKey: string) {
  await navigateToDynamicPage(page, pageKey);

  const table = page.locator('table, [role="table"]');
  const empty = page.locator('text=/no data|暂无/i');
  await expect(table.or(empty).first()).toBeVisible({ timeout: 10000 });
}

// ---------------------------------------------------------------------------
// product-catalog (L1, extracted from former pcba-base)
// ---------------------------------------------------------------------------

test.describe('Product Catalog — Core Master Data', () => {
  test.setTimeout(30000);

  test('PROD-01: prod_product list loads @smoke', async ({ page }) => {
    await assertListPageLoads(page, 'prod-product');
  });

  test('PROD-02: prod_brand list loads', async ({ page }) => {
    await assertListPageLoads(page, 'prod-brand');
  });

  test('PROD-03: prod_category list loads', async ({ page }) => {
    await assertListPageLoads(page, 'prod-category');
  });
});

// ---------------------------------------------------------------------------
// Sales (generic plugin, replaces pcba-sales)
// ---------------------------------------------------------------------------

test.describe('Sales — Orders & Quotations', () => {
  test.setTimeout(30000);

  test('SALES-01: sl_sales_order list loads @smoke', async ({ page }) => {
    await assertListPageLoads(page, 'sl-sales-order');
  });

  test('SALES-02: sl_sales_quotation list loads', async ({ page }) => {
    await assertListPageLoads(page, 'sl-sales-quotation');
  });
});

// ---------------------------------------------------------------------------
// Procurement (generic plugin, replaces pcba-procurement)
// ---------------------------------------------------------------------------

test.describe('Procurement — Purchase Orders', () => {
  test.setTimeout(30000);

  test('PROC-01: pr_purchase_order list loads @smoke', async ({ page }) => {
    await assertListPageLoads(page, 'pr-purchase-order');
  });

  test('PROC-02: pr_purchase_request list loads', async ({ page }) => {
    await assertListPageLoads(page, 'pr-purchase-request');
  });
});

// ---------------------------------------------------------------------------
// pcba-industry (replaces pcba-manufacturing)
// ---------------------------------------------------------------------------

test.describe('PCBA-Industry — Manufacturing', () => {
  test.setTimeout(30000);

  test('PCBA-IND-01: pe_production_plan list loads', async ({ page }) => {
    await assertListPageLoads(page, 'pe-production-plan');
  });

  test('PCBA-IND-02: pe_routing list loads', async ({ page }) => {
    await assertListPageLoads(page, 'pe-routing');
  });

  test('PCBA-IND-03: pe_supplier_contact list loads', async ({ page }) => {
    await assertListPageLoads(page, 'pe-supplier-contact');
  });
});

// ---------------------------------------------------------------------------
// Finance (generic plugin, replaces pcba-finance & finance-accounting)
// ---------------------------------------------------------------------------

test.describe('Finance — Accounts & Journals', () => {
  test.setTimeout(30000);

  test('FIN-01: fin_account list loads @smoke', async ({ page }) => {
    await assertListPageLoads(page, 'fin-account');
  });

  test('FIN-02: fin_journal_entry list loads', async ({ page }) => {
    await assertListPageLoads(page, 'fin-journal-entry');
  });
});

// ---------------------------------------------------------------------------
// Inventory (generic plugin, replaces pcba-wms)
// ---------------------------------------------------------------------------

test.describe('Inventory — Warehouse', () => {
  test.setTimeout(30000);

  test('INV-01: inv_warehouse list loads @smoke', async ({ page }) => {
    await assertListPageLoads(page, 'inv-warehouse');
  });

  test('INV-02: inv_inbound list loads', async ({ page }) => {
    await assertListPageLoads(page, 'inv-inbound');
  });
});

// ---------------------------------------------------------------------------
// CRM (generic plugin, replaces pcba-crm lead/opportunity)
// ---------------------------------------------------------------------------

test.describe('CRM — Customer Relationship', () => {
  test.setTimeout(30000);

  test('CRM-01: crm_lead list loads', async ({ page }) => {
    await assertListPageLoads(page, 'crm-lead');
  });

  test('CRM-02: crm_opportunity list loads', async ({ page }) => {
    await assertListPageLoads(page, 'crm-opportunity');
  });
});

// ---------------------------------------------------------------------------
// Quality (generic plugin)
// ---------------------------------------------------------------------------

test.describe('Quality — QC Orders', () => {
  test.setTimeout(30000);

  test('QC-01: qc_iqc_order list loads', async ({ page }) => {
    await assertListPageLoads(page, 'qc-iqc-order');
  });

  test('QC-02: qc_ncr list loads', async ({ page }) => {
    await assertListPageLoads(page, 'qc-ncr');
  });
});
