import { describe, it, expectTypeOf } from 'vitest';
import type { PageSchema } from '../types';

// Helper: keys of T that are NOT optional
type RequiredKeys<T> = {
  [K in keyof T]-?: {} extends Pick<T, K> ? never : K;
}[keyof T];

describe('PageSchema V2', () => {
  it('requires schemaVersion literal 2', () => {
    expectTypeOf<PageSchema['schemaVersion']>().toEqualTypeOf<2>();
  });

  it('kind is list | form | detail only', () => {
    expectTypeOf<PageSchema['kind']>().toEqualTypeOf<'list' | 'form' | 'detail'>();
  });

  it('required fields are exactly: schemaVersion, kind, id, layout, blocks', () => {
    expectTypeOf<RequiredKeys<PageSchema>>().toEqualTypeOf<
      'schemaVersion' | 'kind' | 'id' | 'layout' | 'blocks'
    >();
  });

  it('removed legacy fields', () => {
    const s = {} as PageSchema;
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
    const _stack: PageSchema['layout'] = { type: 'stack' };
    const _grid: PageSchema['layout'] = { type: 'grid', cols: 12 };
    // @ts-expect-error flex removed
    const _flex: PageSchema['layout'] = { type: 'flex' };
    // @ts-expect-error canvas removed
    const _canvas: PageSchema['layout'] = { type: 'canvas' };
    // @ts-expect-error floor removed
    const _floor: PageSchema['layout'] = { type: 'floor' };
    // silence unused-locals
    void _stack; void _grid; void _flex; void _canvas; void _floor;
  });
});
