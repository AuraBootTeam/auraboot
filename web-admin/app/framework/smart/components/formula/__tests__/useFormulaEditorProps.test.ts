import { describe, it, expect, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import {
  runtimeToEditor,
  editorToRuntime,
  useFormulaEditorProps,
} from '../useFormulaEditorProps';

// ─── runtimeToEditor ─────────────────────────────────────────────────────────

describe('runtimeToEditor', () => {
  it('returns empty string for empty input', () => {
    expect(runtimeToEditor('')).toBe('');
  });

  it('converts ${form.fieldCode} to #fieldCode', () => {
    expect(runtimeToEditor('${form.price}')).toBe('#price');
  });

  it('converts multiple form fields', () => {
    expect(runtimeToEditor('${form.price} * ${form.qty}')).toBe('#price * #qty');
  });

  it('converts ${system.now} to #NOW()', () => {
    expect(runtimeToEditor('${system.now}')).toBe('#NOW()');
  });

  it('converts ${system.currentUser} to #currentUser', () => {
    expect(runtimeToEditor('${system.currentUser}')).toBe('#currentUser');
  });

  it('converts ${system.currentDate} to #CURRENT_DATE()', () => {
    expect(runtimeToEditor('${system.currentDate}')).toBe('#CURRENT_DATE()');
  });

  it('converts ${system.currentTime} to #CURRENT_DATETIME()', () => {
    expect(runtimeToEditor('${system.currentTime}')).toBe('#CURRENT_DATETIME()');
  });

  it('passes through unknown ${...} expressions', () => {
    expect(runtimeToEditor('${someComplexExpr}')).toBe('${someComplexExpr}');
  });

  it('handles unknown system variable as #sysKey fallback', () => {
    expect(runtimeToEditor('${system.unknownVar}')).toBe('#unknownVar');
  });

  it('leaves plain text outside ${} untouched', () => {
    expect(runtimeToEditor('Total: ${form.total} USD')).toBe('Total: #total USD');
  });

  it('handles expression with whitespace inside ${}', () => {
    // ${  form.fieldCode  } – trimmed
    expect(runtimeToEditor('${ form.price }')).toBe('#price');
  });

  it('handles mixed form and system in same string', () => {
    const result = runtimeToEditor('${form.price} created at ${system.now}');
    expect(result).toBe('#price created at #NOW()');
  });
});

// ─── editorToRuntime ─────────────────────────────────────────────────────────

describe('editorToRuntime', () => {
  it('returns empty string for empty input', () => {
    expect(editorToRuntime('')).toBe('');
  });

  it('converts #fieldCode to ${form.fieldCode}', () => {
    expect(editorToRuntime('#price')).toBe('${form.price}');
  });

  it('converts multiple field references', () => {
    expect(editorToRuntime('#price * #qty')).toBe('${form.price} * ${form.qty}');
  });

  it('converts #NOW() to ${system.now}', () => {
    expect(editorToRuntime('#NOW()')).toBe('${system.now}');
  });

  it('converts #CURRENT_DATE() to ${system.currentDate}', () => {
    expect(editorToRuntime('#CURRENT_DATE()')).toBe('${system.currentDate}');
  });

  it('converts #CURRENT_DATETIME() to ${system.currentTime}', () => {
    expect(editorToRuntime('#CURRENT_DATETIME()')).toBe('${system.currentTime}');
  });

  it('converts #currentUser to ${system.currentUser}', () => {
    expect(editorToRuntime('#currentUser')).toBe('${system.currentUser}');
  });

  it('leaves plain text outside # unchanged', () => {
    expect(editorToRuntime('Total: #total USD')).toBe('Total: ${form.total} USD');
  });

  it('handles dot-notation identifiers (#obj.field)', () => {
    const result = editorToRuntime('#parent.child');
    expect(result).toBe('${form.parent.child}');
  });
});

// ─── round-trip symmetry ─────────────────────────────────────────────────────

describe('runtimeToEditor ↔ editorToRuntime round-trip', () => {
  const roundtripCases = [
    '${form.price}',
    '${form.price} * ${form.qty}',
    '${system.now}',
    '${system.currentUser}',
    '${form.a} + ${form.b} - ${system.currentDate}',
  ];

  for (const runtimeExpr of roundtripCases) {
    it(`round-trips: ${runtimeExpr}`, () => {
      const editorSyntax = runtimeToEditor(runtimeExpr);
      const backToRuntime = editorToRuntime(editorSyntax);
      expect(backToRuntime).toBe(runtimeExpr);
    });
  }
});

// ─── useFormulaEditorProps ────────────────────────────────────────────────────

describe('useFormulaEditorProps', () => {
  it('converts runtime value to editor syntax', () => {
    const onChange = vi.fn();
    const { result } = renderHook(() =>
      useFormulaEditorProps({
        value: '${form.price}',
        onChange,
      }),
    );
    expect(result.current.editorValue).toBe('#price');
  });

  it('handleChange converts editor syntax back to runtime and calls onChange', () => {
    const onChange = vi.fn();
    const { result } = renderHook(() =>
      useFormulaEditorProps({
        value: '',
        onChange,
      }),
    );
    act(() => {
      result.current.handleChange('#price * #qty');
    });
    expect(onChange).toHaveBeenCalledWith('${form.price} * ${form.qty}');
  });

  it('formats modelFields into code/name objects for autocomplete', () => {
    const { result } = renderHook(() =>
      useFormulaEditorProps({
        value: '',
        onChange: vi.fn(),
        modelFields: [
          { code: 'price', name: 'Price', dataType: 'number' },
          { code: 'qty', name: 'Quantity' },
        ],
      }),
    );
    expect(result.current.fields).toEqual([
      { code: 'price', name: 'Price' },
      { code: 'qty', name: 'Quantity' },
    ]);
  });

  it('returns empty fields array when modelFields is not provided', () => {
    const { result } = renderHook(() =>
      useFormulaEditorProps({ value: '', onChange: vi.fn() }),
    );
    expect(result.current.fields).toHaveLength(0);
  });

  it('fetchFunctions returns the built-in function list', async () => {
    const { result } = renderHook(() =>
      useFormulaEditorProps({ value: '', onChange: vi.fn() }),
    );
    const fns = await result.current.fetchFunctions!();
    expect(fns.length).toBeGreaterThan(0);
    const names = fns.map((f: { name: string }) => f.name);
    expect(names).toContain('concat');
    expect(names).toContain('IF');
    expect(names).toContain('now');
  });

  it('fetchFunctions is the same reference on re-renders (stable)', () => {
    const onChange = vi.fn();
    const { result, rerender } = renderHook(() =>
      useFormulaEditorProps({ value: '', onChange }),
    );
    const first = result.current.fetchFunctions;
    rerender();
    const second = result.current.fetchFunctions;
    expect(first).toBe(second);
  });

  it('editorValue is memoized (stable when value unchanged)', () => {
    const onChange = vi.fn();
    const { result, rerender } = renderHook(
      ({ v }: { v: string }) => useFormulaEditorProps({ value: v, onChange }),
      { initialProps: { v: '${form.price}' } },
    );
    const first = result.current.editorValue;
    rerender({ v: '${form.price}' });
    expect(result.current.editorValue).toBe(first);
  });

  it('editorValue updates when value prop changes', () => {
    const onChange = vi.fn();
    const { result, rerender } = renderHook(
      ({ v }: { v: string }) => useFormulaEditorProps({ value: v, onChange }),
      { initialProps: { v: '${form.price}' } },
    );
    expect(result.current.editorValue).toBe('#price');
    rerender({ v: '${form.total}' });
    expect(result.current.editorValue).toBe('#total');
  });

  it('handleChange is stable between re-renders when onChange does not change', () => {
    const onChange = vi.fn();
    const { result, rerender } = renderHook(() =>
      useFormulaEditorProps({ value: '', onChange }),
    );
    const first = result.current.handleChange;
    rerender();
    expect(result.current.handleChange).toBe(first);
  });
});
