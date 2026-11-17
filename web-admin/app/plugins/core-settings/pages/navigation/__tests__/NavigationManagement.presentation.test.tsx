import { StrictMode } from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import NavigationManagement from '../NavigationManagement';
import type { NavigationSnapshot } from '../navigationService';
const state = vi.hoisted(() => ({
  allowed: true,
  locale: 'zh-CN',
  read: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
}));
vi.mock('~/contexts/AuthContext', () => ({
  usePermissions: () => ({ hasPermission: () => state.allowed }),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: state.locale, t: (key: string, _args: unknown, fallback: string) => fallback ?? key }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: vi.fn(), showErrorToast: vi.fn() }),
}));
vi.mock('../navigationService', () => ({
  NavigationConflict: class extends Error {},
  navigationService: state,
}));
vi.mock('../NavigationRoleAssignment', () => ({
  default: () => <p>Visible roles configuration</p>,
}));
const snapshot: NavigationSnapshot = {
  revision: 'a'.repeat(64),
  entries: [
    {
      pid: 'menu',
      source: 'default',
      customized: false,
      available: true,
      definition: {
        name: 'BOM projects',
        parentPid: null,
        icon: 'Link',
        kind: 'page',
        target: 'bom-page',
        opening: 'same',
        permissionCode: null,
        authenticated: true,
        visible: true,
        enabled: true,
        orderNo: 1,
      },
    },
  ],
  pages: [{ value: 'bom-page', label: 'BOM projects' }],
  permissions: [],
  audit: [],
};
beforeEach(() => {
  state.allowed = true;
  state.locale = 'zh-CN';
  state.read.mockReset().mockResolvedValue(snapshot);
  state.create.mockReset();
  state.update.mockReset();
});
function show(strict = false) {
  const content = (
    <RouterProvider
      router={createMemoryRouter(
        [
          { path: '/settings/navigation', element: <NavigationManagement /> },
          { path: '/enterprise/permissions', element: <p>Role workspace</p> },
        ],
        { initialEntries: ['/settings/navigation'] },
      )}
    />
  );
  render(strict ? <StrictMode>{content}</StrictMode> : content);
}
describe('high-fidelity menu editor journeys', () => {
  it('ignores an obsolete initial load after the current editor has selected another menu', async () => {
    let resolveOld!: (value: NavigationSnapshot) => void;
    const current = {
      ...snapshot,
      entries: [
        ...snapshot.entries,
        {
          ...snapshot.entries[0],
          pid: 'second',
          definition: { ...snapshot.entries[0].definition, name: 'Engineering docs' },
        },
      ],
    };
    state.read
      .mockImplementationOnce(
        () =>
          new Promise<NavigationSnapshot>((resolve) => {
            resolveOld = resolve;
          }),
      )
      .mockResolvedValue(current);
    show(true);
    await screen.findByTestId('navigation-name');
    fireEvent.click(screen.getByTestId('navigation-entry-second'));
    expect(screen.getByTestId('navigation-name')).toHaveValue('Engineering docs');
    await act(async () => resolveOld(current));
    expect(screen.getByTestId('navigation-name')).toHaveValue('Engineering docs');
  });
  it('opens an actual menu definition with visible roles rather than an empty editor', async () => {
    show();
    expect(await screen.findByTestId('navigation-name')).toHaveValue('BOM projects');
    expect(screen.getByText('Visible roles configuration')).toBeVisible();
    expect(screen.getByTestId('navigation-save')).toBeDisabled();
  });
  it('preserves a new-menu draft through cancel and discards only after confirmation', async () => {
    show();
    await screen.findByTestId('navigation-name');
    fireEvent.click(screen.getByTestId('navigation-add'));
    expect(screen.getByTestId('navigation-create-dialog')).toBeVisible();
    fireEvent.change(screen.getByTestId('navigation-name'), {
      target: { value: 'Engineering docs' },
    });
    fireEvent.click(screen.getByTestId('navigation-create-cancel'));
    expect(await screen.findByTestId('confirm-dialog')).toBeVisible();
    expect(screen.queryByTestId('navigation-create-dialog')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('confirm-cancel'));
    expect(await screen.findByTestId('navigation-name')).toHaveValue('Engineering docs');
    fireEvent.click(screen.getByTestId('navigation-create-cancel'));
    fireEvent.click(await screen.findByTestId('confirm-ok'));
    await waitFor(() =>
      expect(screen.queryByTestId('navigation-create-dialog')).not.toBeInTheDocument(),
    );
    expect(state.create).not.toHaveBeenCalled();
    expect(state.update).not.toHaveBeenCalled();
  });
  it('returns from preview to the same new-menu draft without saving', async () => {
    show();
    await screen.findByTestId('navigation-name');
    fireEvent.click(screen.getByTestId('navigation-add'));
    fireEvent.change(screen.getByTestId('navigation-name'), {
      target: { value: 'Engineering docs' },
    });
    fireEvent.change(screen.getByTestId('navigation-kind'), { target: { value: 'internal' } });
    fireEvent.change(screen.getByTestId('navigation-target'), { target: { value: '/home' } });
    fireEvent.click(screen.getByTestId('navigation-preview'));
    expect(await screen.findByTestId('confirm-dialog')).toBeVisible();
    fireEvent.click(screen.getByTestId('confirm-cancel'));
    expect(await screen.findByTestId('navigation-target')).toHaveValue('/home');
    expect(state.create).not.toHaveBeenCalled();
  });
  it('protects an existing draft when leaving for role permissions', async () => {
    show();
    await screen.findByTestId('navigation-name');
    fireEvent.change(screen.getByTestId('navigation-name'), { target: { value: 'Pending name' } });
    fireEvent.click(screen.getByRole('link', { name: 'Role permissions / Menu management' }));
    fireEvent.click(await screen.findByTestId('confirm-cancel'));
    expect(screen.getByTestId('navigation-name')).toHaveValue('Pending name');
    fireEvent.click(screen.getByRole('link', { name: 'Role permissions / Menu management' }));
    fireEvent.click(await screen.findByTestId('confirm-ok'));
    expect(await screen.findByText('Role workspace')).toBeVisible();
    expect(state.update).not.toHaveBeenCalled();
  });
  it('does not read menu configuration for an unauthorized viewer', () => {
    state.allowed = false;
    show();
    expect(screen.getByText('Menu management unavailable')).toBeVisible();
    expect(state.read).not.toHaveBeenCalled();
  });
});

it.each([['zh-CN', '当前图标'], ['en-US', 'Current icon']])('localizes an unlisted stored menu icon in %s', async (locale, label) => {
  state.locale = locale;
  state.read.mockResolvedValue({ ...snapshot, entries: [{ ...snapshot.entries[0], definition: {
    ...snapshot.entries[0].definition, icon: 'LegacyCustomIcon',
  } }] });
  show();
  await screen.findByTestId('navigation-name');
  expect(document.querySelector('option[value="LegacyCustomIcon"]')).toHaveTextContent(label);
});
