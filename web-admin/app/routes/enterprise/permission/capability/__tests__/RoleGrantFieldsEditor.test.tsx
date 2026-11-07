import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import RoleGrantFieldsEditor, { type GrantFieldBinding } from '../RoleGrantFieldsEditor';
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (key: string, _: unknown, fallback: string) => fallback ?? key }),
}));
function show(code: string, options: Partial<GrantFieldBinding> = {}) {
  const onChange = vi.fn();
  const binding: GrantFieldBinding = {
    bindingPid: 'grant', permissionCode: code, modelCode: 'order', modelLabel: 'Order',
    editable: true, legacy: false, readFields: [], writeFields: [],
    fields: [{ code: 'remark', label: 'Remark' }], metadataRevision: 'a'.repeat(64), ...options,
  };
  render(<RoleGrantFieldsEditor model="order" candidates={[binding]} changes={[]} disabled={false}
    loading={false} error={false} onRetry={vi.fn()} onChange={onChange} />);
  return onChange;
}
describe('complete business grant field allowances', () => {
  it.each(['bom.project.manage', 'bom.convert.execute'])('edits both sets without treating %s as a native CRUD suffix', (code) => {
    const onChange = show(code);
    const read = screen.getByRole('checkbox', { name: 'Remark · May read' });
    const write = screen.getByRole('checkbox', { name: 'Remark · May write' });
    expect(read).toBeEnabled(); expect(write).toBeEnabled();
    fireEvent.click(read);
    expect(onChange).toHaveBeenLastCalledWith([expect.objectContaining({ bindingPid: 'grant', readFields: ['remark'], writeFields: [] })]);
  });
  it('materializes both finite lists only after explicitly configuring a legacy business grant', () => {
    const onChange = show('bom.project.manage', { legacy: true });
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Set a finite field list' }));
    expect(onChange).toHaveBeenCalledWith([expect.objectContaining({ readFields: ['remark'], writeFields: ['remark'] })]);
  });
  it.each(['model.order.read', 'model.order.update'])('retains the separate native grant contract for %s', (code) => {
    show(code);
    expect(screen.getByRole('checkbox', { name: 'Remark · May read' }).hasAttribute('disabled')).toBe(code.endsWith('.update'));
    expect(screen.getByRole('checkbox', { name: 'Remark · May write' }).hasAttribute('disabled')).toBe(code.endsWith('.read'));
  });
  it('keeps both business field sets disabled for a managed binding', () => {
    show('bom.project.manage', { editable: false });
    expect(screen.getByRole('checkbox', { name: 'Remark · May read' })).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: 'Remark · May write' })).toBeDisabled();
  });
});
