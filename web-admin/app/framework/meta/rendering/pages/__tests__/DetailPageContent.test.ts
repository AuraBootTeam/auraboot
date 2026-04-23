import { describe, expect, it } from 'vitest';
import { buildDetailRecordEndpoint } from '../DetailPageContent';

describe('buildDetailRecordEndpoint', () => {
  it('builds the direct record endpoint used by detail pages', () => {
    expect(
      buildDetailRecordEndpoint('showcase_all_fields', '01KPTMPKJEAC6QHW08PE9JE62W'),
    ).toBe('/api/dynamic/showcase_all_fields/01KPTMPKJEAC6QHW08PE9JE62W');
  });
});
