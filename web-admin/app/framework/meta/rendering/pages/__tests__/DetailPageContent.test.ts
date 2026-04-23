import { describe, expect, it } from 'vitest';
import { buildDetailRecordEndpoint, resolveDetailFieldComponent } from '../DetailPageContent';

describe('buildDetailRecordEndpoint', () => {
  it('builds the direct record endpoint used by detail pages', () => {
    expect(
      buildDetailRecordEndpoint('showcase_all_fields', '01KPTMPKJEAC6QHW08PE9JE62W'),
    ).toBe('/api/dynamic/showcase_all_fields/01KPTMPKJEAC6QHW08PE9JE62W');
  });
});

describe('resolveDetailFieldComponent', () => {
  it('maps file dataType to fileattachment for read-only detail rendering', () => {
    expect(resolveDetailFieldComponent({ dataType: 'file' })).toBe('fileattachment');
  });

  it('prefers explicit renderComponent over dataType defaults', () => {
    expect(
      resolveDetailFieldComponent({
        dataType: 'json',
        extension: { renderComponent: 'richtext' },
      }),
    ).toBe('richtext');
  });

  it('keeps existing primitive mappings for dates', () => {
    expect(resolveDetailFieldComponent({ dataType: 'date' })).toBe('date');
    expect(resolveDetailFieldComponent({ dataType: 'datetime' })).toBe('datetime');
  });
});
