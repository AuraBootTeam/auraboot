import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PermissionTree } from '../PermissionTree';
import type { PermissionTreeNode } from '../types';

// Mimics the real I18nContext.translate contract: returns the catalog value when present,
// otherwise the fallback (3rd arg), otherwise the key itself.
const CATALOG: Record<string, string> = {
  'permission.perm_translated': 'Translated Name',
};
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    t: (key: string, _params?: Record<string, unknown>, fallback?: string) =>
      CATALOG[key] ?? fallback ?? key,
    locale: 'en-US',
    setLocale: () => {},
    loading: false,
    recovering: false,
    isRTL: false,
  }),
}));

function node(code: string, name: string): PermissionTreeNode {
  return { id: code, pid: code, code, name, type: 'menu' };
}

describe('PermissionTree i18n', () => {
  it('renders the permission.{code} translation when present', () => {
    render(
      <PermissionTree
        nodes={[node('perm_translated', '原始名称')]}
        selectedIds={[]}
        onSelectionChange={vi.fn()}
      />,
    );
    expect(screen.getByText('Translated Name')).toBeInTheDocument();
    // raw source name must NOT leak when a translation exists
    expect(screen.queryByText('原始名称')).not.toBeInTheDocument();
  });

  it('falls back to the raw node name when no translation record exists', () => {
    render(
      <PermissionTree
        nodes={[node('perm_missing', '回退名称')]}
        selectedIds={[]}
        onSelectionChange={vi.fn()}
      />,
    );
    // must show the fallback name, NOT the raw i18n key "permission.perm_missing"
    expect(screen.getByText('回退名称')).toBeInTheDocument();
    expect(screen.queryByText('permission.perm_missing')).not.toBeInTheDocument();
  });
});
