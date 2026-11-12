import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, it, expect, vi } from 'vitest';
import RoleMenuChecklist from '../RoleMenuChecklist';
import type { RoleNavigationSelection } from '../roleNavigationService';
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (_key: string, _params: unknown, fallback: string) => fallback }),
}));
const snapshot: RoleNavigationSelection = {
  rolePid: 'sales',
  revision: 'a'.repeat(64),
  mode: 'explicit',
  selectedMenuPids: [],
  menus: [
    {
      pid: 'quotes',
      name: 'Quotes',
      parentPid: null,
      directory: true,
      enabled: true,
      source: 'default',
      businessAllowed: true,
      permissionCode: null,
    },
    {
      pid: 'quote-list',
      name: 'Quote list',
      parentPid: 'quotes',
      directory: false,
      enabled: true,
      source: 'default',
      businessAllowed: false,
      permissionCode: 'quote.read',
    },
    {
      pid: 'quote-project',
      name: 'Quote project',
      parentPid: 'quotes',
      directory: false,
      enabled: true,
      source: 'default',
      businessAllowed: true,
      permissionCode: 'quote.read',
    },
    {
      pid: 'stock',
      name: 'Stock',
      parentPid: null,
      directory: false,
      enabled: true,
      source: 'default',
      businessAllowed: false,
      permissionCode: 'stock.read',
    },
  ],
};
function setup(
  selection = snapshot,
  selected: string[] = [],
  disabled = false,
  previewAllowed?: Record<string, boolean> | null,
) {
  const onChange = vi.fn(),
    onPreview = vi.fn();
  render(
    <MemoryRouter>
      <RoleMenuChecklist
        selection={selection}
        selected={selected}
        disabled={disabled}
        onChange={onChange}
        onPreview={onPreview}
        previewAllowed={previewAllowed}
      />
    </MemoryRouter>,
  );
  return { onChange, onPreview };
}
describe('independent role navigation', () => {
  it('allows a visible menu while its business permission is absent', () => {
    const { onChange } = setup();
    fireEvent.click(screen.getByTestId('role-menu-stock'));
    expect(onChange).toHaveBeenCalledWith(['stock']);
  });
  it('selects directory descendants without discarding another selected menu', () => {
    const { onChange } = setup(snapshot, ['stock']);
    fireEvent.click(screen.getByRole('checkbox', { name: 'Quotes' }));
    expect(onChange).toHaveBeenCalledWith(['stock', 'quote-list', 'quote-project']);
  });
  it('unselects a whole group while retaining unrelated selections', () => {
    const { onChange } = setup(snapshot, ['stock', 'quote-list', 'quote-project']);
    fireEvent.click(screen.getByRole('checkbox', { name: 'Quotes' }));
    expect(onChange).toHaveBeenCalledWith(['stock']);
  });
  it('marks partially selected directories as indeterminate', () => {
    setup(snapshot, ['quote-list']);
    expect(
      (screen.getByRole('checkbox', { name: 'Quotes' }) as HTMLInputElement).indeterminate,
    ).toBe(true);
  });
  it('lets an owner explicitly adopt the unchanged legacy projection', () => {
    const { onChange } = setup({ ...snapshot, mode: 'legacy' }, ['quote-list']);
    fireEvent.click(screen.getByTestId('role-menu-adopt'));
    expect(onChange).toHaveBeenCalledWith(['quote-list']);
  });
  it('does not put globally disabled entries into the draft navigation preview', () => {
    setup(
      {
        ...snapshot,
        menus: snapshot.menus.map((m) => (m.pid === 'stock' ? { ...m, enabled: false } : m)),
      },
      ['stock'],
    );
    expect(screen.getByText('No visible entries')).toBeTruthy();
    expect(screen.getByTestId('role-menu-stock')).toBeChecked();
  });
  it('keeps read-only role selections immutable', () => {
    setup(snapshot, ['stock'], true);
    expect(screen.getByTestId('role-menu-stock')).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: 'Quotes' })).toBeDisabled();
  });
  it('opens the explicit user preview instead of performing a business action', () => {
    const { onPreview, onChange } = setup(snapshot, ['stock']);
    fireEvent.click(screen.getByRole('button', { name: 'Preview as a user' }));
    expect(onPreview).toHaveBeenCalledOnce();
    expect(onChange).not.toHaveBeenCalled();
  });
  it('groups preview entries and opens the clicked target without changing grants or menu selection', () => {
    const { onPreview, onChange } = setup(snapshot, ['quote-list', 'quote-project', 'stock']);
    expect(screen.getAllByTestId('role-preview-group')).toHaveLength(2);
    fireEvent.click(screen.getByTestId('role-preview-entry-quote-project'));
    expect(onPreview).toHaveBeenCalledWith('quote-project');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('uses the current draft projection instead of a stale saved denial', () => {
    setup(snapshot, ['quote-list'], false, { 'quote-list': true });
    expect(screen.getByTestId('role-preview-entry-quote-list')).toHaveAttribute(
      'data-business-access',
      'allowed',
    );
  });
  it('does not display the saved allow result while current draft access is unverified', () => {
    setup(snapshot, ['quote-project'], false, null);
    expect(screen.getByTestId('role-preview-entry-quote-project')).toHaveAttribute(
      'data-business-access',
      'unverified',
    );
    expect(screen.getByText('Calculating draft access…')).toBeVisible();
  });
});
