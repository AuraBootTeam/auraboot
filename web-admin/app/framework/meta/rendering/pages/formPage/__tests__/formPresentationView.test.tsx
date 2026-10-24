import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
vi.mock('~/framework/meta/contexts/DataSourceContext', () => ({
  DataSourceProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('~/framework/meta/rendering/DslFormFillContext', () => ({
  DslFormFillProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('react-router', () => ({
  Link: ({ children, to }: { children: React.ReactNode; to: string }) => (
    <a href={to}>{children}</a>
  ),
}));
import { renderFormPageView } from '../renderFormPageView';
afterEach(cleanup);
function args(embedded: boolean): Parameters<typeof renderFormPageView>[0] {
  return {
    schema: {
      kind: 'form',
      title: 'Customer',
      modelCode: 'customer',
      extension: { presentation: 'workspace', formGuidance: { title: 'Guidance', steps: [] } },
      blocks: [
        { id: 'main', blockType: 'form-section', title: 'Basics', fields: [] },
        {
          id: 'actions',
          blockType: 'form-buttons',
          buttons: [{ code: 'submit', action: 'save', label: 'Save', primary: true }],
        },
      ],
    },
    tableName: 'customer',
    mainRecordLoaded: true,
    schemaFieldNames: new Set(),
    fieldMetaLoaded: true,
    onSubmitOverride: embedded ? vi.fn() : undefined,
    onCancelOverride: embedded ? vi.fn() : undefined,
    fieldErrors: {},
    fieldPermissions: {},
    subTableBlocks: [],
    enhancedColumns: {},
    subTableColumnsMap: {},
    modelFields: [],
    aiFields: [],
    aiFill: {},
    summaryErrors: [],
    locale: 'en-US',
    t: (key: string) => key,
    pageContext: {},
    hasPermission: () => true,
    isEditMode: false,
    sidebarWidth: 215,
    formData: {},
  } as unknown as Parameters<typeof renderFormPageView>[0];
}
describe('form presentation host boundaries', () => {
  it('keeps the legacy full-page heading, guidance and fixed action bar', () => {
    render(renderFormPageView(args(false)));
    expect(screen.getByRole('heading', { name: 'Customer' })).toBeVisible();
    expect(screen.getByRole('heading', { name: 'Guidance' })).toBeVisible();
    expect(screen.getByTestId('form-action-footer')).toHaveClass('fixed');
  });
  it('lets an embedded host own its heading and contains its actions', () => {
    render(renderFormPageView(args(true)));
    expect(screen.queryByRole('heading', { name: 'Customer' })).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Guidance' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Save' })).toBeVisible();
    const footer = screen.getByTestId('form-action-footer');
    expect(footer).not.toHaveClass('fixed');
    expect(footer).not.toHaveClass('sticky');
    expect(footer.style.left).toBe('');
  });
});
