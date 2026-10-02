import fs from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import { render, screen, fireEvent } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { CommandPalette } from '../CommandPalette';
import { fetchResult } from '~/shared/services/http-client';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(async () => ({ code: 0, data: { models: [], preference: {} } })),
}));

vi.mock('~/root-data', () => ({ useRootLoaderData: () => ({ menus: [] }) }));

const seed = JSON.parse(
  fs.readFileSync(
    path.resolve(process.cwd(), '../platform/src/main/resources/seed/i18n-base.json'),
    'utf8',
  ),
) as Array<Record<string, string>>;

function renderLocale(locale: string) {
  const yaml = parse(
    fs.readFileSync(
      path.resolve(process.cwd(), `../platform/src/main/resources/i18n.${locale}.yaml`),
      'utf8',
    ),
    { uniqueKeys: false },
  );
  const translations: Record<string, string> = {};
  function flatten(value: Record<string, unknown>, prefix = '') {
    for (const [key, entry] of Object.entries(value)) {
      const fullKey = prefix ? `${prefix}.${key}` : key;
      if (typeof entry === 'string') translations[fullKey] = entry;
      else if (entry && typeof entry === 'object')
        flatten(entry as Record<string, unknown>, fullKey);
    }
  }
  flatten(yaml);
  Object.assign(
    translations,
    Object.fromEntries(seed.filter((r) => r[locale]).map((r) => [r.key, r[locale]])),
  );
  return render(
    <I18nProvider initialData={translations} initialLocale={locale}>
      <CommandPalette />
    </I18nProvider>,
  );
}

describe('CommandPalette bundled locale copy', () => {
  beforeEach(() => {
    vi.mocked(fetchResult)
      .mockReset()
      .mockResolvedValue({ code: '0', desc: '', data: { models: [], preference: {} } });
  });
  it('renders the Chinese header trigger and search dialog from actual YAML and seed resources', () => {
    renderLocale('zh-CN');
    const trigger = screen.getByTestId('header-search-trigger');
    expect(trigger).toHaveTextContent('搜索');
    expect(trigger).not.toHaveTextContent('Search');
    fireEvent.click(trigger);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('搜索页面、记录、文档...')).toBeInTheDocument();
    expect(screen.getByText('全局搜索')).toBeInTheDocument();
  });

  it('renders search settings and the empty state in Chinese', async () => {
    renderLocale('zh-CN');
    fireEvent.click(screen.getByTestId('header-search-trigger'));
    fireEvent.click(screen.getByRole('button', { name: '搜索设置' }));
    expect(await screen.findByText('暂无可搜索的模型')).toBeInTheDocument();
    expect(screen.getByText('选择并排列参与记录搜索的模型。')).toBeInTheDocument();
    expect(screen.getByTestId('command-palette-settings-close')).toHaveTextContent('返回');
  });

  it('shows translated saving state and persisted preference feedback', async () => {
    renderLocale('zh-CN');
    fireEvent.click(screen.getByTestId('header-search-trigger'));
    fireEvent.click(screen.getByRole('button', { name: '搜索设置' }));
    await screen.findByText('暂无可搜索的模型');
    let finishSave!: (value: any) => void;
    vi.mocked(fetchResult).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishSave = resolve;
        }),
    );
    fireEvent.click(screen.getByTestId('command-palette-settings-save'));
    expect(await screen.findByText('保存中...')).toBeInTheDocument();
    finishSave({ code: '0', data: {} });
    expect(await screen.findByText('搜索设置已保存')).toBeInTheDocument();
    expect(fetchResult).toHaveBeenLastCalledWith('/api/search/global/preferences', {
      method: 'put',
      params: { modelCodes: [] },
    });
  });

  it('keeps the English locale translated independently', () => {
    renderLocale('en-US');
    expect(screen.getByTestId('header-search-trigger')).toHaveTextContent('Search');
    fireEvent.click(screen.getByTestId('header-search-trigger'));
    expect(screen.getByPlaceholderText('Search pages, records, docs...')).toBeInTheDocument();
  });
});
