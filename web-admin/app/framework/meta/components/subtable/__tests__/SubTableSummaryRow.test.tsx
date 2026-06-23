import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SubTableSummaryRow } from '../SubTableSummaryRow';

describe('SubTableSummaryRow', () => {
  it('uses a Chinese fallback label when common.total is not translated', () => {
    render(
      <table>
        <SubTableSummaryRow
          columns={[
            { field: 'product', label: '商品' },
            { field: 'amount', label: '金额' },
          ]}
          rows={[{ product: '示例商品', amount: 1299 }]}
          summary={{
            fields: [{ field: 'amount', aggregation: 'sum' }],
          }}
        />
      </table>,
    );

    expect(screen.getByText('合计')).toBeInTheDocument();
    expect(screen.queryByText('Total')).not.toBeInTheDocument();
    expect(screen.getByText('1,299')).toBeInTheDocument();
  });
});
