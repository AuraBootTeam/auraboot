import { describe, expect, it } from 'vitest';
import { resolveFormPresentation, formGridClass, formFieldSpanClass } from '../formPresentation';

describe('composable form presentation', () => {
  it('uses cards and a bounded form without requiring a scene preset', () => {
    expect(resolveFormPresentation(null)).toEqual(resolveFormPresentation());
    expect(resolveFormPresentation()).toEqual({
      width: 'wide',
      columns: 2,
      surface: 'card',
      density: 'comfortable',
      actions: 'sticky',
      guidance: 'none',
    });
  });
  it('preserves the legacy workspace contract and explicit width', () => {
    expect(
      resolveFormPresentation({ presentation: 'workspace', formWidth: 'comfortable' }),
    ).toMatchObject({ width: 'full', actions: 'fixed', guidance: 'aside' });
    expect(resolveFormPresentation({ formWidth: 'comfortable' }).width).toBe('comfortable');
  });
  it('composes independent options and confines embedded actions to their host', () => {
    const extension = {
      presentation: 'workspace',
      formPresentation: {
        width: 'comfortable',
        columns: 3,
        actions: 'fixed',
        density: 'compact',
        surface: 'plain',
        guidance: 'aside',
      },
    };
    expect(resolveFormPresentation(extension)).toMatchObject({
      width: 'comfortable',
      columns: 3,
      surface: 'plain',
      density: 'compact',
    });
    expect(resolveFormPresentation(extension, true)).toMatchObject({
      actions: 'inline',
      guidance: 'none',
    });
  });
  it('handles malformed persisted options without generating unsafe styles', () => {
    expect(
      resolveFormPresentation({
        formPresentation: { width: 'unknown', columns: 12, actions: 'unknown' },
      }),
    ).toMatchObject({ width: 'wide', columns: 2, actions: 'sticky' });
  });
  it('keeps full-width fields across all configured column counts', () => {
    expect(formFieldSpanClass(12, 1)).toBe('');
    expect(formFieldSpanClass(12, 2)).toBe('@[32rem]/form:col-span-2');
    expect(formFieldSpanClass(12, 3)).toContain('@[56rem]/form:col-span-3');
    expect(formFieldSpanClass(6, 3)).toBe('');
    expect(formGridClass(3)).toContain('@[56rem]/form:grid-cols-3');
  });
  it('adapts small forms to content without storing a visual preset', () => {
    expect(resolveFormPresentation({}, false, { fieldCount: 3 })).toMatchObject({
      width: 'comfortable',
      columns: 1,
    });
    expect(resolveFormPresentation({}, false, { fieldCount: 12 })).toMatchObject({
      width: 'wide',
      columns: 2,
    });
    expect(
      resolveFormPresentation({}, false, { fieldCount: 3, hasWideContent: true }),
    ).toMatchObject({ width: 'wide', columns: 2 });
    expect(
      resolveFormPresentation({ formPresentation: { width: 'full', columns: 2 } }, false, {
        fieldCount: 3,
      }),
    ).toMatchObject({ width: 'full', columns: 2 });
    expect(
      resolveFormPresentation({ presentation: 'workspace' }, false, { fieldCount: 3 }),
    ).toMatchObject({ width: 'full', columns: 2, actions: 'fixed' });
  });
});
