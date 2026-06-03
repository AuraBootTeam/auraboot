import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { cellRendererRegistry } from '../CellRendererRegistry';

describe('CellRendererRegistry tag renderer', () => {
  it('renders localized tag labels from tagMap without throwing', () => {
    const result = cellRendererRegistry.render('tag', {
      value: 'published',
      record: {},
      column: {
        field: 'status',
        valueType: 'tag',
        tagMap: {
          published: {
            label: {
              'en-US': 'Published',
              'zh-CN': '已发布',
            },
            color: 'green',
          },
        },
      },
      locale: 'zh-CN',
      t: (key) => key,
    });

    render(<>{result}</>);

    expect(screen.getByText('已发布')).toBeInTheDocument();
    expect(screen.queryByText('渲染错误')).not.toBeInTheDocument();
  });
});

describe('CellRendererRegistry datetime renderer', () => {
  // NOTE: tests use America/New_York deliberately — different from any China dev
  // machine's local timezone — so they fail unless column.timezone is honored
  // (a dayjs(value) machine-local parse would produce a different result).
  it('converts a UTC datetime into the column timezone', () => {
    const result = cellRendererRegistry.render('datetime', {
      value: '2026-06-03T03:08:04.030+00:00',
      record: {},
      column: {
        field: 'bom_task_completed_at',
        valueType: 'datetime',
        timezone: 'America/New_York',
      },
      locale: 'zh-CN',
      t: (key) => key,
    });

    render(<>{result}</>);

    // 03:08 UTC == 23:08 previous day in New York (EDT, UTC-4)
    expect(screen.getByText('2026-06-02 23:08:04')).toBeInTheDocument();
    expect(screen.queryByText('2026-06-03T03:08:04.030+00:00')).not.toBeInTheDocument();
  });

  it('converts a date-typed value honoring the timezone date boundary', () => {
    const result = cellRendererRegistry.render('date', {
      value: '2026-06-03T20:00:00Z',
      record: {},
      column: {
        field: 'ship_date',
        valueType: 'date',
        timezone: 'America/New_York',
      },
      locale: 'zh-CN',
      t: (key) => key,
    });

    render(<>{result}</>);

    // 20:00 UTC == 16:00 same day in New York (still 06-03, not next day)
    expect(screen.getByText('2026-06-03')).toBeInTheDocument();
  });
});
