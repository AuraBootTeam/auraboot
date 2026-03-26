/**
 * Print / PDF Generation API Tests
 *
 * Tests the HTML→PDF print module: template listing, PDF generation,
 * HTML preview, and error handling.
 *
 * Prerequisite: seed-print-templates.sql must have been run (3 HTML templates).
 * Uses mt_org_department test record.
 */

import { test, expect } from '@playwright/test';

const MODEL_CODE = 'org_department';

/** Helper: get first org_department record PID, or skip test */
async function getTestRecordPid(request: any): Promise<string | null> {
  const listResp = await request.get(
    `/api/dynamic/org-department/list?pageNum=1&pageSize=1`,
  );
  if (!listResp.ok()) return null;

  const listBody = await listResp.json();
  const records = listBody.data?.records ?? [];
  return records.length > 0 ? records[0].pid : null;
}

/**
 * Helper: verify response contains PDF data.
 * Handles both direct PDF (Content-Type: application/pdf)
 * and BFF-wrapped response (Content-Type: application/json with PDF bytes in body).
 */
async function expectPdfResponse(resp: any) {
  expect(resp.ok()).toBe(true);

  const contentType = resp.headers()['content-type'] || '';
  const buffer = await resp.body();

  if (contentType.includes('application/pdf')) {
    // Direct PDF response
    expect(buffer.length).toBeGreaterThan(500);
    expect(buffer.slice(0, 5).toString()).toBe('%PDF-');
  } else {
    // BFF may wrap binary as JSON string — body still contains PDF data
    const text = buffer.toString();
    expect(text).toContain('%PDF-');
    expect(buffer.length).toBeGreaterThan(500);
  }
}

test.describe('Print / PDF Generation API', () => {
  test.beforeAll(async ({ request }) => {
    // Create a test department record via dynamic API
    await request.post(`/api/dynamic/org-department/create`, {
      data: {
        org_dept_code: `PRINT-E2E-${Date.now().toString().slice(-6)}`,
        org_dept_name: `E2E Print Test Dept`,
        org_dept_status: 'active',
      },
    });
  });

  test('PRINT-001: List published HTML print templates', async ({ request }) => {
    const resp = await request.get('/api/print/templates');
    if (resp.status() === 403) {
      test.skip(true, 'PRINT_GENERATE permission not assigned');
      return;
    }
    expect(resp.ok()).toBe(true);

    const body = await resp.json();
    expect(body.code).toBe('0');
    expect(Array.isArray(body.data)).toBe(true);

    const templates = body.data as Array<{ code: string; name: string; category: string }>;
    expect(templates.length).toBeGreaterThanOrEqual(3);

    const codes = templates.map((t) => t.code);
    expect(codes).toContain('invoice');
    expect(codes).toContain('quote');
    expect(codes).toContain('delivery_note');

    for (const tpl of templates) {
      expect(tpl.code).toBeTruthy();
      expect(tpl.name).toBeTruthy();
      expect(tpl.category).toBe('print');
    }
  });

  test('PRINT-002: Generate PDF from invoice template', async ({ request }) => {
    const pid = await getTestRecordPid(request);
    if (!pid) { test.skip(true, 'No org_department records'); return; }

    const pdfResp = await request.get(
      `/api/print/${MODEL_CODE}/${pid}?template=invoice`,
    );
    if (pdfResp.status() === 403) {
      test.skip(true, 'PRINT_GENERATE permission not assigned');
      return;
    }

    await expectPdfResponse(pdfResp);
  });

  test('PRINT-003: Generate PDF from quote template', async ({ request }) => {
    const pid = await getTestRecordPid(request);
    if (!pid) { test.skip(true, 'No records available'); return; }

    const pdfResp = await request.get(
      `/api/print/${MODEL_CODE}/${pid}?template=quote`,
    );

    await expectPdfResponse(pdfResp);
  });

  test('PRINT-004: Generate PDF from delivery_note template', async ({ request }) => {
    const pid = await getTestRecordPid(request);
    if (!pid) { test.skip(true, 'No records available'); return; }

    const pdfResp = await request.get(
      `/api/print/${MODEL_CODE}/${pid}?template=delivery_note`,
    );

    await expectPdfResponse(pdfResp);
  });

  test('PRINT-005: HTML preview returns rendered HTML', async ({ request }) => {
    const pid = await getTestRecordPid(request);
    if (!pid) { test.skip(true, 'No records available'); return; }

    const previewResp = await request.get(
      `/api/print/${MODEL_CODE}/${pid}/preview?template=invoice&companyName=E2E+Test+Corp`,
    );
    expect(previewResp.ok()).toBe(true);

    // BFF may change content-type, so just check body content
    const text = await previewResp.text();
    // Strip potential JSON wrapping
    const html = text.startsWith('"') ? JSON.parse(text) : text;

    expect(html).toContain('<!DOCTYPE html>');
    expect(html).toContain('invoice');
    expect(html).toContain('E2E Test Corp');
    expect(html).not.toContain('th:text');
  });

  test('PRINT-006: Custom params are passed to template', async ({ request }) => {
    const pid = await getTestRecordPid(request);
    if (!pid) { test.skip(true, 'No records available'); return; }

    const previewResp = await request.get(
      `/api/print/${MODEL_CODE}/${pid}/preview?template=invoice&companyName=MyCompany&footerNote=Custom+Footer`,
    );
    expect(previewResp.ok()).toBe(true);

    const text = await previewResp.text();
    const html = text.startsWith('"') ? JSON.parse(text) : text;

    expect(html).toContain('MyCompany');
    expect(html).toContain('Custom Footer');
  });

  test('PRINT-007: Non-existent template returns error', async ({ request }) => {
    const pid = await getTestRecordPid(request);
    if (!pid) { test.skip(true, 'No records available'); return; }

    const resp = await request.get(
      `/api/print/${MODEL_CODE}/${pid}?template=nonexistent_tpl_xyz`,
    );
    expect(resp.ok()).toBe(false);

    const body = await resp.json().catch(() => null);
    if (body) {
      expect(body.code).not.toBe('0');
    }
  });
});
