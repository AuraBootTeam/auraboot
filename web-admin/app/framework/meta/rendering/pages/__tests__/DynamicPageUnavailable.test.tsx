import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DynamicPageUnavailable } from '../DynamicPageUnavailable';

const state = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: state.locale, t: (key: string) => key }),
}));
afterEach(cleanup);

describe('DynamicPageUnavailable', () => {
  it.each([
    ['zh-CN', '页面不可用', '无权限访问此页面，请联系管理员。'],
    ['en', 'Page Unavailable', 'Access denied. Contact your administrator to request access.'],
  ])(
    'renders an actionable %s denial without internal route diagnostics',
    (locale, title, detail) => {
      state.locale = locale;
      render(
        <DynamicPageUnavailable message="Access denied for menu path: /p/bom_material_rule_dict; InternalDiagnostic" />,
      );
      expect(document.body.textContent).not.toContain('/p/');
      expect(screen.getByRole('heading').textContent).toBe(title);
      expect(screen.getByText(detail)).toBeTruthy();
      expect(document.body.textContent).not.toContain('/p/');
      expect(document.body.textContent).not.toContain('InternalDiagnostic');
    },
  );
});
