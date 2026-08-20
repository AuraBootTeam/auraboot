import { describe, expect, it } from 'vitest';

import { resolveBuiltInBulkCapabilities, selectBulkEditableColumns } from '../bulkCapabilities';

describe('CRM-safe built-in bulk capabilities', () => {
  it('preserves legacy defaults when a page has no explicit config', () => {
    expect(resolveBuiltInBulkCapabilities(undefined, () => false, 0)).toEqual({
      edit: true,
      delete: true,
      export: true,
    });
  });

  it('gates each configured operation by capability and permission', () => {
    const resolved = resolveBuiltInBulkCapabilities(
      {
        edit: { permissionCode: 'crm.opportunity.manage' },
        delete: false,
        export: { permissionCode: 'crm.opportunity.read' },
      },
      (permissionCode) => permissionCode === 'crm.opportunity.read',
      3,
    );

    expect(resolved).toEqual({ edit: false, delete: false, export: true });
  });

  it('keeps state-machine fields out of strict bulk edit', () => {
    const columns = [
      { field: 'crm_opp_name', editable: true },
      { field: 'crm_opp_stage', editable: false },
      { field: 'actions', isActionColumn: true },
    ];

    expect(selectBulkEditableColumns(columns, true).map((column) => column.field)).toEqual([
      'crm_opp_name',
    ]);
  });
});
