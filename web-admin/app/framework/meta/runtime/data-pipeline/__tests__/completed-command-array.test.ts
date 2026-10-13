import { describe, expect, it } from 'vitest';
import { completedCommandArray, completedCommandOptions } from '../completed-command-array';
import { projectRows } from '../../expression/row-projection';
const command = (items: unknown[], total = items.length) => ({
  phaseReached: 'completed',
  data: { items, total },
});
const config = {
  dataPath: 'items',
  valueField: 'pid',
  labelField: 'name',
  selectableField: 'selectable',
};
describe('Completed command collections', () => {
  it('keeps exact array scalars and disabled options', () => {
    const row = { pid: 'p', name: 'Address', selectable: false, qty: '9007199254740993.000001' };
    expect(completedCommandArray(command([row]), config)).toEqual([row]);
    expect(completedCommandOptions(command([row]), config)).toEqual([
      { value: 'p', label: 'Address', disabled: true },
    ]);
    expect(completedCommandOptions(command([]), config)).toEqual([]);
  });
  it.each([
    undefined,
    { phaseReached: 'handler', data: { items: [] } },
    command([null]),
    command([[]]),
    command(Array(1001).fill({})),
  ])('refuses invalid arrays', (value) => {
    expect(() => completedCommandArray(value, config)).toThrow();
  });
  it.each([
    command([{ pid: 'p', name: 'N', selectable: true }], 2),
    command([{ pid: 'p', name: 'N', selectable: 'false' }]),
    command([
      { pid: 'p', name: 'N', selectable: true },
      { pid: 'p', name: 'N', selectable: true },
    ]),
  ])('rejects partial and ambiguous options', (value) => {
    expect(() => completedCommandOptions(value, config)).toThrow();
  });
  it('refuses accessors and inherited/prototype paths', () => {
    expect(() =>
      completedCommandArray(
        {
          phaseReached: 'completed',
          data: {
            get items() {
              throw Error('executed');
            },
          },
        },
        config,
      ),
    ).toThrow('Accessor');
    expect(() => completedCommandArray(command([]), { dataPath: 'constructor.name' })).toThrow(
      'Invalid row path',
    );
    expect(() =>
      completedCommandArray(
        { phaseReached: 'completed', data: Object.create({ items: [] }) },
        config,
      ),
    ).toThrow();
  });
});
describe('PROJECT_ROWS boundary', () => {
  it('rejects sparse/accessor arrays without running user getters', () => {
    let calls = 0;
    const rows = [{ pid: 'p' }];
    Object.defineProperty(rows, '0', {
      get() {
        calls++;
        return { pid: 'p' };
      },
    });
    expect(() => projectRows(rows, { identity: 'pid' }, 'identity')).toThrow('forbidden');
    expect(calls).toBe(0);
    expect(() => projectRows(Array(1), { identity: 'pid' }, 'identity')).toThrow();
    expect(() => completedCommandArray(command(Array(1)), config)).toThrow();
  });
  it('accepts the exact thousand-row bound with independent identities', () => {
    const rows = Array.from({ length: 1000 }, (_, index) => ({ pid: `p${index}` }));
    expect(projectRows(rows, { identity: 'pid' }, 'identity')).toHaveLength(1000);
  });
  it('explicit fallback preserves scalars and whitelist', () => {
    expect(
      projectRows(
        [{ pid: 'p', demand: { revision: 3 }, qty: '0.000000000000001', included: false }],
        {
          sourcePid: 'pid',
          sourceRevision: ['revision', 'demand.revision'],
          quantity: 'qty',
          included: 'included',
        },
        'sourcePid',
      ),
    ).toEqual([
      { sourcePid: 'p', sourceRevision: 3, quantity: '0.000000000000001', included: false },
    ]);
  });
  it.each([
    Array(1001).fill({ pid: 'p' }),
    [{ pid: '' }],
    [{ pid: 'p' }, { pid: 'p' }],
    [{ pid: 'p', qty: { value: '1' } }],
  ])('rejects invalid payload rows', (rows) => {
    expect(() => projectRows(rows, { identity: 'pid', quantity: 'qty' }, 'identity')).toThrow();
  });
  it('validates unused paths and empty schemas', () => {
    expect(() =>
      projectRows([{ pid: 'p' }], { identity: ['pid', '__proto__.pid'] }, 'identity'),
    ).toThrow();
    expect(() => projectRows([], { identity: 'constructor.pid' }, 'identity')).toThrow();
    expect(() => projectRows([], { identity: 'pid' }, 'missing')).toThrow();
  });
});
