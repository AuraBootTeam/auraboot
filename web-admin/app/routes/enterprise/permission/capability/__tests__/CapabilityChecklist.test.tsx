import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import CapabilityChecklist from '../CapabilityChecklist';
import type { CapabilityGroup } from '../types';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (_key: string, _vars?: unknown, fallback?: string) => fallback }),
}));

function cap(code: string, granted: boolean, sensitive = false) {
  return { code, group: '客户管理', label: `${code}-label`, sensitive, includes: [], granted, conventionDerived: false };
}

const groups: CapabilityGroup[] = [
  { group: '客户管理', capabilities: [cap('crm.cap.account', true), cap('crm.cap.account_contact_full', false, true)] },
];

describe('CapabilityChecklist', () => {
  it('renders capability labels and marks only sensitive ones with a lock', () => {
    render(<CapabilityChecklist groups={groups} selected={['crm.cap.account']} onToggle={() => {}} />);
    expect(screen.getByText('crm.cap.account-label')).toBeTruthy();
    expect(screen.getByTestId('capability-sensitive-crm.cap.account_contact_full')).toBeTruthy();
    expect(screen.queryByTestId('capability-sensitive-crm.cap.account')).toBeNull();
  });

  it('reflects the current selection in the checkboxes', () => {
    render(<CapabilityChecklist groups={groups} selected={['crm.cap.account']} onToggle={() => {}} />);
    expect((screen.getByTestId('capability-checkbox-crm.cap.account') as HTMLInputElement).checked).toBe(true);
    expect((screen.getByTestId('capability-checkbox-crm.cap.account_contact_full') as HTMLInputElement).checked).toBe(false);
  });

  it('calls onToggle with the capability code when a checkbox is clicked', () => {
    const onToggle = vi.fn();
    render(<CapabilityChecklist groups={groups} selected={[]} onToggle={onToggle} />);
    fireEvent.click(screen.getByTestId('capability-checkbox-crm.cap.account'));
    expect(onToggle).toHaveBeenCalledWith('crm.cap.account');
  });

  it('keeps every related menu in a collapsed disclosure and omits empty disclosures', () => {
    const withMenus: CapabilityGroup[] = [
      {
        group: '客户管理',
        capabilities: [
          { ...cap('crm.cap.account_view', false), unlockedMenus: ['客户', '客户联系人'] },
          { ...cap('crm.cap.account', true), unlockedMenus: [] },
        ],
      },
    ];
    render(<CapabilityChecklist groups={withMenus} selected={[]} onToggle={() => {}} />);
    const menus = screen.getByTestId('capability-menus-crm.cap.account_view') as HTMLDetailsElement;
    expect(menus.open).toBe(false);
    expect(menus.querySelector('summary')?.textContent).toBe('Related menus · 2');
    expect(Array.from(menus.querySelectorAll('li'), menu => menu.textContent)).toEqual(['客户', '客户联系人']);
    expect(screen.queryByTestId('capability-menus-crm.cap.account')).toBeNull();
  });
});
