import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ChartEmptyState } from '../ChartEmptyState';
import { chartEmptyStateText } from '../chartEmptyStateText';
import messages from '../chartEmptyState.i18n.json';

// The locale contract under test: every chrome string must come from the
// chartEmptyState catalog via useI18n().locale; authored title/description
// props keep their source values (user data is never translated).
const language = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: language.locale }),
}));

describe('ChartEmptyState locale contracts', () => {
  afterEach(() => {
    language.locale = 'zh-CN';
  });

  it('exposes zh-CN and en catalog entries for every chrome string', () => {
    for (const [key, entry] of Object.entries(messages)) {
      expect(entry['zh-CN'], `${key} must have a zh-CN entry`).toBeTruthy();
      expect(entry['en'], `${key} must have an en entry`).toBeTruthy();
    }
  });

  it('renders the default zh-CN chrome from the catalog', () => {
    language.locale = 'zh-CN';
    render(<ChartEmptyState />);

    const root = screen.getByTestId('chart-empty-state');
    expect(root).toHaveTextContent('等待数据');
    expect(root).toHaveTextContent('暂无数据');
    expect(root).toHaveTextContent('数据源已连接');
    expect(root).not.toHaveTextContent('Awaiting Data');
  });

  it.each(['en', 'en-US'])('localizes the chrome through the catalog for %s', (locale) => {
    language.locale = locale;
    render(<ChartEmptyState />);

    const root = screen.getByTestId('chart-empty-state');
    expect(root).toHaveTextContent('Awaiting data');
    expect(root).toHaveTextContent('No data yet');
    expect(root).toHaveTextContent('Data source is connected.');
    expect(root).not.toHaveTextContent('等待数据');
  });

  it('falls back to zh-CN for an unmapped locale instead of rendering the bare English literal', () => {
    language.locale = 'fr-FR';
    render(<ChartEmptyState />);

    const root = screen.getByTestId('chart-empty-state');
    expect(root).toHaveTextContent('等待数据');
    expect(root).not.toHaveTextContent('Awaiting data');
  });

  it('keeps authored title and description props untranslated while chrome localizes', () => {
    language.locale = 'en';
    render(<ChartEmptyState title="季度回款" description="录入合同回款后可见" />);

    const root = screen.getByTestId('chart-empty-state');
    expect(root).toHaveTextContent('Awaiting data');
    expect(root).toHaveTextContent('季度回款');
    expect(root).toHaveTextContent('录入合同回款后可见');
    expect(root).not.toHaveTextContent('No data yet');
  });

  it('resolves every key through the same helper the component uses', () => {
    expect(chartEmptyStateText('awaitingData', 'zh-CN')).toBe('等待数据');
    expect(chartEmptyStateText('awaitingData', 'en')).toBe('Awaiting data');
    expect(chartEmptyStateText('noDataYet', 'zh-CN')).toBe('暂无数据');
    expect(chartEmptyStateText('emptyHint', 'en')).toBe(
      'Data source is connected. Add records to populate this widget.',
    );
  });
});
