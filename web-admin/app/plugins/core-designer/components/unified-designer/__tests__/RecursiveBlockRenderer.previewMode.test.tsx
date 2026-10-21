import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { RecursiveBlockRenderer } from '../runtime/RecursiveBlockRenderer';
import type { DslBlockV3, PageSchemaV3 } from '../types';

vi.mock('~/contexts/AuthContext', () => ({
  usePermissions: () => ({ hasPermission: () => true }),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en-US', t: (key: string) => key }),
}));

function schema(block: DslBlockV3): PageSchemaV3 {
  return { schemaVersion: 3, kind: 'detail', id: 'data_execution_contract', blocks: [block] };
}

const sources = [
  { type: 'namedQuery', queryCode: 'preview_only_query' },
  { type: 'query-builder', query: { modelCode: 'page_schema' } },
];

describe('explicit static data execution mode', () => {
  it.each(sources)('keeps helper %j local without invoking the runtime loader', async source => {
    const loadHelperBlockData = vi.fn(async () => ({
      source: 'named-query' as const, description: 'Remote helper data',
    }));
    render(<RecursiveBlockRenderer
      runtimeServices={{ loadHelperBlockData }}
      schema={schema({
        id: 'helper', blockType: 'ai-fill-banner',
        dataSource: { ...source, executionMode: 'preview' },
        props: { description: 'Authored helper preview' },
      })}
    />);
    await waitFor(() => expect(loadHelperBlockData).not.toHaveBeenCalled());
    expect(screen.getByTestId('runtime-ai-fill-banner-helper')).toHaveTextContent('Authored helper preview');
    expect(screen.queryByTestId('runtime-helper-error-helper')).not.toBeInTheDocument();
  });

  it.each(sources)('keeps widget %j local without invoking the runtime loader', async source => {
    const loadWidgetData = vi.fn(async () => ({
      source: 'named-query' as const, columns: ['Name'], rows: [['Remote widget data']],
    }));
    render(<RecursiveBlockRenderer
      runtimeServices={{ loadWidgetData }}
      schema={schema({
        id: 'widget', blockType: 'widget', widgetType: 'table',
        dataSource: { ...source, executionMode: 'preview' },
        props: { columns: ['Name'], rows: [['Authored widget preview']] },
      })}
    />);
    await waitFor(() => expect(loadWidgetData).not.toHaveBeenCalled());
    expect(screen.getByTestId('runtime-widget-table-widget')).toHaveTextContent('Authored widget preview');
    expect(screen.queryByTestId('runtime-widget-error-widget')).not.toBeInTheDocument();
  });

  it.each(['live', undefined])('retains helper named-query execution in mode %s', async executionMode => {
    const loadHelperBlockData = vi.fn(async () => ({
      source: 'named-query' as const, description: 'Remote helper data',
    }));
    render(<RecursiveBlockRenderer
      runtimeServices={{ loadHelperBlockData }}
      schema={schema({
        id: 'helper', blockType: 'ai-fill-banner',
        dataSource: { type: 'namedQuery', queryCode: 'live_helper_query', executionMode },
        props: { description: 'Authored helper preview' },
      })}
    />);
    expect(await screen.findByText('Remote helper data')).toBeVisible();
    expect(loadHelperBlockData).toHaveBeenCalled();
  });

  it.each(['live', undefined])('retains widget named-query execution in mode %s', async executionMode => {
    const loadWidgetData = vi.fn(async () => ({
      source: 'named-query' as const, columns: ['Name'], rows: [['Remote widget data']],
    }));
    render(<RecursiveBlockRenderer
      runtimeServices={{ loadWidgetData }}
      schema={schema({
        id: 'widget', blockType: 'widget', widgetType: 'table',
        dataSource: { type: 'namedQuery', queryCode: 'live_widget_query', executionMode },
        props: { columns: ['Name'], rows: [['Authored widget preview']] },
      })}
    />);
    expect(await screen.findByText('Remote widget data')).toBeVisible();
    expect(loadWidgetData).toHaveBeenCalled();
  });
});
