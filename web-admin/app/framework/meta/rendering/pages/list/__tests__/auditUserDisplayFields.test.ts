import { describe, expect, it } from 'vitest';
import {
  resolveAuditUserCellValue,
  resolveAuditUserDisplayFields,
} from '../auditUserDisplayFields';

describe('resolveAuditUserDisplayFields', () => {
  it('requests one projection hint for the visible creator column', () => {
    expect(
      resolveAuditUserDisplayFields({
        table: { columns: [{ field: 'name' }, { field: 'created_by' }, { field: 'updated_at' }] },
      }),
    ).toBe('created_by');
  });

  it('does not request a user lookup when audit columns are absent or hidden', () => {
    expect(resolveAuditUserDisplayFields({ columns: [{ field: 'name' }] })).toBeUndefined();
    expect(
      resolveAuditUserDisplayFields({ columns: [{ field: 'created_by', visible: false }] }),
    ).toBeUndefined();
  });

  it('whitelists and deduplicates creator/modifier fields', () => {
    expect(
      resolveAuditUserDisplayFields({
        columns: [
          { field: 'created_by' },
          { field: 'unknown_by' },
          { field: 'updated_by' },
          { field: 'created_by' },
        ],
      }),
    ).toBe('created_by,updated_by');
  });
});

describe('resolveAuditUserCellValue', () => {
  it('uses the safe display projection when the raw audit user id is omitted', () => {
    expect(
      resolveAuditUserCellValue(
        { created_by: null, created_by_display: 'Admin User' },
        'created_by',
        null,
      ),
    ).toBe('Admin User');
  });

  it('does not apply the audit projection contract to ordinary fields', () => {
    expect(resolveAuditUserCellValue({ status_display: '完成' }, 'status', null)).toBeNull();
  });
});
