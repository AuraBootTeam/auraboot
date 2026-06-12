import { describe, expect, it } from 'vitest';
import { isUnconfiguredStubPage } from '../isUnconfiguredStubPage';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';

/**
 * Item-3: the platform's MetaModelServiceImpl.autoCreateDefaultPages emits an
 * unconfigured placeholder page tagged extension.auto_created=true (verified on
 * the isolated stack: /api/pages/key/webhook_delivery_log_list returns
 * {"extension":{"auto_created":true}}). Such a page renders a misleading empty
 * shell (raw-code title + zero-column table + "no data") instead of failing
 * loud. isUnconfiguredStubPage is the detector that drives the fail-fast path.
 */

// Partial schema mock — these tests only exercise the extension field.
const schema = (extension?: Record<string, unknown>): UnifiedSchema =>
  ({ extension }) as unknown as UnifiedSchema;

describe('isUnconfiguredStubPage', () => {
  it('flags an auto_created stub page (platform placeholder)', () => {
    expect(isUnconfiguredStubPage(schema({ auto_created: true }))).toBe(true);
  });

  it('does not flag a real plugin-defined page (no auto_created marker)', () => {
    expect(isUnconfiguredStubPage(schema({}))).toBe(false);
    expect(isUnconfiguredStubPage(schema(undefined))).toBe(false);
  });

  it('does not flag when auto_created is explicitly false', () => {
    expect(isUnconfiguredStubPage(schema({ auto_created: false }))).toBe(false);
  });

  it('handles the JSON string form "true" defensively', () => {
    // Backend stores JSONB {"auto_created": true}; guard the coerced string form
    // too, mirroring the Java guard isAutoCreatedStubPage (String.valueOf check).
    expect(isUnconfiguredStubPage(schema({ auto_created: 'true' }))).toBe(true);
  });

  it('returns false for null/undefined schema (defensive)', () => {
    expect(isUnconfiguredStubPage(null as unknown as UnifiedSchema)).toBe(false);
    expect(isUnconfiguredStubPage(undefined as unknown as UnifiedSchema)).toBe(false);
  });
});
