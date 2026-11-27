import React from 'react';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DataPathTable } from './DataPathTable';

const viewport = vi.hoisted(() => ({ narrow: true }));
vi.mock('~/framework/meta/rendering/components/ResponsiveBlockLayout', () => ({
  useMediaQuery: () => viewport.narrow,
}));
vi.mock('~/routes/_shared/dynamic-route-utils', () => ({
  getLocalizedText: (value: any, locale: string) =>
    typeof value === 'string' ? value : value?.[locale] || '',
}));
const columns = [
  { field: 'demand.name', label: { en: 'Material', 'zh-CN': '物料' } },
  { field: 'quantity', label: 'Quantity' },
  { field: 'taxIncluded', label: 'Tax included' },
  { field: 'note', label: 'Note' },
  { field: 'state', label: 'State', renderType: 'tag', dictCode: 'status' },
];
const props = {
  title: 'Current lines',
  columns,
  rows: [
    {
      sourcePid: 'source-1',
      demand: { name: 'Resistor' },
      quantity: 0,
      taxIncluded: false,
      note: null,
      state: 'submitted',
    },
  ],
  locale: 'en',
  t: (key: string) => (key === 'common.noData' ? 'No records' : key),
  getDictItems: () => [{ value: 'submitted', label: 'Submitted' }],
};
afterEach(() => {
  cleanup();
  viewport.narrow = true;
});

describe('Readonly dataPath table mobile cards', () => {
  it('renders all columns including nested, zero, false, empty and dictionary values', () => {
    render(<DataPathTable {...props} mobileCard={{ enabled: true, titleField: 'demand.name' }} />);
    const card = screen.getByTestId('detail-datapath-card');
    expect(within(card).getByRole('heading', { name: 'Resistor' })).toBeInTheDocument();
    for (const label of ['Material', 'Quantity', 'Tax included', 'Note', 'State'])
      expect(card).toHaveTextContent(label);
    for (const value of ['0', 'false', '—', 'Submitted']) expect(card).toHaveTextContent(value);
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(card.querySelector('input,button,a,select,textarea')).toBeNull();
  });
  it('retains distinct source rows with the same material name', () => {
    render(
      <DataPathTable
        {...props}
        rows={[...props.rows, { ...props.rows[0], sourcePid: 'source-2' }]}
        mobileCard={{ enabled: true }}
      />,
    );
    expect(screen.getAllByTestId('detail-datapath-card')).toHaveLength(2);
    expect(screen.getAllByRole('heading', { name: 'Resistor' })).toHaveLength(2);
  });
  it('preserves the desktop table', () => {
    viewport.narrow = false;
    render(<DataPathTable {...props} mobileCard={{ enabled: true }} />);
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(screen.queryByTestId('detail-datapath-cards')).not.toBeInTheDocument();
  });
  it('does not change unconfigured narrow tables', () => {
    render(<DataPathTable {...props} />);
    expect(screen.getByRole('table')).toBeInTheDocument();
  });
  it('respects explicit disabled cards', () => {
    render(<DataPathTable {...props} mobileCard={{ enabled: false }} />);
    expect(screen.getByRole('table')).toBeInTheDocument();
  });
  it('shows localized empty feedback without inventing rows', () => {
    render(<DataPathTable {...props} rows={[]} mobileCard={{ enabled: true }} />);
    expect(screen.getByTestId('detail-datapath-empty')).toHaveTextContent('No records');
    expect(screen.queryByTestId('detail-datapath-card')).not.toBeInTheDocument();
  });
  it('retains localized labels and datetime formatting', () => {
    render(
      <DataPathTable
        {...props}
        locale="zh-CN"
        columns={[columns[0], { field: 'at', label: 'Time', renderType: 'datetime' }]}
        rows={[{ demand: { name: '电阻' }, at: '2026-11-20T00:00:00Z' }]}
        mobileCard={{ enabled: true }}
      />,
    );
    expect(screen.getByText('物料')).toBeInTheDocument();
    expect(screen.getByText('电阻')).toBeInTheDocument();
    expect(screen.queryByText('2026-11-20T00:00:00Z')).not.toBeInTheDocument();
  });
});
