import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PagePresentationProvider, useSchemaPresentation, usePagePresentation } from '../PagePresentationContext';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';
const t = (key: string) => key;
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en', t }) }));
function Page({ schema }: { schema: UnifiedSchema | null }) { useSchemaPresentation(schema); return null; }
function Observer() { const presentation = usePagePresentation(); return <output data-testid="mode">{presentation?.title || 'standard'}</output>; }
const schema = { pageKey: 'orders_list', kind: 'list', title: { en: 'Orders' }, extension: { presentation: 'workspace', workspaceLabel: { en: 'Sales' } }, blocks: [] } as UnifiedSchema;
describe('Schema-owned page presentation', () => {
  it('uses the declared page configuration and clears it when that page unmounts', () => {
    const { rerender } = render(<PagePresentationProvider><Observer /><Page schema={schema} /></PagePresentationProvider>);
    expect(screen.getByTestId('mode')).toHaveTextContent('Orders');
    rerender(<PagePresentationProvider><Observer /></PagePresentationProvider>);
    expect(screen.getByTestId('mode')).toHaveTextContent('standard');
  });
  it('does not change an undeclared page into a workspace', () => {
    render(<PagePresentationProvider><Observer /><Page schema={{ ...schema, extension: {} }} /></PagePresentationProvider>);
    expect(screen.getByTestId('mode')).toHaveTextContent('standard');
  });
});
