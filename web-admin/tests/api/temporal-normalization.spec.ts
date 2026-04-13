/**
 * Temporal Normalization API Tests
 *
 * Verifies that the PayloadTemporalNormalizer correctly handles date/datetime fields
 * at the HTTP boundary without requiring browser UI interaction.
 *
 * Pipeline position: runs after SCHEMA_VALIDATE and before ASSERT.
 *
 * Coverage:
 *   TN-01: Valid DATE string ("yyyy-MM-dd") is accepted and record is created
 *   TN-02: Valid DATETIME string with UTC offset (Z suffix) is accepted
 *   TN-03: Valid DATETIME string with explicit offset (+08:00) is accepted
 *   TN-04: DATETIME string without offset returns HTTP 400
 *   TN-05: DATE field with invalid format returns HTTP 400
 *   TN-06: X-Timezone header does not cause errors (header accepted on any request)
 *   TN-07: Null temporal field value is accepted (no parse attempt)
 *
 * Models used:
 *   - e2et_order     → e2et_order_date (DATE)
 *   - crm_activity   → crm_act_date (DATETIME), crm_act_start_time (DATETIME)
 *
 * @since 8.1.0
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../e2e/helpers';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Execute a command and return the raw response object.
 * Does not throw on non-OK HTTP status — callers assert themselves.
 */
async function executeRaw(
  page: Page,
  commandCode: string,
  payload: Record<string, unknown>,
  targetRecordId?: string,
  extraHeaders?: Record<string, string>,
): Promise<{ status: number; body: any }> {
  const data: Record<string, unknown> = { payload };
  if (targetRecordId) data.targetRecordId = targetRecordId;

  const resp = await page.request.post(`/api/meta/commands/execute/${commandCode}`, {
    data,
    headers: {
      'Content-Type': 'application/json',
      ...extraHeaders,
    },
  });

  let body: any = {};
  try {
    body = await resp.json();
  } catch {
    // ignore parse errors for error responses
  }

  return { status: resp.status(), body };
}

// ---------------------------------------------------------------------------
// DATE field tests  (model: e2et_order, field: e2et_order_date, command: e2et:create_order)
// ---------------------------------------------------------------------------

test.describe('Temporal Normalization — DATE fields', () => {
  test('TN-01: valid DATE string accepted and record created @smoke', async ({ page }) => {
    const { status, body } = await executeRaw(page, 'e2et:create_order', {
      e2et_order_title: `TN_DATE_${uniqueId()}`,
      e2et_order_type: 'normal',
      e2et_order_urgent: false,
      e2et_order_date: '2026-03-18',
    });

    // Should succeed — HTTP 200 and business code "0"
    expect(status).toBe(200);
    expect(String(body?.code ?? '')).toBe('0');

    // Cleanup: delete created record
    const recordId = body?.data?.data?.recordId ?? body?.data?.data?.pid ?? body?.data?.data?.id;
    if (recordId) {
      await executeRaw(page, 'e2et:delete_order', {}, String(recordId)).catch(() => {});
    }
  });

  test('TN-05: invalid DATE format returns HTTP 400', async ({ page }) => {
    const { status, body } = await executeRaw(page, 'e2et:create_order', {
      e2et_order_title: `TN_BADDATE_${uniqueId()}`,
      e2et_order_type: 'normal',
      e2et_order_urgent: false,
      // Not a valid ISO-8601 date — missing leading zeros and wrong separator
      e2et_order_date: '18/03/2026',
    });

    expect(status).toBe(400);
    // Response body should describe the parse failure
    const bodyStr = JSON.stringify(body).toLowerCase();
    expect(bodyStr).toMatch(/e2et_order_date|temporal|date|invalid|parse/);
  });

  test('TN-07: null DATE value is accepted (field omitted from normalization)', async ({
    page,
  }) => {
    const { status, body } = await executeRaw(page, 'e2et:create_order', {
      e2et_order_title: `TN_NULL_${uniqueId()}`,
      e2et_order_type: 'normal',
      e2et_order_urgent: false,
      e2et_order_date: null,
    });

    // Null values are skipped by the normalizer — request should succeed
    expect(status).toBe(200);
    expect(String(body?.code ?? '')).toBe('0');

    const recordId = body?.data?.data?.recordId ?? body?.data?.data?.pid ?? body?.data?.data?.id;
    if (recordId) {
      await executeRaw(page, 'e2et:delete_order', {}, String(recordId)).catch(() => {});
    }
  });
});

// ---------------------------------------------------------------------------
// DATETIME field tests  (model: crm_activity, field: crm_act_date / crm_act_start_time)
// ---------------------------------------------------------------------------

test.describe('Temporal Normalization — DATETIME fields', () => {
  test('TN-02: DATETIME with Z suffix (UTC) is accepted @smoke', async ({ page }) => {
    const { status, body } = await executeRaw(page, 'crm:create_activity', {
      crm_act_subject: `TN_DTZUTC_${uniqueId()}`,
      crm_act_type: 'call',
      crm_act_status: 'planned',
      // UTC datetime — normalizer must parse to Instant
      crm_act_date: '2026-03-18T02:30:00Z',
    });

    expect(status).toBe(200);
    expect(String(body?.code ?? '')).toBe('0');

    const recordId = body?.data?.data?.recordId ?? body?.data?.data?.pid ?? body?.data?.data?.id;
    if (recordId) {
      await executeRaw(page, 'crm:update_activity', {}, String(recordId)).catch(() => {});
    }
  });

  test('TN-03: DATETIME with explicit offset (+08:00) is accepted', async ({ page }) => {
    const { status, body } = await executeRaw(page, 'crm:create_activity', {
      crm_act_subject: `TN_DTZOFFSET_${uniqueId()}`,
      crm_act_type: 'meeting',
      crm_act_status: 'planned',
      // Datetime with +08:00 offset — normalizer must parse to Instant
      crm_act_date: '2026-03-18T10:30:00+08:00',
      crm_act_start_time: '2026-03-18T10:30:00+08:00',
      crm_act_end_time: '2026-03-18T12:00:00+08:00',
    });

    expect(status).toBe(200);
    expect(String(body?.code ?? '')).toBe('0');

    const recordId = body?.data?.data?.recordId ?? body?.data?.data?.pid ?? body?.data?.data?.id;
    if (recordId) {
      await executeRaw(page, 'crm:update_activity', {}, String(recordId)).catch(() => {});
    }
  });

  test('TN-04: DATETIME without offset returns HTTP 400 @smoke', async ({ page }) => {
    const { status, body } = await executeRaw(page, 'crm:create_activity', {
      crm_act_subject: `TN_DTNOOFFSET_${uniqueId()}`,
      crm_act_type: 'call',
      crm_act_status: 'planned',
      // No timezone offset — normalizer must reject this
      crm_act_date: '2026-03-18T10:30:00',
    });

    expect(status).toBe(400);
    // Error message should reference the field and expected format
    const bodyStr = JSON.stringify(body).toLowerCase();
    expect(bodyStr).toMatch(/crm_act_date|temporal|offset|datetime|invalid|parse/);
  });

  test('TN-06: X-Timezone header is forwarded without causing errors', async ({ page }) => {
    // X-Timezone is an informational header for client-side display purposes.
    // The normalizer does not use it (all datetimes must carry their own offset),
    // but the header must not break the pipeline.
    const { status, body } = await executeRaw(
      page,
      'crm:create_activity',
      {
        crm_act_subject: `TN_XTZHEADER_${uniqueId()}`,
        crm_act_type: 'email',
        crm_act_status: 'planned',
        crm_act_date: '2026-03-18T10:30:00+08:00',
      },
      undefined,
      { 'X-Timezone': 'Asia/Shanghai' },
    );

    // Request should succeed regardless of the X-Timezone header value
    expect(status).toBe(200);
    expect(String(body?.code ?? '')).toBe('0');

    const recordId = body?.data?.data?.recordId ?? body?.data?.data?.pid ?? body?.data?.data?.id;
    if (recordId) {
      await executeRaw(page, 'crm:update_activity', {}, String(recordId)).catch(() => {});
    }
  });
});
