import { describe, expect, it } from 'vitest';

import { resolveListRowClickMode } from '../rowClickNavigation';

describe('resolveListRowClickMode', () => {
  it('defaults to detail navigation when nothing is configured', () => {
    expect(resolveListRowClickMode({})).toBe('detail');
  });

  it('treats page-like values as detail navigation', () => {
    expect(
      resolveListRowClickMode({
        schemaDetailNavigation: 'page',
      }),
    ).toBe('detail');
    expect(
      resolveListRowClickMode({
        tableOnRowClick: 'navigate',
      }),
    ).toBe('detail');
    expect(
      resolveListRowClickMode({
        tableRowClickAction: 'detail',
      }),
    ).toBe('detail');
  });

  it('only opens drawer when explicitly configured', () => {
    expect(
      resolveListRowClickMode({
        tableRowClickAction: 'drawer',
      }),
    ).toBe('drawer');
  });

  it('supports explicitly disabling row click', () => {
    expect(
      resolveListRowClickMode({
        schemaDetailNavigation: 'none',
      }),
    ).toBe('none');
  });
});
