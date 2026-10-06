import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CapabilityChecklist from '../CapabilityChecklist';
import type { Capability, CapabilityGroup } from '../types';

const i18n = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: i18n.locale, t: (_key: string, _vars?: unknown, fallback?: string) => fallback }),
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

const legacyTranslations: Array<Capability['localizedLabels']> = [undefined, null, {}, { en: '' }];

const groups: CapabilityGroup[] = [
  {
    group: '客户管理',
    capabilities: [cap('crm.cap.account', true), cap('crm.cap.account_contact_full', false, true)],
  },
];

describe('CapabilityChecklist', () => {
  beforeEach(() => { i18n.locale = 'zh-CN'; });
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
  it('renders the English declaration label and toggles its original capability code', () => {
    i18n.locale = 'en-US';
    const onToggle = vi.fn();
    const localized: CapabilityGroup[] = [{ group: 'Organization', capabilities: [{
      ...cap('org.cap.role', true), label: '管理角色',
      localizedLabels: { 'zh-CN': '管理角色', en: 'Manage Roles' },
    }] }];
    render(<CapabilityChecklist groups={localized} selected={['org.cap.role']} onToggle={onToggle} />);
    const checkbox = screen.getByRole('checkbox', { name: 'Manage Roles' });
    expect((checkbox as HTMLInputElement).checked).toBe(true);
    expect(screen.queryByText('管理角色')).toBeNull();
    fireEvent.click(checkbox);
    expect(onToggle).toHaveBeenCalledExactlyOnceWith('org.cap.role');
  });

  it('updates a declaration label when the locale changes without changing selection', () => {
    const localized: CapabilityGroup[] = [{ group: 'Organization', capabilities: [{
      ...cap('org.cap.role', true), label: '管理角色',
      localizedLabels: { 'zh-CN': '管理角色', en: 'Manage Roles' },
    }] }];
    const props = { groups: localized, selected: ['org.cap.role'], onToggle: vi.fn() };
    const view = render(<CapabilityChecklist {...props} />);
    expect(screen.getByRole('checkbox', { name: '管理角色' })).toBeChecked();
    i18n.locale = 'en-GB';
    view.rerender(<CapabilityChecklist {...props} />);
    expect(screen.getByRole('checkbox', { name: 'Manage Roles' })).toBeChecked();
    expect(props.onToggle).not.toHaveBeenCalled();
  });

  it.each(legacyTranslations)('preserves a legacy label for absent or empty translations: %j', (localizedLabels) => {
    i18n.locale = 'en-US';
    const legacy: CapabilityGroup[] = [{ group: 'Legacy', capabilities: [{
      ...cap('legacy.cap.read', false), label: 'Legacy Read', localizedLabels,
    }] }];
    render(<CapabilityChecklist groups={legacy} selected={[]} onToggle={() => {}} />);
    expect(screen.getByRole('checkbox', { name: 'Legacy Read' })).not.toBeChecked();
  });

  it('switches a description hint by locale without changing the grant selection', () => {
    const capability = {
      ...cap('org.cap.role', true), label: 'Manage Roles',
      description: 'Source role description',
      localizedDescriptions: { 'zh-CN': 'Source role description', en: 'View and manage roles' },
    };
    const props = { groups: [{ group: 'Organization', capabilities: [capability] }],
      selected: ['org.cap.role'], onToggle: vi.fn() };
    const view = render(<CapabilityChecklist {...props} />);
    expect(screen.getByTestId('capability-org.cap.role')).toHaveAttribute('title', 'Source role description');
    i18n.locale = 'en-GB';
    view.rerender(<CapabilityChecklist {...props} />);
    expect(screen.getByTestId('capability-org.cap.role')).toHaveAttribute('title', 'View and manage roles');
    expect(screen.getByRole('checkbox', { name: 'Manage Roles' })).toBeChecked();
    expect(props.onToggle).not.toHaveBeenCalled();
  });

  it('keeps a legacy description and omits the hint when no description is supplied', () => {
    i18n.locale = 'en-US';
    const legacy = { ...cap('legacy.cap.read', false), description: 'Legacy description' };
    render(<CapabilityChecklist groups={[{ group: 'Legacy', capabilities: [legacy, cap('empty.cap.read', false)] }]}
      selected={[]} onToggle={vi.fn()} />);
    expect(screen.getByTestId('capability-legacy.cap.read')).toHaveAttribute('title', 'Legacy description');
    expect(screen.getByTestId('capability-empty.cap.read')).not.toHaveAttribute('title');
  });

});
