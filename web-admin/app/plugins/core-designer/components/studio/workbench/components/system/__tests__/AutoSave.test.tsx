import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AutoSave } from '~/plugins/core-designer/components/studio/workbench/components/system/AutoSave';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';

// Explicitly mock useI18n so locale='zh-CN' is a test contract,
// not an implicit dependency on the createContext default value.
vi.mock('~/contexts/I18nContext', async () => {
  const actual = await vi.importActual<typeof import('~/contexts/I18nContext')>(
    '~/contexts/I18nContext',
  );
  return {
    ...actual,
    useI18n: () => ({
      locale: 'zh-CN',
      t: (key: string, _params?: Record<string, any>, fallback?: string) => fallback ?? key,
      setLocale: () => {},
      loading: false,
      isRTL: false,
    }),
  };
});

const mockVersionManager = {
  createVersion: vi.fn(),
};

vi.mock('~/plugins/core-designer/components/studio/services/managers', () => ({
  getVersionManager: () => mockVersionManager,
}));

const schema = {
  id: 'page',
  name: 'Test',
  version: '1.0',
  meta: {
    title: '测试',
    createdAt: '',
    updatedAt: '',
  },
  components: [],
};

describe('AutoSave (studio implementation)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mockVersionManager.createVersion.mockResolvedValue(undefined);
    (globalThis.navigator as any) = {
      onLine: true,
      sendBeacon: vi.fn(),
    };
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders status indicator', () => {
    render(<AutoSave pageId="p1" schema={schema as any} />);
    // Default locale is zh-CN; resolveDesignerText resolves to the zh-CN string
    expect(
      screen.getByText(resolveDesignerText(DESIGNER_I18N.autoSave.unsaved, 'zh-CN')),
    ).toBeInTheDocument();
  });

  it('renders manual save button disabled initially', () => {
    render(<AutoSave pageId="p1" schema={schema as any} />);
    const noChangesTitle = resolveDesignerText(DESIGNER_I18N.autoSave.noUnsavedChanges, 'zh-CN');
    const manualButtons = screen.getAllByTitle(noChangesTitle);
    expect(manualButtons.length).toBeGreaterThan(0);
    manualButtons.forEach((button: HTMLElement) =>
      expect(button as HTMLButtonElement).toBeDisabled(),
    );
  });
});
