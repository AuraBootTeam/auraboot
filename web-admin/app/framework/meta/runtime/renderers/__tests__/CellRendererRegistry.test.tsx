import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { cellRendererRegistry } from '../CellRendererRegistry';
import { StatusDot } from '../statusTone';

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

describe('CellRendererRegistry progress renderer — threshold tones', () => {
  const thresholds = [
    { max: 60, tone: 'gray' },
    { max: 80, tone: 'amber' },
    { tone: 'green' },
  ];
  const renderScore = (value: number) => {
    const result = cellRendererRegistry.render('progress', {
      value,
      record: {},
      column: { field: 'score', valueType: 'progress', render: { thresholds } },
    });
    return render(<>{result}</>).container;
  };

  it('cold (<60) → gray bar', () => {
    expect(renderScore(45).querySelector('.bg-status-gray')).toBeTruthy();
  });
  it('warm (60–80) → amber bar', () => {
    expect(renderScore(70).querySelector('.bg-status-amber')).toBeTruthy();
  });
  it('hot (≥80) → green bar', () => {
    expect(renderScore(92).querySelector('.bg-status-green')).toBeTruthy();
  });
  it('shows percentage text and clamps to 100%', () => {
    expect(renderScore(150).textContent).toContain('100%');
  });
  it('falls back to legacy single color when no thresholds', () => {
    const result = cellRendererRegistry.render('progress', {
      value: 50,
      record: {},
      column: { field: 'score', valueType: 'progress', render: { progressColor: 'blue' } },
    });
    expect(render(<>{result}</>).container.querySelector('.bg-blue-600')).toBeTruthy();
  });
});

describe('StatusDot — icon mode (category dims like lead source)', () => {
  it('renders a leading lucide icon instead of the color dot when icon is given', () => {
    const { container } = render(<StatusDot tone="gray" label="网站" icon="Globe" />);
    expect(container.querySelector('svg')).toBeTruthy(); // lucide icon rendered
    expect(container.querySelector('.bg-status-gray')).toBeFalsy(); // no color dot
    expect(container.textContent).toContain('网站');
  });
  it('renders the semantic color dot when no icon is given', () => {
    const { container } = render(<StatusDot tone="green" label="已转化" />);
    expect(container.querySelector('.bg-status-green')).toBeTruthy();
    expect(container.querySelector('svg')).toBeFalsy();
  });
});

describe('CellRendererRegistry rating renderer', () => {
  it('renders a numeric rating as filled stars out of five', () => {
    const result = cellRendererRegistry.render('rating', {
      value: 3,
      record: {},
      column: { field: 'score', valueType: 'rating' },
      locale: 'en-US',
      t: (key) => key,
    });
    const { container } = render(<>{result}</>);
    const stars = container.querySelectorAll('svg');
    expect(stars).toHaveLength(5);
    const filled = [...stars].filter((s) =>
      (s.getAttribute('class') || '').includes('fill-amber-500'),
    ).length;
    expect(filled).toBe(3);
    expect(container.querySelector('[aria-label="3/5"]')).toBeTruthy();
  });
});
