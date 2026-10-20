import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DataPathTable } from '../DetailPageContent';

describe('generic detail aggregate table', () => {
  it('renders nested business values and preserves exact decimal strings without raw object text', () => {
    render(<DataPathTable locale="en" t={(key) => key} columns={[
      { field: 'demand.materialName', label: 'Material' },
      { field: 'quote.amount', label: 'Amount' },
    ]} rows={[{ demand: { materialName: 'Resistor' }, quote: { amount: '9007199254740993.123456' } }]} />);
    expect(screen.getByText('Resistor')).toBeVisible();
    expect(screen.getByText('9007199254740993.123456')).toBeVisible();
    expect(screen.queryByText('[object Object]')).toBeNull();
  });
  it('preserves a literal dotted field before the nested fallback', () => {
    render(<DataPathTable locale="en" t={(key) => key} columns={[
      { field: 'quote.amount', label: 'Amount' },
    ]} rows={[{ 'quote.amount': '10.000001', quote: { amount: 'wrong' } }]} />);
    expect(screen.getByText('10.000001')).toBeVisible();
    expect(screen.queryByText('wrong')).toBeNull();
  });
});
