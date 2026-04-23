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
