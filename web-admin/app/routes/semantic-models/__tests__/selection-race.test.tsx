import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { expect, it, vi } from 'vitest';
import SemanticModelsPage from '../index';

const { fetchMeta } = vi.hoisted(() => ({ fetchMeta: vi.fn() }));
vi.mock('~/plugins/core-semantic/api/semanticApi', () => ({
  fetchSemanticMeta: fetchMeta,
  fetchUsageSummary: vi.fn().mockResolvedValue(null),
  validateSemanticYaml: vi.fn(), publishSemanticYaml: vi.fn(), runSemanticQuery: vi.fn(),
  EXAMPLE_SEMANTIC_YAML: '',
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'zh-CN', t: (key: string, _: unknown, fallback: string) => fallback || key }),
}));

it('a late initial catalog response preserves the model chosen after a refresh', async () => {
  const catalog = { models: [
    { code: 'default', label: { 'zh-CN': '默认模型' }, metrics: [{ code: 'default_count' }], dimensions: [] },
    { code: 'chosen', label: { 'zh-CN': '已选模型' }, metrics: [{ code: 'chosen_count' }], dimensions: [] },
  ] };
  let release!: (value: typeof catalog) => void;
  const delayed = new Promise<typeof catalog>(resolve => { release = resolve; });
  fetchMeta.mockReturnValueOnce(delayed).mockResolvedValue(catalog);
  render(<MemoryRouter><SemanticModelsPage /></MemoryRouter>);
  await waitFor(() => expect(fetchMeta).toHaveBeenCalledTimes(1));
  fireEvent.click(screen.getByTestId('semantic-models-reload'));
  fireEvent.click(await screen.findByTestId('semantic-model-item-chosen'));
  await waitFor(() => expect(screen.getByTestId('semantic-metric-chosen_count')).toBeInTheDocument());
  await act(async () => { release(catalog); await delayed; });
  expect(screen.getByTestId('semantic-metric-chosen_count')).toBeInTheDocument();
  expect(screen.queryByTestId('semantic-metric-default_count')).not.toBeInTheDocument();
});
