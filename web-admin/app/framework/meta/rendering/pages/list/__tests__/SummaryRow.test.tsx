import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ColumnConfig } from '~/framework/meta/schemas/types';
import { SummaryRow, hasAnyAggregate, type SummaryRowProps } from '../SummaryRow';

const columns: ColumnConfig[] = [
  { field: 'name', label: 'Name', width: 200 },
  { field: 'qty', label: 'Qty', width: 120, valueType: 'text', aggregate: 'sum', align: 'right' },
  {
    field: 'amount',
    label: 'Amount',
    width: 140,
    valueType: 'currency',
    currencyCode: 'USD',
    aggregate: 'sum',
    align: 'right',
  },
];

const rows = [
  { name: 'A', qty: 2, amount: '1,000.50' },
  { name: 'B', qty: 3, amount: 2000 },
  { name: 'C', qty: 'x', amount: null },
];

function renderSummary(overrides: Partial<SummaryRowProps> = {}) {
  const props: SummaryRowProps = {
    columns,
    rows,
    enableSelection: false,
    hasActionColumn: false,
    getColumnWidth: (c) => (typeof c.width === 'number' ? c.width : 120),
    locale: 'en',
    t: (key) => key,
    ...overrides,
  };
  // SummaryRow renders a <tfoot> — wrap in a table for valid DOM.
  return render(
    <table>
      <tbody>
        <tr>
          <td>body</td>
        </tr>
      </tbody>
      <SummaryRow {...props} />
    </table>,
  );
}

describe('hasAnyAggregate', () => {
  it('returns true when any non-action column declares a valid aggregate', () => {
    expect(hasAnyAggregate(columns)).toBe(true);
  });

  it('returns false when no column declares an aggregate', () => {
    expect(hasAnyAggregate([{ field: 'a' }, { field: 'b' }])).toBe(false);
  });

  it('ignores aggregates on the action column', () => {
    expect(
      hasAnyAggregate([{ field: '_actions', isActionColumn: true, aggregate: 'sum' } as any]),
    ).toBe(false);
  });

  it('ignores an invalid aggregate kind', () => {
    expect(hasAnyAggregate([{ field: 'a', aggregate: 'median' as any }])).toBe(false);
  });
});

describe('SummaryRow rendering', () => {
  it('renders a footer row with the summary label in the first cell', () => {
    renderSummary();
    const footer = screen.getByTestId('list-summary-row');
    expect(footer.tagName.toLowerCase()).toBe('tfoot');
    expect(screen.getByTestId('summary-cell-name')).toHaveTextContent('Summary');
  });

  it('computes the sum aggregate for a plain numeric column', () => {
    renderSummary();
    const cell = screen.getByTestId('summary-cell-qty');
    // 2 + 3 (skip 'x') = 5
    expect(cell).toHaveTextContent('5');
    expect(cell).toHaveAttribute('data-aggregate', 'sum');
  });

  it('computes and currency-formats the sum for a currency column', () => {
    renderSummary();
    const cell = screen.getByTestId('summary-cell-amount');
    // 1000.50 + 2000 (skip null) = 3000.50, USD-formatted
    expect(cell).toHaveTextContent('3,000.50');
    expect(cell.textContent).toMatch(/\$|USD/);
  });

  it('applies tabular-nums + alignment classes on aggregated cells', () => {
    renderSummary();
    const cell = screen.getByTestId('summary-cell-amount');
    expect(cell).toHaveClass('tabular-nums');
    expect(cell).toHaveClass('text-right');
  });

  it('keeps cells aligned: selection placeholder + action placeholder when present', () => {
    renderSummary({ enableSelection: true, hasActionColumn: true });
    const footer = screen.getByTestId('list-summary-row');
    const cells = footer.querySelectorAll('td');
    // selection + 3 data columns + action = 5 cells
    expect(cells).toHaveLength(5);
    expect(screen.getByTestId('summary-cell-actions')).toBeInTheDocument();
  });

  it('shows an em dash for an aggregated column with no numeric data', () => {
    renderSummary({
      columns: [
        { field: 'name', label: 'Name', width: 200 },
        { field: 'qty', label: 'Qty', width: 120, aggregate: 'sum', align: 'right' },
      ],
      rows: [
        { name: 'A', qty: 'x' },
        { name: 'B', qty: null },
      ],
    });
    expect(screen.getByTestId('summary-cell-qty')).toHaveTextContent('—');
  });

  it('uses the localized kind label when the translator resolves it', () => {
    renderSummary({
      t: (key) => (key === 'list.summary.sum' ? '合计' : key),
    });
    expect(screen.getByTestId('summary-cell-qty')).toHaveTextContent('合计');
  });

  it('renders a count aggregate as an integer', () => {
    renderSummary({
      columns: [
        { field: 'name', label: 'Name', width: 200, aggregate: 'count' },
        { field: 'qty', label: 'Qty', width: 120 },
      ],
    });
    // 3 non-null names → count = 3
    expect(screen.getByTestId('summary-cell-name')).toHaveTextContent('3');
    expect(screen.getByTestId('summary-cell-name')).toHaveAttribute('data-aggregate', 'count');
  });
});
