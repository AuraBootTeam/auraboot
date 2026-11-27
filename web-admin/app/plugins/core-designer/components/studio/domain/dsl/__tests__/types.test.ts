import { describe, it, expectTypeOf, expect } from 'vitest';
import type { UnifiedPageSchema } from '../types';

// Helper: keys of T that are NOT optional
type RequiredKeys<T> = {
  [K in keyof T]-?: {} extends Pick<T, K> ? never : K;
}[keyof T];

describe('UnifiedPageSchema V2', () => {
  it('requires schemaVersion literal 2', () => {
    expectTypeOf<UnifiedPageSchema['schemaVersion']>().toEqualTypeOf<2>();
  });

  it('kind is list | form | detail only', () => {
    expectTypeOf<UnifiedPageSchema['kind']>().toEqualTypeOf<'list' | 'form' | 'detail'>();
  });

  it('required fields are exactly: schemaVersion, kind, id, layout, blocks', () => {
    expectTypeOf<RequiredKeys<UnifiedPageSchema>>().toEqualTypeOf<
      'schemaVersion' | 'kind' | 'id' | 'layout' | 'blocks'
    >();
  });

  it('removed legacy fields', () => {
    const s = {} as UnifiedPageSchema;
    // @ts-expect-error areas removed
    s.areas;
    // @ts-expect-error floors removed
    s.floors;
    // @ts-expect-error components removed
    s.components;
    // @ts-expect-error $schema removed
    s.$schema;
    // @ts-expect-error version removed
    s.version;
    // @ts-expect-error state removed
    s.state;
    // @ts-expect-error dataSources removed
    s.dataSources;
    // @ts-expect-error handlers removed
    s.handlers;
    // @ts-expect-error enableMultiView removed
    s.enableMultiView;
  });

  it('layout is stack or grid-with-cols only', () => {
    const _stack: UnifiedPageSchema['layout'] = { type: 'stack' };
    const _grid: UnifiedPageSchema['layout'] = { type: 'grid', cols: 12 };
    // @ts-expect-error flex removed
    const _flex: UnifiedPageSchema['layout'] = { type: 'flex' };
    // @ts-expect-error canvas removed
    const _canvas: UnifiedPageSchema['layout'] = { type: 'canvas' };
    // @ts-expect-error floor removed
    const _floor: UnifiedPageSchema['layout'] = { type: 'floor' };
    // silence unused-locals
    void _stack; void _grid; void _flex; void _canvas; void _floor;
  });
});


describe('field override roundtrip', () => {
  it('retains unknown extension keys and explicit false flags without lossy shorthand', async () => {
    const { serializeFieldOverride, parseFieldShorthand } = await import('../types');
    const field = { field: 'amount', required: false, validation: [{ type: 'min', value: 0 }], layout: { colSpan: 12 }, refTarget: { targetEntity: 'account' }, customExtension: { enabled: true } };
    expect(parseFieldShorthand(serializeFieldOverride(field))).toEqual(field);
    expect(parseFieldShorthand(serializeFieldOverride({ field: 'name', required: false }))).toEqual({ field: 'name', required: false });
  });
});
