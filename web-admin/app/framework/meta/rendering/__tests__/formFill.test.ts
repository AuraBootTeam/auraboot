import { describe, expect, it } from 'vitest';
import { applyFormFill, collectFormFillFields, undoFormFill } from '../formFill';

const fields = [
  { code: 'name', label: 'Name', type: 'string' as const },
  { code: 'amount', label: 'Amount', type: 'number' as const },
  { code: 'approved', label: 'Approved', type: 'boolean' as const, locked: true },
  { code: 'date', label: 'Date', type: 'string' as const, format: 'date' as const },
  { code: 'status', label: 'Status', type: 'string' as const, enum: ['new', 'open'] },
];

describe('form fill draft contract', () => {
  it('fills empty fields without overwriting existing or protected values', () => {
    const result = applyFormFill({ name: 'Customer edit' }, fields, {
      name: 'AI name',
      amount: 12,
      approved: true,
      missing: 'bad',
    });
    expect(result.values).toEqual({ name: 'Customer edit', amount: 12 });
    expect(result.applied).toEqual({ amount: { before: undefined, after: 12 } });
    expect(result.conflicts).toEqual(['name']);
    expect(result.rejected).toEqual(['approved', 'missing']);
  });

  it('rejects wrong types, impossible dates, unknown enum and clearing null', () => {
    const result = applyFormFill({}, fields, {
      name: null,
      amount: '12',
      date: '2026-02-30',
      status: 'invented',
    });
    expect(result.values).toEqual({});
    expect(result.rejected).toHaveLength(4);
  });

  it('does not treat zero and false as empty or reapply identical values', () => {
    const result = applyFormFill({ amount: 0, name: 'Same' }, fields, { amount: 12, name: 'Same' });
    expect(result.values).toEqual({ amount: 0, name: 'Same' });
    expect(result.conflicts).toEqual(['amount']);
    expect(result.applied).toEqual({});
  });

  it('undo preserves edits made after the AI patch', () => {
    const result = applyFormFill({ name: '' }, fields, { name: 'AI', amount: 12 });
    expect(undoFormFill({ ...result.values, name: 'Human' }, result.applied)).toEqual({
      name: 'Human',
    });
  });

  it('derives locks from schema, metadata, permissions and unsupported references', () => {
    const schema = {
      blocks: [
        {
          fields: [
            { field: 'name', label: 'Name' },
            { field: 'amount', props: { aiLocked: true } },
            { field: 'owner', dataType: 'reference' },
            { field: 'hidden' },
            { field: 'calculated' },
            { field: 'conditional', dataType: 'string', readOnlyWhen: 'form.status !== "draft"' },
          ],
        },
      ],
    };
    const result = collectFormFillFields(
      schema,
      {
        name: { dataType: 'string' },
        amount: { dataType: 'decimal' },
        calculated: { dataType: 'decimal', extensionProps: { computed: true } },
      },
      { hidden: 'hidden' },
      (value) => (typeof value === 'string' ? value : 'Field'),
    );
    expect(result.filter((field) => !field.locked).map((field) => field.code)).toEqual(['name']);
    expect(result.find((field) => field.code === 'amount')?.locked).toBe(true);
  });
  it('keeps ambiguous values unfilled and never exposes protected field evidence', () => {
    const result = applyFormFill(
      {},
      fields,
      { name: 'Acme', amount: 12 },
      {
        name: { status: 'supported', quote: 'Acme' },
        amount: { status: 'ambiguous', quote: '12 or 20', reason: 'multiple_values' },
        approved: { status: 'supported', quote: 'Secret' },
        unknown: { status: 'ambiguous', quote: 'Secret', reason: 'unclear_mapping' },
      },
    );
    expect(result.values).toEqual({ name: 'Acme' });
    expect(result.reviews).toEqual({
      name: { status: 'supported', quote: 'Acme' },
      amount: { status: 'ambiguous', quote: '12 or 20', reason: 'multiple_values' },
    });
    expect(result.rejected).toEqual(['amount']);
  });
});
