import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { BlockConfig } from '~/framework/meta/schemas/types';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: 'en' }),
}));

const recordListProps = vi.fn();
vi.mock('../RecordListView', () => ({
  RecordListView: (props: any) => {
    recordListProps(props);
    return <div data-testid="record-list-view" />;
  },
}));

import { EmbeddedListBlockRenderer } from '../EmbeddedListBlockRenderer';

const block = {
  id: 'task_items',
  blockType: 'embedded-list',
  modelCode: 'bom_standard_item',
  parentField: 'bom_std_task_id',
  title: { 'zh-CN': '标准 BOM 行', en: 'Standard BOM Rows' },
  columns: [{ field: 'bom_std_material_code', label: 'Code' }],
} as unknown as BlockConfig;

describe('EmbeddedListBlockRenderer', () => {
  it('builds fixedFilters from parentField + explicit parentRecordId and passes columns/modelCode', () => {
    recordListProps.mockClear();
    render(<EmbeddedListBlockRenderer block={block} parentRecordId="TASK-1" />);

    expect(screen.getByTestId('record-list-view')).toBeInTheDocument();
    const props = recordListProps.mock.calls.at(-1)![0];
    expect(props.modelCode).toBe('bom_standard_item');
    expect(props.fixedFilters).toEqual({ bom_std_task_id: 'TASK-1' });
    expect(props.columns).toHaveLength(1);
  });

  it('falls back to runtime context $page.recordId when no explicit parent id', () => {
    recordListProps.mockClear();
    const runtime = { getContext: () => ({ $page: { recordId: 'CTX-9' } }) } as any;
    render(<EmbeddedListBlockRenderer block={block} runtime={runtime} />);

    const props = recordListProps.mock.calls.at(-1)![0];
    expect(props.fixedFilters).toEqual({ bom_std_task_id: 'CTX-9' });
  });

  it('renders a warning (not RecordListView) when modelCode is missing', () => {
    recordListProps.mockClear();
    const bad = { id: 'x', blockType: 'embedded-list', columns: [] } as unknown as BlockConfig;
    render(<EmbeddedListBlockRenderer block={bad} parentRecordId="T" />);

    expect(screen.queryByTestId('record-list-view')).not.toBeInTheDocument();
    expect(screen.getByText(/missing modelCode/i)).toBeInTheDocument();
  });
});
