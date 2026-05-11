import React from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { ChatBiResultCard } from '../ChatBiResultCard';

describe('ChatBiResultCard', () => {
  it('renders raw records as a table when columns metadata is absent', () => {
    render(
      <ChatBiResultCard
        result={{
          total: 1,
          records: [
            {
              product_id: '01PRODUCT',
              supplier_id: '01SUPPLIER',
              supplier_name: 'Shenzhen Precision Components',
              unit_price: 8.75,
            },
          ],
        }}
      />,
    );

    expect(screen.getByText('Data Query')).toBeInTheDocument();
    expect(screen.getByText('supplier_name')).toBeInTheDocument();
    expect(screen.getByText('Shenzhen Precision Components')).toBeInTheDocument();
    expect(screen.getByText('01SUPPLIER')).toBeInTheDocument();
  });
});
