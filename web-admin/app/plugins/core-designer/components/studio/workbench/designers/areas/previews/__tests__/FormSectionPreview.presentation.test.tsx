import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'zh-CN', t: (key: string) => key }),
}));
import { FormSectionPreview } from '../FormSectionPreview';
import { StudioFormPresentationContext } from '../../../../StudioFormPresentation';
import { resolveFormPresentation } from '~/framework/meta/rendering/pages/formPage/formPresentation';
import type { DslBlock } from '~/plugins/core-designer/components/studio/domain/dsl/types';
afterEach(cleanup);
describe('Studio form section presentation', () => {
  it('uses real metadata labels, configured columns and full-width v4 field spans', () => {
    render(
      <StudioFormPresentationContext.Provider
        value={{
          presentation: resolveFormPresentation({ formPresentation: { columns: 3 } }),
          fields: [{ modelCode: 'delivery', code: 'notes', label: '交付说明' }],
        }}
      >
        <FormSectionPreview
          readonly
          block={
            {
              id: 'delivery',
              blockType: 'form-section',
              fields: [{ field: 'notes', layout: { colSpan: 12 } }],
            } as unknown as DslBlock
          }
        />
      </StudioFormPresentationContext.Provider>,
    );
    expect(screen.getByText('交付说明')).toBeVisible();
    const field = screen.getByTestId('designer-field-notes');
    expect(field).toHaveClass('@[56rem]/form:col-span-3');
    expect(field.parentElement).toHaveClass('@[56rem]/form:grid-cols-3');
  });
});
