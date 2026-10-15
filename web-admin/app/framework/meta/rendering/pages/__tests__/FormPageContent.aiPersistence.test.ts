import { useState } from 'react';
import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useFormFill } from '../../useFormFill';
import { type FormFillField } from '../../formFill';
import { buildDynamicCrudFormPayload } from '../FormPageContent';

const fields: FormFillField[] = [
  { code: 'name', label: 'Name', type: 'string' },
  { code: 'contact', label: 'Contact', type: 'string' },
];
const metadata = { name: { dataType: 'string' }, contact: { dataType: 'string' } };

// Real hook -> its actual state setter -> the production CRUD serializer. AI is
// deliberately not marked as a manual edit; no synthetic dirty flag fixes this.
function useEditForm(initial: Record<string, unknown>) {
  const [values, setValues] = useState(initial);
  const ai = useFormFill('customer-edit', 'customers', fields, values, setValues, true);
  return {
    values, setValues, ai,
    payload: buildDynamicCrudFormPayload(values, metadata, new Set(), 'update', initial),
  };
}

describe('AI draft changes persist through the pure CRUD edit path', () => {
  it.each([undefined, null, ''])('persists an applied empty-field value (%s) without manual dirty flags', (contact) => {
    const initial = { name: '华辰制造', contact, pid: 'record', tenant_id: 99, updated_at: 'audit', unknown: 'display' };
    const { result } = renderHook(() => useEditForm(initial));
    act(() => { result.current.ai.target.apply({ contact: '张经理' }); });
    expect(result.current.values.contact).toBe('张经理');
    expect(result.current.ai.receipt?.applied.contact).toEqual({ before: contact, after: '张经理' });
    expect(result.current.payload).toEqual({ contact: '张经理' });
  });

  it('undo restores the loaded value, removes the pending write, and permits another AI application', () => {
    const { result } = renderHook(() => useEditForm({ name: '华辰制造', contact: '' }));
    act(() => { result.current.ai.target.apply({ contact: '张经理' }); });
    expect(result.current.payload).toEqual({ contact: '张经理' });
    act(() => { result.current.ai.undo(); });
    expect(result.current.values.contact).toBe('');
    expect(result.current.payload).toEqual({});
    act(() => { result.current.ai.target.apply({ contact: '李经理' }); });
    expect(result.current.payload).toEqual({ contact: '李经理' });
  });

  it('protects manual values from AI and does not undo a human edit made after the AI fill', () => {
    const { result } = renderHook(() => useEditForm({ name: '华辰制造', contact: '' }));
    act(() => { result.current.ai.target.apply({ name: 'Wrong company', contact: '张经理' }); });
    expect(result.current.ai.receipt?.conflicts).toContain('name');
    expect(result.current.values.name).toBe('华辰制造');
    act(() => { result.current.setValues(current => ({ ...current, contact: '人工核对联系人' })); });
    act(() => { result.current.ai.undo(); });
    expect(result.current.values.contact).toBe('人工核对联系人');
    expect(result.current.payload).toEqual({ contact: '人工核对联系人' });
    act(() => { result.current.ai.target.apply({ contact: 'AI overwrite' }); });
    expect(result.current.ai.receipt?.conflicts).toContain('contact');
    expect(result.current.payload).toEqual({ contact: '人工核对联系人' });
  });

  it('keeps an explicit null clear and omits normalized unchanged business values', () => {
    const initial = { name: '华辰制造', contact: '张经理', config: '{"enabled":true}' };
    const meta = { ...metadata, config: { dataType: 'json' } };
    expect(buildDynamicCrudFormPayload({ ...initial, contact: null, config: { enabled: true } }, meta, new Set(), 'update', initial))
      .toEqual({ contact: null });
  });
});
