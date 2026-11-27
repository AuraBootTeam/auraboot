/**
 * DetailBlockRenderer, default detail section: a section whose every field is empty on the record
 * shows one line that says so, instead of a grid of labels over dashes.
 */
import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// heavy neighbours of the renderer are not under test here
vi.mock('../detail/WorkspaceRecordSection', () => ({ WorkspaceRecordSection: () => null }));
vi.mock('@auraboot/runtime-kernel', () => ({ BlockRenderer: () => null }));
vi.mock('~/framework/extensions/contributed-components', () => ({
  ContributedSubTableViewer: () => null,
  LazyContributedComponent: () => null,
}));
vi.mock('~/framework/meta/rendering/blocks/EmbeddedListBlockRenderer', () => ({ EmbeddedListBlockRenderer: () => null }));
vi.mock('~/framework/meta/rendering/blocks/MonthlyGridViewer', () => ({ MonthlyGridViewer: () => null }));
vi.mock('~/framework/meta/rendering/blocks/FieldHistoryViewer', () => ({ FieldHistoryViewer: () => null }));
vi.mock('~/framework/meta/rendering/blocks/ActivityTimeline', () => ({
  ActivityTimeline: () => null,
  resolveBusinessActivityDataSource: vi.fn(),
}));
vi.mock('~/framework/meta/rendering/blocks/RecordComments', () => ({ RecordComments: () => null }));
vi.mock('~/contexts/TimezoneContext', () => ({ useTimezone: () => ({ timezone: 'UTC', formats: {} }) }));
vi.mock('~/contexts/AuthContext', () => ({ useAuth: () => ({ hasPermission: () => true }) }));
vi.mock('~/routes/_shared/dynamic-route-utils', () => ({
  getLocalizedText: (value: unknown, locale: string) => {
    if (typeof value === 'string') return value;
    const record = (value ?? {}) as Record<string, string>;
    return record[locale] ?? record['zh-CN'] ?? record.en ?? '';
  },
  // stands in for the real field: label, then the value or a dash
  DynamicField: ({ field, value, displayValue }: { field: { field: string; label?: unknown }; value: unknown; displayValue: unknown }) => {
    const label = typeof field.label === 'string' ? field.label : ((field.label as Record<string, string> | undefined)?.['zh-CN'] ?? field.field);
    return (
      <dl>
        <dt>{label}</dt>
        <dd>{displayValue != null && displayValue !== '' ? String(displayValue) : value != null && value !== '' ? String(value) : '—'}</dd>
      </dl>
    );
  },
}));
vi.mock('../detailRecordRequests', () => ({
  getByDataPath: (obj: unknown, path?: string) =>
    obj == null || !path ? obj : path.split('.').reduce((acc: any, key: string) => (acc == null ? undefined : acc[key]), obj),
  readDetailRecordField: (record: Record<string, unknown>, code: string) => {
    if (Object.prototype.hasOwnProperty.call(record, code)) return record[code];
    const camel = code.replace(/_([a-z])/g, (_m, letter: string) => letter.toUpperCase());
    return Object.prototype.hasOwnProperty.call(record, camel) ? record[camel] : undefined;
  },
}));

import { DetailBlockRenderer } from '../DetailBlockRenderer';

const renderSection = (block: Record<string, unknown>, recordData: Record<string, unknown>, t: (key: string) => string = (key) => key) =>
  render(
    <DetailBlockRenderer
      block={{ blockType: 'detail-section', title: { 'zh-CN': '数据迁移', en: 'Data migration' }, ...block } as never}
      recordData={recordData as never}
      rawData={recordData as never}
      recordPid="01MTEST"
      locale="zh-CN"
      t={t}
    />,
  );

describe('DetailBlockRenderer — empty detail section', () => {
  it('a section whose every field is empty shows one line and no field grid', () => {
    renderSection({ fields: [{ field: 'migration_summary' }, { field: 'migration_receipt' }] }, { migration_summary: null });

    expect(screen.getByTestId('detail-section-empty').textContent).toContain('这一段还没有内容。');
    expect(screen.queryByTestId('form-field-migration_summary')).toBeNull();
    expect(screen.getByRole('heading', { name: '数据迁移' })).toBeInTheDocument();
  });

  it('one value is enough to show the fields as before', () => {
    renderSection({ fields: [{ field: 'migration_summary' }, { field: 'migration_receipt' }] }, { migration_receipt: 0 });

    expect(screen.queryByTestId('detail-section-empty')).toBeNull();
    expect(screen.getByTestId('form-field-migration_summary')).toBeInTheDocument();
    expect(screen.getByTestId('form-field-migration_receipt')).toBeInTheDocument();
  });

  it("says what the page tells it to say about an empty section", () => {
    renderSection(
      { fields: [{ field: 'migration_summary' }], emptyText: '这次运行的计划里没有数据迁移。' },
      {},
    );

    expect(screen.getByTestId('detail-section-empty').textContent).toContain('这次运行的计划里没有数据迁移。');
  });

  it('uses the translated sentence when the catalog has it', () => {
    renderSection({ fields: [{ field: 'migration_summary' }] }, {}, (key) => (key === 'detail.section.empty' ? '本段暂无数据。' : key));

    expect(screen.getByTestId('detail-section-empty').textContent).toContain('本段暂无数据。');
  });

  it('a section bound to a nested object reads that object, not the record around it', () => {
    renderSection(
      { dataPath: 'latestHeartbeat', fields: [{ field: 'at' }] },
      { latestHeartbeat: { at: '2026-11-26T01:00:00Z' } },
    );

    expect(screen.queryByTestId('detail-section-empty')).toBeNull();
    expect(screen.getByTestId('form-field-at')).toBeInTheDocument();
  });

  it('the line shows when the nested object is empty, even if the record has a field of that name', () => {
    renderSection(
      { dataPath: 'latestHeartbeat', fields: [{ field: 'at' }] },
      { at: '2026-11-25T00:00:00Z', latestHeartbeat: {} },
    );

    expect(screen.getByTestId('detail-section-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('form-field-at')).toBeNull();
  });

  it('a section without fields shows neither the line nor fields', () => {
    renderSection({ fields: [] }, {});

    expect(screen.queryByTestId('detail-section-empty')).toBeNull();
    expect(screen.getByRole('heading', { name: '数据迁移' })).toBeInTheDocument();
  });
});
