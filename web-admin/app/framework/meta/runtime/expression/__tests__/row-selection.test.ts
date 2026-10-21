import { describe, expect, it } from 'vitest';
import { initializeSelectedRows, positiveRowValue, selectRows } from '../row-selection';
import { projectRows } from '../row-projection';
import { blockSchema } from '../../../validation/schemas/block.schema';
const rule = { identityField: 'pid', selectedField: 'selected', positiveField: 'remaining' };
describe('Bounded declarative selection with current exact facts', () => {
  it('selects only positive current facts and projects exact payload, never false/zero coercion', () => {
    const rows = [
      {
        pid: 'p',
        remaining: '0.000000000000000001',
        quantity: '0.000000000000000001',
        included: false,
      },
      { pid: 'z', remaining: '0.000', quantity: '0' },
    ];
    const form = initializeSelectedRows(rows, { ...rule, defaultSelected: true });
    expect(form.map((r) => r.selected)).toEqual([true, false]);
    expect(
      projectRows(
        selectRows(form, rule, rows),
        { pid: 'pid', quantity: 'quantity', included: 'included' },
        'pid',
      ),
    ).toEqual([{ pid: 'p', quantity: '0.000000000000000001', included: false }]);
    expect(positiveRowValue(0)).toBe(false);
    expect(positiveRowValue('0.00')).toBe(false);
    expect(() => positiveRowValue(false)).toThrow();
    expect(() => positiveRowValue(0.1)).toThrow();
    expect(
      selectRows(
        form.map((row) => ({ ...row, selected: false })),
        rule,
        rows,
      ),
    ).toEqual([]);
  });
  it('refuses unknown/duplicate identities, malformed metrics and selection flags', () => {
    const rows = [{ pid: 'p', remaining: '1' }];
    const form = initializeSelectedRows(rows, { ...rule, defaultSelected: true });
    for (const bad of [
      form.map((r) => ({ ...r, selected: 'true' })),
      form.map((r) => ({ ...r, pid: 'foreign' })),
      [...form, ...form],
      form.map((r) => ({ ...r, remaining: false })),
    ])
      expect(() => selectRows(bad, rule, rows)).toThrow();
    for (const metric of [
      null,
      undefined,
      '',
      'false',
      '-1',
      '1e1',
      ' 1',
      Number.MAX_SAFE_INTEGER + 1,
    ])
      expect(() =>
        initializeSelectedRows([{ pid: 'p', remaining: metric }], {
          ...rule,
          defaultSelected: true,
        }),
      ).toThrow();
    expect(() =>
      initializeSelectedRows(rows, { ...rule, defaultSelected: 'true' } as any),
    ).toThrow();
  });
  it('checks latest source facts, not editable row claims or stale positive snapshots', () => {
    const rows = [{ pid: 'p', remaining: '2', quantity: '1' }];
    const form = initializeSelectedRows(rows, { ...rule, defaultSelected: true });
    expect(() => selectRows(form, rule, [{ pid: 'p', remaining: '0' }])).toThrow(
      'availability changed',
    );
    expect(() => selectRows(form, rule, [{ pid: 'p', remaining: '1' }])).toThrow(
      'availability changed',
    );
    expect(() => selectRows(form, rule, [])).toThrow();
    expect(() => selectRows(form, rule, [...rows, ...rows])).toThrow();
    expect(() =>
      selectRows([{ pid: 'p', remaining: '1', selected: true }], rule, [
        { pid: 'p', remaining: '0' },
      ]),
    ).toThrow();
    expect(
      selectRows(
        form.map((r) => ({ ...r, selected: false })),
        rule,
        [{ pid: 'p', remaining: '0' }],
      ),
    ).toEqual([]);
  });
  it('retains bounds and rejects getters/prototypes and unknown rule keys', () => {
    expect(() =>
      initializeSelectedRows(Array(1001).fill({ pid: 'p', remaining: '1' }), {
        ...rule,
        defaultSelected: true,
      }),
    ).toThrow();
    expect(() => initializeSelectedRows(Array(1), { ...rule })).toThrow();
    expect(() => selectRows([], { ...rule, code: 'arbitrary' } as any, [])).toThrow();
    expect(() => selectRows([], { ...rule, selectedField: '__proto__' }, [])).toThrow();
    expect(() => selectRows([], { ...rule, selectedField: 'pid' }, [])).toThrow();
    let calls = 0;
    const row = {
      pid: 'p',
      remaining: '1',
      get secret() {
        calls++;
        return 'never';
      },
    };
    expect(() => initializeSelectedRows([row], { ...rule })).toThrow('Accessor');
    expect(calls).toBe(0);
  });
  it('accepts the actual declarative card schema and rejects string default or extra selection callbacks', () => {
    expect(
      blockSchema.parse({
        id: 's',
        blockType: 'card-grid',
        field: 'rows',
        rowSelection: {
          ...rule,
          defaultSelected: true,
          label: 'Select',
          emptyText: 'Choose a row',
        },
      }).rowSelection?.defaultSelected,
    ).toBe(true);
    expect(() =>
      blockSchema.parse({
        id: 's',
        blockType: 'card-grid',
        rowSelection: { ...rule, defaultSelected: 'true' },
      }),
    ).toThrow();
    expect(() =>
      blockSchema.parse({
        id: 's',
        blockType: 'card-grid',
        rowSelection: { ...rule, filter: 'row=>row.quantity>0' },
      }),
    ).toThrow();
  });
});
