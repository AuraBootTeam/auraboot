import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { SearchPanel } from '../SearchPanel';
import { searchService } from '../SearchService';
import type { SearchIndexEntry, SearchScope } from '../types';

const dictionary = (locale: string) => parse(readFileSync(
  path.resolve(process.cwd(), `../platform/src/main/resources/i18n.${locale}.yaml`), 'utf8',
));
const scopes: SearchScope[] = ['fields', 'components', 'bindings', 'actions'];
const indexers = scopes.map((scope, index) => () => [{
  id: `fixture-${scope}`, type: ['field', 'component', 'binding', 'action'][index],
  text: { title: `amount ${scope}` },
  data: { id: `fixture-${scope}`, type: ['field', 'component', 'binding', 'action'][index],
    title: `amount ${scope}`, score: 0 },
}] as SearchIndexEntry[]);
function localized(locale: string, props: React.ComponentProps<typeof SearchPanel> = {}) {
  return render(<I18nProvider initialLocale={locale} initialData={dictionary(locale)}>
    <SearchPanel {...props} />
  </I18nProvider>);
}
beforeEach(() => {
  localStorage.clear(); searchService.reset(); searchService.clearHistory(); searchService.clearCache();
  scopes.forEach((scope, index) => searchService.registerIndexer(scope, indexers[index]));
});
afterEach(() => {
  cleanup();
  scopes.forEach((scope, index) => searchService.unregisterIndexer(scope, indexers[index]));
  searchService.reset(); searchService.clearHistory(); vi.restoreAllMocks();
});

describe.each(['en-US', 'zh-CN'])('designer search in %s', locale => {
  it('localizes labels while preserving actual scope filters', async () => {
    const labels = dictionary(locale).designer_search;
    localized(locale);
    const input = screen.getByPlaceholderText(labels.placeholder);
    for (const scope of ['all', ...scopes]) expect(screen.getByRole('button', { name: labels.scope[scope] })).toBeInTheDocument();
    fireEvent.change(input, { target: { value: 'amount' } });
    await waitFor(() => expect(searchService.getState().results).toHaveLength(4));
    for (const scope of scopes) {
      fireEvent.click(screen.getByRole('button', { name: labels.scope[scope] }));
      await waitFor(() => expect(searchService.getState().results.map(result => result.id)).toEqual([`fixture-${scope}`]));
      expect(screen.getByRole('button', { name: `amount ${scope}` })).toBeInTheDocument();
    }
    fireEvent.click(screen.getByRole('button', { name: labels.scope.all }));
    await waitFor(() => expect(searchService.getState().results).toHaveLength(4));
  });
  it('interpolates an unmatched query as text and clears service state', async () => {
    const labels = dictionary(locale).designer_search;
    localized(locale);
    const input = screen.getByPlaceholderText(labels.placeholder);
    const query = '<script>missing</script>';
    fireEvent.change(input, { target: { value: query } });
    await waitFor(() => expect(searchService.getState().query).toBe(query));
    expect(screen.getByText(labels.no_results.replace('{query}', query))).toBeInTheDocument();
    expect(document.querySelector('script')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: labels.clear_query }));
    expect(input).toHaveValue('');
    expect(searchService.getState().query).toBe('');
    expect(searchService.getState().results).toEqual([]);
    expect(input).toHaveFocus();
  });
  it('uses history controls to rerun and erase real searches', async () => {
    const labels = dictionary(locale).designer_search;
    await searchService.search({ query: 'amount', scope: 'all' }); searchService.reset();
    localized(locale);
    const input = screen.getByPlaceholderText(labels.placeholder);
    fireEvent.focus(input);
    expect(screen.getByText(labels.history)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'amount', exact: true }));
    await waitFor(() => expect(searchService.getState().results).toHaveLength(4));
    expect(input).toHaveValue('amount');
    fireEvent.click(screen.getByRole('button', { name: labels.clear_query }));
    fireEvent.focus(input);
    fireEvent.click(screen.getByRole('button', { name: labels.clear_history }));
    expect(searchService.getState().history).toEqual([]);
    expect(screen.queryByText(labels.history)).not.toBeInTheDocument();
  });
  it('keeps keyboard selection and caller-provided placeholder semantics', async () => {
    const onSelect = vi.fn(); localized(locale, { onSelect, placeholder: 'Custom query' });
    const input = screen.getByPlaceholderText('Custom query');
    fireEvent.change(input, { target: { value: 'amount' } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'amount actions' })).toBeInTheDocument());
    fireEvent.keyDown(input, { key: 'ArrowDown' }); fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect.mock.calls[0][0].id).toBe('fixture-components');
    expect(searchService.getState().selectedId).toBe('fixture-components');
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(input).toHaveValue(''); expect(searchService.getState().selectedId).toBeNull();
  });
});
