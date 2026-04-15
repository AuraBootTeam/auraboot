import { describe, it, expectTypeOf } from 'vitest';
import type { PageSchema } from '../types';

describe('PageSchema V2', () => {
  it('requires schemaVersion literal 2', () => {
    expectTypeOf<PageSchema['schemaVersion']>().toEqualTypeOf<2>();
  });

  it('kind is list | form | detail only', () => {
    expectTypeOf<PageSchema['kind']>().toEqualTypeOf<'list' | 'form' | 'detail'>();
  });

  it('blocks is required (not optional)', () => {
    const s = {} as PageSchema;
    // @ts-expect-error blocks is required
    const { blocks: _b } = s as Omit<PageSchema, 'blocks'>;
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
    s['$schema'];
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
    const stack: PageSchema['layout'] = { type: 'stack' };
    const grid: PageSchema['layout'] = { type: 'grid', cols: 12 };
    // @ts-expect-error flex removed
    const flex: PageSchema['layout'] = { type: 'flex' };
    // @ts-expect-error canvas removed
    const canvas: PageSchema['layout'] = { type: 'canvas' };
    // @ts-expect-error floor removed
    const floor: PageSchema['layout'] = { type: 'floor' };
  });
});
