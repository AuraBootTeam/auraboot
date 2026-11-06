import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CapabilityChecklist from '../CapabilityChecklist';
import type { Capability, CapabilityGroup } from '../types';
import type { PermissionMatrixDTO } from '../../types';

const i18n = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: i18n.locale,
    t: (_key: string, _vars?: unknown, fallback?: string) => fallback,
  }),
}));

function cap(code: string, granted: boolean, sensitive = false) {
  return {
    authorizationState: granted ? ('full' as const) : ('none' as const),
    description: null,
    tier: null,
    displayGroupOrder: 10000,
    displayOrder: 100,
    localizedLabels: {},
    localizedDescriptions: {},
    missingCodes: [],
    unlockedMenus: null,

    code,
    group: '客户管理',
    label: `${code}-label`,
    sensitive,
    includes: [],
    granted,
    conventionDerived: false,
  };
}

const legacyTranslations: Array<Capability['localizedLabels'] | null | undefined> = [
  undefined,
  null,
  {},
  { en: '' },
];

const groups: CapabilityGroup[] = [
  {
    group: '客户管理',
    capabilities: [cap('crm.cap.account', true), cap('crm.cap.account_contact_full', false, true)],
  },
];

describe('CapabilityChecklist', () => {
  it('renders a readable group heading for an ungrouped declared capability and permits selection', () => {
    const onToggle = vi.fn();
    render(
      <CapabilityChecklist
        groups={[{ group: null, capabilities: [{ ...cap('org.cap.read', false), group: null }] }]}
        selected={[]}
        onToggle={onToggle}
      />,
    );
    expect(screen.getByText('Other capabilities')).toBeVisible();
    fireEvent.click(screen.getByRole('checkbox', { name: 'org.cap.read-label' }));
    expect(onToggle).toHaveBeenCalledWith('org.cap.read');
    expect(screen.queryByText('null')).toBeNull();
  });
  beforeEach(() => {
    i18n.locale = 'zh-CN';
  });
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
    expect(menus.querySelector('summary')?.textContent).toBe('Related pages (reference only) · 2');
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
      'Permission items 1/3',
    );
    expect(screen.getByTestId('capability-partial-crm.cap.contact').textContent).toBe(
      'Permission items 1/2',
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
    const localized: CapabilityGroup[] = [
      {
        group: 'Organization',
        capabilities: [
          {
            ...cap('org.cap.role', true),
            label: '管理角色',
            localizedLabels: { 'zh-CN': '管理角色', en: 'Manage Roles' },
          },
        ],
      },
    ];
    render(
      <CapabilityChecklist groups={localized} selected={['org.cap.role']} onToggle={onToggle} />,
    );
    const checkbox = screen.getByRole('checkbox', { name: 'Manage Roles' });
    expect((checkbox as HTMLInputElement).checked).toBe(true);
    expect(screen.queryByText('管理角色')).toBeNull();
    fireEvent.click(checkbox);
    expect(onToggle).toHaveBeenCalledExactlyOnceWith('org.cap.role');
  });

  it('updates a declaration label when the locale changes without changing selection', () => {
    const localized: CapabilityGroup[] = [
      {
        group: 'Organization',
        capabilities: [
          {
            ...cap('org.cap.role', true),
            label: '管理角色',
            localizedLabels: { 'zh-CN': '管理角色', en: 'Manage Roles' },
          },
        ],
      },
    ];
    const props = { groups: localized, selected: ['org.cap.role'], onToggle: vi.fn() };
    const view = render(<CapabilityChecklist {...props} />);
    expect(screen.getByRole('checkbox', { name: '管理角色' })).toBeChecked();
    i18n.locale = 'en-GB';
    view.rerender(<CapabilityChecklist {...props} />);
    expect(screen.getByRole('checkbox', { name: 'Manage Roles' })).toBeChecked();
    expect(props.onToggle).not.toHaveBeenCalled();
  });

  it.each(legacyTranslations)(
    'preserves a legacy label for absent or empty translations: %j',
    (localizedLabels) => {
      i18n.locale = 'en-US';
      const legacy: CapabilityGroup[] = [
        {
          group: 'Legacy',
          capabilities: [
            {
              ...cap('legacy.cap.read', false),
              label: 'Legacy Read',
              // @ts-expect-error Deliberately malformed legacy data exercises defensive rendering, not the current wire contract.
              localizedLabels,
            },
          ],
        },
      ];
      render(<CapabilityChecklist groups={legacy} selected={[]} onToggle={() => {}} />);
      expect(screen.getByRole('checkbox', { name: 'Legacy Read' })).not.toBeChecked();
    },
  );

  it('switches a description hint by locale without changing the grant selection', () => {
    const capability = {
      ...cap('org.cap.role', true),
      label: 'Manage Roles',
      description: 'Source role description',
      localizedDescriptions: { 'zh-CN': 'Source role description', en: 'View and manage roles' },
    };
    const props = {
      groups: [{ group: 'Organization', capabilities: [capability] }],
      selected: ['org.cap.role'],
      onToggle: vi.fn(),
    };
    const view = render(<CapabilityChecklist {...props} />);
    expect(screen.getByTestId('capability-org.cap.role')).toHaveAttribute(
      'title',
      'Source role description',
    );
    i18n.locale = 'en-GB';
    view.rerender(<CapabilityChecklist {...props} />);
    expect(screen.getByTestId('capability-org.cap.role')).toHaveAttribute(
      'title',
      'View and manage roles',
    );
    expect(screen.getByRole('checkbox', { name: 'Manage Roles' })).toBeChecked();
    expect(props.onToggle).not.toHaveBeenCalled();
  });

  it('keeps a legacy description and omits the hint when no description is supplied', () => {
    i18n.locale = 'en-US';
    const legacy = { ...cap('legacy.cap.read', false), description: 'Legacy description' };
    render(
      <CapabilityChecklist
        groups={[{ group: 'Legacy', capabilities: [legacy, cap('empty.cap.read', false)] }]}
        selected={[]}
        onToggle={vi.fn()}
      />,
    );
    expect(screen.getByTestId('capability-legacy.cap.read')).toHaveAttribute(
      'title',
      'Legacy description',
    );
    expect(screen.getByTestId('capability-empty.cap.read')).not.toHaveAttribute('title');
  });
});

describe('permission disclosure contract', () => {
  it('exposes included permission codes without changing role selection', () => {
    const onToggle = vi.fn();
    render(
      <CapabilityChecklist
        groups={[
          {
            group: 'Quote',
            capabilities: [
              {
                ...cap('quote.view', false),
                includes: ['quote.read'],
                missingCodes: ['quote.read'],
              },
            ],
          },
        ]}
        selected={[]}
        onToggle={onToggle}
        disabled
      />,
    );
    const disclosure = screen.getByTestId('capability-permissions-quote.view');
    (disclosure as HTMLDetailsElement).open = true;
    fireEvent(disclosure, new Event('toggle'));
    expect(screen.getByText('quote.read')).toBeVisible();
    expect(screen.getByText('Definition unavailable; verify this permission')).toBeVisible();
    expect(onToggle).not.toHaveBeenCalled();
  });
});

function matrixFixture(grants: boolean[]): PermissionMatrixDTO {
  return {
    modules: [
      {
        moduleCode: 'quote',
        moduleName: 'Quote',
        resources: [
          {
            resourceCode: 'quote',
            resourceName: 'Quotation',
            actions: grants.map((granted, index) => ({
              permissionId: index + 1,
              permissionPid: `p${index}`,
              code: `quote.permission.${index}`,
              action: `read${index}`,
              label: `Read quotation ${index}`,
              granted,
              supported: true,
            })),
          },
        ],
      },
    ],
  };
}
function openDetails(code: string) {
  const node = screen.getByTestId(`capability-permissions-${code}`) as HTMLDetailsElement;
  node.open = true;
  fireEvent(node, new Event('toggle'));
  return node;
}
describe('saved and draft permission coverage', () => {
  it('distinguishes saved 2/4 from pending grants, revocation and shared preservation', () => {
    const duty = {
      ...cap('quote.view', false),
      authorizationState: 'partial' as const,
      includes: [0, 1, 2, 3].map((n) => `quote.permission.${n}`),
      missingCodes: ['quote.permission.2', 'quote.permission.3'],
    };
    const visible = [{ group: 'Quote', capabilities: [duty] }];
    const catalog = [
      ...visible,
      {
        group: 'Hidden by search',
        capabilities: [
          {
            ...cap('other.view', true),
            label: 'Other reading duty',
            includes: ['quote.permission.1'],
          },
        ],
      },
    ];
    const onToggle = vi.fn();
    render(
      <CapabilityChecklist
        groups={visible}
        catalog={catalog}
        selected={[]}
        onToggle={onToggle}
        matrix={matrixFixture([true, true, false, false])}
        projectedMatrix={matrixFixture([false, true, true, false])}
      />,
    );
    expect(screen.getByText('Permission items 2/4')).toBeVisible();
    expect(screen.queryByText('Read quotation 0')).toBeNull();
    const details = openDetails('quote.view');
    expect(details.textContent).toContain('Read quotation 0');
    expect(
      screen.getByTestId('capability-permission-quote.view-quote.permission.0'),
    ).toHaveTextContent('Will revoke');
    expect(
      screen.getByTestId('capability-permission-quote.view-quote.permission.1'),
    ).toHaveTextContent('Unchanged');
    expect(
      screen.getByTestId('capability-permission-quote.view-quote.permission.2'),
    ).toHaveTextContent('Will grant');
    const shared = screen
      .getByText('Also included by 1 duties (declarations, not grant provenance)')
      .closest('details') as HTMLDetailsElement;
    shared.open = true;
    expect(screen.getByText('Other reading duty')).toBeVisible();
    expect(onToggle).not.toHaveBeenCalled();
  });
  it('filters names and exact long codes, explains empty results, and remains readable when editing is disabled', () => {
    const duty = {
      ...cap('quote.view', true),
      includes: [0, 1, 2, 3].map((n) => `quote.permission.${n}`),
    };
    render(
      <CapabilityChecklist
        groups={[{ group: 'Quote', capabilities: [duty] }]}
        selected={['quote.view']}
        disabled
        onToggle={() => {
          throw new Error('read-only interaction wrote selection');
        }}
        matrix={matrixFixture([true, true, true, true])}
        projectedMatrix={matrixFixture([true, true, true, true])}
      />,
    );
    openDetails('quote.view');
    const search = screen.getByRole('searchbox', { name: 'Filter by name or permission code' });
    fireEvent.change(search, { target: { value: 'quotation 2' } });
    expect(screen.getByText('quote.permission.2')).toBeVisible();
    expect(screen.queryByText('quote.permission.0')).toBeNull();
    fireEvent.change(search, { target: { value: 'does-not-exist' } });
    expect(screen.getByRole('status')).toHaveTextContent('No matching permission items');
    fireEvent.change(search, { target: { value: 'quote.permission.3' } });
    expect(screen.getByText('Read quotation 3')).toBeVisible();
    expect(screen.getByRole('checkbox')).toBeDisabled();
  });
});
