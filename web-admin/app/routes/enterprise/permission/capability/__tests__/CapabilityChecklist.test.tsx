import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import CapabilityChecklist from '../CapabilityChecklist';
import type { CapabilityGroup } from '../types';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (_key: string, _vars?: unknown, fallback?: string) => fallback }),
}));

function cap(code: string, granted: boolean, sensitive = false) {
  return {
    code,
    group: '客户管理',
    label: `${code}-label`,
    sensitive,
    includes: [],
    granted,
    conventionDerived: false,
  };
}

const groups: CapabilityGroup[] = [
  {
    group: '客户管理',
    capabilities: [cap('crm.cap.account', true), cap('crm.cap.account_contact_full', false, true)],
  },
];

describe('CapabilityChecklist', () => {
  it('renders capability labels and marks only sensitive ones with a lock', () => {
    render(
      <CapabilityChecklist groups={groups} selected={['crm.cap.account']} onToggle={() => {}} />,
    );
    expect(screen.getByText('crm.cap.account-label')).toBeTruthy();
    expect(screen.getByTestId('capability-sensitive-crm.cap.account_contact_full')).toBeTruthy();
    expect(screen.queryByTestId('capability-sensitive-crm.cap.account')).toBeNull();
  });

  it('reflects the current selection in the checkboxes', () => {
    render(
      <CapabilityChecklist groups={groups} selected={['crm.cap.account']} onToggle={() => {}} />,
    );
    expect(
      (screen.getByTestId('capability-checkbox-crm.cap.account') as HTMLInputElement).checked,
    ).toBe(true);
    expect(
      (screen.getByTestId('capability-checkbox-crm.cap.account_contact_full') as HTMLInputElement)
        .checked,
    ).toBe(false);
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
    expect(Array.from(menus.querySelectorAll('li'), (menu) => menu.textContent)).toEqual([
      '客户',
      '客户联系人',
    ]);
    expect(screen.queryByTestId('capability-menus-crm.cap.account')).toBeNull();
  });
  it('explains shared partial actions once, counts them, and keeps revocation explicit', () => {
    const partial: CapabilityGroup[] = [
      {
        group: '客户管理',
        capabilities: [
          {
            ...cap('crm.cap.account', false),
            authorizationState: 'partial',
            includes: ['meta.command.execute', 'crm.account.read', 'crm.account.update'],
            missingCodes: ['crm.account.read', 'crm.account.update'],
          },
          {
            ...cap('crm.cap.contact', false),
            authorizationState: 'partial',
            includes: ['meta.command.execute', 'crm.contact.read'],
            missingCodes: ['crm.contact.read'],
          },
        ],
      },
    ];
    const revoke = vi.fn();
    const props = {
      groups: partial,
      selected: [] as string[],
      onToggle: vi.fn(),
      onRevokePartial: revoke,
    };
    const { rerender } = render(<CapabilityChecklist {...props} />);
    expect(screen.getAllByTestId('capability-partial-guidance')).toHaveLength(1);
    expect(screen.getByTestId('capability-partial-crm.cap.account').textContent).toBe(
      'Actions 1/3',
    );
    expect(screen.getByTestId('capability-partial-crm.cap.contact').textContent).toBe(
      'Actions 1/2',
    );
    expect(
      screen.getByTestId('capability-checkbox-crm.cap.account').getAttribute('aria-checked'),
    ).toBe('mixed');
    fireEvent.click(screen.getByTestId('capability-revoke-partial-crm.cap.account'));
    expect(revoke).toHaveBeenCalledWith('crm.cap.account');
    expect(props.onToggle).not.toHaveBeenCalled();
    rerender(
      <CapabilityChecklist
        {...props}
        selected={['crm.cap.account']}
        revokedPartial={['crm.cap.contact']}
      />,
    );
    expect(screen.queryByTestId('capability-partial-guidance')).toBeNull();
    expect(screen.queryByTestId('capability-partial-crm.cap.account')).toBeNull();
    expect(screen.getByTestId('capability-revoke-partial-crm.cap.contact').textContent).toBe(
      'Pending revocation · undo',
    );
  });
});
